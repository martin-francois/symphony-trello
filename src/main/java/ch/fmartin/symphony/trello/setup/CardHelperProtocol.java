package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.CliExitCodes;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The helper accepts card operations only. Config paths, credentials, endpoints and lifecycle
/// actions are deliberately absent from this wire contract. See ADR 0157.
@NullMarked
final class CardHelperProtocol {
    static final int VERSION = 1;
    static final int MAX_REQUEST_BYTES = 256 * 1024;
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 8;
    static final Duration REQUEST_LIFETIME = Duration.ofMinutes(2);

    private CardHelperProtocol() {}

    static ObjectMapper json() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_JSON_DEPTH)
                        .maxStringLength(MAX_RESPONSE_BYTES)
                        .build())
                .build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    static Request from(CardCommandRequest request) {
        OperationArguments operation =
                switch (request.operation()) {
                    case CardCommandRequest.ListCards list ->
                        new OperationArguments(
                                CardCommandRequest.LIST,
                                list.listOrRole()
                                        .map(value -> Map.of("list", value))
                                        .orElseGet(Map::of));
                    case CardCommandRequest.ShowCard show ->
                        new OperationArguments(CardCommandRequest.SHOW, Map.of("card", show.card()));
                    case CardCommandRequest.CreateCard create ->
                        new OperationArguments(
                                CardCommandRequest.CREATE,
                                Map.of(
                                        "title",
                                        create.title(),
                                        "description",
                                        create.description(),
                                        "list",
                                        create.listOrRole()));
                    case CardCommandRequest.MoveCard move ->
                        new OperationArguments(
                                CardCommandRequest.MOVE, Map.of("card", move.card(), "list", move.listOrRole()));
                    case CardCommandRequest.CommentOnCard comment ->
                        new OperationArguments(
                                CardCommandRequest.COMMENT, Map.of("card", comment.card(), "text", comment.text()));
                    case CardCommandRequest.ArchiveCard archive ->
                        new OperationArguments(
                                CardCommandRequest.ARCHIVE,
                                Map.of("card", archive.card(), "confirm_title", archive.confirmTitle()));
                };
        return new Request(
                VERSION,
                request.board().orElse(null),
                request.workflow().map(CardHelperProtocol::absolutePath).orElse(null),
                request.json(),
                operation.name(),
                operation.arguments());
    }

    private static String absolutePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    record Request(
            int version,
            @Nullable String board,
            @Nullable String workflow,
            boolean json,
            String operation,
            Map<String, String> arguments) {
        Request {
            if (version != VERSION || operation == null || arguments == null || (board != null && workflow != null)) {
                throw invalidRequest();
            }
            arguments = Map.copyOf(arguments);
        }

        CardCommandRequest command(Path trustedConfigDir) {
            CardCommandRequest.Operation action =
                    switch (operation) {
                        case CardCommandRequest.LIST -> list();
                        case CardCommandRequest.SHOW -> show();
                        case CardCommandRequest.CREATE -> create();
                        case CardCommandRequest.MOVE -> move();
                        case CardCommandRequest.COMMENT -> comment();
                        case CardCommandRequest.ARCHIVE -> archive();
                        default -> throw invalidRequest();
                    };
            return new CardCommandRequest(
                    Optional.ofNullable(board),
                    Optional.ofNullable(workflow).map(Path::of),
                    Optional.of(trustedConfigDir),
                    Optional.empty(),
                    json,
                    action);
        }

        private CardCommandRequest.Operation list() {
            keys(Set.of("list"));
            return new CardCommandRequest.ListCards(Optional.ofNullable(arguments.get("list")));
        }

        private CardCommandRequest.Operation show() {
            keys(Set.of("card"));
            return new CardCommandRequest.ShowCard(required("card"));
        }

        private CardCommandRequest.Operation create() {
            keys(Set.of("title", "description", "list"));
            return new CardCommandRequest.CreateCard(
                    required("title"), arguments.getOrDefault("description", ""), required("list"));
        }

        private CardCommandRequest.Operation move() {
            keys(Set.of("card", "list"));
            return new CardCommandRequest.MoveCard(required("card"), required("list"));
        }

        private CardCommandRequest.Operation comment() {
            keys(Set.of("card", "text"));
            return new CardCommandRequest.CommentOnCard(required("card"), required("text"));
        }

        private CardCommandRequest.Operation archive() {
            keys(Set.of("card", "confirm_title"));
            return new CardCommandRequest.ArchiveCard(required("card"), required("confirm_title"));
        }

        private String required(String name) {
            String value = arguments.get(name);
            if (value == null || value.isBlank()) {
                throw invalidRequest();
            }
            return value;
        }

        private void keys(Set<String> accepted) {
            if (!accepted.containsAll(arguments.keySet())) {
                throw invalidRequest();
            }
        }
    }

    record Reply(int version, int exitCode, String output, @Nullable String failureCode) {
        static Reply failed(String code) {
            return new Reply(VERSION, CliExitCodes.SETUP_FAILURE, "", code);
        }
    }

    record Ready(int version, UUID generation, long pid) {}

    record Submission(UUID generation, long expiresAtEpochMillis, Request command) {
        Submission {
            if (generation == null || command == null) {
                throw invalidRequest();
            }
        }
    }

    private record OperationArguments(String name, Map<String, String> arguments) {}

    private static TrelloBoardSetupException invalidRequest() {
        return new TrelloBoardSetupException(
                "setup_card_helper_invalid_request",
                "cannot run card request: expected a supported card operation and its arguments");
    }
}
