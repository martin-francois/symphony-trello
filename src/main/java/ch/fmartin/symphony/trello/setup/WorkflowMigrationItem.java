package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.WorkflowBodyMigration.MigrationTarget;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// The migration decision for one workflow, computed before anything is written.
@NullMarked
record WorkflowMigrationItem(
        MigrationTarget target,
        WorkflowBodyClassification classification,
        String problem,
        Optional<String> sourceVersion,
        boolean downgrade,
        Optional<GeneratedWorkflowBodyInputs> inputs,
        Optional<WorkflowBodyReplacement> replacement,
        boolean refreshRecord) {
    WorkflowBodyClassification.Action action() {
        return classification.action();
    }

    Path workflowPath() {
        return target.workflowPath();
    }

    Optional<String> targetBody() {
        return inputs.map(TrelloBoardSetup::generatedWorkflowBody);
    }

    String heading() {
        return DisplayNames.quotedName(target.label()) + " (" + workflowPath() + ")";
    }

    String sourceVersionLabel() {
        return sourceVersion.map(version -> "Symphony " + version).orElse("the previously installed Symphony version");
    }
}
