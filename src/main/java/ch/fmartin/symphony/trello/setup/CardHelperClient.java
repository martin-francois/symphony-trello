package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/// A credential-free client. A restricted caller cannot start the trusted helper or fall back to
/// reading its configuration. A submitted write is never retried automatically.
final class CardHelperClient implements CardCommandRunner {
    private final Map<String, String> environment;
    private final Duration responseTimeout;
    private final ObjectMapper json = CardHelperProtocol.json();

    CardHelperClient(Map<String, String> environment) {
        this(environment, CardHelperProtocol.REQUEST_LIFETIME);
    }

    CardHelperClient(Map<String, String> environment, Duration responseTimeout) {
        this.environment = Map.copyOf(environment);
        this.responseTimeout = responseTimeout;
    }

    @Override
    public int run(CardCommandRequest request, PrintStream out) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                Optional.empty(), request.configDir(), Optional.empty(), request.stateHome(), environment);
        return run(request, CardHelperFiles.from(paths), out);
    }

    int run(CardCommandRequest request, CardHelperFiles files, PrintStream out) throws IOException {
        CardHelperProtocol.Ready ready = readReady(files);
        UUID id = UUID.randomUUID();
        try (var watcher = files.responses().getFileSystem().newWatchService()) {
            files.responses()
                    .register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            byte[] bytes = json.writeValueAsBytes(new CardHelperProtocol.Submission(
                    ready.generation(),
                    System.currentTimeMillis() + responseTimeout.toMillis(),
                    CardHelperProtocol.from(request)));
            CardHelperFiles.publish(files.requests(), files.request(id), bytes, CardHelperProtocol.MAX_REQUEST_BYTES);
            long deadline = System.nanoTime() + responseTimeout.toNanos();
            while (!Files.exists(files.response(id), LinkOption.NOFOLLOW_LINKS)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw uncertainOutcome();
                }
                var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key != null) {
                    key.pollEvents();
                    if (!key.reset()) {
                        throw uncertainOutcome();
                    }
                }
            }
            return renderReply(files, id, out);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "cannot wait for card result: inspect the board before retrying the operation", interrupted);
        }
    }

    private CardHelperProtocol.Ready readReady(CardHelperFiles files) {
        try {
            CardHelperProtocol.Ready ready = json.readValue(
                    CardHelperFiles.read(files.ready(), CardHelperProtocol.MAX_REQUEST_BYTES),
                    CardHelperProtocol.Ready.class);
            if (ready.version() != CardHelperProtocol.VERSION || ready.generation() == null) {
                throw unavailable();
            }
            return ready;
        } catch (IOException | IllegalArgumentException failure) {
            // Missing, inaccessible and malformed helper state all require a trusted restart.
            throw unavailable(failure);
        }
    }

    private int renderReply(CardHelperFiles files, UUID id, PrintStream out) throws IOException {
        CardHelperProtocol.Reply reply = json.readValue(
                CardHelperFiles.read(files.response(id), CardHelperProtocol.MAX_RESPONSE_BYTES),
                CardHelperProtocol.Reply.class);
        if (reply.version() != CardHelperProtocol.VERSION || reply.output() == null) {
            throw uncertainOutcome();
        }
        if (reply.failureCode() != null) {
            if ("setup_worker_board_not_found".equals(reply.failureCode())) {
                throw ConnectedWorkflowResolver.notConnected();
            }
            throw new TrelloBoardSetupException(
                    reply.failureCode(),
                    "cannot complete card operation: inspect the board and run diagnostics from a trusted terminal before retrying");
        }
        out.print(reply.output());
        return reply.exitCode();
    }

    private static TrelloBoardSetupException unavailable() {
        return unavailable(null);
    }

    private static TrelloBoardSetupException unavailable(Throwable cause) {
        return new TrelloBoardSetupException(
                "setup_card_helper_unavailable",
                "cannot contact card helper: run symphony-trello card-helper start from a trusted terminal",
                cause);
    }

    private static TrelloBoardSetupException uncertainOutcome() {
        return new TrelloBoardSetupException(
                "setup_card_helper_outcome_unknown",
                "cannot confirm card operation: inspect the board before retrying; the operation was not retried automatically");
    }
}
