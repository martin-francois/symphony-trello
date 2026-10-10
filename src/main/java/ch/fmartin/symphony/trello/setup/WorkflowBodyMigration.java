package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.CliExitCodes;
import ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.Action;
import ch.fmartin.symphony.trello.setup.WorkflowBodyClassifier.WorkflowBodyAssessment;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Java-owned workflow body migration for `symphony-trello migrate-workflows` (ADR 0095). Both
/// installers call this command after an update or a supported downgrade, so the classification and
/// every write decision live here instead of in shell or PowerShell.
@NullMarked
final class WorkflowBodyMigration {
    static final String GUIDE_ANCHOR = "migrate-generated-workflows";
    static final String TARGET_BODY_FILE_INFIX = ".symphony-";
    static final String TARGET_BODY_FILE_SUFFIX = "-body.txt";

    private static final String REPOSITORY_URL = "https://github.com/martin-francois/symphony-trello";

    private final String runningVersionText;
    private final WorkflowBodyWriter writer;
    private final String command;
    private final UnaryOperator<String> shellArgument;

    WorkflowBodyMigration(
            String runningVersionText, WorkflowBodyWriter writer, String command, UnaryOperator<String> shellArgument) {
        this.runningVersionText = runningVersionText;
        this.writer = writer;
        this.command = command;
        this.shellArgument = shellArgument;
    }

    int run(WorkflowMigrationRequest request, Terminal terminal) throws IOException {
        PrintStream out = terminal.out(); // NOPMD - Terminal owns the stream.
        if (request.markMigrated()
                && request.board().isEmpty()
                && request.workflow().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--mark-migrated needs --board NAME or --workflow PATH.");
        }
        Optional<SymphonyVersion> running = SymphonyVersion.parse(runningVersionText);
        Optional<SymphonyVersion> from = request.fromVersion().flatMap(SymphonyVersion::parse);
        out.println();
        out.println("Generated workflow check");
        Optional<SymphonyVersion> newerMajor = from.filter(source -> newerMajor(source, running));
        if (newerMajor.isPresent()) {
            printMajorDowngradeRejection(newerMajor.orElseThrow(), out);
            return CliExitCodes.SETUP_FAILURE;
        }
        List<MigrationTarget> targets = targets(request);
        if (targets.isEmpty()) {
            out.println("  OK  No connected workflows to check.");
            return 0;
        }
        GeneratedWorkflowStore store = GeneratedWorkflowStore.inConfigDir(request.configDir());
        List<GeneratedWorkflowRecord> records = records(store, out);
        List<WorkflowMigrationItem> items = targets.stream()
                .map(target -> assess(target, records, from, running))
                .toList();
        out.println("Symphony " + runningVersionText + " compared " + items.size()
                + (items.size() == 1 ? " workflow" : " workflows") + " with the workflow body it generates.");
        items.forEach(item -> out.println(item.reportLine(runningVersionText)));

        recordCurrentBodies(request, store, items, out);
        guideManualMigrations(request, items, out);
        int failedWrites = replaceGeneratedBodies(request, store, items, terminal);
        markManualMigrations(request, store, items, out);
        if (failedWrites > 0) {
            // A failed workflow write is unexpected, so it goes through the CLI failure path that
            // writes a sanitized troubleshooting report. The lines above already name each backup.
            throw new TrelloBoardSetupException(
                    "setup_workflow_migration_failed",
                    "Could not migrate " + failedWrites + " confirmed workflow body"
                            + (failedWrites == 1 ? "" : "s")
                            + ". The output above says where each backup is and whether the original is in place.");
        }
        return items.stream().anyMatch(item -> item.action() == Action.REJECT) ? CliExitCodes.SETUP_FAILURE : 0;
    }

    private void printMajorDowngradeRejection(SymphonyVersion from, PrintStream out) {
        out.println("  REJECTED  Symphony " + from + " was replaced by Symphony " + runningVersionText
                + ". Downgrading to an older major version is not supported, so no workflow was checked or"
                + " changed.");
        out.println("            Reinstall a Symphony " + from.major() + ".x release to keep using these workflows.");
    }

    private static List<MigrationTarget> targets(WorkflowMigrationRequest request) throws IOException {
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(
                        request.configDir().resolve(ConnectedBoardManifest.FILE_NAME))
                .loadForLifecycle();
        return request.board()
                .map(selector -> List.of(MigrationTarget.of(manifest.selectBoard(selector))))
                .or(() -> request.workflow().map(workflow -> List.of(explicitTarget(manifest, workflow))))
                .orElseGet(() ->
                        manifest.boards().stream().map(MigrationTarget::of).toList());
    }

    private static MigrationTarget explicitTarget(ConnectedBoardManifest manifest, Path workflow) {
        return manifest.selectWorkflow(workflow)
                .map(board -> new MigrationTarget(board.boardName(), workflow, Optional.of(board.githubEnabled())))
                .orElseGet(() -> new MigrationTarget(PathNames.fileName(workflow), workflow, Optional.empty()));
    }

    private static List<GeneratedWorkflowRecord> records(GeneratedWorkflowStore store, PrintStream out) {
        try {
            return store.records();
        } catch (IOException | RuntimeException e) {
            // Without readable provenance every workflow falls back to the manual path, which never
            // writes a workflow, so the check can still report safely.
            out.println("  WARN  Could not read the recorded generated bodies. Every workflow is treated as"
                    + " having no record until you repair or remove this file:");
            out.println("        " + store.storePath());
            return List.of();
        }
    }

    private WorkflowMigrationItem assess(
            MigrationTarget target,
            List<GeneratedWorkflowRecord> records,
            Optional<SymphonyVersion> from,
            Optional<SymphonyVersion> running) {
        Path path = target.workflowPath();
        if (Files.isSymbolicLink(path)) {
            return WorkflowMigrationItem.malformed(
                    target, "the workflow path is a symbolic link, and Symphony migrates only regular files");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return WorkflowMigrationItem.malformed(target, "the workflow file is missing");
        }
        String content;
        Map<String, Object> metadata;
        try {
            content = Files.readString(path);
            metadata = new WorkflowLoader().load(path).config();
        } catch (IOException | WorkflowException e) {
            return WorkflowMigrationItem.malformed(
                    target, "the workflow file cannot be read or its front matter is invalid");
        }
        Optional<GeneratedWorkflowRecord> record = GeneratedWorkflowStore.find(records, path);
        WorkflowProvenance provenance = WorkflowProvenance.of(record, from, running);
        return WorkflowFileText.parse(content)
                .map(text -> assessParsed(target, text, metadata, record, provenance))
                .orElseGet(() -> WorkflowMigrationItem.malformed(
                        target, "the front matter is missing or unclosed, or the file uses lone carriage returns"));
    }

    private WorkflowMigrationItem assessParsed(
            MigrationTarget target,
            WorkflowFileText text,
            Map<String, Object> metadata,
            Optional<GeneratedWorkflowRecord> record,
            WorkflowProvenance provenance) {
        if (record.isPresent() && provenance.newerMajor()) {
            return provenance.item(target, WorkflowBodyClassification.UNSUPPORTED_MAJOR_DOWNGRADE);
        }
        return record.map(GeneratedWorkflowRecord::inputs)
                .or(() -> GeneratedWorkflowBodyInputs.fromMetadata(metadata, target.githubEnabled()))
                .map(inputs -> classify(target, text, inputs, record, provenance))
                .orElseGet(() -> provenance.item(target, WorkflowBodyClassification.UNKNOWN_SOURCE));
    }

    private static WorkflowMigrationItem classify(
            MigrationTarget target,
            WorkflowFileText text,
            GeneratedWorkflowBodyInputs inputs,
            Optional<GeneratedWorkflowRecord> record,
            WorkflowProvenance provenance) {
        String targetBody = TrelloBoardSetup.generatedWorkflowBody(inputs);
        WorkflowBodyAssessment assessment = WorkflowBodyClassifier.classify(
                text.normalizedBody(),
                record.map(GeneratedWorkflowRecord::body).map(WorkflowFileText::normalize),
                targetBody);
        Optional<WorkflowBodyReplacement> replacement =
                assessment.classification().action() == Action.REPLACE
                        ? Optional.of(new WorkflowBodyReplacement(
                                text,
                                text.body().substring(0, text.rawBodyOffset(assessment.sourceStart())),
                                text.body().substring(text.rawBodyOffset(assessment.sourceEnd())),
                                targetBody))
                        : Optional.empty();
        boolean refreshRecord = assessment.classification() == WorkflowBodyClassification.ALREADY_CURRENT
                && !record.map(GeneratedWorkflowRecord::body)
                        .map(targetBody::equals)
                        .orElse(false);
        return new WorkflowMigrationItem(
                target,
                assessment.classification(),
                "",
                provenance.sourceVersion(),
                provenance.downgrade(),
                Optional.of(inputs),
                replacement,
                refreshRecord);
    }

    private void recordCurrentBodies(
            WorkflowMigrationRequest request,
            GeneratedWorkflowStore store,
            List<WorkflowMigrationItem> items,
            PrintStream out) {
        if (request.dryRun()) {
            return;
        }
        items.stream().filter(WorkflowMigrationItem::refreshRecord).forEach(item -> recordTargetBody(store, item, out));
    }

    private void guideManualMigrations(
            WorkflowMigrationRequest request, List<WorkflowMigrationItem> items, PrintStream out) {
        List<WorkflowMigrationItem> manual = items.stream()
                .filter(item -> item.action() == Action.MANUAL || item.action() == Action.REJECT)
                .toList();
        if (manual.isEmpty()) {
            return;
        }
        out.println();
        out.println("Manual workflow migration needed");
        for (WorkflowMigrationItem item : manual) {
            out.println("  " + item.heading());
            if (item.action() == Action.REJECT) {
                out.println("    Symphony " + runningVersionText + " does not migrate workflows from another major"
                        + " version. Reinstall a release with the same major version as "
                        + item.sourceVersionLabel() + " to keep using this workflow.");
            } else {
                item.targetBody()
                        .ifPresentOrElse(
                                targetBody -> guideManualMigration(request, item, targetBody, out),
                                () -> out.println(
                                        "    Fix the workflow file, then run " + migrateCommand() + " again."));
            }
        }
    }

    private void guideManualMigration(
            WorkflowMigrationRequest request, WorkflowMigrationItem item, String targetBody, PrintStream out) {
        Path preview = targetBodyPath(item.workflowPath());
        if (request.dryRun()) {
            out.println("    WOULD write the Symphony " + runningVersionText + " generated body to:");
            out.println("      " + preview);
        } else if (request.nonInteractive()) {
            // Unattended updates only report, so they do not leave a preview file on every run.
            out.println("    Run " + migrateCommand() + " in a terminal to write the Symphony " + runningVersionText
                    + " generated body next to this workflow.");
        } else if (writeTargetBody(preview, targetBody)) {
            out.println("    Symphony " + runningVersionText + " generated body:");
            out.println("      " + preview);
        } else {
            out.println("    Could not write the generated body preview next to the workflow.");
        }
        if (item.classification() == WorkflowBodyClassification.UNKNOWN_SOURCE) {
            out.println("    Symphony rendered this body from the workflow's current list settings.");
        }
        out.println("    1. Keep the workflow metadata unchanged: everything up to and including the closing ---"
                + " line.");
        out.println("    2. Compare the text after that line with the generated body, copy the generated text you"
                + " want, and keep your own additions.");
        out.println("    3. Then record the migration with this command:");
        out.println("      " + migrateCommand() + " --workflow "
                + shellArgument.apply(item.workflowPath().toString()) + " --mark-migrated");
        guideLink().ifPresent(link -> out.println("    Guide: " + link));
        if (item.downgrade()) {
            out.println("    " + downgradeWarning(item));
        }
    }

    /// Returns the number of confirmed replacements that failed.
    private int replaceGeneratedBodies(
            WorkflowMigrationRequest request,
            GeneratedWorkflowStore store,
            List<WorkflowMigrationItem> items,
            Terminal terminal)
            throws IOException {
        List<WorkflowMigrationItem> replaceable =
                items.stream().filter(item -> item.replacement().isPresent()).toList();
        if (replaceable.isEmpty()) {
            return 0;
        }
        PrintStream out = terminal.out(); // NOPMD - Terminal owns the stream.
        out.println();
        out.println("Workflow body migrations");
        replaceable.forEach(item -> explainReplacement(item, out));
        if (request.dryRun()) {
            replaceable.forEach(item -> {
                out.println("  WOULD back up and replace the generated body in:");
                out.println("    " + item.workflowPath());
            });
            return 0;
        }
        if (request.nonInteractive()) {
            out.println("  No workflow was changed. Run " + migrateCommand()
                    + " in a terminal to review and apply these migrations.");
            replaceable.stream()
                    .filter(WorkflowMigrationItem::downgrade)
                    .forEach(item -> out.println("  " + downgradeWarning(item)));
            return 0;
        }
        ConfirmationMode mode =
                replaceable.size() == 1 ? ConfirmationMode.EACH : ConfirmationMode.ask(terminal, replaceable.size());
        int failedWrites = 0;
        for (WorkflowMigrationItem item : replaceable) {
            if (mode.confirms(terminal, item)) {
                failedWrites += replace(store, item, out) ? 0 : 1;
            } else {
                printDeclined(item, out);
            }
        }
        return failedWrites;
    }

    private void explainReplacement(WorkflowMigrationItem item, PrintStream out) {
        WorkflowBodyReplacement replacement = item.replacement().orElseThrow();
        out.println("  " + item.heading());
        if (item.downgrade()) {
            out.println("    This workflow was created for a newer Symphony version (" + item.sourceVersionLabel()
                    + ") than Symphony " + runningVersionText + ". Migrating replaces its generated body with the"
                    + " body that Symphony " + runningVersionText + " generates.");
        } else {
            out.println("    The generated workflow changed since " + item.sourceVersionLabel()
                    + ". Migrating gives this workflow the Symphony " + runningVersionText
                    + " workflow fixes and features.");
        }
        out.println("    Only the text after the closing --- line changes. The workflow metadata stays as it is.");
        if (replacement.preservesUserAdditions()) {
            out.println(
                    "    Your text " + additionPlacement(replacement) + " the generated body stays exactly as it is.");
            out.println("    Warning: your kept text can conflict with the new generated body and cause unexpected"
                    + " behavior. Review it after the migration.");
        }
    }

    private static String additionPlacement(WorkflowBodyReplacement replacement) {
        if (!replacement.preservedPrefix().isEmpty()
                && !replacement.preservedSuffix().isEmpty()) {
            return "before and after";
        }
        return replacement.preservedPrefix().isEmpty() ? "after" : "before";
    }

    private boolean replace(GeneratedWorkflowStore store, WorkflowMigrationItem item, PrintStream out) {
        Path workflow = item.workflowPath();
        WorkflowBodyWriter.WriteResult result =
                writer.write(workflow, item.replacement().orElseThrow());
        if (!result.written()) {
            out.println("  FAILED  Could not migrate this workflow (" + result.failure() + "):");
            out.println("          " + workflow);
            result.backup()
                    .ifPresent(backup -> out.println(
                            result.restored()
                                    ? "          Symphony restored the original workflow from the backup: " + backup
                                    : "          The backup of the original workflow is: " + backup));
            return false;
        }
        Path backup = result.backup().orElseThrow();
        out.println("  OK  Migrated " + workflow);
        out.println("      Backup: " + backup);
        out.println("      To undo, copy the backup over the workflow. If Symphony " + runningVersionText
                + " causes problems, also reinstall " + item.sourceVersionLabel()
                + " with the installer's --version option.");
        recordTargetBody(store, item, out);
        return true;
    }

    private void printDeclined(WorkflowMigrationItem item, PrintStream out) {
        out.println("  SKIPPED  This workflow keeps its current body: " + item.workflowPath());
        if (item.downgrade()) {
            out.println("           " + downgradeWarning(item));
        } else {
            out.println("           It does not get the Symphony " + runningVersionText
                    + " workflow fixes and features. Run " + migrateCommand() + " to migrate it later.");
        }
    }

    private String downgradeWarning(WorkflowMigrationItem item) {
        return "Warning: this workflow was created for " + item.sourceVersionLabel()
                + ", which is newer than Symphony " + runningVersionText + ". It may mention features that Symphony "
                + runningVersionText + " does not support and can behave unexpectedly.";
    }

    private void markManualMigrations(
            WorkflowMigrationRequest request,
            GeneratedWorkflowStore store,
            List<WorkflowMigrationItem> items,
            PrintStream out) {
        if (!request.markMigrated()) {
            return;
        }
        out.println();
        for (WorkflowMigrationItem item : items) {
            Path workflow = item.workflowPath();
            if (item.action() != Action.MANUAL || item.inputs().isEmpty()) {
                out.println("  OK  " + workflow + " needs no manual migration record.");
            } else if (request.dryRun()) {
                out.println("  WOULD record " + workflow + " as migrated to the Symphony " + runningVersionText
                        + " generated body.");
            } else if (recordTargetBody(store, item, out)) {
                out.println("  OK  Recorded " + workflow + " as migrated to the Symphony " + runningVersionText
                        + " generated body. Later updates compare against that body.");
            }
        }
    }

    private boolean recordTargetBody(GeneratedWorkflowStore store, WorkflowMigrationItem item, PrintStream out) {
        Path workflow = item.workflowPath();
        try {
            store.put(GeneratedWorkflowRecord.current(
                    workflow, runningVersionText, item.inputs().orElseThrow()));
            return true;
        } catch (IOException | RuntimeException e) {
            out.println("  WARN  Could not record the generated body of " + workflow
                    + " in the provenance store. The next check may ask about this workflow again.");
            return false;
        }
    }

    private static boolean writeTargetBody(Path preview, String targetBody) {
        if (Files.isSymbolicLink(preview) || Files.isDirectory(preview, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        @Nullable Path temporary = null;
        try {
            temporary = AtomicFiles.siblingTemporaryFile(preview);
            Files.writeString(temporary, targetBody);
            AtomicFiles.replace(temporary, preview);
            return true;
        } catch (IOException e) {
            AtomicFiles.deleteQuietly(temporary);
            return false;
        }
    }

    Path targetBodyPath(Path workflowPath) {
        return workflowPath.resolveSibling(PathNames.fileName(workflowPath)
                + TARGET_BODY_FILE_INFIX
                + runningVersionText
                + TARGET_BODY_FILE_SUFFIX);
    }

    private Optional<String> guideLink() {
        return SymphonyVersion.parse(runningVersionText)
                .filter(SymphonyVersion::release)
                .map(version -> REPOSITORY_URL + "/blob/v" + version + "/README.md#" + GUIDE_ANCHOR);
    }

    private String migrateCommand() {
        return command + " migrate-workflows";
    }

    private static boolean newerMajor(SymphonyVersion source, Optional<SymphonyVersion> running) {
        return running.map(current -> source.major() > current.major()).orElse(false);
    }

    record MigrationTarget(String label, Path workflowPath, Optional<Boolean> githubEnabled) {
        MigrationTarget {
            workflowPath = workflowPath.toAbsolutePath().normalize();
        }

        static MigrationTarget of(ConnectedBoard board) {
            return new MigrationTarget(board.boardName(), board.workflowPath(), Optional.of(board.githubEnabled()));
        }
    }

    /// The version a workflow body came from: the recorded version, or the previously installed version
    /// passed by the installer for a workflow without a record.
    private record WorkflowProvenance(
            Optional<String> sourceVersion,
            Optional<SymphonyVersion> parsedSourceVersion,
            Optional<SymphonyVersion> running) {
        static WorkflowProvenance of(
                Optional<GeneratedWorkflowRecord> record,
                Optional<SymphonyVersion> from,
                Optional<SymphonyVersion> running) {
            Optional<String> recordedVersion = record.map(GeneratedWorkflowRecord::symphonyVersion);
            return new WorkflowProvenance(
                    recordedVersion.or(() -> from.map(SymphonyVersion::toString)),
                    recordedVersion.flatMap(SymphonyVersion::parse).or(() -> from),
                    running);
        }

        boolean downgrade() {
            return parsedSourceVersion
                    .flatMap(source -> running.map(current -> source.compareTo(current) > 0))
                    .orElse(false);
        }

        boolean newerMajor() {
            return parsedSourceVersion
                    .map(source -> WorkflowBodyMigration.newerMajor(source, running))
                    .orElse(false);
        }

        WorkflowMigrationItem item(MigrationTarget target, WorkflowBodyClassification classification) {
            return new WorkflowMigrationItem(
                    target, classification, "", sourceVersion, downgrade(), Optional.empty(), Optional.empty(), false);
        }
    }

    /// How the operator confirms several replaceable workflows.
    private enum ConfirmationMode {
        ALL,
        EACH,
        NONE;

        static ConfirmationMode ask(Terminal terminal, int count) throws IOException {
            String answer = terminal.readLine("Replace the generated body in all " + count
                    + " workflows above? Enter all, each, or none [each]: ");
            if (answer == null) {
                return NONE;
            }
            String normalized = answer.strip().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || name(EACH).startsWith(normalized)) {
                return EACH;
            }
            return name(ALL).startsWith(normalized) ? ALL : NONE;
        }

        boolean confirms(Terminal terminal, WorkflowMigrationItem item) throws IOException {
            return this == ALL
                    || (this == EACH
                            && PromptSupport.yes(
                                    terminal, "Replace the generated body in " + item.heading() + "? [y/N] "));
        }

        private static String name(ConfirmationMode mode) {
            return mode.name().toLowerCase(Locale.ROOT);
        }
    }
}
