package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.CliExitCodes;
import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/// Builds a config directory with connected workflows, recorded generated bodies, and a migration
/// service whose running version, clock, and file operations the test controls.
final class WorkflowMigrationFixture {
    static final String OLDER_VERSION = "1.3.0";
    static final String RUNNING_VERSION = "1.4.0";
    static final String NEWER_VERSION = "1.5.0";
    static final String COMMAND = "symphony-trello";
    static final Instant NOW = Instant.parse("2026-10-04T08:15:30Z");
    static final String BACKUP_SUFFIX = WorkflowBodyWriter.BACKUP_INFIX + "20261004T081530Z";
    static final GeneratedWorkflowBodyInputs GITHUB_INPUTS = new GeneratedWorkflowBodyInputs(
            List.of("Ready for Codex", "In Progress", "Merging"),
            List.of("Done"),
            "In Progress",
            "Human Review",
            "Blocked",
            "Merging",
            true);
    static final GeneratedWorkflowBodyInputs NON_GITHUB_INPUTS = new GeneratedWorkflowBodyInputs(
            List.of("Ready for Codex", "In Progress"),
            List.of("Done"),
            "In Progress",
            "Human Review",
            null,
            null,
            false);

    private final Path configDir;
    private final List<ConnectedBoard> boards = new ArrayList<>();

    WorkflowMigrationFixture(Path configDir) {
        this.configDir = configDir;
    }

    Path configDir() {
        return configDir;
    }

    /// The body the running version generates for the inputs.
    static String targetBody(GeneratedWorkflowBodyInputs inputs) {
        return TrelloBoardSetup.generatedWorkflowBody(inputs);
    }

    /// A stand-in for the body another Symphony version generated for the same inputs: the target
    /// body with one changed instruction, so both bodies share most of their text.
    static String otherVersionBody(GeneratedWorkflowBodyInputs inputs) {
        String target = targetBody(inputs);
        return target.replace("## Description", "## Card Description (older generated wording)");
    }

    /// The complete workflow file that setup writes for the inputs, with the body replaced.
    static String workflowFile(GeneratedWorkflowBodyInputs inputs, String body) {
        String generated = TrelloBoardSetup.workflowTemplate(
                "board-key",
                inputs,
                Path.of("./workspaces"),
                TrelloBoardSetup.DEFAULT_SERVER_PORT,
                1,
                TrelloBoardSetup.CodexModelDefaults.fallback(),
                TrelloBoardSetup.RepositoryDefaults.empty());
        return WorkflowFileText.parse(generated).orElseThrow().metadata() + body;
    }

    Path connectedWorkflow(String boardName, GeneratedWorkflowBodyInputs inputs, String content) throws IOException {
        Path workflow = configDir.resolve("WORKFLOW." + TrelloBoardSetup.slugify(boardName) + ".md");
        Files.writeString(workflow, content);
        boards.add(new ConnectedBoard(
                "id-" + boards.size(),
                "key-" + boards.size(),
                boardName,
                "https://trello.com/b/key-" + boards.size(),
                workflow.toAbsolutePath().normalize(),
                configDir.resolve(".env"),
                configDir.resolve("workspaces"),
                TrelloBoardSetup.DEFAULT_SERVER_PORT + boards.size(),
                inputs.githubEnabled(),
                List.of(),
                false));
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(boards));
        return workflow;
    }

    void record(Path workflow, String version, GeneratedWorkflowBodyInputs inputs, String body) throws IOException {
        GeneratedWorkflowStore.inConfigDir(configDir).put(new GeneratedWorkflowRecord(workflow, version, inputs, body));
    }

    Optional<GeneratedWorkflowRecord> recorded(Path workflow) throws IOException {
        return GeneratedWorkflowStore.find(
                GeneratedWorkflowStore.inConfigDir(configDir).records(), workflow);
    }

    WorkflowBodyMigration migration() {
        return migration(new WorkflowBodyWriter(clock()));
    }

    WorkflowBodyMigration migration(WorkflowBodyWriter writer) {
        return new WorkflowBodyMigration(RUNNING_VERSION, writer, COMMAND, value -> "'" + value + "'");
    }

    Path targetBodyPath(Path workflow) {
        return migration().targetBodyPath(workflow);
    }

    /// A connected workflow whose body another Symphony version generated, changed by `bodyEdit`, with
    /// that version's body recorded as its provenance.
    GeneratedWorkflow generatedBy(
            String sourceVersion, String boardName, GeneratedWorkflowBodyInputs inputs, UnaryOperator<String> bodyEdit)
            throws IOException {
        String content = workflowFile(inputs, bodyEdit.apply(otherVersionBody(inputs)));
        Path workflow = connectedWorkflow(boardName, inputs, content);
        record(workflow, sourceVersion, inputs, otherVersionBody(inputs));
        return new GeneratedWorkflow(workflow, content);
    }

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    WorkflowMigrationRequest request() {
        return new WorkflowMigrationRequest(
                configDir, Optional.empty(), Optional.empty(), false, false, Optional.empty(), false);
    }

    WorkflowMigrationRequest dryRun() {
        return new WorkflowMigrationRequest(
                configDir, Optional.empty(), Optional.empty(), true, false, Optional.empty(), false);
    }

    WorkflowMigrationRequest nonInteractive() {
        return new WorkflowMigrationRequest(
                configDir, Optional.empty(), Optional.empty(), false, true, Optional.empty(), false);
    }

    WorkflowMigrationRequest fromVersion(String version) {
        return new WorkflowMigrationRequest(
                configDir, Optional.empty(), Optional.empty(), false, false, Optional.of(version), false);
    }

    WorkflowMigrationRequest board(String selector) {
        return new WorkflowMigrationRequest(
                configDir, Optional.of(selector), Optional.empty(), false, false, Optional.empty(), false);
    }

    WorkflowMigrationRequest explicitWorkflow(Path workflow, boolean markMigrated) {
        return new WorkflowMigrationRequest(
                configDir, Optional.empty(), Optional.of(workflow), false, false, Optional.empty(), markMigrated);
    }

    MigrationRun run(WorkflowMigrationRequest request, String... answers) throws IOException {
        return run(migration(), request, answers);
    }

    /// Runs the migration the way the CLI does: an expected or unexpected setup failure becomes exit
    /// code 2 with its error code.
    static MigrationRun run(WorkflowBodyMigration migration, WorkflowMigrationRequest request, String... answers)
            throws IOException {
        var terminal = new RecordingTerminal(answers);
        try {
            return new MigrationRun(migration.run(request, terminal), terminal.stdout(), Optional.empty());
        } catch (TrelloBoardSetupException e) {
            return new MigrationRun(CliExitCodes.SETUP_FAILURE, terminal.stdout(), Optional.of(e.code()));
        }
    }

    static Path backupOf(Path workflow) {
        return workflow.resolveSibling(PathNames.fileName(workflow) + BACKUP_SUFFIX);
    }

    record MigrationRun(int exitCode, String output, Optional<String> failureCode) {}

    record GeneratedWorkflow(Path path, String content) {}
}
