package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Maps one fuzzed Trello card payload through [TrelloClient#normalize] and checks the mapping contract.
/// The standalone `TrelloCardPayloadFuzzer` and the JUnit `TrelloCardPayloadFuzzTest` share this class,
/// so OSS-Fuzz runs and Maven regression runs check the same property.
///
/// The fuzz input ends with nine choice bytes, which [FuzzedDataProvider] reads from the end of the data:
/// four priority label settings, then the closed flags of four board lists and of the board. Everything
/// before them is the card response body. `docs/fuzzing.md` describes the seed layout.
public final class TrelloCardPayloadFuzzInvariants {
    // Room for a card with dozens of comments, checklist items, and attachments.
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final String UNKNOWN_PAYLOAD_CODE = "trello_unknown_payload";

    private static final String BOARD_ID = "board-1";
    private static final List<ListChoice> BOARD_LISTS = List.of(
            new ListChoice("list-todo", "Todo"),
            new ListChoice("list-progress", "In Progress"),
            new ListChoice("list-done", "Done"),
            new ListChoice("list-archived", "Later"));
    // Spellings that exercise label name normalization against the configured priority label keys.
    private static final List<String> PRIORITY_LABEL_NAMES = List.of("P1", "P2", "Priority: High", " urgent ");
    // Zero leaves a label unconfigured; other values are priorities.
    private static final int MAX_PRIORITY = 3;
    private static final TypeReference<Map<String, Object>> CARD_PAYLOAD = new TypeReference<>() {};
    // Quarkus injects TrelloClient's ObjectMapper. This is the only deserialization setting in which it
    // differs from Jackson's defaults, so this mapper parses a card body into the same Java types.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final ConfigResolver CONFIG_RESOLVER = new ConfigResolver();

    private TrelloCardPayloadFuzzInvariants() {}

    public static MappingResult mapCardPayload(FuzzedDataProvider data) {
        TrelloClient.BoardContext board = board(data);
        EffectiveConfig config = config(data);
        byte[] body = data.consumeBytes(MAX_PAYLOAD_BYTES);
        Map<String, Object> payload;
        try {
            payload = JSON.readValue(body, CARD_PAYLOAD);
        } catch (IOException notACardObject) {
            // TrelloClient reports a body Jackson cannot read as a map as trello_api_request before
            // mapping starts, so it is outside this target.
            return MappingResult.NOT_MAPPED;
        }
        if (payload == null) {
            // A JSON null body is also a request-layer gap, tracked in #833.
            return MappingResult.NOT_MAPPED;
        }
        return mapCard(payload, board, config);
    }

    public static void assertMappingKeepsItsContract(MappingResult result) {
        if (result.rejection() != null) {
            if (!UNKNOWN_PAYLOAD_CODE.equals(result.rejection().code())) {
                throw new AssertionError(
                        "card mapping rejected a payload with "
                                + result.rejection().code(),
                        result.rejection());
            }
            return;
        }
        result.card().ifPresent(TrelloCardPayloadFuzzInvariants::assertCardHasIdentity);
    }

    private static TrelloClient.BoardContext board(FuzzedDataProvider data) {
        boolean boardClosed = data.consumeBoolean();
        List<TrelloClient.BoardList> lists = new ArrayList<>();
        for (ListChoice list : BOARD_LISTS) {
            lists.add(new TrelloClient.BoardList(list.id(), list.name(), data.consumeBoolean()));
        }
        return TrelloClient.BoardContext.of(BOARD_ID, boardClosed, lists);
    }

    private static EffectiveConfig config(FuzzedDataProvider data) {
        Map<String, Object> priorityLabels = new HashMap<>();
        for (String label : PRIORITY_LABEL_NAMES) {
            int priority = data.consumeInt(0, MAX_PRIORITY);
            if (priority > 0) {
                priorityLabels.put(label, priority);
            }
        }
        Map<String, Object> tracker = Map.of(
                "kind",
                "trello",
                "api_key",
                "key",
                "api_token",
                "token",
                "board_id",
                BOARD_ID,
                "priority_labels",
                priorityLabels);
        return CONFIG_RESOLVER.resolve(new WorkflowDefinition(Path.of("WORKFLOW.md"), Map.of("tracker", tracker), ""));
    }

    private static MappingResult mapCard(
            Map<String, Object> payload, TrelloClient.BoardContext board, EffectiveConfig config) {
        try {
            return new MappingResult(TrelloClient.normalize(payload, board, config), null);
        } catch (TrelloException rejected) {
            return new MappingResult(Optional.empty(), rejected);
        }
    }

    private static void assertCardHasIdentity(Card card) {
        if (blank(card.id())) {
            throw new AssertionError("mapped card has a blank id");
        }
        if (blank(card.title())) {
            throw new AssertionError("mapped card has a blank name");
        }
        if (blank(card.boardId())) {
            throw new AssertionError("mapped card has a blank board id");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ListChoice(String id, String name) {}

    /// The card mapping's outcome: a card, no card, or the [TrelloException] it threw.
    public record MappingResult(Optional<Card> card, @Nullable TrelloException rejection) {
        static final MappingResult NOT_MAPPED = new MappingResult(Optional.empty(), null);
    }
}
