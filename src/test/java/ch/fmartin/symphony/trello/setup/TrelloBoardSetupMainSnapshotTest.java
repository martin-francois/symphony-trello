package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.testsupport.CredentialSentinels.TRELLO_API_KEY;
import static ch.fmartin.symphony.trello.testsupport.CredentialSentinels.TRELLO_API_TOKEN;
import static ch.fmartin.symphony.trello.testsupport.TestRepositoryUrls.HTTPS;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.CliRunResult;
import ch.fmartin.symphony.trello.testsupport.TerminalSnapshots;
import ch.fmartin.symphony.trello.testsupport.TranscriptNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/// Command-boundary happy-path snapshots for every public `symphony-trello` command.
///
/// Each test owns its fixture state below its own temporary directory, so the suite stays safe
/// under parallel JUnit execution. The detailed validation and failure matrices stay in the
/// lower-layer tests (ADR 0055); these snapshots protect the complete user-visible transcript of
/// one successful run per command.
@DisabledOnOs(
        value = OS.WINDOWS,
        disabledReason =
                "The baselines record POSIX paths and quoting; run scripts/snapshot-tests-docker.sh on Windows")
final class TrelloBoardSetupMainSnapshotTest extends LocalSetupFixtureSupport {
    private static final TerminalSnapshots SNAPSHOTS = TerminalSnapshots.repository();
    private static final String BOARD_URL = "https://trello.com/b/abc123/snapshot-queue";
    private static final String BOARD_NAME = "Snapshot Queue";
    private static final int FIXED_SERVER_PORT = 18_080;
    private static final long FIXED_WORKER_PID = 4_242;

    @Test
    void rootHelp() {
        // given
        String[] command = {"--help"};

        // when
        CliRunResult result = runCli(command);

        // then
        SNAPSHOTS.verify("cli/root-help", result, normalizer());
    }

    @Test
    void setupLocal() throws Exception {
        // given
        commands.skipHealthStart = true;

        // when
        CliRunResult result = runCli(setupLocalArguments(FIXED_SERVER_PORT));

        // then
        SNAPSHOTS.verify("cli/setup-local", result, normalizer());
        assertThat(fixture.envPath()).content(StandardCharsets.UTF_8).contains(TRELLO_API_KEY, TRELLO_API_TOKEN);
        assertThat(commands.startedWorkflows)
                .containsExactly(fixture.workflowPath().toString());
    }

    @Test
    void setupLocalCheck() throws Exception {
        // given
        int workerPort = availablePort();
        connectBoard(workerPort);
        fixture.givenWorkerRunning(fixture.workflowPath());

        // when
        CliRunResult result = runCli("setup-local", "check", "--endpoint", endpoint());

        // then
        SNAPSHOTS.verify("cli/setup-local-check", result, normalizerWithWorkerPort(workerPort));
    }

    @Test
    void setupLocalRepairPort() throws Exception {
        // given
        int workerPort = availablePort();
        connectBoard(workerPort);

        // when
        CliRunResult result = runCli("setup-local", "repair-port", "--board", BOARD_NAME);

        // then
        SNAPSHOTS.verify("cli/setup-local-repair-port", result, normalizerWithWorkerPort(workerPort));
    }

    @Test
    void setupLocalConfigureGithub() throws Exception {
        // given
        int workerPort = availablePort();
        connectBoard(workerPort);
        prepareNextSetupRunWithGithubAuth();

        // when
        CliRunResult result = runCli(
                "setup-local",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                TRELLO_API_KEY,
                "--token",
                TRELLO_API_TOKEN,
                "--board",
                BOARD_URL,
                "configure-github");

        // then
        SNAPSHOTS.verify("cli/setup-local-configure-github", result, normalizerWithWorkerPort(workerPort));
        assertThat(fixture.workflowPath()).content(StandardCharsets.UTF_8).contains("Pull Request Publication");
    }

    @Test
    void newBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("new-board").resolve("WORKFLOW.snapshot-queue.md");
        Files.createDirectories(workflow.getParent());

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                TRELLO_API_KEY,
                "--token",
                TRELLO_API_TOKEN,
                "--name",
                BOARD_NAME,
                "--repository-url",
                HTTPS,
                "--workflow",
                workflow.toString(),
                "--manifest",
                workflow.resolveSibling(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                workflow.resolveSibling(".env").toString(),
                "--workspace-root",
                tempDir.resolve("workspaces").toString(),
                "--server-port",
                String.valueOf(FIXED_SERVER_PORT));

        // then
        SNAPSHOTS.verify("cli/new-board", result, normalizer());
        assertThat(workflow).exists();
        assertThat(trello.createdLists()).isNotEmpty();
    }

    @Test
    void importBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("import-board").resolve("WORKFLOW.snapshot-queue.md");
        Files.createDirectories(workflow.getParent());

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                TRELLO_API_KEY,
                "--token",
                TRELLO_API_TOKEN,
                "--board",
                BOARD_URL,
                "--active",
                "Ready for Codex",
                "--in-progress",
                "In Progress",
                "--terminal",
                "Done",
                "--repository-url",
                HTTPS,
                "--workflow",
                workflow.toString(),
                "--manifest",
                workflow.resolveSibling(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                workflow.resolveSibling(".env").toString(),
                "--workspace-root",
                tempDir.resolve("workspaces").toString(),
                "--server-port",
                String.valueOf(FIXED_SERVER_PORT));

        // then
        SNAPSHOTS.verify("cli/import-board", result, normalizer());
        assertThat(workflow).exists();
    }

    @Test
    void listWorkspaces() {
        // given
        Path env = tempDir.resolve(".env.list-workspaces");

        // when
        CliRunResult result = runCli(
                "list-workspaces",
                "--endpoint",
                endpoint(),
                "--key",
                TRELLO_API_KEY,
                "--token",
                TRELLO_API_TOKEN,
                "--env",
                env.toString());

        // then
        SNAPSHOTS.verify("cli/list-workspaces", result, normalizer());
    }

    @Test
    void start() throws Exception {
        // given
        WorkerScenario worker = connectedWorker();
        worker.fixture().stubHealthyStartedWorker(worker.board(), FIXED_WORKER_PID);

        // when
        CliRunResult result = runWorkerCommand(worker, "start");

        // then
        SNAPSHOTS.verify("cli/start", result, normalizer());
        assertThat(worker.managedFiles().pidFile())
                .content(StandardCharsets.UTF_8)
                .contains(String.valueOf(FIXED_WORKER_PID));
    }

    @Test
    void stop() throws Exception {
        // given
        WorkerScenario worker = connectedWorker();
        worker.fixture().stubStoppableManagedPid(worker.board(), FIXED_WORKER_PID);

        // when
        CliRunResult result = runWorkerCommand(worker, "stop");

        // then
        SNAPSHOTS.verify("cli/stop", result, normalizer());
        assertThat(worker.managedFiles().pidFile()).doesNotExist();
    }

    @Test
    void status() throws Exception {
        // given
        WorkerScenario worker = connectedWorker();
        worker.fixture()
                .stubManagedPidWithHealth(
                        worker.board(),
                        FIXED_WORKER_PID,
                        worker.fixture().sameWorkflowWithPid(worker.board(), FIXED_WORKER_PID));

        // when
        CliRunResult result = runWorkerCommand(worker, "status");

        // then
        SNAPSHOTS.verify("cli/status", result, normalizer());
    }

    @Test
    void logs() throws Exception {
        // given
        WorkerScenario worker = connectedWorker();
        Files.writeString(
                worker.managedFiles().stdoutLog(),
                """
                2026-01-01 00:00:00 INFO Symphony for Trello started
                2026-01-01 00:00:01 INFO Polling board "Snapshot Queue"
                """);

        // when
        CliRunResult result = runWorkerCommand(worker, "logs");

        // then
        SNAPSHOTS.verify("cli/logs", result, normalizer());
    }

    @Test
    void diagnostics() throws Exception {
        // given
        WorkerScenario worker = connectedWorker();
        Path report = tempDir.resolve("diagnostics.txt");

        // when
        List<String> arguments = new ArrayList<>(List.of("diagnostics"));
        arguments.addAll(worker.selectionArguments());
        arguments.addAll(List.of("--output", report.toString()));
        CliRunResult result = runCli(arguments.toArray(String[]::new));

        // then
        SNAPSHOTS.verify("cli/diagnostics", result, normalizer());
        assertThat(report).isNotEmptyFile();
    }

    private WorkerScenario connectedWorker() throws Exception {
        var fixture = new LocalWorkerManagerTestFixture(tempDir.resolve("worker"));
        ConnectedBoard board = fixture.connectedBoard("board-1", BOARD_NAME, "snapshot-queue");
        fixture.save(board);
        return new WorkerScenario(fixture, board);
    }

    private record WorkerScenario(LocalWorkerManagerTestFixture fixture, ConnectedBoard board) {
        LocalWorkerPaths paths() {
            return fixture.paths;
        }

        ManagedProcessStore.ManagedProcessFiles managedFiles() throws Exception {
            return fixture.managedFiles(board);
        }

        /// Arguments that select this scenario's directories and board, shared by every worker command
        /// and diagnostics.
        List<String> selectionArguments() {
            return List.of(
                    "--config-dir",
                    paths().configDir().toString(),
                    "--workspace-root",
                    paths().workspaceRoot().toString(),
                    "--state-home",
                    paths().stateHome().toString(),
                    "--board",
                    board.boardName());
        }
    }

    private String[] setupLocalArguments(int serverPort) {
        return new String[] {
            "setup-local",
            "--non-interactive",
            "--endpoint",
            endpoint(),
            "--key",
            TRELLO_API_KEY,
            "--token",
            TRELLO_API_TOKEN,
            "--board-name",
            BOARD_NAME,
            "--workflow",
            fixture.workflowPath().toString(),
            "--env",
            fixture.envPath().toString(),
            "--workspace-root",
            fixture.workspaceRoot().toString(),
            "--server-port",
            String.valueOf(serverPort),
            "--no-github"
        };
    }

    private void connectBoard(int workerPort) {
        commands.skipHealthStart = true;
        runCli(setupLocalArguments(workerPort)).assertSuccess();
        commands.skipHealthStart = false;
        commands.startedWorkflows.clear();
    }

    private CliRunResult runWorkerCommand(WorkerScenario worker, String command) {
        List<String> arguments = new ArrayList<>(
                List.of(command, "--app-home", worker.paths().appHome().toString()));
        arguments.addAll(worker.selectionArguments());
        return runCli(setup, worker.fixture().manager, arguments.toArray(String[]::new));
    }

    private CliRunResult runCli(String... args) {
        return runCli(setup, workerManager, args);
    }

    private CliRunResult runCli(LocalSetup localSetup, LocalWorkerManager manager, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        TrelloBoardSetup boardSetup = new TrelloBoardSetup(new ObjectMapper()).withPortProbe(port -> false);
        // An empty property lookup pins the command name and shell quoting to their defaults, so
        // the transcript does not depend on how the test JVM was started.
        int exitCode = SetupSystemProperties.withLookup(
                Map.<String, String>of()::get,
                () -> TrelloBoardSetupMain.run(
                        args,
                        new TrelloBoardSetupService(boardSetup, manager, Map.of()),
                        localSetup,
                        manager,
                        new PrintStream(stdout, true, StandardCharsets.UTF_8),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8)));
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private TranscriptNormalizer normalizer() {
        return TranscriptNormalizer.builder()
                .temporaryRoot(tempDir, TranscriptNormalizer.TEMPORARY_ROOT)
                .build();
    }

    private TranscriptNormalizer normalizerWithWorkerPort(int workerPort) {
        return TranscriptNormalizer.builder()
                .temporaryRoot(tempDir, TranscriptNormalizer.TEMPORARY_ROOT)
                .literal("127.0.0.1:" + workerPort, "127.0.0.1:<WORKER_PORT>")
                .build();
    }
}
