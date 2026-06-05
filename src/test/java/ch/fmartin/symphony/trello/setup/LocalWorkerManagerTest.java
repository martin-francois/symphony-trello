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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalWorkerManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void startLaunchesPackagedAppAndWritesManagedPid() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubHealthyStartedWorker(board, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

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
        assertThat(Files.readString(onlyPidFile(fixture.paths.stateHome()))).isEqualTo("42");
    }

    @Test
    void startResolvesRelativeEnvPathBeforeLaunchingPackagedApp() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Path relativeEnv = Path.of(".env.relative");
        Path expectedEnv = relativeEnv.toAbsolutePath().normalize();
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                server:
                  port: 18080
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), expectedEnv))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.waitForSameWorkflow(board, board.serverPort()))
                .thenReturn(fixture.sameWorkflow(board));
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
        WorkerRunResult result = fixture.start(fixture.startAllRequest());

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
        fixture.stubStartedWorkerProcessValidation(board, 42, false, false);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("newly started managed process is not running");
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
    }

    @Test
    void startStopsNewlyStartedWorkerWhenPidValidationFails() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubStartedWorkerProcessValidation(board, 42, true, false);
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("newly started managed process is not running");
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
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
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("stable HTTP port");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startFailsBeforeLaunchingPackagedAppWhenWorkerCredentialsAreMissing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.deleteIfExists(board.envPath());
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_missing_trello_credentials");
            assertThat(failure).hasMessage("Missing Trello credentials for worker start.");
            assertThat(failure.dotenvPath()).contains(board.envPath());
            assertThat(failure.trelloApiKeyEnvironmentName()).contains("TRELLO_API_KEY");
            assertThat(failure.trelloApiTokenEnvironmentName()).contains("TRELLO_API_TOKEN");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startAllowsFileBackedWorkflowCredentialsWithoutDotenvCredentials() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path secrets = fixture.paths.configDir().resolve("secrets");
        Files.createDirectories(secrets);
        Files.writeString(secrets.resolve("trello-api-key"), "key-from-file\n", StandardCharsets.UTF_8);
        Files.writeString(secrets.resolve("trello-api-token"), "token-from-file\n", StandardCharsets.UTF_8);
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  api_key: file:secrets/trello-api-key
                  api_token: file:secrets/trello-api-token
                  board_id: board-1
                server:
                  port: 18080
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        Files.deleteIfExists(board.envPath());
        fixture.save(board);
        fixture.stubHealthyStartedWorker(board, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startFailsBeforeLaunchingPackagedAppWhenCustomEnvironmentCredentialsAreMissing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  api_key: $CUSTOM_TRELLO_API_KEY
                  api_token: $CUSTOM_TRELLO_API_TOKEN
                  board_id: board-1
                server:
                  port: 18080
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        Files.deleteIfExists(board.envPath());
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_missing_trello_credentials");
            assertThat(failure).hasMessage("Missing Trello credentials for worker start.");
            assertThat(failure.trelloApiKeyEnvironmentName()).contains("CUSTOM_TRELLO_API_KEY");
            assertThat(failure.trelloApiTokenEnvironmentName()).contains("CUSTOM_TRELLO_API_TOKEN");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startSkipsCredentialPreflightForUnsupportedTrackerKind() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: linear
                  board_id: board-1
                server:
                  port: 18080
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        Files.deleteIfExists(board.envPath());
        fixture.save(board);
        fixture.stubHealthyStartedWorker(board, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startIsIdempotentWhenExpectedWorkerIsAlreadyHealthy() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.writeManagedPid(board, 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(fixture.sameWorkflow(board));

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("already running");
    }

    @Test
    void startIdempotencyWithManagedPidReportsExplicitEnvOverrideWasNotApplied() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path envPath = fixture.paths.configDir().resolve(".env.override");
        int overridePort = 19090;
        fixture.writeEnv(envPath);
        fixture.save(board);
        fixture.stubManagedPid(board, 42);
        fixture.stubWorkflowHealth(board, envPath, overridePort, fixture.sameWorkflow(board, overridePort));

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
        result.assertSuccess()
                .stdoutContains(
                        "Supplied --env was not applied because Symphony for Trello is already running.",
                        "Stop and start this worker to use: "
                                + envPath.toAbsolutePath().normalize(),
                        "already running",
                        "pid=42");
        verify(fixture.healthChecker).managedHealthPort(board.workflowPath(), board.serverPort(), envPath);
        verify(fixture.healthChecker)
                .workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), overridePort);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startValidatesExplicitEnvOverrideBeforeAlreadyRunningFastPath() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path envPath = fixture.paths.configDir().resolve(".env.key-only");
        Files.writeString(envPath, "TRELLO_API_KEY=test-key\n", StandardCharsets.UTF_8);
        fixture.save(board);
        fixture.stubManagedPid(board, 42);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue", envPath)));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("Missing Trello API token for worker start.");
        assertThat((TrelloBoardSetupException) thrown)
                .extracting(TrelloBoardSetupException::code)
                .isEqualTo("setup_worker_missing_api_token");
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
                .thenReturn(fixture.sameWorkflow(board));

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Docs Queue"));

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
        fixture.writeEnv(envPath);
        fixture.save(board);
        fixture.stubWorkflowHealth(board, envPath, overridePort, fixture.sameWorkflow(board, overridePort));

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Docs Queue", envPath));

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Supplied --env was not applied because Symphony for Trello is already running.",
                        "Stop and start this worker to use: "
                                + envPath.toAbsolutePath().normalize(),
                        "already running",
                        "http://127.0.0.1:" + overridePort);
        verify(fixture.healthChecker).managedHealthPort(board.workflowPath(), board.serverPort(), envPath);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startAttemptsNormalLaunchWhenPidFileIsMissingAndHealthReportsWrongWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubWorkflowHealth(board, fixture.wrongWorkflow(board));
        fixture.stubStartedWorkerHealth(board, 42, fixture.wrongWorkflow(board));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("did not report");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startSurfacesTrelloAuthFailureFromWorkerStartupLogs() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.managedFiles(board);
        fixture.stubStoppedStartedWorker(board, 42);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Files.writeString(
                            files.stdoutLog(),
                            """
                            Caused by: ch.fmartin.symphony.trello.tracker.TrelloException: Trello authentication failed
                            \tat ch.fmartin.symphony.trello.tracker.TrelloClient.statusException(TrelloClient.java:600)
                            """,
                            StandardCharsets.UTF_8);
                    return new ManagedProcessHandle(42);
                });

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("trello_auth_failed");
            assertThat(failure).hasMessage("Trello authentication failed while starting Symphony.");
        });
    }

    @Test
    void startIgnoresStaleTrelloAuthFailureFromPreviousStartupLogs() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.managedFiles(board);
        Files.writeString(
                files.stdoutLog(),
                """
                Caused by: ch.fmartin.symphony.trello.tracker.TrelloException: Trello authentication failed
                """,
                StandardCharsets.UTF_8);
        fixture.stubStoppedStartedWorker(board, 42);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_start_unhealthy");
            assertThat(failure).hasMessageContaining("did not report the expected workflow and board");
        });
    }

    @Test
    void startSurfacesTrelloAuthFailureWhenWorkerTruncatesPreviousLogs() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.managedFiles(board);
        Files.writeString(
                files.stdoutLog(),
                """
                Stale startup log content that is longer than the next worker startup failure.
                This simulates redirect targets left behind by an earlier managed process start.
                """,
                StandardCharsets.UTF_8);
        fixture.stubStoppedStartedWorker(board, 42);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Files.writeString(files.stdoutLog(), "Trello authentication failed\n", StandardCharsets.UTF_8);
                    return new ManagedProcessHandle(42);
                });

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("trello_auth_failed");
            assertThat(failure).hasMessage("Trello authentication failed while starting Symphony.");
        });
    }

    @Test
    void startSurfacesTrelloAuthFailureWhenWorkerRewritesLogsToLargerFile() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        when(fixture.platform.appendsToExistingLogs()).thenReturn(false);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.managedFiles(board);
        Files.writeString(files.stdoutLog(), "stale log\n", StandardCharsets.UTF_8);
        fixture.stubStoppedStartedWorker(board, 42);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Files.writeString(
                            files.stdoutLog(),
                            """
                            Trello authentication failed
                            Additional startup details that make this rewritten log longer than the previous file.
                            """,
                            StandardCharsets.UTF_8);
                    return new ManagedProcessHandle(42);
                });

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("trello_auth_failed");
            assertThat(failure).hasMessage("Trello authentication failed while starting Symphony.");
        });
    }

    @Test
    void statusReportsUnhealthyWhenManagedPidLocalHealthDoesNotMatch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPidWithHealth(board, 42, fixture.wrongWorkflow(board));

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

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
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.sameWorkflow(board));

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

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
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.sameWorkflow(board));

        // when
        var thrown = catchThrowable(() -> fixture.stop(fixture.stopRequest("Queue")));

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
        WorkerRunResult result = fixture.stop(fixture.stopAllRequest());

        // then
        result.assertSuccess().stdoutContains("Stopped WORKFLOW.first.md", "Stopped WORKFLOW.second.md");
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
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
        WorkerRunResult result = fixture.stop(fixture.stopAllRequest());

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
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest()));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("--board NAME or --workflow PATH");
    }

    @Test
    void startRejectsAmbiguousBoardNameSelector() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "Duplicate", "duplicate-one");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Duplicate", "duplicate-two");
        fixture.save(first, second);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Duplicate")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_board_ambiguous");
            assertThat(failure)
                    .hasMessage(
                            "Multiple connected boards match --board. Re-run with a board id, short link, or --workflow.");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void statusRejectsAmbiguousBoardNameSelectorButAllowsWorkflowSelector() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "Duplicate", "duplicate-one");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Duplicate", "duplicate-two");
        fixture.save(first, second);
        when(fixture.healthChecker.boardHealth(second)).thenReturn(fixture.sameWorkflow(second));

        // when
        Throwable thrown = catchThrowable(() -> fixture.status(fixture.statusRequest("Duplicate")));
        WorkerRunResult workflowResult = fixture.status(fixture.statusWorkflowRequest(second.workflowPath()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_board_ambiguous");
            assertThat(failure)
                    .hasMessage(
                            "Multiple connected boards match --board. Re-run with a board id, short link, or --workflow.");
        });
        workflowResult.assertSuccess().stdoutContains("running \"Duplicate\"");
    }

    @Test
    void stopRejectsAmbiguousBoardNameSelectorButAllowsWorkflowSelector() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "Duplicate", "duplicate-one");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Duplicate", "duplicate-two");
        fixture.save(first, second);
        fixture.writeManagedPid(second, 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), second.workflowPath()))
                .thenReturn(true);
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> fixture.stop(fixture.stopRequest("Duplicate")));
        WorkerRunResult workflowResult = fixture.stop(fixture.stopWorkflowRequest(second.workflowPath()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_board_ambiguous");
            assertThat(failure)
                    .hasMessage(
                            "Multiple connected boards match --board. Re-run with a board id, short link, or --workflow.");
        });
        workflowResult.assertSuccess().stdoutContains("Stopped WORKFLOW.duplicate-two.md");
    }

    @Test
    void explicitWorkflowUsesWorkflowBoardIdAndPortWithoutManifestEntry() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.direct.md");
        Path env = fixture.paths.defaultEnvPath();
        Files.createDirectories(fixture.paths.configDir());
        fixture.writeEnv(env);
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
        WorkerRunResult result = fixture.start(fixture.startWorkflowRequest(workflow));

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
        fixture.writeManagedPid(board, 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

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
        fixture.writeManagedPid(board, 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Skipped unmanaged stale pid");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    private static Path onlyPidFile(Path stateHome) throws Exception {
        List<Path> files = pidFiles(stateHome);
        assertThat(files).hasSize(1);
        return files.getFirst();
    }

    private static List<Path> pidFiles(Path stateHome) throws Exception {
        try (Stream<Path> paths = Files.list(stateHome)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".pid"))
                    .toList();
        }
    }
}
