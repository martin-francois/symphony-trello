package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Optional;

/// Builds the private-context lookup requests that setup tests send to [SetupDiagnosticReporter].
final class DiagnosticsRequests {
    private DiagnosticsRequests() {}

    /// A `--show-private-context --lookup TOKEN` request without a selector, the shape of the
    /// command a failed start prints.
    static SetupDiagnosticReporter.DiagnosticsRequest lookupWithoutSelector(LocalWorkerPaths paths, String token) {
        return lookup(
                Optional.empty(), Optional.empty(), paths.configDir(), paths.workspaceRoot(), paths.stateHome(), token);
    }

    static SetupDiagnosticReporter.DiagnosticsRequest lookup(
            Optional<String> board,
            Optional<Path> workflow,
            Path configDir,
            Path workspaceRoot,
            Path stateHome,
            String token) {
        return new SetupDiagnosticReporter.DiagnosticsRequest(
                board,
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.of(workspaceRoot),
                Optional.of(stateHome),
                Optional.empty(),
                workflow,
                Optional.of(token));
    }
}
