package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.CliExitCodes;
import ch.fmartin.symphony.trello.setup.WorkflowBodyClassification.Action;
import ch.fmartin.symphony.trello.setup.WorkflowBodyClassifier.WorkflowBodyAssessment;
import ch.fmartin.symphony.trello.setup.WorkflowMigrationReporter.PreviewOutcome;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
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
/// installers share this command so classification and write decisions follow the same flow.
@NullMarked
final class WorkflowBodyMigration {
    static final String TARGET_BODY_FILE_INFIX = ".symphony-";
    static final String TARGET_BODY_FILE_SUFFIX = "-body.txt";

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
        if (request.markMigrated()
                && request.board().isEmpty()
                && request.workflow().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--mark-migrated needs --board NAME or --workflow PATH.");
        }
        var report = new WorkflowMigrationReporter(
                runningVersionText,
                command,
                request,
                shellArgument,
                terminal.out()); // NOPMD - Terminal owns the stream.
        Optional<SymphonyVersion> running = SymphonyVersion.parse(runningVersionText);
        Optional<SymphonyVersion> from = request.fromVersion().flatMap(SymphonyVersion::parse);
        report.checkStarted();
        if (rejectsMajorDowngrade(from, running, report)) {
            return CliExitCodes.SETUP_FAILURE;
        }
        List<MigrationTarget> targets = targets(request);
        if (targets.isEmpty()) {
            report.noConnectedWorkflows();
            return 0;
        }
        GeneratedWorkflowStore store = GeneratedWorkflowStore.inConfigDir(request.configDir());
        List<GeneratedWorkflowRecord> records = records(store, report);
        List<WorkflowMigrationItem> items = targets.stream()
                .map(target -> assess(target, records, from, running))
                .toList();
        report.comparison(items);
        if (items.stream().anyMatch(item -> item.action() == Action.REJECT)) {
            items.stream().filter(item -> item.action() == Action.REJECT).forEach(report::majorDowngradeNotMigrated);
            return CliExitCodes.SETUP_FAILURE;
        }

        var context = new RunContext(request, store, items, terminal, report);
        if (request.markMigrated()) {
            markManualMigrations(context);
            return 0;
        }
        recordCurrentBodies(context);
        guideManualMigrations(context);
        int failedWrites = replaceGeneratedBodies(context);
        if (failedWrites > 0) {
            // A failed workflow write is unexpected, so it goes through the CLI failure path that
            // writes a sanitized troubleshooting report. The lines above already name each backup.
            throw new TrelloBoardSetupException(
                    "setup_workflow_migration_failed", WorkflowMigrationReporter.failedWritesMessage(failedWrites));
        }
        return 0;
    }

    private static boolean rejectsMajorDowngrade(
            Optional<SymphonyVersion> from, Optional<SymphonyVersion> running, WorkflowMigrationReporter report) {
        Optional<SymphonyVersion> newerMajor = from.filter(source -> newerMajor(source, running));
        newerMajor.ifPresent(report::majorDowngradeRejected);
        return newerMajor.isPresent();
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

    private static List<GeneratedWorkflowRecord> records(
            GeneratedWorkflowStore store, WorkflowMigrationReporter report) {
        try {
            return store.records();
        } catch (IOException | RuntimeException e) {
            // Without readable provenance every workflow falls back to the manual path, which never
            // writes a workflow, so the check can still report safely.
            report.storeUnreadable(store.storePath());
            return List.of();
        }
    }

    private WorkflowMigrationItem assess(
            MigrationTarget target,
            List<GeneratedWorkflowRecord> records,
            Optional<SymphonyVersion> from,
            Optional<SymphonyVersion> running) {
        Path path = target.workflowPath();
        Optional<GeneratedWorkflowRecord> record = GeneratedWorkflowStore.find(records, path);
        WorkflowProvenance provenance = WorkflowProvenance.of(record, from, running);
        if (provenance.newerMajor()) {
            return provenance.item(target, WorkflowBodyClassification.UNSUPPORTED_MAJOR_DOWNGRADE);
        }
        if (Files.isSymbolicLink(path)) {
            return provenance.malformed(
                    target, record, "the workflow path is a symbolic link, and Symphony migrates only regular files");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return provenance.malformed(target, record, "the workflow file is missing");
        }
        String content;
        Map<String, Object> metadata;
        try {
            content = Files.readString(path);
            metadata = new WorkflowLoader().load(path).config();
        } catch (IOException | WorkflowException e) {
            return provenance.malformed(
                    target, record, "the workflow file cannot be read or its front matter is invalid");
        }
        return WorkflowFileText.parse(content)
                .map(text -> assessParsed(target, text, metadata, record, provenance))
                .orElseGet(() -> provenance.malformed(
                        target,
                        record,
                        "the front matter is missing or unclosed, or the file uses lone carriage returns"));
    }

    private WorkflowMigrationItem assessParsed(
            MigrationTarget target,
            WorkflowFileText text,
            Map<String, Object> metadata,
            Optional<GeneratedWorkflowRecord> record,
            WorkflowProvenance provenance) {
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

    private void recordCurrentBodies(RunContext context) {
        if (context.request().dryRun()) {
            return;
        }
        context.items().stream()
                .filter(WorkflowMigrationItem::refreshRecord)
                .forEach(item -> recordTargetBody(context, item));
    }

    private void guideManualMigrations(RunContext context) {
        List<WorkflowMigrationItem> manual = context.items().stream()
                .filter(item -> item.action() == Action.MANUAL || item.action() == Action.REJECT)
                .toList();
        if (manual.isEmpty()) {
            return;
        }
        WorkflowMigrationReporter report = context.report();
        report.manualMigrationsStarted();
        for (WorkflowMigrationItem item : manual) {
            report.manualMigrationHeading(item);
            if (item.action() == Action.REJECT) {
                report.majorDowngradeNotMigrated(item);
            } else {
                item.targetBody()
                        .ifPresentOrElse(
                                targetBody -> guideManualMigration(context.request(), item, targetBody, report),
                                report::fixWorkflowFile);
            }
        }
    }

    private void guideManualMigration(
            WorkflowMigrationRequest request,
            WorkflowMigrationItem item,
            String targetBody,
            WorkflowMigrationReporter report) {
        if (item.classification() == WorkflowBodyClassification.MALFORMED) {
            report.fixWorkflowFile();
        }
        Path preview = targetBodyPath(item.workflowPath());
        PreviewOutcome outcome;
        if (request.dryRun()) {
            outcome = PreviewOutcome.WOULD_WRITE;
        } else if (request.nonInteractive()) {
            // Unattended updates only report, so they do not leave a preview file on every run.
            outcome = PreviewOutcome.NOT_WRITTEN_UNATTENDED;
        } else {
            outcome = writeTargetBody(preview, targetBody) ? PreviewOutcome.WRITTEN : PreviewOutcome.WRITE_FAILED;
        }
        report.manualMigrationSteps(item, preview, outcome);
    }

    /// Returns the number of confirmed replacements that failed.
    private int replaceGeneratedBodies(RunContext context) throws IOException {
        List<WorkflowMigrationItem> replaceable = context.items().stream()
                .filter(item -> item.replacement().isPresent())
                .toList();
        if (replaceable.isEmpty()) {
            return 0;
        }
        WorkflowMigrationReporter report = context.report();
        report.replacementsStarted();
        replaceable.forEach(
                item -> report.replacementExplained(item, item.replacement().orElseThrow()));
        if (context.request().dryRun()) {
            replaceable.forEach(report::wouldReplace);
            return 0;
        }
        if (context.request().nonInteractive()) {
            report.replacementsLeftUnattended(replaceable);
            return 0;
        }
        Terminal terminal = context.terminal();
        ConfirmationMode mode =
                replaceable.size() == 1 ? ConfirmationMode.EACH : ConfirmationMode.ask(terminal, replaceable.size());
        int failedWrites = 0;
        for (WorkflowMigrationItem item : replaceable) {
            if (mode.confirms(terminal, item)) {
                failedWrites += replace(context, item) ? 0 : 1;
            } else {
                report.declined(item);
            }
        }
        return failedWrites;
    }

    private boolean replace(RunContext context, WorkflowMigrationItem item) {
        WorkflowBodyWriter.WriteResult result =
                writer.write(item.workflowPath(), item.replacement().orElseThrow());
        if (!result.written()) {
            context.report().migrationFailed(item, result);
            return false;
        }
        context.report().migrated(item, result.backup().orElseThrow());
        recordTargetBody(context, item);
        return true;
    }

    private void markManualMigrations(RunContext context) {
        WorkflowMigrationReporter report = context.report();
        report.manualRecordsStarted();
        for (WorkflowMigrationItem item : context.items()) {
            Path workflow = item.workflowPath();
            if (item.inputs().isEmpty()) {
                report.noManualRecordNeeded(workflow);
            } else if (context.request().dryRun()) {
                report.wouldRecordMigrated(workflow);
            } else if (recordTargetBody(context, item)) {
                report.recordedMigrated(workflow);
            }
        }
    }

    private boolean recordTargetBody(RunContext context, WorkflowMigrationItem item) {
        Path workflow = item.workflowPath();
        try {
            context.store()
                    .put(GeneratedWorkflowRecord.current(
                            workflow, runningVersionText, item.inputs().orElseThrow()));
            return true;
        } catch (IOException | RuntimeException e) {
            context.report().recordFailed(workflow);
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

    private static boolean newerMajor(SymphonyVersion source, Optional<SymphonyVersion> running) {
        return running.map(current -> source.major() > current.major()).orElse(false);
    }

    /// The state one `migrate-workflows` run shares between its steps.
    private record RunContext(
            WorkflowMigrationRequest request,
            GeneratedWorkflowStore store,
            List<WorkflowMigrationItem> items,
            Terminal terminal,
            WorkflowMigrationReporter report) {}

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

        WorkflowMigrationItem malformed(
                MigrationTarget target, Optional<GeneratedWorkflowRecord> record, String problem) {
            return new WorkflowMigrationItem(
                    target,
                    WorkflowBodyClassification.MALFORMED,
                    problem,
                    sourceVersion,
                    downgrade(),
                    record.map(GeneratedWorkflowRecord::inputs),
                    Optional.empty(),
                    false);
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
