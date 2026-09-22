package ch.fmartin.symphony.trello.telemetry;

import com.google.common.util.concurrent.Striped;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// Owns `telemetry.json` and `telemetry.lock` in the state directory. Every change is a short
/// transaction under an OS file lock on the stable lock file plus a JVM-local lock for threads of
/// the same process, followed by an atomic replacement of the JSON file. The lock is never held
/// across a network request or a console prompt.
public final class TelemetryStateStore {
    public static final String STATE_FILE = "telemetry.json";
    public static final String LOCK_FILE = "telemetry.lock";
    static final Duration LOCK_WAIT = Duration.ofSeconds(10);
    private static final Duration LOCK_POLL = Duration.ofMillis(25);
    private static final int JVM_LOCK_STRIPES = 64;
    private static final Striped<Lock> JVM_LOCKS = Striped.lazyWeakLock(JVM_LOCK_STRIPES);
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    private final Path directory;
    private final Path stateFile;
    private final Path lockFile;
    private final Duration lockWait;

    public TelemetryStateStore(Path stateDir) {
        this(stateDir, LOCK_WAIT);
    }

    TelemetryStateStore(Path stateDir, Duration lockWait) {
        this.directory = stateDir.toAbsolutePath().normalize();
        this.stateFile = directory.resolve(STATE_FILE);
        this.lockFile = directory.resolve(LOCK_FILE);
        this.lockWait = lockWait;
    }

    public Path stateFile() {
        return stateFile;
    }

    /// Reads without locking. Callers that decide anything must re-read inside [#update].
    public StateRead read() {
        String json;
        try {
            json = Files.readString(stateFile);
        } catch (NoSuchFileException exception) {
            return StateRead.absent();
        } catch (IOException | SecurityException exception) {
            return StateRead.unreadable("telemetry state could not be read: " + exception.getMessage());
        }
        return parse(json);
    }

    /// Applies `transaction` to the current state under the cross-process lock. The transaction sees
    /// the initial state when no file exists, returns the new state to persist or `null` to leave
    /// the file untouched, and its result is returned to the caller.
    public <T> T update(Function<TelemetryState, Update<T>> transaction) {
        // One time budget covers both locks, so a transaction stuck in this JVM cannot block a
        // second store on the same file forever.
        long deadline = System.nanoTime() + lockWait.toNanos();
        Lock jvmLock = JVM_LOCKS.get(lockFile.toString());
        if (!tryLockWithin(jvmLock, deadline)) {
            throw new TelemetryStateException("telemetry state lock is held by another transaction of this process");
        }
        try {
            ensureDirectory();
            try (FileChannel channel = FileChannel.open(
                            lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
                    FileLock ignored = acquire(channel, deadline)) {
                TelemetryState current = currentForUpdate();
                Update<T> update = transaction.apply(current);
                TelemetryState next = update.next();
                if (next != null && !next.equals(current)) {
                    write(next);
                }
                return update.result();
            } catch (IOException exception) {
                throw new TelemetryStateException(
                        "telemetry state could not be updated: " + exception.getMessage(), exception);
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private TelemetryState currentForUpdate() {
        StateRead read = read();
        return switch (read.status()) {
            case ABSENT -> TelemetryState.initial();
            case VALID -> read.state().orElseThrow();
            case UNREADABLE ->
                throw new TelemetryStateException(read.problem().orElse("telemetry state is unreadable"));
        };
    }

    private StateRead parse(String json) {
        TelemetryState state;
        try {
            state = TelemetryStateJson.read(json);
        } catch (IOException | RuntimeException exception) {
            return StateRead.unreadable("telemetry state is not valid JSON or contains invalid values");
        }
        if (state.formatVersion() != TelemetryState.FORMAT_VERSION) {
            return StateRead.unreadable("telemetry state format version " + state.formatVersion()
                    + " is not supported by this release (expected " + TelemetryState.FORMAT_VERSION + ")");
        }
        return state.invariantProblem()
                .map(problem -> StateRead.unreadable("telemetry state is inconsistent: " + problem))
                .orElseGet(() -> StateRead.valid(state));
    }

    private static boolean tryLockWithin(Lock lock, long deadline) {
        try {
            return lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelemetryStateException("interrupted while waiting for the telemetry state lock", exception);
        }
    }

    private FileLock acquire(FileChannel channel, long deadline) throws IOException {
        while (true) {
            FileLock lock = tryLockUnlessHeldByThisJvm(channel);
            if (lock != null) {
                return lock;
            }
            if (System.nanoTime() >= deadline) {
                throw new TelemetryStateException("telemetry state lock is held by another process");
            }
            pollDelayForBoundedLockWait();
        }
    }

    /// Another thread of this JVM holding the OS lock is treated like another process: wait.
    private static @Nullable FileLock tryLockUnlessHeldByThisJvm(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException heldByThisJvm) {
            return null;
        }
    }

    private static void pollDelayForBoundedLockWait() {
        try {
            TimeUnit.NANOSECONDS.sleep(LOCK_POLL.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TelemetryStateException("interrupted while waiting for the telemetry state lock", exception);
        }
    }

    private void ensureDirectory() {
        try {
            if (posix()) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
            } else {
                Files.createDirectories(directory);
            }
        } catch (IOException exception) {
            throw new TelemetryStateException("telemetry state directory could not be created", exception);
        }
    }

    private void write(TelemetryState state) throws IOException {
        Path temporary = posix()
                ? Files.createTempFile(
                        directory, "telemetry-", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : Files.createTempFile(directory, "telemetry-", ".tmp");
        try {
            Files.writeString(temporary, TelemetryStateJson.write(state));
            try (FileChannel written = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                written.force(true);
            }
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("atomic replacement of telemetry.json is not supported here", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /// The outcome of a transaction: the state to persist (or `null` for no write) and a result.
    public record Update<T>(@Nullable TelemetryState next, T result) {
        public static <T> Update<T> unchanged(T result) {
            return new Update<>(null, result);
        }

        public static <T> Update<T> write(TelemetryState next, T result) {
            return new Update<>(next, result);
        }
    }

    /// A lock-free read of the state file.
    public record StateRead(Status status, Optional<TelemetryState> state, Optional<String> problem) {
        public enum Status {
            ABSENT,
            VALID,
            UNREADABLE
        }

        static StateRead absent() {
            return new StateRead(Status.ABSENT, Optional.empty(), Optional.empty());
        }

        static StateRead valid(TelemetryState state) {
            return new StateRead(Status.VALID, Optional.of(state), Optional.empty());
        }

        static StateRead unreadable(String problem) {
            return new StateRead(Status.UNREADABLE, Optional.empty(), Optional.of(problem));
        }

        /// The state to reason about when nothing is stored yet: the initial defaults.
        public TelemetryState stateOrInitial() {
            return state.orElseGet(TelemetryState::initial);
        }

        public boolean unreadable() {
            return status == Status.UNREADABLE;
        }
    }
}
