package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.time.ApplicationClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/// Owns a fixed config directory and executes only card requests. Its lifetime does not depend on
/// any board worker. Claiming requests before execution avoids replay after a helper crash.
final class CardHelperServer implements AutoCloseable {
    private static final int MAX_ENTRIES_PER_PASS = 128;
    private static final Duration SCAN_INTERVAL = Duration.ofSeconds(1);
    private static final String UNCERTAIN = "setup_card_helper_outcome_unknown";

    private final Path configDir;
    private final CardHelperFiles files;
    private final CardCommandRunner commands;
    private final Clock clock;
    private final ObjectMapper json = CardHelperProtocol.json();
    private final UUID generation = UUID.randomUUID();
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private final WatchService watcher;
    private @Nullable RequestScan requestScan;

    CardHelperServer(Path configDir, CardHelperFiles files, CardCommandRunner commands) throws IOException {
        this(configDir, files, commands, ApplicationClock.systemUtc());
    }

    CardHelperServer(Path configDir, CardHelperFiles files, CardCommandRunner commands, Clock clock)
            throws IOException {
        this.configDir = configDir.toRealPath();
        this.files = files;
        this.commands = commands;
        this.clock = clock;
        files.initialize(this.configDir);
        lockChannel = FileChannel.open(
                files.processLock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            processLock = acquireLock(lockChannel);
            watcher = openWatcher(files.requests());
        } catch (IOException | RuntimeException failure) {
            closeAfterFailure(lockChannel, failure);
            throw failure;
        }
        try {
            recoverClaimedRequests();
            CardHelperFiles.publish(
                    files.privateState(),
                    files.ready(),
                    json.writeValueAsBytes(new CardHelperProtocol.Ready(
                            CardHelperProtocol.VERSION,
                            generation,
                            ProcessHandle.current().pid())),
                    CardHelperProtocol.MAX_REQUEST_BYTES);
        } catch (IOException | RuntimeException failure) {
            closeAfterFailure(this, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static WatchService openWatcher(Path requests) throws IOException {
        WatchService watcher = requests.getFileSystem().newWatchService();
        try {
            requests.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            return watcher;
        } catch (IOException | RuntimeException failure) {
            closeAfterFailure(watcher, failure);
            throw failure;
        }
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw alreadyRunning();
            }
            return lock;
        } catch (OverlappingFileLockException failure) {
            throw new IOException("cannot start card helper: a helper already owns this installation", failure);
        }
    }

    private static IOException alreadyRunning() {
        return new IOException("cannot start card helper: a helper already owns this installation");
    }

    void serve() throws IOException, InterruptedException {
        try {
            while (processLock.isValid()) {
                if (processPending()) {
                    continue;
                }
                WatchKey key = watcher.poll(SCAN_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                if (key != null) {
                    key.pollEvents();
                    if (!key.reset()) {
                        throw new IOException(
                                "cannot watch card requests: the helper request directory is unavailable");
                    }
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // Closing the watcher is the helper's normal shutdown signal.
            return;
        }
    }

    synchronized boolean processPending() throws IOException {
        if (!processLock.isValid()) {
            return false;
        }
        removeExpiredResponses();
        RequestScan scan = currentScan(); // NOPMD - the server owns this cursor across bounded passes.
        boolean more;
        try {
            int inspected = 0;
            while (inspected < MAX_ENTRIES_PER_PASS && scan.entries().hasNext()) {
                Path request = scan.entries().next();
                inspected++;
                if (!Files.isDirectory(request, LinkOption.NOFOLLOW_LINKS)) {
                    process(request);
                }
            }
            more = scan.entries().hasNext();
        } catch (IOException | RuntimeException failure) {
            requestScan = null;
            closeAfterFailure(scan, failure);
            throw failure;
        }
        if (!more) {
            requestScan = null;
            scan.close();
        }
        return more;
    }

    private RequestScan currentScan() throws IOException {
        RequestScan current = requestScan;
        if (current != null) {
            return current;
        }
        requestScan = new RequestScan(files.requests());
        return requestScan;
    }

    private static final class RequestScan implements AutoCloseable {
        private final DirectoryStream<Path> stream;
        private final Iterator<Path> entries;

        // This cursor owns the stream until exhaustion, scan failure or server shutdown.
        @SuppressWarnings("StreamResourceLeak")
        private RequestScan(Path requests) throws IOException {
            stream = Files.newDirectoryStream(requests, "*.request");
            try {
                entries = stream.iterator();
            } catch (RuntimeException failure) {
                closeAfterFailure(stream, failure);
                throw failure;
            }
        }

        Iterator<Path> entries() {
            return entries;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    private void process(Path request) throws IOException {
        UUID id;
        try {
            id = CardHelperFiles.requestId(request);
        } catch (IllegalArgumentException ignored) {
            // Unrecognized files cannot name an operation or a response path.
            discardMessage(request);
            return;
        }
        if (Files.exists(files.response(id), LinkOption.NOFOLLOW_LINKS)) {
            discardMessage(request);
            return;
        }
        Path claimed = files.claimed(id);
        try {
            Files.move(request, claimed, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException ignored) {
            // The client withdrew an unpublished request before the helper claimed it.
            return;
        }
        CardHelperProtocol.Reply reply = execute(claimed);
        writeReply(id, reply);
        discardMessage(claimed);
    }

    private CardHelperProtocol.Reply execute(Path claimed) {
        CardHelperProtocol.Submission submission;
        try {
            submission = json.readValue(
                    CardHelperFiles.read(claimed, CardHelperProtocol.MAX_REQUEST_BYTES),
                    CardHelperProtocol.Submission.class);
            long remaining = submission.expiresAtEpochMillis() - clock.millis();
            if (!generation.equals(submission.generation())
                    || remaining <= 0
                    || remaining > CardHelperProtocol.REQUEST_LIFETIME.toMillis()) {
                return CardHelperProtocol.Reply.failed(UNCERTAIN);
            }
        } catch (IOException | RuntimeException failure) {
            // Parser exceptions include input excerpts. Never expose them from a trusted process.
            return CardHelperProtocol.Reply.failed("setup_card_helper_invalid_request");
        }
        try (var buffer = new LimitedOutput();
                var out = new PrintStream(buffer, false, StandardCharsets.UTF_8)) {
            int exitCode = commands.run(submission.command().command(configDir), out);
            if (out.checkError() || buffer.exceeded()) {
                return CardHelperProtocol.Reply.failed("setup_card_helper_response_too_large");
            }
            return new CardHelperProtocol.Reply(CardHelperProtocol.VERSION, exitCode, buffer.text(), null);
        } catch (TrelloBoardSetupException failure) {
            return CardHelperProtocol.Reply.failed(failure.code());
        } catch (IOException | RuntimeException failure) {
            // The caller needs an explicit uncertain outcome, not internal paths or credentials.
            return CardHelperProtocol.Reply.failed(UNCERTAIN);
        }
    }

    private void writeReply(UUID id, CardHelperProtocol.Reply reply) throws IOException {
        byte[] bytes = json.writeValueAsBytes(reply);
        if (bytes.length > CardHelperProtocol.MAX_RESPONSE_BYTES) {
            bytes = json.writeValueAsBytes(CardHelperProtocol.Reply.failed("setup_card_helper_response_too_large"));
        }
        CardHelperFiles.publish(files.privateState(), files.response(id), bytes, CardHelperProtocol.MAX_RESPONSE_BYTES);
    }

    private void recoverClaimedRequests() throws IOException {
        try (var entries = Files.newDirectoryStream(files.privateState(), "*.request")) {
            for (Path claimed : entries) {
                UUID id = CardHelperFiles.requestId(claimed);
                if (!Files.exists(files.response(id), LinkOption.NOFOLLOW_LINKS)) {
                    writeReply(id, CardHelperProtocol.Reply.failed(UNCERTAIN));
                }
                discardMessage(claimed);
            }
        }
    }

    private void removeExpiredResponses() throws IOException {
        long cutoff = clock.millis() - CardHelperProtocol.REQUEST_LIFETIME.toMillis();
        try (var entries = Files.newDirectoryStream(files.responses(), "*.response")) {
            for (Path response : entries) {
                if (Files.getLastModifiedTime(response, LinkOption.NOFOLLOW_LINKS)
                                .toMillis()
                        < cutoff) {
                    discardMessage(response);
                }
            }
        }
    }

    private static void discardMessage(Path message) throws IOException {
        try {
            Files.deleteIfExists(message);
        } catch (DirectoryNotEmptyException ignored) {
            // A substituted directory is inert. Never traverse a client's tree during cleanup.
            return;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!processLock.isValid()) {
            return;
        }
        RequestScan scan = requestScan;
        requestScan = null;
        try (lockChannel;
                watcher;
                scan) {
            Files.deleteIfExists(files.ready());
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean exceeded;

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            requireCapacity(length);
            bytes.write(value, offset, length);
        }

        private void requireCapacity(int additional) throws IOException {
            if (additional > CardHelperProtocol.MAX_RESPONSE_BYTES - bytes.size()) {
                exceeded = true;
                throw new IOException("cannot render card result: response exceeds the size limit");
            }
        }

        boolean exceeded() {
            return exceeded;
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
