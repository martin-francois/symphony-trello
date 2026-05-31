package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
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
    final LocalWorkerManager manager;

    LocalWorkerManagerTestFixture(Path tempDir) {
        this.paths = LocalWorkerPaths.from(
                Optional.of(tempDir.resolve("app")),
                Optional.of(tempDir.resolve("config")),
                Optional.of(tempDir.resolve("workspaces")),
                Optional.of(tempDir.resolve("state")),
                Map.of());
        this.platform = mock(ManagedProcessPlatform.class);
        when(platform.appendsToExistingLogs()).thenReturn(true);
        this.healthChecker = mock(LocalHealthChecker.class);
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
        this.manager = new LocalWorkerManager(
                Map.of(), new WorkflowConfigEditor(), healthChecker, platform, new LocalLogTailer());
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

    void stubHealthyStartedWorker(ConnectedBoard board, long pid) throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(board, board.serverPort())).thenReturn(sameWorkflow(board));
        when(platform.isAlive(pid)).thenReturn(true);
        when(platform.isManaged(pid, paths.appHome(), board.workflowPath())).thenReturn(true);
    }

    void stubStoppedStartedWorker(ConnectedBoard board, long pid) throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(board, board.serverPort())).thenReturn(stopped(board));
        when(platform.stop(pid, Duration.ofSeconds(15), Duration.ofSeconds(5))).thenReturn(true);
    }

    void stubStartedWorkerHealth(ConnectedBoard board, long pid, BoardHealth health) throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(board, board.serverPort())).thenReturn(health);
        when(platform.stop(pid, Duration.ofSeconds(15), Duration.ofSeconds(5))).thenReturn(true);
    }

    void stubStartedWorkerProcessValidation(ConnectedBoard board, long pid, boolean alive, boolean managed)
            throws Exception {
        when(platform.start(any(), eq(paths.appHome()), any(), any(), any())).thenReturn(new ManagedProcessHandle(pid));
        stubManagedPort(board);
        when(healthChecker.waitForSameWorkflow(board, board.serverPort())).thenReturn(sameWorkflow(board));
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

    StopWorkerRequest stopRequest(String boardName) {
        return stopRequest(Optional.of(boardName));
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
        String slug = boardName.toLowerCase(Locale.ROOT);
        Path workflow = paths.configDir().resolve("WORKFLOW." + slug + ".md");
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
        return new ConnectedBoard(
                boardId,
                boardId,
                boardName,
                "https://trello.com/b/" + boardId,
                workflow.toAbsolutePath().normalize(),
                paths.defaultEnvPath(),
                paths.workspaceRoot(),
                ConfigDefaults.DEFAULT_SERVER_PORT,
                false,
                List.of(),
                false);
    }

    void writeEnv(Path envPath) throws Exception {
        Path parent = envPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(envPath, "TRELLO_API_KEY=test-key\nTRELLO_API_TOKEN=test-token\n", StandardCharsets.UTF_8);
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
