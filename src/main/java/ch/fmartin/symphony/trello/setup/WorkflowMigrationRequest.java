package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// Inputs of `symphony-trello migrate-workflows`. Without a selector, every connected workflow in the
/// config directory's manifest is checked; `workflow` may also name an explicitly configured
/// workflow outside the manifest.
@NullMarked
record WorkflowMigrationRequest(
        Path configDir,
        Optional<String> board,
        Optional<Path> workflow,
        boolean dryRun,
        boolean nonInteractive,
        Optional<String> fromVersion,
        boolean markMigrated) {
    WorkflowMigrationRequest {
        configDir = configDir.toAbsolutePath().normalize();
        workflow = workflow.map(path -> path.toAbsolutePath().normalize());
    }
}
