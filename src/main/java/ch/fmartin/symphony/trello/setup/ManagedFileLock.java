package ch.fmartin.symphony.trello.setup;

import com.google.common.util.concurrent.Striped;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/// Serializes managed state changes across threads and JVMs. Nested lifecycle calls on the same
/// thread share the OS lock until the last enclosing transaction closes.
final class ManagedFileLock implements AutoCloseable {
    private static final int LOCK_STRIPES = 1024;
    private static final Striped<Lock> LOCAL_LOCKS = Striped.lazyWeakLock(LOCK_STRIPES);
    private static final ThreadLocal<Map<Path, HeldLock>> HELD = ThreadLocal.withInitial(HashMap::new);

    private final Path path;
    private final HeldLock held;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    private ManagedFileLock(Path path, HeldLock held) {
        this.path = path;
        this.held = held;
        held.users++;
    }

    static ManagedFileLock acquire(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path canonical = PathNames.requireParent(absolute).toRealPath().resolve(PathNames.fileName(absolute));
        Map<Path, HeldLock> locks = HELD.get();
        HeldLock existing = locks.get(canonical);
        if (existing != null) {
            return new ManagedFileLock(canonical, existing);
        }
        Lock local = LOCAL_LOCKS.get(canonical);
        local.lock();
        // Ownership passes to the returned lease; failed acquisition closes the channel below.
        @SuppressWarnings("PMD.CloseResource")
        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                    canonical, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            var acquired = new HeldLock(local, channel, channel.lock());
            locks.put(canonical, acquired);
            return new ManagedFileLock(canonical, acquired);
        } catch (IOException | RuntimeException failure) {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            } finally {
                local.unlock();
                if (locks.isEmpty()) {
                    HELD.remove();
                }
            }
            throw failure;
        }
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // Lock ownership requires the identical thread object.
    public void close() throws IOException {
        if (Thread.currentThread() != owner) { // NOPMD - Lock ownership requires the identical thread object.
            throw new IllegalStateException("cannot close managed file lock: close it on the acquiring thread");
        }
        if (closed) {
            return;
        }
        closed = true;
        held.users--;
        if (held.users > 0) {
            return;
        }
        try (held.channel;
                held.file) {
            // The OS lock must be released before another local thread opens the same lock file.
        } finally {
            Map<Path, HeldLock> locks = HELD.get();
            locks.remove(path);
            if (locks.isEmpty()) {
                HELD.remove();
            }
            held.local.unlock();
        }
    }

    private static final class HeldLock {
        private final Lock local;
        private final FileChannel channel;
        private final FileLock file;
        private int users;

        private HeldLock(Lock local, FileChannel channel, FileLock file) {
            this.local = local;
            this.channel = channel;
            this.file = file;
        }
    }
}
