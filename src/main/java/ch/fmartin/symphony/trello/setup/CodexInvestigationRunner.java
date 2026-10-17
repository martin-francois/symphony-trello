package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/// Runs one local Codex investigation of an unexpected setup failure. Tests replace the process
/// implementation with a fake so the setup-failure flow stays deterministic.
@FunctionalInterface
interface CodexInvestigationRunner {
    /// Set in the Codex process environment. A setup command that Codex runs for validation and
    /// that fails again must not offer another Codex investigation.
    String INVESTIGATION_MARKER_ENV = "SYMPHONY_TRELLO_CODEX_INVESTIGATION";

    Outcome investigate(Request request);

    static String describe(Duration timeout) {
        long minutes = timeout.toMinutes();
        if (minutes > 0 && timeout.equals(Duration.ofMinutes(minutes))) {
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        return timeout.toMillis() + " ms";
    }

    /// Builds the full process command for the resolved Codex executable, including the Windows
    /// batch-shim wrapping that the diagnostics tool probe already applies.
    @FunctionalInterface
    interface CodexCommand {
        String[] command(String... arguments);
    }

    /// One investigation request.
    ///
    /// @param prompt sanitized prompt text; it must not contain raw private values
    /// @param workingRoot directory Codex starts in and may change
    /// @param additionalWritableRoots further directories Codex may change
    record Request(
            CodexCommand codex, String prompt, Path workingRoot, List<Path> additionalWritableRoots, Duration timeout) {
        public Request {
            additionalWritableRoots = List.copyOf(additionalWritableRoots);
        }
    }

    sealed interface Outcome {
        record Completed(CodexInvestigationFinding finding) implements Outcome {}

        /// Codex could not start, timed out, exited non-zero, or returned no usable answer.
        record Failed(String reason) implements Outcome {}
    }
}
