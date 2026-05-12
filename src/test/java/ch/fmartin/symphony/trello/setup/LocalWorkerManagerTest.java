package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkerManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void startLaunchesPackagedAppAndWritesManagedPid() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.waitForSameWorkflow(board, board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess()
                .stdoutContains("Started Symphony for Trello: \"Queue\"")
                .stdoutDoesNotContain("Log:");
        verify(fixture.platform)
                .start(
                        argThat(command -> command.contains("-jar")
                                && command.contains(fixture.paths
                                        .appHome()
                                        .resolve("target/quarkus-app/quarkus-run.jar")
                                        .toString())),
                        eq(fixture.paths.appHome()),
                        any(),
                        any(),
                        any());
        assertThat(Files.readString(Files.list(fixture.paths.stateHome())
                        .filter(path -> path.getFileName().toString().endsWith(".pid"))
                        .findFirst()
                        .orElseThrow()))
                .isEqualTo("42");
    }

    @Test
    void startResolvesRelativeEnvPathBeforeLaunchingPackagedApp() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Path relativeEnv = Path.of(".env.relative");
        Path expectedEnv = relativeEnv.toAbsolutePath().normalize();
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), expectedEnv))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.waitForSameWorkflow(board, board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.of(relativeEnv),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess();
        verify(fixture.platform)
                .start(
                        any(),
                        eq(fixture.paths.appHome()),
                        argThat(environment ->
                                expectedEnv.toString().equals(environment.get("SYMPHONY_TRELLO_DOTENV"))),
                        any(),
                        any());
    }

    @Test
    void startAllLaunchesEveryConnectedBoard() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "First Queue");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Second Queue");
        fixture.save(first, second);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42), new ManagedProcessHandle(43));
        when(fixture.healthChecker.waitForSameWorkflow(any(), anyInt()))
                .thenReturn(new BoardHealth(BoardHealthKind.SAME_WORKFLOW, 18080, Optional.empty(), Optional.empty()));
        when(fixture.platform.isAlive(anyLong())).thenReturn(true);
        when(fixture.platform.isManaged(anyLong(), eq(fixture.paths.appHome()), any()))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()),
                true));

        // then
        result.assertSuccess().stdoutContains("\"First Queue\"", "\"Second Queue\"");
        verify(fixture.platform, times(2)).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startFailsWhenAnotherWorkerProvidesHealthButNewProcessExited() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.waitForSameWorkflow(board, board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));
        when(fixture.platform.isAlive(42)).thenReturn(false);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()))));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("newly started managed process is not running");
        assertThat(Files.list(fixture.paths.stateHome())
                        .filter(path -> path.getFileName().toString().endsWith(".pid")))
                .isEmpty();
    }

    @Test
    void startStopsNewlyStartedWorkerWhenPidValidationFails() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.waitForSameWorkflow(board, board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()))));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("newly started managed process is not running");
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        assertThat(Files.list(fixture.paths.stateHome())
                        .filter(path -> path.getFileName().toString().endsWith(".pid")))
                .isEmpty();
    }

    @Test
    void startValidatesManagedHealthPortBeforeLaunchingPackagedApp() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenThrow(new TrelloBoardSetupException(
                        "setup_managed_port_required", "Managed local setup needs a stable HTTP port."));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()))));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("stable HTTP port");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startIsIdempotentWhenExpectedWorkerIsAlreadyHealthy() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files =
                new ManagedProcessStore(fixture.paths.stateHome()).files(board.workflowPath());
        Files.createDirectories(fixture.paths.stateHome());
        new ManagedProcessStore(fixture.paths.stateHome()).writePid(files.pidFile(), 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess().stdoutContains("already running");
    }

    @Test
    void startIdempotencyWithManagedPidUsesExplicitEnvOverride() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path envPath = fixture.paths.configDir().resolve(".env.override");
        int overridePort = 19090;
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files =
                new ManagedProcessStore(fixture.paths.stateHome()).files(board.workflowPath());
        Files.createDirectories(fixture.paths.stateHome());
        new ManagedProcessStore(fixture.paths.stateHome()).writePid(files.pidFile(), 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), envPath))
                .thenReturn(overridePort);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), overridePort))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        overridePort,
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.of(envPath),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess().stdoutContains("already running", "pid=42");
        verify(fixture.healthChecker).managedHealthPort(board.workflowPath(), board.serverPort(), envPath);
        verify(fixture.healthChecker)
                .workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), overridePort);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startIsIdempotentWhenExpectedWorkerIsHealthyButPidFileIsMissing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Docs Queue");
        fixture.save(board);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.of("Docs Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess().stdoutContains("already running", "http://127.0.0.1:" + board.serverPort());
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startIdempotencyHealthCheckUsesExplicitEnvOverrideWhenPidFileIsMissing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Docs Queue");
        Path envPath = fixture.paths.configDir().resolve(".env.override");
        int overridePort = 19090;
        fixture.save(board);
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), envPath))
                .thenReturn(overridePort);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), overridePort))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        overridePort,
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.of("Docs Queue"),
                Optional.empty(),
                Optional.of(envPath),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess().stdoutContains("already running", "http://127.0.0.1:" + overridePort);
        verify(fixture.healthChecker).managedHealthPort(board.workflowPath(), board.serverPort(), envPath);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startAttemptsNormalLaunchWhenPidFileIsMissingAndHealthReportsWrongWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.WRONG_WORKFLOW,
                        board.serverPort(),
                        Optional.of(fixture.paths
                                .configDir()
                                .resolve("WORKFLOW.other.md")
                                .toString()),
                        Optional.of(board.boardId())));
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.waitForSameWorkflow(board, board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.WRONG_WORKFLOW,
                        board.serverPort(),
                        Optional.of(fixture.paths
                                .configDir()
                                .resolve("WORKFLOW.other.md")
                                .toString()),
                        Optional.of(board.boardId())));
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()))));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("did not report");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void statusReportsUnhealthyWhenManagedPidLocalHealthDoesNotMatch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files =
                new ManagedProcessStore(fixture.paths.stateHome()).files(board.workflowPath());
        Files.createDirectories(fixture.paths.stateHome());
        new ManagedProcessStore(fixture.paths.stateHome()).writePid(files.pidFile(), 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.healthChecker.boardHealth(board))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.WRONG_WORKFLOW,
                        board.serverPort(),
                        Optional.of(fixture.paths
                                .configDir()
                                .resolve("WORKFLOW.other.md")
                                .toString()),
                        Optional.of(board.boardId())));

        // when
        WorkerRunResult result = fixture.status(new WorkerStatusRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess()
                .stdoutContains("unhealthy \"Queue\" pid=42", "WRONG_WORKFLOW")
                .stdoutDoesNotContain("running \"Queue\"");
    }

    @Test
    void statusReportsUntrackedRunningWorkerWhenPidFileIsMissingButHealthMatches() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.boardHealth(board))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));

        // when
        WorkerRunResult result = fixture.status(new WorkerStatusRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess()
                .stdoutContains("running \"Queue\"", "untracked, no managed pid")
                .stdoutDoesNotContain("stopped \"Queue\"");
    }

    @Test
    void stopFailsForUntrackedRunningWorkerWhenPidFileIsMissingButHealthMatches() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.boardHealth(board))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));

        // when
        var thrown = catchThrowable(() -> fixture.stop(new StopWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()))));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("no managed pid");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void stopWithoutSelectorStopsAllConnectedManagedWorkers() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "First");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Second");
        fixture.save(first, second);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Files.createDirectories(fixture.paths.stateHome());
        store.writePid(store.files(first.workflowPath()).pidFile(), 41);
        store.writePid(store.files(second.workflowPath()).pidFile(), 42);
        when(fixture.platform.isAlive(41)).thenReturn(true);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(41, fixture.paths.appHome(), first.workflowPath()))
                .thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), second.workflowPath()))
                .thenReturn(true);
        when(fixture.platform.stop(anyLong(), any(Duration.class), any(Duration.class)))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.stop(new StopWorkerRequest(
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess().stdoutContains("Stopped WORKFLOW.first.md", "Stopped WORKFLOW.second.md");
        assertThat(Files.list(fixture.paths.stateHome())
                        .filter(path -> path.getFileName().toString().endsWith(".pid")))
                .isEmpty();
    }

    @Test
    void stopWithoutSelectorFallsBackToManagedPidFilesWhenNoBoardsAreConnected() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Files.createDirectories(fixture.paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files =
                store.files(fixture.paths.configDir().resolve("WORKFLOW.direct.md"));
        store.writePid(files.pidFile(), 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome())).thenReturn(true);
        when(fixture.platform.stop(anyLong(), any(Duration.class), any(Duration.class)))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.stop(new StopWorkerRequest(
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess().stdoutContains("Stopped WORKFLOW.direct.md");
        assertThat(files.pidFile()).doesNotExist();
    }

    @Test
    void startRequiresSelectorWhenMultipleBoardsAreConnected() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        fixture.save(fixture.connectedBoard("board-1", "First"), fixture.connectedBoard("board-2", "Second"));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(new StartWorkerRequest(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()))));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("--board NAME or --workflow PATH");
    }

    @Test
    void explicitWorkflowUsesWorkflowBoardIdAndPortWithoutManifestEntry() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.direct.md");
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: direct-board
                server:
                  port: 19090
                ---
                # Direct
                """,
                StandardCharsets.UTF_8);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(any(), anyInt(), nullable(Path.class)))
                .thenReturn(19090);
        when(fixture.healthChecker.waitForSameWorkflow(any(), eq(19090)))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        19090,
                        Optional.of(workflow.toString()),
                        Optional.of("direct-board")));
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(
                        42, fixture.paths.appHome(), workflow.toAbsolutePath().normalize()))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.start(new StartWorkerRequest(
                Optional.empty(),
                Optional.of(workflow),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess();
        verify(fixture.healthChecker)
                .waitForSameWorkflow(
                        argThat(board -> "direct-board".equals(board.boardId()) && board.serverPort() == 19090),
                        eq(19090));
    }

    @Test
    void startDoesNotStopReusedPidBelongingToAnotherWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Files.createDirectories(fixture.paths.stateHome());
        store.writePid(store.files(board.workflowPath()).pidFile(), 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(new StartWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome()))));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("State file belongs to another process");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void stopDoesNotStopReusedPidBelongingToAnotherWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Files.createDirectories(fixture.paths.stateHome());
        store.writePid(store.files(board.workflowPath()).pidFile(), 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);

        // when
        WorkerRunResult result = fixture.stop(new StopWorkerRequest(
                Optional.of("Queue"),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess().stdoutContains("Skipped unmanaged stale pid");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }
}
