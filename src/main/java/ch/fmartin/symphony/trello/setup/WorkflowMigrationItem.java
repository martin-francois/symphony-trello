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
    static WorkflowMigrationItem malformed(MigrationTarget target, String problem) {
        return new WorkflowMigrationItem(
                target,
                WorkflowBodyClassification.MALFORMED,
                problem,
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty(),
                false);
    }

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

    String reportLine(String runningVersion) {
        return "  %-9s %s: %s".formatted(status(), heading(), description(runningVersion));
    }

    private String status() {
        return switch (classification) {
            case NO_GENERATED_BODY_CHANGE -> "OK";
            case ALREADY_CURRENT -> "CURRENT";
            case UNCHANGED_GENERATED_BODY, USER_PREFIX_SUFFIX -> "MIGRATE";
            case INTERNALLY_CUSTOMIZED, DUPLICATE_GENERATED_BODY, UNKNOWN_SOURCE, MALFORMED -> "MANUAL";
            case UNSUPPORTED_MAJOR_DOWNGRADE -> "REJECTED";
        };
    }

    private String description(String runningVersion) {
        return switch (classification) {
            case NO_GENERATED_BODY_CHANGE -> "the generated workflow body did not change";
            case ALREADY_CURRENT -> "already has the Symphony " + runningVersion + " generated body";
            case UNCHANGED_GENERATED_BODY -> "unchanged generated body from " + sourceVersionLabel();
            case USER_PREFIX_SUFFIX ->
                "generated body from " + sourceVersionLabel() + " with your own text before or after it";
            case INTERNALLY_CUSTOMIZED ->
                "the generated body from " + sourceVersionLabel() + " was edited, reordered, or partly removed";
            case DUPLICATE_GENERATED_BODY ->
                "the generated body from " + sourceVersionLabel() + " appears more than once";
            case UNKNOWN_SOURCE ->
                "Symphony has no record of the generated body this workflow started from, and it does not contain"
                        + " the Symphony " + runningVersion + " body";
            case MALFORMED -> problem;
            case UNSUPPORTED_MAJOR_DOWNGRADE ->
                "created by " + sourceVersionLabel() + "; downgrading to another major version is not supported";
        };
    }
}
