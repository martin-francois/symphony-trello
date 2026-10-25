package ch.fmartin.symphony.trello.setup;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;

/// The output of `symphony-trello migrate-workflows` (ADR 0095). [WorkflowBodyMigration] decides what
/// happens to each workflow; this class owns how those decisions read, so wording changes stay here.
@NullMarked
final class WorkflowMigrationReporter {
    static final String GUIDE_ANCHOR = "migrate-generated-workflows";

    private static final String REPOSITORY_URL = "https://github.com/martin-francois/symphony-trello";

    private final String runningVersionText;
    private final String baseCommand;
    private final String selectedCommand;
    private final UnaryOperator<String> shellArgument;
    private final PrintStream out;

    WorkflowMigrationReporter(
            String runningVersionText,
            String command,
            WorkflowMigrationRequest request,
            UnaryOperator<String> shellArgument,
            PrintStream out) {
        this.runningVersionText = runningVersionText;
        this.baseCommand = command + " migrate-workflows --config-dir "
                + shellArgument.apply(request.configDir().toString());
        this.selectedCommand = baseCommand
                + request.board()
                        .map(board -> " --board " + shellArgument.apply(board))
                        .orElseGet(() -> request.workflow()
                                .map(workflow -> " --workflow " + shellArgument.apply(workflow.toString()))
                                .orElse(""));
        this.shellArgument = shellArgument;
        this.out = out;
    }

    /// What became of the target body preview for a manual migration.
    enum PreviewOutcome {
        WOULD_WRITE,
        NOT_WRITTEN_UNATTENDED,
        WRITTEN,
        WRITE_FAILED
    }

    void checkStarted() {
        out.println();
        out.println("Generated workflow check");
    }

    void majorDowngradeRejected(SymphonyVersion from) {
        out.println("  REJECTED  Symphony " + from + " was replaced by " + running()
                + ". Downgrading to an older major version is not supported, so no workflow was checked or"
                + " changed.");
        out.println("            Reinstall a Symphony " + from.major() + ".x release to keep using these workflows.");
    }

    void noConnectedWorkflows() {
        out.println("  OK  No connected workflows to check.");
    }

    void storeUnreadable(Path storePath) {
        out.println("  WARN  Could not read the recorded generated bodies. Every workflow is treated as"
                + " having no record until you repair or remove this file:");
        out.println("        " + storePath);
    }

    void comparison(List<WorkflowMigrationItem> items) {
        out.println(running() + " compared " + quantity(items.size(), "workflow", "workflows")
                + " with the workflow body it generates.");
        items.forEach(item -> out.println("  %-9s %s: %s".formatted(status(item), item.heading(), description(item))));
    }

    void manualMigrationsStarted() {
        out.println();
        out.println("Manual workflow migration needed");
    }

    void manualMigrationHeading(WorkflowMigrationItem item) {
        out.println("  " + item.heading());
    }

    void majorDowngradeNotMigrated(WorkflowMigrationItem item) {
        out.println("    " + running() + " does not migrate workflows from another major"
                + " version. Reinstall a release with the same major version as "
                + item.sourceVersionLabel() + " to keep using this workflow.");
    }

    void fixWorkflowFile() {
        out.println("    Fix the workflow file, then run " + migrateCommand() + " again.");
    }

    void manualMigrationSteps(WorkflowMigrationItem item, Path preview, PreviewOutcome outcome) {
        switch (outcome) {
            case WOULD_WRITE -> {
                out.println("    WOULD write the " + generatedBody() + " to:");
                out.println("      " + preview);
            }
            case NOT_WRITTEN_UNATTENDED ->
                out.println("    Run " + migrateCommand() + " in a terminal to write the " + generatedBody()
                        + " next to this workflow.");
            case WRITTEN -> {
                out.println("    " + generatedBody() + ":");
                out.println("      " + preview);
            }
            case WRITE_FAILED -> out.println("    Could not write the generated body preview next to the workflow.");
        }
        if (item.classification() == WorkflowBodyClassification.UNKNOWN_SOURCE) {
            out.println("    Symphony rendered this body from the workflow's current list settings.");
        }
        if (item.classification() == WorkflowBodyClassification.MALFORMED) {
            out.println("    1. Repair the workflow file and its metadata before applying the generated body.");
        } else {
            out.println("    1. Keep the workflow metadata unchanged: everything up to and including the closing ---"
                    + " line.");
        }
        out.println("    2. Compare the text after that line with the generated body, copy the generated text you"
                + " want, and keep your own additions.");
        out.println("    3. Then record the migration with this command:");
        out.println("      " + baseCommand + " --workflow "
                + shellArgument.apply(item.workflowPath().toString()) + " --mark-migrated");
        guideLink().ifPresent(link -> out.println("    Guide: " + link));
        if (item.downgrade()) {
            out.println("    " + downgradeWarning(item));
        }
    }

    void replacementsStarted() {
        out.println();
        out.println("Workflow body migrations");
    }

    void replacementExplained(WorkflowMigrationItem item, WorkflowBodyReplacement replacement) {
        out.println("  " + item.heading());
        if (item.downgrade()) {
            out.println("    This workflow was created for a newer Symphony version (" + item.sourceVersionLabel()
                    + ") than " + running() + ". Migrating replaces its generated body with the"
                    + " body that " + running() + " generates.");
        } else {
            out.println("    The generated workflow changed since " + item.sourceVersionLabel()
                    + ". Migrating gives this workflow " + fixesAndFeatures() + ".");
        }
        out.println("    Only the text after the closing --- line changes. The workflow metadata stays as it is.");
        if (replacement.preservesUserAdditions()) {
            out.println(
                    "    Your text " + additionPlacement(replacement) + " the generated body stays exactly as it is.");
            out.println("    Warning: your kept text can conflict with the new generated body and cause unexpected"
                    + " behavior. Review it after the migration.");
        }
    }

    void wouldReplace(WorkflowMigrationItem item) {
        out.println("  WOULD back up and replace the generated body in:");
        out.println("    " + item.workflowPath());
    }

    void replacementsLeftUnattended(List<WorkflowMigrationItem> replaceable) {
        out.println("  No workflow was changed. Run " + migrateCommand()
                + " in a terminal to review and apply these migrations.");
        replaceable.stream()
                .filter(WorkflowMigrationItem::downgrade)
                .forEach(item -> out.println("  " + downgradeWarning(item)));
    }

    void migrated(WorkflowMigrationItem item, Path backup) {
        out.println("  OK  Migrated " + item.workflowPath());
        out.println("      Backup: " + backup);
        out.println("      To undo, copy the backup over the workflow. If " + running()
                + " causes problems, also reinstall " + item.sourceVersionLabel()
                + " with the installer's --version option.");
    }

    void migrationFailed(WorkflowMigrationItem item, WorkflowBodyWriter.WriteResult result) {
        out.println("  FAILED  Could not migrate this workflow (" + result.failure() + "):");
        out.println("          " + item.workflowPath());
        switch (result.outcome()) {
            case UNCHANGED -> out.println("          Symphony left the existing workflow in place.");
            case RESTORED ->
                out.println("          Symphony restored the original workflow from the backup: "
                        + result.backup().orElseThrow());
            case RESTORE_FAILED ->
                out.println(
                        "          cannot restore the original workflow: restore the backup before restarting workers");
            case WRITTEN ->
                throw new IllegalArgumentException("cannot report a migration failure: the write succeeded");
        }
        result.backup().ifPresent(backup -> out.println("          The backup of the original workflow is: " + backup));
    }

    void declined(WorkflowMigrationItem item) {
        out.println("  SKIPPED  This workflow keeps its current body: " + item.workflowPath());
        if (item.downgrade()) {
            out.println("           " + downgradeWarning(item));
        } else {
            out.println("           It does not get " + fixesAndFeatures() + ". Run " + migrateCommand()
                    + " to migrate it later.");
        }
    }

    void manualRecordsStarted() {
        out.println();
    }

    void noManualRecordNeeded(Path workflow) {
        out.println("  OK  " + workflow + " needs no manual migration record.");
    }

    void wouldRecordMigrated(Path workflow) {
        out.println("  WOULD record " + workflow + " as migrated to the " + generatedBody() + ".");
    }

    void recordedMigrated(Path workflow) {
        out.println("  OK  Recorded " + workflow + " as migrated to the " + generatedBody()
                + ". Later updates compare against that body.");
    }

    void recordFailed(Path workflow) {
        out.println("  WARN  Could not record the generated body of " + workflow
                + " in the provenance store. The next check may ask about this workflow again.");
    }

    static String failedWritesMessage(int failedWrites) {
        return "Could not migrate " + quantity(failedWrites, "confirmed workflow body", "confirmed workflow bodies")
                + ". The output above says where each backup is and whether the original is in place.";
    }

    /// `count` followed by the singular or plural noun, such as `1 workflow` or `2 workflows`.
    static String quantity(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private static String status(WorkflowMigrationItem item) {
        return switch (item.classification()) {
            case NO_GENERATED_BODY_CHANGE -> "OK";
            case ALREADY_CURRENT -> "CURRENT";
            case UNCHANGED_GENERATED_BODY, USER_PREFIX_SUFFIX -> "MIGRATE";
            case INTERNALLY_CUSTOMIZED, DUPLICATE_GENERATED_BODY, UNKNOWN_SOURCE, MALFORMED -> "MANUAL";
            case UNSUPPORTED_MAJOR_DOWNGRADE -> "REJECTED";
        };
    }

    private String description(WorkflowMigrationItem item) {
        String source = item.sourceVersionLabel();
        return switch (item.classification()) {
            case NO_GENERATED_BODY_CHANGE -> "the generated workflow body did not change";
            case ALREADY_CURRENT -> "already has the " + generatedBody();
            case UNCHANGED_GENERATED_BODY -> "unchanged generated body from " + source;
            case USER_PREFIX_SUFFIX -> "generated body from " + source + " with your own text before or after it";
            case INTERNALLY_CUSTOMIZED ->
                "the generated body from " + source + " was edited, reordered, or partly removed";
            case DUPLICATE_GENERATED_BODY -> "the generated body from " + source + " appears more than once";
            case UNKNOWN_SOURCE ->
                "Symphony has no record of the generated body this workflow started from, and it does not contain"
                        + " the " + running() + " body";
            case MALFORMED -> item.problem();
            case UNSUPPORTED_MAJOR_DOWNGRADE ->
                "created by " + source + "; downgrading to another major version is not supported";
        };
    }

    private static String additionPlacement(WorkflowBodyReplacement replacement) {
        if (!replacement.preservedPrefix().isEmpty()
                && !replacement.preservedSuffix().isEmpty()) {
            return "before and after";
        }
        return replacement.preservedPrefix().isEmpty() ? "after" : "before";
    }

    private String downgradeWarning(WorkflowMigrationItem item) {
        return "Warning: this workflow was created for " + item.sourceVersionLabel() + ", which is newer than "
                + running() + ". It may mention features that " + running()
                + " does not support and can behave unexpectedly.";
    }

    private Optional<String> guideLink() {
        return SymphonyVersion.parse(runningVersionText)
                .filter(SymphonyVersion::release)
                .map(version -> REPOSITORY_URL + "/blob/v" + version + "/README.md#" + GUIDE_ANCHOR);
    }

    private String migrateCommand() {
        return selectedCommand;
    }

    private String running() {
        return "Symphony " + runningVersionText;
    }

    private String generatedBody() {
        return running() + " generated body";
    }

    private String fixesAndFeatures() {
        return "the " + running() + " workflow fixes and features";
    }
}
