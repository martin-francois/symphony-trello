package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.time.ApplicationClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/// A credential-free client. A restricted caller cannot start the trusted helper or fall back to
/// reading its configuration. A submitted write is never retried automatically.
final class CardHelperClient implements CardCommandRunner {
    private final Map<String, String> environment;
    private final Duration responseTimeout;
    private final Clock clock;
    private final Duration heartbeatCheckInterval;
    private final ObjectMapper json = CardHelperProtocol.json();

    CardHelperClient(Map<String, String> environment) {
        this(environment, CardHelperProtocol.REQUEST_LIFETIME);
    }

    CardHelperClient(Map<String, String> environment, Duration responseTimeout) {
        this(environment, responseTimeout, ApplicationClock.systemUtc(), CardHelperProtocol.HEARTBEAT_INTERVAL);
    }

    CardHelperClient(
            Map<String, String> environment, Duration responseTimeout, Clock clock, Duration heartbeatCheckInterval) {
        this.environment = Map.copyOf(environment);
        this.responseTimeout = responseTimeout;
        this.clock = clock;
        this.heartbeatCheckInterval = heartbeatCheckInterval;
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
                    ready.generation(), clock.millis() + responseTimeout.toMillis(), CardHelperProtocol.from(request)));
            CardHelperFiles.publish(files.requests(), files.request(id), bytes, CardHelperProtocol.MAX_REQUEST_BYTES);
            return awaitReply(files, id, watcher, out);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "cannot wait for card result: inspect the board before retrying the operation", interrupted);
        }
    }

    /// Once the request is published the helper may be executing it, so an IO failure here is an
    /// unknown outcome rather than a plain read or write error.
    private int awaitReply(CardHelperFiles files, UUID id, WatchService watcher, PrintStream out)
            throws InterruptedException {
        try {
            long deadline = System.nanoTime() + responseTimeout.toNanos();
            while (!Files.exists(files.response(id), LinkOption.NOFOLLOW_LINKS)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw uncertainOutcome();
                }
                if (helperDied(files) && !Files.exists(files.response(id), LinkOption.NOFOLLOW_LINKS)) {
                    throw abandon(files, id);
                }
                var key = watcher.poll(Math.min(remaining, heartbeatCheckInterval.toNanos()), TimeUnit.NANOSECONDS);
                if (key != null) {
                    key.pollEvents();
                    if (!key.reset()) {
                        throw uncertainOutcome();
                    }
                }
            }
            return renderReply(files, id, out);
        } catch (IOException failure) {
            throw uncertainOutcome(failure);
        }
    }

    private CardHelperProtocol.Ready readReady(CardHelperFiles files) {
        try {
            CardHelperProtocol.Ready ready = json.readValue(
                    CardHelperFiles.read(files.ready(), CardHelperProtocol.MAX_REQUEST_BYTES),
                    CardHelperProtocol.Ready.class);
            if (ready.version() != CardHelperProtocol.VERSION || ready.generation() == null || helperDied(files)) {
                throw unavailable();
            }
            return ready;
        } catch (IOException | IllegalArgumentException failure) {
            // Missing, inaccessible and malformed helper state all require a trusted restart.
            throw unavailable(failure);
        }
    }

    /// A crashed helper leaves its readiness record behind but stops refreshing it. Checking the
    /// record instead of the PID works from a sandbox that hides the host helper's process.
    private boolean helperDied(CardHelperFiles files) throws IOException {
        try {
            return files.heartbeatStale(clock.millis());
        } catch (NoSuchFileException gone) {
            // A graceful stop removes the record; the helper is as unavailable as after a crash.
            return true;
        }
    }

    /// The helper claims a request by moving it out of the request directory. A request still
    /// there was never executed, so the client withdraws it; a claimed one has an unknown outcome.
    private static TrelloBoardSetupException abandon(CardHelperFiles files, UUID id) throws IOException {
        if (Files.deleteIfExists(files.request(id))) {
            return unavailable();
        }
        return uncertainOutcome();
    }

    private int renderReply(CardHelperFiles files, UUID id, PrintStream out) throws IOException {
        CardHelperProtocol.Reply reply = json.readValue(
                CardHelperFiles.read(files.response(id), CardHelperProtocol.MAX_RESPONSE_BYTES),
                CardHelperProtocol.Reply.class);
        if (reply.version() != CardHelperProtocol.VERSION || reply.output() == null) {
            throw uncertainOutcome();
        }
        if (reply.failureCode() != null) {
            if (ConnectedWorkflowResolver.BOARD_NOT_FOUND_CODE.equals(reply.failureCode())) {
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
                CardHelperProtocol.UNAVAILABLE_CODE,
                "cannot contact card helper: run symphony-trello card-helper start from a trusted terminal",
                cause);
    }

    private static TrelloBoardSetupException uncertainOutcome() {
        return uncertainOutcome(null);
    }

    private static TrelloBoardSetupException uncertainOutcome(@Nullable Throwable cause) {
        return new TrelloBoardSetupException(
                CardHelperProtocol.OUTCOME_UNKNOWN_CODE,
                "cannot confirm card operation: inspect the board before retrying; the operation was not retried automatically",
                cause);
    }
}
