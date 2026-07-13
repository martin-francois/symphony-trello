package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.testsupport.TestEnv;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class LocalWorkerManagerTestFixture {
    final LocalWorkerPaths paths;
    final ManagedProcessPlatform platform;
    final LocalHealthChecker healthChecker;
    final LocalWorkerManager.TrelloCredentialPreflight credentialPreflight;
    final LocalWorkerManager manager;

    LocalWorkerManagerTestFixture(Path tempDir) {
        this(tempDir, Map.of());
    }

    LocalWorkerManagerTestFixture(Path tempDir, Map<String, String> environment) {
        this(tempDir, environment, new WorkflowConfigEditor());
    }

    LocalWorkerManagerTestFixture(Path tempDir, Map<String, String> environment, WorkflowConfigEditor workflowConfig) {
        this.paths = LocalWorkerPaths.from(
                Optional.of(tempDir.resolve("app")),
                Optional.of(tempDir.resolve("config")),
                Optional.of(tempDir.resolve("workspaces")),
                Optional.of(tempDir.resolve("state")),
                environment);
        this.platform = mock();
        when(platform.appendsToExistingLogs()).thenReturn(true);
        this.healthChecker = mock();
        when(healthChecker.boardHealth(any()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.STOPPED,
                        ConfigDefaults.DEFAULT_SERVER_PORT,
                        Optional.empty(),
                        Optional.empty()));
        when(healthChecker.managedHealthPort(any(), anyInt(), nullable(Path.class)))
                .thenReturn(ConfigDefaults.DEFAULT_SERVER_PORT);
        when(healthChecker.workflowHealth(any(), nullable(String.class), nullable(String.class), anyInt()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.STOPPED,
                        ConfigDefaults.DEFAULT_SERVER_PORT,
                        Optional.empty(),
                        Optional.empty()));
        this.credentialPreflight = mock();
        this.manager = new LocalWorkerManager(
                environment, workflowConfig, healthChecker, platform, new LocalLogTailer(), credentialPreflight);
    }

    WorkerRunResult start(StartWorkerRequest request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        int exitCode = manager.start(request, printStream(stdout));
        return new WorkerRunResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    WorkerRunResult stop(StopWorkerRequest request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        int exitCode = manager.stop(request, printStream(stdout));
        return new WorkerRunResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    WorkerRunResult status(WorkerStatusRequest request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        int exitCode = manager.status(request, printStream(stdout));
        return new WorkerRunResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    WorkerRunResult logs(WorkerLogsRequest request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        int exitCode = manager.logs(request, printStream(stdout));
        return new WorkerRunResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
    }

    void stubHealthyStartedWorker(ConnectedBoard board, long pid) throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(eq(board), eq(board.serverPort()), any()))
                .thenReturn(sameWorkflow(board));
        when(platform.isAlive(pid)).thenReturn(true);
        when(platform.isManaged(pid, paths.appHome(), board.workflowPath())).thenReturn(true);
    }

    ManagedProcessStore.ManagedProcessFiles stubStoppedStartedWorkerWithStartupLog(
            ConnectedBoard board, long pid, String startupLog) throws Exception {
        ManagedProcessStore.ManagedProcessFiles files = managedFiles(board);
        stubStoppedStartedWorker(board, pid);
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenAnswer(invocation -> {
            Files.writeString(files.stdoutLog(), startupLog, StandardCharsets.UTF_8);
            return new ManagedProcessHandle(pid);
        });
        return files;
    }

    void stubStoppedStartedWorker(ConnectedBoard board, long pid) throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(eq(board), eq(board.serverPort()), any()))
                .thenReturn(stopped(board));
        when(platform.stop(pid, Duration.ofSeconds(15), Duration.ofSeconds(5))).thenReturn(true);
    }

    void stubStartedWorkerHealth(ConnectedBoard board, long pid, BoardHealth health) throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(eq(board), eq(board.serverPort()), any()))
                .thenReturn(health);
        when(platform.stop(pid, Duration.ofSeconds(15), Duration.ofSeconds(5))).thenReturn(true);
    }

    void stubStartedWorkerProcessValidation(ConnectedBoard board, long pid, boolean alive, boolean managed)
            throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(eq(board), eq(board.serverPort()), any()))
                .thenReturn(sameWorkflow(board));
        when(platform.isAlive(pid)).thenReturn(alive);
        if (alive) {
            when(platform.isManaged(pid, paths.appHome(), board.workflowPath())).thenReturn(managed);
        }
    }

    void stubManagedPid(ConnectedBoard board, long pid) throws Exception {
        writeManagedPid(board, pid);
        when(platform.isAlive(pid)).thenReturn(true);
        when(platform.isManaged(pid, paths.appHome(), board.workflowPath())).thenReturn(true);
    }

    void stubManagedPidWithHealth(ConnectedBoard board, long pid, BoardHealth health) throws Exception {
        stubManagedPid(board, pid);
        when(healthChecker.boardHealth(board)).thenReturn(health);
    }

    void stubWorkflowHealth(ConnectedBoard board, BoardHealth health) throws Exception {
        when(healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(health);
    }

    void stubWorkflowHealth(ConnectedBoard board, Path envPath, int port, BoardHealth health) throws Exception {
        when(healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), envPath))
                .thenReturn(port);
        when(healthChecker.workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), port))
                .thenReturn(health);
    }

    void stubManagedPort(ConnectedBoard board) throws Exception {
        when(healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
    }

    ManagedProcessStore.ManagedProcessFiles writeManagedPid(ConnectedBoard board, long pid) throws Exception {
        ManagedProcessStore store = new ManagedProcessStore(paths.stateHome());
        Files.createDirectories(paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
        store.writePid(files.pidFile(), pid);
        return files;
    }

    ManagedProcessStore.ManagedProcessFiles managedFiles(ConnectedBoard board) throws Exception {
        Files.createDirectories(paths.stateHome());
        return new ManagedProcessStore(paths.stateHome()).files(board.workflowPath());
    }

    StartWorkerRequest startRequest(String boardName) {
        return startRequest(Optional.of(boardName), Optional.empty(), Optional.empty());
    }

    StartWorkerRequest startRequest(String boardName, Path envPath) {
        return startRequest(Optional.of(boardName), Optional.empty(), Optional.of(envPath));
    }

    StartWorkerRequest startWorkflowRequest(Path workflow) {
        return startRequest(Optional.empty(), Optional.of(workflow), Optional.empty());
    }

    StartWorkerRequest startWorkflowRequest(Path workflow, Path envPath) {
        return startRequest(Optional.empty(), Optional.of(workflow), Optional.of(envPath));
    }

    StartWorkerRequest startAllRequest() {
        return new StartWorkerRequest(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()),
                true);
    }

    StartWorkerRequest startRequest() {
        return startRequest(Optional.empty(), Optional.empty(), Optional.empty());
    }

    StartWorkerRequest startEnvRequest(Path envPath) {
        return startRequest(Optional.empty(), Optional.empty(), Optional.of(envPath));
    }

    StopWorkerRequest stopRequest(String boardName) {
        return stopRequest(Optional.of(boardName));
    }

    StopWorkerRequest stopWorkflowRequest(Path workflow) {
        return new StopWorkerRequest(
                Optional.empty(),
                Optional.of(workflow),
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    StopWorkerRequest stopAllRequest() {
        return stopRequest(Optional.empty());
    }

    WorkerStatusRequest statusRequest(String boardName) {
        return new WorkerStatusRequest(
                Optional.of(boardName),
                Optional.empty(),
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    WorkerStatusRequest statusAllRequest() {
        return new WorkerStatusRequest(
                Optional.empty(),
                Optional.empty(),
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    WorkerStatusRequest statusWorkflowRequest(Path workflow) {
        return new WorkerStatusRequest(
                Optional.empty(),
                Optional.of(workflow),
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    WorkerLogsRequest logsRequest(String boardName) {
        return new WorkerLogsRequest(
                Optional.of(boardName),
                Optional.empty(),
                false,
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    WorkerLogsRequest logsWorkflowRequest(Path workflow) {
        return new WorkerLogsRequest(
                Optional.empty(),
                Optional.of(workflow),
                false,
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    BoardHealth sameWorkflow(ConnectedBoard board) {
        return sameWorkflow(board, board.serverPort());
    }

    BoardHealth sameWorkflow(ConnectedBoard board, int port) {
        return new BoardHealth(
                BoardHealthKind.SAME_WORKFLOW,
                port,
                Optional.of(board.workflowPath().toString()),
                Optional.of(board.boardId()));
    }

    BoardHealth sameWorkflowWithPid(ConnectedBoard board, long workerPid) {
        return new BoardHealth(
                BoardHealthKind.SAME_WORKFLOW,
                board.serverPort(),
                Optional.of(board.workflowPath().toString()),
                Optional.of(board.boardId()),
                Optional.of(workerPid));
    }

    BoardHealth stopped(ConnectedBoard board) {
        return new BoardHealth(BoardHealthKind.STOPPED, board.serverPort(), Optional.empty(), Optional.empty());
    }

    BoardHealth stopped(int port) {
        return new BoardHealth(BoardHealthKind.STOPPED, port, Optional.empty(), Optional.empty());
    }

    BoardHealth wrongWorkflow(ConnectedBoard board) {
        return new BoardHealth(
                BoardHealthKind.WRONG_WORKFLOW,
                board.serverPort(),
                Optional.of(paths.configDir().resolve("WORKFLOW.other.md").toString()),
                Optional.of(board.boardId()));
    }

    ConnectedBoard connectedBoard(String boardId, String boardName) throws Exception {
        return connectedBoard(boardId, boardName, boardName.toLowerCase(Locale.ROOT));
    }

    ConnectedBoard connectedBoard(String boardId, String boardName, String workflowSlug) throws Exception {
        Path workflow = paths.configDir().resolve("WORKFLOW." + workflowSlug + ".md");
        Files.createDirectories(paths.configDir());
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: %s
                server:
                  port: %d
                ---
                # %s
                """
                        .formatted(boardId, ConfigDefaults.DEFAULT_SERVER_PORT, boardName),
                StandardCharsets.UTF_8);
        writeEnv(paths.defaultEnvPath());
        return ConnectedBoardBuilder.connectedBoard(workflow.toAbsolutePath().normalize())
                .withBoardId(boardId)
                .withBoardKey(boardId)
                .withBoardName(boardName)
                .withBoardUrl("https://trello.com/b/" + boardId)
                .withEnvPath(paths.defaultEnvPath())
                .withWorkspaceRoot(paths.workspaceRoot())
                .build();
    }

    void writeEnv(Path envPath) throws Exception {
        Path parent = envPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(envPath, TestEnv.trelloCredentials("test-key", "test-token"), StandardCharsets.UTF_8);
    }

    void save(ConnectedBoard... boards) throws Exception {
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(boards));
        new ConnectedBoardRepository(paths.manifestPath()).save(manifest);
    }

    private StartWorkerRequest startRequest(
            Optional<String> boardName, Optional<Path> workflow, Optional<Path> envPath) {
        return new StartWorkerRequest(
                boardName,
                workflow,
                envPath,
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    private StopWorkerRequest stopRequest(Optional<String> boardName) {
        return new StopWorkerRequest(
                boardName,
                Optional.empty(),
                Optional.of(paths.appHome()),
                Optional.of(paths.configDir()),
                Optional.of(paths.workspaceRoot()),
                Optional.of(paths.stateHome()));
    }

    private static PrintStream printStream(ByteArrayOutputStream stdout) {
        return new PrintStream(stdout, true, StandardCharsets.UTF_8);
    }
}

record WorkerRunResult(int exitCode, String stdout) {
    WorkerRunResult assertSuccess() {
        assertThat(exitCode).as("stdout:%n%s", stdout).isZero();
        return this;
    }

    WorkerRunResult stdoutContains(String... expected) {
        assertThat(stdout).contains(expected);
        return this;
    }

    WorkerRunResult stdoutDoesNotContain(String... forbidden) {
        assertThat(stdout).doesNotContain(forbidden);
        return this;
    }
}
