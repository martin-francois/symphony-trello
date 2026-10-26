package ch.fmartin.symphony.trello.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/// Confirms the exit of a foreground runtime command and its attached shutdown-time descendants.
/// Commands must not detach children or spawn replacements during shutdown. See ADR 0131.
public final class RuntimeProcessShutdown {
    private static final Duration PHASE_TIMEOUT = Duration.ofSeconds(2);

    private RuntimeProcessShutdown() {}

    public static void stop(Process process) {
        try (var descendants = process.descendants()) {
            var owned = new ArrayList<>(descendants.toList());
            owned.add(process.toHandle());
            stop(owned, PHASE_TIMEOUT);
        } catch (UnconfirmedExitException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnconfirmedExitException(e);
        }
    }

    static void stop(List<ProcessHandle> owned, Duration timeout) {
        boolean interrupted = Thread.interrupted();
        try {
            signal(owned, false);
            interrupted |= awaitExit(owned, timeout);
            if (anyAlive(owned)) {
                signal(owned, true);
                interrupted |= awaitExit(owned, timeout);
            }
            if (anyAlive(owned)) {
                throw new UnconfirmedExitException();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void signal(List<ProcessHandle> owned, boolean force) {
        for (ProcessHandle process : owned) {
            if (process.isAlive()) {
                if (force) {
                    process.destroyForcibly();
                } else {
                    process.destroy();
                }
            }
        }
    }

    private static boolean awaitExit(List<ProcessHandle> owned, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        for (ProcessHandle process : owned) {
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return interrupted;
                }
                try {
                    process.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException e) {
                    return interrupted;
                }
            }
        }
        return interrupted;
    }

    private static boolean anyAlive(List<ProcessHandle> owned) {
        return owned.stream().anyMatch(ProcessHandle::isAlive);
    }

    public static final class UnconfirmedExitException extends RuntimeException {
        public UnconfirmedExitException() {
            this(null);
        }

        private UnconfirmedExitException(@Nullable Throwable cause) {
            super(
                    "cannot release runtime workspace: process exit remains unconfirmed after bounded shutdown; retain the workspace and inspect its processes",
                    cause);
        }
    }
}
