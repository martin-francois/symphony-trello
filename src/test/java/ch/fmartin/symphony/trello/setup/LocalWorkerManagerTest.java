package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;

final class LocalWorkerManagerTest {
    private static final long MISSING_WORKFLOW_STATE_PID = 42L;

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
    void startDoesNotRewriteExistingGithubWorkflowBeforeLaunchingWorker() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        writeExistingGithubWorkflowWithoutSandboxPolicy(board, "  board_id: \"board-1\"\n", "");
        String originalWorkflow = Files.readString(board.workflowPath(), StandardCharsets.UTF_8);
        ConnectedBoard githubBoard = new ConnectedBoard(
                board.boardId(),
                board.boardKey(),
                board.boardName(),
                board.boardUrl(),
                board.workflowPath(),
                board.envPath(),
                board.workspaceRoot(),
                board.serverPort(),
                true,
                board.additionalWritableRoots(),
                board.dangerFullAccess());
        fixture.save(githubBoard);
        fixture.stubHealthyStartedWorker(githubBoard, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello: \"Queue\"");
        assertThat(board.workflowPath()).content(StandardCharsets.UTF_8).isEqualTo(originalWorkflow);
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
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
        when(fixture.healthChecker.waitForSameWorkflow(eq(board), eq(board.serverPort()), any()))
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
        saveConnectedBoardsAndStubSuccessfulStarts(fixture, "First Queue", "Second Queue");

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
        ManagedProcessStore.ManagedProcessFiles files = fixture.managedFiles(board);
        fixture.stubStartedWorkerProcessValidation(board, 42, false, false);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("newly started managed process is not running")
                .hasMessageContaining("symphony-trello diagnostics")
                .hasMessageContaining("symphony-trello logs")
                .hasMessageNotContaining(files.stdoutLog().toString())
                .hasMessageNotContaining(files.stderrLog().toString())
                .hasMessageNotContaining(tempDir.toString());
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
    void startRejectsUnresolvedWorkflowServerPortBeforeLaunchingPackagedApp() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
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
                  port: $SYMPHONY_TEST_PORT
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure)
                    .hasMessageContaining(
                            "server.port references a missing environment variable", "selected workflow file");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startRejectsInvalidWorkflowServerPortEnvironmentValueWithActionableMessage() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture =
                new LocalWorkerManagerTestFixture(tempDir, Map.of("SYMPHONY_TEST_PORT", "70000"));
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
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
                  port: $SYMPHONY_TEST_PORT
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure)
                    .hasMessageContaining("server.port must resolve to an integer between 0 and 65535")
                    .hasMessageContaining("selected workflow file");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @MethodSource("unresolvedServerPortStartSelectors")
    @ParameterizedTest(name = "{0}")
    void startAllowsUnresolvedWorkflowServerPortWhenHttpPortOverrideIsConfigured(
            UnresolvedServerPortStartSelector selector) throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = unresolvedServerPortBoardWithHttpOverride(fixture);

        // when
        WorkerRunResult result = fixture.start(selector.request(fixture, board));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello: \"Queue\"");
    }

    private static Stream<UnresolvedServerPortStartSelector> unresolvedServerPortStartSelectors() {
        return Stream.of(
                new UnresolvedServerPortStartSelector(
                        "board selector", (fixture, ignored) -> fixture.startRequest("Queue")),
                new UnresolvedServerPortStartSelector(
                        "workflow selector", (fixture, board) -> fixture.startWorkflowRequest(board.workflowPath())));
    }

    @Test
    void startRejectsUnresolvedWorkflowBoardIdBeforeLaunchingPackagedApp() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: $MISSING_BOARD_ID
                server:
                  port: 18080
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure)
                    .hasMessageContaining(
                            "tracker.board_id references a missing environment variable", "selected workflow file");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startRejectsUnsupportedTrackerKindWithoutCredentialPreflightError() throws Exception {
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

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage())
                    .contains("tracker.kind must be trello")
                    .doesNotContain("credential");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startIsIdempotentWhenExpectedWorkerIsAlreadyHealthy() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        stubManagedSameWorkflow(fixture, board, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("already running");
    }

    @Test
    void startDoesNotResolveLaunchFileSecretsWhenExpectedWorkerIsAlreadyHealthy() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path missingSecret = fixture.paths.configDir().resolve("missing-secret.txt");
        writeWorkflowWithMissingFileSecret(board, missingSecret);
        fixture.save(board);
        stubManagedSameWorkflow(fixture, board, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("already running");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentStartsForSameBoardReportOnlyOneStartAction() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPort(board);
        CountDownLatch firstStartEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstStart = new CountDownLatch(1);
        AtomicInteger startCalls = new AtomicInteger();
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenAnswer(invocation -> {
                    startCalls.incrementAndGet();
                    firstStartEntered.countDown();
                    assertThat(releaseFirstStart.await(5, TimeUnit.SECONDS))
                            .as("the test releases the first concurrent start within 5 seconds")
                            .isTrue();
                    return new ManagedProcessHandle(42);
                });
        when(fixture.healthChecker.waitForSameWorkflow(eq(board), eq(board.serverPort()), any()))
                .thenReturn(fixture.sameWorkflow(board));
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(fixture.stopped(board), fixture.sameWorkflow(board));

        AtomicReference<Thread> secondThread = new AtomicReference<>();
        AtomicReference<WorkerRunResult> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<WorkerRunResult> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        Thread first = startThread(() -> fixture.start(fixture.startRequest("Queue")), firstResult, firstError);
        Thread second = null;
        try {
            assertThat(firstStartEntered.await(5, TimeUnit.SECONDS))
                    .as(
                            "first start should reach platform.start, thread state=%s, result=%s, error=%s",
                            first.getState(), firstResult, firstError)
                    .isTrue();
            second = startThread(() -> fixture.start(fixture.startRequest("Queue")), secondResult, secondError);
            secondThread.set(second);
            awaitCondition(
                    () -> startCalls.get() > 1 || secondError.get() != null || threadIsWaiting(secondThread.get()));

            // when
            assertThat(startCalls).hasValue(1);
        } finally {
            releaseFirstStart.countDown();
        }
        first.join(Duration.ofSeconds(5));
        if (second != null) {
            second.join(Duration.ofSeconds(5));
        }

        // then
        assertThat(firstError).hasValue(null);
        assertThat(secondError).hasValue(null);
        assertThat(firstResult.get().stdout()).contains("Started Symphony for Trello: \"Queue\"");
        assertThat(secondResult.get().stdout()).contains("already running").doesNotContain("Started Symphony");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startWaitsForAnotherProcessHoldingTheWorkerLock() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubHealthyStartedWorker(board, 42);
        AtomicInteger startCalls = new AtomicInteger();
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenAnswer(invocation -> {
                    startCalls.incrementAndGet();
                    return new ManagedProcessHandle(42);
                });
        Path processLockFile = fixture.managedFiles(board).processLockFile();
        Files.createDirectories(processLockFile.getParent());
        Process lockHolder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ExternalFileLockHolder.class.getName(),
                        processLockFile.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertThat(lockHolder.inputReader().readLine()).isEqualTo("locked");
            AtomicReference<WorkerRunResult> startResult = new AtomicReference<>();
            AtomicReference<Throwable> startError = new AtomicReference<>();
            Thread start = startThread(() -> fixture.start(fixture.startRequest("Queue")), startResult, startError);

            // when
            awaitCondition(() -> startCalls.get() > 0 || threadIsWaitingForFileLock(start));

            // then
            assertThat(startCalls).hasValue(0);
            assertThat(threadIsWaitingForFileLock(start))
                    .as("the start thread waits for the external worker lock")
                    .isTrue();
            lockHolder.getOutputStream().close();
            assertThat(lockHolder.waitFor(Duration.ofSeconds(5)))
                    .as("the external worker-lock holder exits within 5 seconds")
                    .isTrue();
            assertThat(start.join(Duration.ofSeconds(5)))
                    .as("the worker start completes after the external lock is released")
                    .isTrue();
            assertThat(startError).hasNullValue();
            startResult.get().assertSuccess().stdoutContains("Started Symphony for Trello");
        } finally {
            if (lockHolder.isAlive()) {
                lockHolder.destroyForcibly();
                lockHolder.waitFor(Duration.ofSeconds(5));
            }
        }
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
    void startIgnoresInvalidExplicitEnvOverrideWhenWorkerIsAlreadyRunning() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path envPath = fixture.paths.configDir().resolve(".env.key-only");
        Files.writeString(envPath, "TRELLO_API_KEY=test-key\n", StandardCharsets.UTF_8);
        fixture.save(board);
        fixture.stubManagedPid(board, 42);
        fixture.stubWorkflowHealth(board, envPath, board.serverPort(), fixture.sameWorkflow(board));

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue", envPath));

        // then
        result.assertSuccess().stdoutContains("already running");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startValidatesExplicitEnvOverrideBeforeLaunchingWorker() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path envPath = fixture.paths.configDir().resolve(".env.key-only");
        Files.writeString(envPath, "TRELLO_API_KEY=test-key\n", StandardCharsets.UTF_8);
        fixture.save(board);
        fixture.stubWorkflowHealth(board, envPath, board.serverPort(), fixture.stopped(board));

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

    @CsvSource({"$REAL_KEY", "${REAL_KEY}", "${REAL_KEY:-fallback}"})
    @ParameterizedTest
    void startRejectsReferenceLookingDotenvCredentialsBeforeTrelloPreflightAndLaunch(String dotenvValue)
            throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.envPath(),
                "TRELLO_API_KEY=" + dotenvValue + "\nTRELLO_API_TOKEN=real-token\n",
                StandardCharsets.UTF_8);
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code())
                    .as("the expected local error must fire instead of a misleading Trello auth failure")
                    .isEqualTo("setup_credentials_environment_reference");
            assertThat(failure).hasMessageContaining("credential file values are used literally");
            assertThat(failure.dotenvPath()).contains(board.envPath());
        });
        verify(fixture.credentialPreflight, never()).verify(any(), any(), any());
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startPrefersShellEnvironmentCredentialsOverReferenceLookingDotenvValues() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(
                tempDir, Map.of("TRELLO_API_KEY", "env-key", "TRELLO_API_TOKEN", "env-token"));
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.envPath(),
                "TRELLO_API_KEY=${REAL_KEY}\nTRELLO_API_TOKEN=${REAL_TOKEN}\n",
                StandardCharsets.UTF_8);
        fixture.save(board);
        fixture.stubHealthyStartedWorker(board, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("Started Symphony for Trello")
                .stdoutDoesNotContain("setup_credentials_environment_reference");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startIsIdempotentWhenExpectedWorkerIsHealthyButPidFileIsMissing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Docs Queue");
        fixture.save(board);
        stubSameWorkflowHealthProbe(fixture, board);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Docs Queue"));

        // then
        result.assertSuccess().stdoutContains("already running", "http://127.0.0.1:" + board.serverPort());
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startDoesNotResolveLaunchFileSecretsWhenHealthProbeFindsAlreadyHealthyWorkerWithoutPid() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Docs Queue");
        Path missingSecret = fixture.paths.configDir().resolve("missing-secret.txt");
        writeWorkflowWithMissingFileSecret(board, missingSecret);
        fixture.save(board);
        stubSameWorkflowHealthProbe(fixture, board);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Docs Queue"));

        // then
        result.assertSuccess().stdoutContains("already running", "http://127.0.0.1:" + board.serverPort());
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @MethodSource("invalidWorkflowConfigurations")
    @ParameterizedTest
    void startRejectsInvalidWorkflowConfigurationBeforeLaunch(String workflowContent, String expectedMessagePart)
            throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Files.writeString(board.workflowPath(), workflowContent, StandardCharsets.UTF_8);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage()).contains(expectedMessagePart);
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    static Stream<Arguments> invalidWorkflowConfigurations() {
        return Stream.of(
                Arguments.of(
                        """
                        ---
                        tracker: [
                        ---
                        Body
                        """,
                        "invalid YAML"),
                Arguments.of(
                        """
                        ---
                        server:
                          port: 18080
                        ---
                        Body
                        """,
                        "tracker.kind must be trello"),
                Arguments.of(
                        """
                        ---
                        tracker:
                          kind: trello
                          board_id: board-1
                          endpoint: "not-a-url"
                        server:
                          port: 18080
                        ---
                        Body
                        """,
                        "tracker.endpoint must be an absolute http(s) URL"),
                Arguments.of(
                        """
                        ---
                        tracker:
                          kind: trello
                          board_id: board-1
                        agent:
                          max_concurrent_agents: 0
                        server:
                          port: 18080
                        ---
                        Body
                        """,
                        "max_concurrent_agents must be positive"),
                Arguments.of(
                        """
                        ---
                        tracker:
                          kind: trello
                          board_id: board-1
                        polling:
                          interval_ms: -1
                        server:
                          port: 18080
                        ---
                        Body
                        """,
                        "interval_ms must be non-negative"),
                Arguments.of(
                        """
                        ---
                        tracker:
                          kind: trello
                          board_id: board-1
                        codex:
                          command: ""
                        server:
                          port: 18080
                        ---
                        Body
                        """,
                        "codex.command is required"));
    }

    @Test
    void startRejectsMissingWorkflowFileBeforeLaunch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Files.delete(board.workflowPath());

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertWorkflowInvalidForLaunch(thrown, "missing workflow file");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void lifecycleCommandsReportMalformedManifestAsExpectedConfigError() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(fixture.paths.manifestPath(), "not-valid-json", StandardCharsets.UTF_8);

        // when
        Throwable thrown = catchThrowable(() -> fixture.status(fixture.statusRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_manifest_unavailable");
            assertThat(failure.getMessage())
                    .contains("not valid JSON", ConnectedBoardManifest.FILE_NAME)
                    .doesNotContain("double-quote", "Unexpected character");
        });
    }

    @Test
    void statusEscapesEmbeddedQuotesInBoardNames() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Quote \" Board");
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Quote \" Board"));

        // then
        result.assertSuccess().stdoutContains("stopped \"Quote \\\" Board\"");
    }

    @Test
    void statusEscapesNewlinesInBoardNames() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Line1\nLine2");
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Line1\nLine2"));

        // then
        result.assertSuccess().stdoutContains("stopped \"Line1\\nLine2\"");
        assertThat(result.stdout().lines().filter(line -> line.equals("Line2")).count())
                .as("a newline in a Trello board name must not split the status line")
                .isZero();
    }

    @Test
    void statusReportsLinuxRuntimeHealthWithoutQueryingSystemd() throws Exception {
        // given
        Path home = tempDir.resolve("linux-home");
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(
                tempDir, Map.of("SYMPHONY_TRELLO_TEST_OS", "Linux", "SYMPHONY_TRELLO_TEST_HOME", home.toString()));
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPidWithHealth(board, 42, fixture.sameWorkflowWithPid(board, 42));

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("running \"Queue\" pid=42")
                .stdoutDoesNotContain("autostart", "systemd", "unavailable");
    }

    @Test
    void statusReportsMacosRuntimeHealthWithoutQueryingLaunchctl() throws Exception {
        // given
        Path home = tempDir.resolve("mac-home");
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(
                tempDir,
                Map.of(
                        "SYMPHONY_TRELLO_TEST_OS",
                        "Darwin",
                        "SYMPHONY_TRELLO_TEST_HOME",
                        home.toString(),
                        "SYMPHONY_TRELLO_TEST_UID",
                        "501"));
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("stopped \"Queue\"").stdoutDoesNotContain("autostart", "LaunchAgent");
    }

    @Test
    void statusReportsWindowsRuntimeHealthWithoutQueryingTaskScheduler() throws Exception {
        // given
        Path appData = tempDir.resolve("windows-appdata");
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(
                tempDir, Map.of("SYMPHONY_TRELLO_TEST_OS", "Windows 11", "APPDATA", appData.toString()));
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("stopped \"Queue\"").stdoutDoesNotContain("autostart", "scheduled_task");
    }

    @Test
    void statusReportsEverySelectedWorkflowIndependentlyWithoutAutostartOutput() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard running = fixture.connectedBoard("board-running", "Running", "01-running");
        ConnectedBoard stopped = fixture.connectedBoard("board-stopped", "Stopped", "02-stopped");
        ConnectedBoard wrongWorkflow = fixture.connectedBoard("board-wrong", "Wrong", "03-wrong");
        ConnectedBoard invalid = fixture.connectedBoard("board-invalid", "Invalid", "04-invalid");
        Files.writeString(invalid.workflowPath(), "plain body\n", StandardCharsets.UTF_8);
        fixture.save(running, stopped, wrongWorkflow, invalid);
        fixture.stubManagedPidWithHealth(running, 41, fixture.sameWorkflowWithPid(running, 41));
        fixture.stubManagedPidWithHealth(wrongWorkflow, 42, fixture.wrongWorkflow(wrongWorkflow));

        // when
        WorkerRunResult result = fixture.status(fixture.statusAllRequest());

        // then
        result.assertSuccess()
                .stdoutContains(
                        "running \"Running\" pid=41",
                        "stopped \"Stopped\"",
                        "unhealthy \"Wrong\" pid=42",
                        "WRONG_WORKFLOW",
                        "invalid \"Invalid\"")
                .stdoutDoesNotContain("autostart", "systemctl", "launchctl", "schtasks", "\n\n");
        assertThat(result.stdout().lines().toList())
                .extracting(line -> line.substring(0, line.indexOf(' ')))
                .containsExactly("running", "stopped", "unhealthy", "invalid");
        verify(fixture.healthChecker, never()).boardHealth(invalid);
    }

    @MethodSource("statusHealthProbeFailures")
    @ParameterizedTest(name = "{0}")
    void statusReportsHealthProbeFailureAndContinuesWithSiblings(
            String ignoredDescription, RuntimeException failure, String expectedStatus) throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard broken = fixture.connectedBoard("board-broken", "Broken", "01-broken");
        ConnectedBoard stopped = fixture.connectedBoard("board-stopped", "Stopped", "02-stopped");
        fixture.save(broken, stopped);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(broken, 42);
        when(fixture.healthChecker.boardHealth(broken)).thenThrow(failure);

        // when
        WorkerRunResult result = fixture.status(fixture.statusAllRequest());

        // then
        result.assertSuccess()
                .stdoutContains(expectedStatus, "stopped \"Stopped\"")
                .stdoutDoesNotContain("private context", failure.getClass().getSimpleName());
        assertThat(result.stdout().lines().toList()).containsExactly(expectedStatus, "stopped \"Stopped\"");
        assertStatusEvidenceFailureIsReadOnlyAndContinues(fixture, files, stopped);
    }

    @Test
    void statusContainsUncheckedWorkflowDiagnosticsFailureAndContinuesWithSiblings() throws Exception {
        // given
        WorkflowConfigEditor workflowConfig = mock();
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir, Map.of(), workflowConfig);
        ConnectedBoard broken = fixture.connectedBoard("board-broken", "Broken", "01-broken");
        ConnectedBoard stopped = fixture.connectedBoard("board-stopped", "Stopped", "02-stopped");
        fixture.save(broken, stopped);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(broken, 42);
        IllegalStateException failure =
                new IllegalStateException("private workflow environment and local path context");
        when(workflowConfig.diagnosticsValidation(eq(broken.workflowPath()), any()))
                .thenThrow(failure);
        when(workflowConfig.diagnosticsValidation(eq(stopped.workflowPath()), any()))
                .thenReturn(WorkflowValidation.valid());

        // when
        WorkerRunResult result = fixture.status(fixture.statusAllRequest());

        // then
        assertIsolatedStatusEvidenceFailure(result, failure);
        verify(fixture.healthChecker, never()).boardHealth(broken);
        assertStatusEvidenceFailureIsReadOnlyAndContinues(fixture, files, stopped);
    }

    @Test
    void statusContainsUncheckedProcessEvidenceFailureAndContinuesWithSiblings() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard broken = fixture.connectedBoard("board-broken", "Broken", "01-broken");
        ConnectedBoard stopped = fixture.connectedBoard("board-stopped", "Stopped", "02-stopped");
        fixture.save(broken, stopped);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(broken, 42);
        IllegalStateException failure = new IllegalStateException("private process evidence context");
        when(fixture.platform.isAlive(42)).thenThrow(failure);

        // when
        WorkerRunResult result = fixture.status(fixture.statusAllRequest());

        // then
        assertIsolatedStatusEvidenceFailure(result, failure);
        assertStatusEvidenceFailureIsReadOnlyAndContinues(fixture, files, stopped);
    }

    @Test
    void statusDoesNotCatchErrorsFromPerWorkflowEvidence() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard broken = fixture.connectedBoard("board-broken", "Broken", "01-broken");
        ConnectedBoard stopped = fixture.connectedBoard("board-stopped", "Stopped", "02-stopped");
        fixture.save(broken, stopped);
        AssertionError failure = new AssertionError("fatal evidence failure");
        when(fixture.healthChecker.boardHealth(broken)).thenThrow(failure);

        // when
        Throwable thrown = catchThrowable(() -> fixture.status(fixture.statusAllRequest()));

        // then
        assertThat(thrown).isSameAs(failure);
        verify(fixture.healthChecker, never()).boardHealth(stopped);
    }

    @Test
    void stopEscapesEmbeddedQuotesInBoardNamesWhenSkippingStalePids() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Quote \" Board");
        fixture.save(board);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        store.writePid(store.files(board.workflowPath()).pidFile(), 42);
        when(fixture.platform.isAlive(42L)).thenReturn(true);
        when(fixture.platform.isManaged(42L, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopRequest("Quote \" Board"));

        // then
        result.assertSuccess().stdoutContains("Skipped unmanaged stale pid for \"Quote \\\" Board\" pid=42");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void statusResolvesSymlinkedWorkflowSelectorToConnectedBoard() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Path link = tempDir.resolve("workflow-link.md");
        Files.createSymbolicLink(link, board.workflowPath());

        // when
        WorkerRunResult result = fixture.status(fixture.statusWorkflowRequest(link));

        // then
        result.assertSuccess().stdoutContains("stopped \"Queue\"").stdoutDoesNotContain("workflow-link.md");
    }

    @Test
    void statusDisambiguatesDuplicateConnectedBoardNames() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "Queue", "queue-one");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Queue", "queue-two");
        fixture.save(first, second);

        // when
        WorkerRunResult result = fixture.status(new WorkerStatusRequest(
                Optional.empty(),
                Optional.empty(),
                Optional.of(fixture.paths.appHome()),
                Optional.of(fixture.paths.configDir()),
                Optional.of(fixture.paths.workspaceRoot()),
                Optional.of(fixture.paths.stateHome())));

        // then
        result.assertSuccess()
                .stdoutContains(
                        "stopped \"Queue\" [board-1 WORKFLOW.queue-one.md]",
                        "stopped \"Queue\" [board-2 WORKFLOW.queue-two.md]");
    }

    @Test
    void statusKeepsConciseLabelForUniqueBoardNames() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("stopped \"Queue\"").stdoutDoesNotContain("[board-1");
    }

    @Test
    void rotateLogsForReplacedBoardsMovesLogsWhenWorkflowPathIsReusedForDifferentBoard() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard oldBoard = fixture.connectedBoard("board-old", "Queue", "queue");
        ConnectedBoard newBoard = fixture.connectedBoard("board-new", "Queue Reborn", "queue");
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files = store.files(oldBoard.workflowPath());
        Files.createDirectories(fixture.paths.stateHome());
        Files.writeString(files.stdoutLog(), "old board startup failure", StandardCharsets.UTF_8);

        // when
        fixture.manager.rotateLogsForReplacedBoards(fixture.paths, newBoard, List.of(oldBoard));

        // then
        assertThat(files.stdoutLog()).doesNotExist();
        Path rotated = files.stdoutLog().resolveSibling(files.stdoutLog().getFileName() + ".previous");
        assertThat(rotated).content(StandardCharsets.UTF_8).isEqualTo("old board startup failure");
    }

    @Test
    void rotateLogsForReplacedBoardsKeepsLogsForSameBoardIdentity() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files = store.files(board.workflowPath());
        Files.createDirectories(fixture.paths.stateHome());
        Files.writeString(files.stdoutLog(), "same board history", StandardCharsets.UTF_8);

        // when
        fixture.manager.rotateLogsForReplacedBoards(fixture.paths, board, List.of(board));

        // then
        assertThat(files.stdoutLog()).content(StandardCharsets.UTF_8).isEqualTo("same board history");
    }

    @Test
    void startRejectsOccupiedNonSymphonyStatusPortBeforeLaunch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.PORT_USED, board.serverPort(), Optional.empty(), Optional.empty()));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_port_in_use");
            assertThat(failure.getMessage())
                    .contains("port " + board.serverPort() + " is already in use", "Queue")
                    .doesNotContain(".log", ".err");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
        assertThat(new ManagedProcessStore(fixture.paths.stateHome())
                        .readPid(new ManagedProcessStore(fixture.paths.stateHome())
                                .files(board.workflowPath())
                                .pidFile()))
                .as("no managed pid state for the failed attempt")
                .isNull();
    }

    @Test
    void startWorkflowRejectsBoardAlreadyManagedByAnotherRunningWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPid(board, 4242L);
        Path staleWorkflow = fixture.paths.configDir().resolve("WORKFLOW.stale-copy.md");
        Files.writeString(
                staleWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: 18081
                ---
                Body
                """,
                StandardCharsets.UTF_8);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(staleWorkflow)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_board_already_managed");
            assertThat(failure.getMessage()).contains("Queue", "already managed by a running worker");
            assertThat(failure.getMessage())
                    .doesNotContain(board.workflowPath().toString(), staleWorkflow.toString(), tempDir.toString());
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startWorkflowRejectsBoardManagedThroughStoredUrlShortLink() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path rowWorkflow = fixture.paths.configDir().resolve("WORKFLOW.shortlink-row.md");
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(
                rowWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "000000000000000000000002"
                server:
                  port: %d
                ---
                # Shortlink Queue
                """
                        .formatted(ConfigDefaults.DEFAULT_SERVER_PORT),
                StandardCharsets.UTF_8);
        fixture.writeEnv(fixture.paths.defaultEnvPath());
        ConnectedBoard row = ConnectedBoardBuilder.connectedBoard(
                        rowWorkflow.toAbsolutePath().normalize())
                .withBoardId("000000000000000000000002")
                .withBoardKey("000000000000000000000002")
                .withBoardName("Shortlink Queue")
                .withBoardUrl("https://trello.com/b/SYNTH002/synthetic-board")
                .withEnvPath(fixture.paths.defaultEnvPath())
                .withWorkspaceRoot(fixture.paths.workspaceRoot())
                .build();
        fixture.save(row);
        fixture.stubManagedPid(row, 4242L);
        Path staleWorkflow = fixture.paths.configDir().resolve("WORKFLOW.shortlink-stale.md");
        Files.writeString(
                staleWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: SYNTH002
                server:
                  port: 18081
                ---
                Body
                """,
                StandardCharsets.UTF_8);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(staleWorkflow)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_board_already_managed");
            assertThat(failure.getMessage()).contains("Shortlink Queue", "already managed by a running worker");
            assertThat(failure.getMessage())
                    .doesNotContain(rowWorkflow.toString(), staleWorkflow.toString(), tempDir.toString());
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @MethodSource("invalidManifestContents")
    @ParameterizedTest
    void lifecycleCommandsClassifyInvalidManifestShapesAsExpectedConfigErrors(String name, String content)
            throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(fixture.paths.manifestPath(), content, StandardCharsets.UTF_8);

        // when
        List<Throwable> failures = lifecycleCommandFailures(fixture);

        // then
        assertLifecycleManifestRejections(fixture, failures, name);
    }

    private static Stream<Arguments> invalidManifestContents() {
        return Stream.of(
                Arguments.of("malformed", "not-valid-json"),
                Arguments.of("empty file", ""),
                Arguments.of("top-level null", "null"),
                Arguments.of("top-level array", "[]"),
                Arguments.of("null boards", "{\"boards\":null}"),
                Arguments.of("non-array boards", "{\"boards\":\"not-array\"}"),
                Arguments.of("null board row", "{\"boards\":[null]}"));
    }

    @Test
    void lifecycleCommandsClassifyIncompleteManifestRowsAsExpectedConfigErrors() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(fixture.paths.manifestPath(), "{\"boards\":[{}]}", StandardCharsets.UTF_8);

        // when
        List<Throwable> failures = lifecycleCommandFailures(fixture);

        // then
        assertLifecycleManifestRejections(fixture, failures, "incomplete row");
    }

    @Test
    void startAllSkipsBoardAlreadyManagedByAnotherRunningWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard running = fixture.connectedBoard("board-1", "Queue", "queue-running");
        ConnectedBoard stale = fixture.connectedBoard("board-1", "Queue", "queue-stale");
        fixture.save(running, stale);
        fixture.stubManagedPid(running, 4242L);
        fixture.stubWorkflowHealth(running, fixture.sameWorkflow(running));

        // when
        WorkerRunResult result = fixture.start(fixture.startAllRequest());

        // then
        result.assertSuccess()
                .stdoutContains("Skipped workflow file", "already managed by another running workflow")
                .stdoutDoesNotContain(
                        running.workflowPath().toString(), stale.workflowPath().toString(), tempDir.toString());
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startWorkflowAttemptsLaunchWhenOtherWorkflowForSameBoardIsNotRunning() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Path staleWorkflow = fixture.paths.configDir().resolve("WORKFLOW.stale-copy.md");
        Files.writeString(
                staleWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: 18081
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(99L));
        when(fixture.platform.stop(eq(99L), any(Duration.class), any(Duration.class)))
                .thenReturn(true);
        when(fixture.healthChecker.waitForSameWorkflow(any(), anyInt(), any())).thenReturn(fixture.stopped(18081));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(staleWorkflow)));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("did not report");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startRestartsAliveManagedWorkerWhenHealthProbeStopsResponding() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = saveBoardWithManagedPid(fixture);
        stubSuccessfulManagedRestart(fixture, board, portUsed(board));

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello: \"Queue\"");
        assertThat(Files.readString(onlyPidFile(fixture.paths.stateHome()))).isEqualTo("99");
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        verify(fixture.platform, times(1)).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startReportsPortConflictAfterStoppingHungManagedWorkerWhenPortRemainsOccupied() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = saveBoardWithManagedPid(fixture);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(portUsed(board), portUsed(board));
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_port_in_use");
            assertThat(failure.getMessage())
                    .contains("port " + board.serverPort() + " is already in use", "Queue")
                    .doesNotContain(".log", ".err", tempDir.toString());
        });
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
    }

    @Test
    void startRestartsAliveManagedWorkerWhenHealthProbeReportsAnotherWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = saveBoardWithManagedPid(fixture);
        stubSuccessfulManagedRestart(fixture, board, fixture.wrongWorkflow(board));

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello: \"Queue\"");
        assertThat(Files.readString(onlyPidFile(fixture.paths.stateHome()))).isEqualTo("99");
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startReportsPathSafeConflictWhenPostStopHealthReportsAnotherWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = saveBoardWithManagedPid(fixture);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(portUsed(board), fixture.wrongWorkflow(board));
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_port_in_use");
            assertThat(failure.getMessage())
                    .contains("already serving another Symphony workflow", "Stop that worker")
                    .doesNotContain(
                            board.workflowPath().toString(),
                            fixture.paths.configDir().toString(),
                            fixture.paths.stateHome().toString(),
                            tempDir.toString(),
                            ".log",
                            ".err");
        });
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
    }

    @MethodSource("postStopSameWorkflowStartSelectors")
    @ParameterizedTest(name = "{0}")
    void startRepairsTrackingWhenPostStopHealthReportsVerifiedManagedWorker(StartSelector selector) throws Exception {
        // given
        PostStopSameWorkflow scenario = postStopSameWorkflowWithReportedPid(true);

        // when
        WorkerRunResult result = scenario.fixture().start(selector.request(scenario.fixture(), scenario.board()));

        // then
        result.assertSuccess()
                .stdoutContains(
                        "already running", "Restored missing managed worker tracking pid=" + scenario.reportedPid());
        assertNoLifecyclePrivateOutput(result, scenario.fixture(), scenario.board());
        assertThat(Files.readString(onlyPidFile(scenario.fixture().paths.stateHome())))
                .isEqualTo(Long.toString(scenario.reportedPid()));
        verifyPostStopStoppedWithoutLaunch(scenario.fixture());
    }

    @Test
    void startReportsManualStopWhenPostStopHealthReportsUnmanagedWorkerPid() throws Exception {
        // given
        PostStopSameWorkflow scenario = postStopSameWorkflowWithReportedPid(false);

        // when
        WorkerRunResult result = scenario.fixture().start(scenario.fixture().startRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains(
                        "already running",
                        "Stop the worker process manually",
                        "Reported worker pid=" + scenario.reportedPid());
        assertNoPostStopUntrackedResult(result, scenario.fixture(), scenario.board());
    }

    @Test
    void startReportsManualStopWhenPostStopHealthReportsSameWorkflowWithoutPid() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = saveBoardWithManagedPid(fixture);
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(portUsed(board), fixture.sameWorkflow(board));
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("already running", "Stop the worker process manually")
                .stdoutDoesNotContain("Reported worker pid=");
        assertNoPostStopUntrackedResult(result, fixture, board);
    }

    private static Stream<StartSelector> postStopSameWorkflowStartSelectors() {
        return Stream.of(
                new StartSelector("plain start", (fixture, ignored) -> fixture.startRequest()),
                new StartSelector("board selector", (fixture, ignored) -> fixture.startRequest("Queue")),
                new StartSelector(
                        "workflow selector", (fixture, board) -> fixture.startWorkflowRequest(board.workflowPath())),
                new StartSelector("start all", (fixture, ignored) -> fixture.startAllRequest()));
    }

    @Test
    void startRestoresMissingManagedPidForHealthyUntrackedManagedWorker() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Docs Queue");
        fixture.save(board);
        long workerPid = stubUntrackedWorkerStartHealth(fixture, board, true);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Docs Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("already running", "Restored missing managed worker tracking pid=" + workerPid);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        assertThat(store.readPid(store.files(board.workflowPath()).pidFile())).isEqualTo(workerPid);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startReportsUntrackedWorkerPidWhenProcessIsNotManagedByThisInstall() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Docs Queue");
        fixture.save(board);
        long workerPid = stubUntrackedWorkerStartHealth(fixture, board, false);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Docs Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("already running", "Reported worker pid=" + workerPid)
                .stdoutDoesNotContain("Restored missing managed worker tracking");
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        assertThat(store.readPid(store.files(board.workflowPath()).pidFile())).isNull();
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
    void startReportsInvalidWorkflowInsteadOfOccupiedFallbackPort() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: "not-a-port"
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        fixture.stubWorkflowHealth(
                board,
                new BoardHealth(BoardHealthKind.PORT_USED, board.serverPort(), Optional.empty(), Optional.empty()));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code())
                    .as("invalid workflow must not be masked by the occupied fallback port")
                    .isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage()).contains("Invalid workflow configuration:");
        });
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startRejectsPortAlreadyServingAnotherWorkflowBeforeLaunch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubWorkflowHealth(board, fixture.wrongWorkflow(board));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_port_in_use");
            assertThat(failure.getMessage())
                    .contains(
                            "already serving another Symphony workflow",
                            "Queue",
                            "another Symphony workflow",
                            "Stop that worker or change the workflow server.port");
            assertThat(failure.getMessage())
                    .doesNotContain(
                            board.workflowPath().toString(),
                            fixture.paths.configDir().toString(),
                            fixture.paths.stateHome().toString(),
                            ".log",
                            ".err");
        });
        assertThat(pidFiles(fixture.paths.stateHome()))
                .as("rejected start leaves no managed pid state")
                .isEmpty();
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void failedArchivedBoardStartLeavesNoMisleadingStoppedLifecycleState() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubStoppedStartedWorkerWithStartupLog(board, 42, "Configured Trello board is archived\n");

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));
        WorkerRunResult status = fixture.status(fixture.statusRequest("Queue"));
        WorkerRunResult stop = fixture.stop(fixture.stopRequest("Queue"));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.code())
                .isEqualTo("trello_board_closed"));
        assertThat(pidFiles(fixture.paths.stateHome()))
                .as("failed start leaves no managed pid state")
                .isEmpty();
        status.assertSuccess().stdoutContains("stopped \"Queue\"").stdoutDoesNotContain("pid=");
        stop.assertSuccess()
                .stdoutContains("Symphony for Trello is already stopped for \"Queue\"")
                .stdoutDoesNotContain("Stopped Queue", "Stopped WORKFLOW");
    }

    @Test
    void startClassifiesInvalidTrelloCredentialsBeforeLaunch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        doThrow(new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed while starting Symphony."))
                .when(fixture.credentialPreflight)
                .verify(any(), any(), any());

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("trello_auth_failed");
            assertThat(failure.dotenvPath()).contains(fixture.paths.defaultEnvPath());
            assertThat(failure.getMessage()).doesNotContain(".log", ".err");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
    }

    @Test
    void startClassifiesMalformedCredentialRejectionBeforeLaunch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        doThrow(new TrelloBoardSetupException(
                        "trello_invalid_request", "Trello rejected the setup request: invalid key"))
                .when(fixture.credentialPreflight)
                .verify(any(), any(), any());

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("trello_auth_failed");
            assertThat(failure.getMessage()).contains("Trello rejected the resolved API credentials");
            assertThat(failure.dotenvPath()).contains(fixture.paths.defaultEnvPath());
            assertThat(failure.getMessage()).doesNotContain(".log", ".err");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
    }

    @Test
    void startProceedsWhenCredentialPreflightHitsTransportProblems() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        doThrow(new TrelloBoardSetupException("trello_api_request", "Trello request failed"))
                .when(fixture.credentialPreflight)
                .verify(any(), any(), any());
        fixture.stubHealthyStartedWorker(board, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startSurfacesTrelloAuthFailureFromWorkerStartupLogs() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubStoppedStartedWorkerWithStartupLog(
                board,
                42,
                """
                Caused by: ch.fmartin.symphony.trello.tracker.TrelloException: Trello authentication failed
                \tat ch.fmartin.symphony.trello.tracker.TrelloClient.statusException(TrelloClient.java:600)
                """);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("trello_auth_failed");
            assertThat(failure).hasMessage("Trello authentication failed while starting Symphony.");
        });
    }

    @Test
    void startAttachesShellCredentialSourceToTrelloAuthFailures() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(
                tempDir, Map.of("TRELLO_API_KEY", "shell-key", "TRELLO_API_TOKEN", "shell-token"));
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.managedFiles(board);
        stubStartupLogRewrite(fixture, board, files, "Trello authentication failed\n");

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("trello_auth_failed");
            assertThat(failure.trelloApiKeyCredentialSource())
                    .contains(TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT);
            assertThat(failure.trelloApiTokenCredentialSource())
                    .contains(TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT);
            assertThat(failure.dotenvPath()).contains(board.envPath());
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
        String workflowToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), board.workflowPath());
        String stdoutToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), files.stdoutLog());
        String stderrToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), files.stderrLog());

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_start_unhealthy");
            assertThat(failure)
                    .hasMessageContaining("did not report the expected workflow and board")
                    .hasMessageContaining("symphony-trello diagnostics")
                    .hasMessageContaining("symphony-trello logs")
                    .hasMessageContaining("Private context tokens: workflow=%s", workflowToken)
                    .hasMessageContaining("stdout_log=%s", stdoutToken)
                    .hasMessageContaining("stderr_log=%s", stderrToken)
                    .hasMessageContaining(PrivateContextTokens.lookupCommand(workflowToken))
                    .hasMessageNotContaining(files.stdoutLog().toString())
                    .hasMessageNotContaining(files.stderrLog().toString())
                    .hasMessageNotContaining(tempDir.toString());
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
        stubStartupLogRewrite(
                fixture,
                board,
                files,
                """
                Trello authentication failed
                Additional startup details that make this rewritten log longer than the previous file.
                """);

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
    void statusReportsUnhealthyWhenEndpointPidDoesNotMatchManagedPid() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPidWithHealth(board, 42, fixture.sameWorkflowWithPid(board, 99));

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("unhealthy \"Queue\" pid=42", "WORKER_PID_MISMATCH")
                .stdoutDoesNotContain("running \"Queue\"");
    }

    @Test
    void statusReportsUnhealthyWhenMatchingEndpointOmitsWorkerPid() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPidWithHealth(board, 42, fixture.sameWorkflow(board));

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("unhealthy \"Queue\" pid=42", "WORKER_PID_MISMATCH")
                .stdoutDoesNotContain("running \"Queue\"");
    }

    @Test
    void statusReportsUntrackedRunningWorkerWhenPidFileIsMissingButHealthMatches() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        stubUntrackedStatusHealth(fixture, board, 73, true);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("running \"Queue\"", "untracked, no managed pid")
                .stdoutDoesNotContain("stopped \"Queue\"");
    }

    @Test
    void statusRejectsUntrackedEndpointWhoseReportedPidIsNotManaged() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        stubUntrackedStatusHealth(fixture, board, 73, false);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("unhealthy \"Queue\"", "UNVERIFIED_WORKER_PID")
                .stdoutDoesNotContain("running \"Queue\"");
    }

    @Test
    void statusRejectsManagedEndpointPidWhenWorkflowIdentityDoesNotMatch() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.boardHealth(board))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.WRONG_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of("different-board"),
                        Optional.of(73L)));
        when(fixture.platform.isAlive(73L)).thenReturn(true);
        when(fixture.platform.isManaged(73L, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("unhealthy \"Queue\"", "WRONG_WORKFLOW")
                .stdoutDoesNotContain("running \"Queue\"");
    }

    @Test
    void statusReportsForeignPortWithoutManagedPidAsUnhealthy() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.boardHealth(board)).thenReturn(portUsed(board));

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("unhealthy \"Queue\"", "PORT_USED")
                .stdoutDoesNotContain("running \"Queue\"", "stopped \"Queue\"");
    }

    @Test
    void statusReportsStalePidFileTokenWithoutRawPath() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(board, 42);
        when(fixture.platform.isAlive(42L)).thenReturn(true);
        when(fixture.platform.isManaged(42L, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);
        String pidToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), files.pidFile());

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("stale \"Queue\" pid=42 does not belong to this install", "pid_file_token=" + pidToken)
                .stdoutDoesNotContain(
                        files.pidFile().toString(),
                        fixture.paths.stateHome().toString(),
                        fixture.paths.appHome().toString(),
                        tempDir.toString());
        assertThat(files.pidFile()).exists().content(StandardCharsets.UTF_8).isEqualTo("42");
    }

    @Test
    void statusPrefersVerifiedReplacementWorkerOverStaleManagedPidMetadata() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(board, 42);
        when(fixture.platform.isAlive(42L)).thenReturn(true);
        when(fixture.platform.isManaged(42L, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.sameWorkflowWithPid(board, 73));
        when(fixture.platform.isAlive(73L)).thenReturn(true);
        when(fixture.platform.isManaged(73L, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("running \"Queue\" pid=73", "stale managed pid=42")
                .stdoutDoesNotContain("stale \"Queue\" pid=42", "UNVERIFIED_WORKER_PID");
        assertThat(files.pidFile()).exists().content(StandardCharsets.UTF_8).isEqualTo("42");
    }

    @Test
    void statusLeavesDeadManagedPidStateUnchanged() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(board, 42);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("stopped \"Queue\"");
        assertThat(files.pidFile()).exists().content(StandardCharsets.UTF_8).isEqualTo("42");
    }

    @Test
    void statusReportsInvalidConnectedBoardWorkflowInsteadOfStoppedForPlainFile() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(board.workflowPath(), "plain body\n", StandardCharsets.UTF_8);
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains(
                        "invalid \"Queue\"",
                        "unreadable or invalid workflow configuration",
                        "that board's workflow file")
                .stdoutDoesNotContain(board.workflowPath().toString(), tempDir.toString())
                .stdoutDoesNotContain("stopped \"Queue\"");
        verify(fixture.healthChecker, never()).boardHealth(any());
    }

    @Test
    void statusReportsInvalidConnectedBoardWorkflowInsteadOfStoppedForMissingFile() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.delete(board.workflowPath());
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("invalid \"Queue\"", "missing workflow file", "that board's workflow file")
                .stdoutDoesNotContain(board.workflowPath().toString(), tempDir.toString())
                .stdoutDoesNotContain("stopped \"Queue\"");
        verify(fixture.healthChecker, never()).boardHealth(any());
    }

    @Test
    void statusResolvesWorkflowServerPortFromConnectedBoardEnvFile() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue").withServerPort(19091);
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: $SYMPHONY_TEST_PORT
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                board.envPath(),
                """
                TRELLO_API_KEY=test-key
                TRELLO_API_TOKEN=test-token
                SYMPHONY_TEST_PORT=19091
                """,
                StandardCharsets.UTF_8);
        fixture.save(board);
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.stopped(19091));

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("stopped \"Queue\"")
                .stdoutDoesNotContain("invalid \"Queue\"", "invalid server.port");
    }

    @Test
    void statusStillReportsRunningWorkerWhenManifestConsistencyValidationWarns() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.workflowPath(),
                Files.readString(board.workflowPath(), StandardCharsets.UTF_8).replace("port: 18080", "port: 18081"),
                StandardCharsets.UTF_8);
        fixture.save(board);
        stubUntrackedStatusHealth(fixture, board, 73, true);

        // when
        WorkerRunResult result = fixture.status(fixture.statusRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("running \"Queue\"", "untracked, no managed pid")
                .stdoutDoesNotContain("invalid \"Queue\"", "stopped \"Queue\"");
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
    void stopStopsHealthyUntrackedWorkerWhenPidIsVerifiablyManaged() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        long workerPid = stubUntrackedWorkerStopHealth(fixture, board, true);
        when(fixture.platform.stop(workerPid, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Stopped untracked managed worker", "pid=" + workerPid);
        verify(fixture.platform).stop(workerPid, Duration.ofSeconds(15), Duration.ofSeconds(5));
    }

    @Test
    void stopReportsUntrackedWorkerPidWhenProcessIsNotManagedByThisInstall() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        long workerPid = stubUntrackedWorkerStopHealth(fixture, board, false);

        // when
        var thrown = catchThrowable(() -> fixture.stop(fixture.stopRequest("Queue")));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("no managed pid")
                .hasMessageContaining("Reported worker pid=%s", workerPid);
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void stopBoardSelectorReportsAlreadyStoppedWhenNoWorkerIsRunning() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopRequest("Queue"));

        // then
        result.assertSuccess()
                .stdoutContains("Symphony for Trello is already stopped for \"Queue\"")
                .stdoutDoesNotContain("Stopped Symphony for Trello for \"Queue\"");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void stopWorkflowSelectorReportsAlreadyStoppedWhenNoWorkerIsRunning() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopWorkflowRequest(board.workflowPath()));

        // then
        result.assertSuccess()
                .stdoutContains("Symphony for Trello is already stopped for \"Queue\"")
                .stdoutDoesNotContain("Stopped Symphony for Trello for \"Queue\"");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void stopWorkflowSelectorDoesNotRequireResolvedWorkflowServerPort() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: $SYMPHONY_TEST_PORT
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        fixture.save(board);
        fixture.writeManagedPid(board, 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopWorkflowRequest(board.workflowPath()));

        // then
        result.assertSuccess().stdoutContains("Stopped Symphony for Trello for \"Queue\"");
    }

    @Test
    void logsWorkflowSelectorDoesNotRequireResolvedWorkflowServerPort() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: $SYMPHONY_TEST_PORT
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.managedFiles(board);
        Files.writeString(files.stdoutLog(), "worker log line\n", StandardCharsets.UTF_8);

        // when
        WorkerRunResult result = fixture.logs(fixture.logsWorkflowRequest(board.workflowPath()));

        // then
        result.assertSuccess().stdoutContains("worker log line");
    }

    @Test
    void concurrentStopsForSameBoardReportOnlyOneStopAction() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.writeManagedPid(board, 42);
        CountDownLatch firstStopEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstStop = new CountDownLatch(1);
        AtomicInteger stopCalls = new AtomicInteger();
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenAnswer(invocation -> {
                    stopCalls.incrementAndGet();
                    firstStopEntered.countDown();
                    assertThat(releaseFirstStop.await(5, TimeUnit.SECONDS))
                            .as("the test releases the first concurrent stop within 5 seconds")
                            .isTrue();
                    return true;
                });

        AtomicReference<Thread> secondThread = new AtomicReference<>();
        AtomicReference<WorkerRunResult> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<WorkerRunResult> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        Thread first = startThread(() -> fixture.stop(fixture.stopRequest("Queue")), firstResult, firstError);
        Thread second = null;
        try {
            assertThat(firstStopEntered.await(5, TimeUnit.SECONDS))
                    .as(
                            "first stop should reach platform.stop, thread state=%s, result=%s, error=%s",
                            first.getState(), firstResult, firstError)
                    .isTrue();
            second = startThread(() -> fixture.stop(fixture.stopRequest("Queue")), secondResult, secondError);
            secondThread.set(second);
            awaitCondition(() -> stopCalls.get() > 1 || threadIsWaiting(secondThread.get()));

            // when
            assertThat(stopCalls).hasValue(1);
        } finally {
            releaseFirstStop.countDown();
        }
        first.join(Duration.ofSeconds(5));
        if (second != null) {
            second.join(Duration.ofSeconds(5));
        }

        // then
        assertThat(firstError).hasValue(null);
        assertThat(secondError).hasValue(null);
        assertThat(firstResult.get().stdout()).contains("Stopped Symphony for Trello for \"Queue\"");
        assertThat(secondResult.get().stdout())
                .contains("Symphony for Trello is already stopped for \"Queue\"")
                .doesNotContain("Stopped Symphony for Trello for \"Queue\"");
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
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
        result.assertSuccess()
                .stdoutContains(
                        "Stopped Symphony for Trello for \"First\"", "Stopped Symphony for Trello for \"Second\"");
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
    void startWithoutSelectorLaunchesEveryConnectedBoard() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "First");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Second");
        saveConnectedBoardsAndStubSuccessfulStarts(fixture, first, second);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest());

        // then
        result.assertSuccess().stdoutContains("\"First\"", "\"Second\"");
        verify(fixture.platform, times(2)).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
    }

    @Test
    void startEnvWithoutSelectorRequiresBoardOrWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "First");
        ConnectedBoard second = fixture.connectedBoard("board-2", "Second");
        fixture.save(first, second);
        Path env = fixture.paths.configDir().resolve(".env.override");
        fixture.writeEnv(env);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startEnvRequest(env)));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage("--env requires --board or --workflow.");
        assertThat(((TrelloBoardSetupException) thrown).code()).isEqualTo("setup_worker_selection_conflict");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
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
        when(fixture.healthChecker.boardHealth(second)).thenReturn(fixture.sameWorkflowWithPid(second, 73));
        when(fixture.platform.isAlive(73)).thenReturn(true);
        when(fixture.platform.isManaged(73, fixture.paths.appHome(), second.workflowPath()))
                .thenReturn(true);

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
        workflowResult.assertSuccess().stdoutContains("Stopped Symphony for Trello for \"Duplicate\"");
    }

    @Test
    void lifecycleWorkflowSelectorsRejectDuplicateManifestRows() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "Dup Workflow A", "shared-workflow");
        ConnectedBoard second = ConnectedBoardBuilder.connectedBoard(first.workflowPath())
                .withBoardId("board-2")
                .withBoardKey("short-2")
                .withBoardName("Dup Workflow B")
                .withBoardUrl("https://trello.com/b/short-2/dup-workflow-b")
                .withEnvPath(first.envPath())
                .withWorkspaceRoot(fixture.paths.workspaceRoot())
                .withServerPort(first.serverPort())
                .build();
        fixture.save(first, second);

        // when
        Throwable status = catchThrowable(() -> fixture.status(fixture.statusWorkflowRequest(first.workflowPath())));
        Throwable start = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(first.workflowPath())));
        Throwable logs = catchThrowable(() -> fixture.logs(fixture.logsWorkflowRequest(first.workflowPath())));

        // then
        assertDuplicateWorkflowSelector(status, first, second);
        assertDuplicateWorkflowSelector(start, first, second);
        assertDuplicateWorkflowSelector(logs, first, second);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void lifecycleWorkflowSelectorsRejectMissingWorkflowFileBeforeActing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("missing.WORKFLOW.md");

        // when
        Throwable start = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(workflow)));
        Throwable status = catchThrowable(() -> fixture.status(fixture.statusWorkflowRequest(workflow)));
        Throwable stop = catchThrowable(() -> fixture.stop(fixture.stopWorkflowRequest(workflow)));
        Throwable logs = catchThrowable(() -> fixture.logs(fixture.logsWorkflowRequest(workflow)));

        // then
        assertWorkflowInvalidForLaunch(start, "missing workflow file");
        assertInvalidExplicitWorkflowSelector(status, "--workflow must point to an existing workflow file.");
        assertInvalidExplicitWorkflowSelector(stop, "--workflow must point to an existing workflow file.");
        assertInvalidExplicitWorkflowSelector(logs, "--workflow must point to an existing workflow file.");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @MethodSource("missingWorkflowArtifactScenarios")
    @ParameterizedTest(name = "{0} rejects missing explicit workflow before status side effects")
    void statusWorkflowSelectorRejectsMissingWorkflowDespiteState(MissingWorkflowArtifactScenario scenario)
            throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path workflow = fixture.paths.configDir().resolve("missing.WORKFLOW.md");
        ManagedProcessStore.ManagedProcessFiles files = store.files(workflow);
        scenario.create(fixture, files);
        StateSnapshot before = StateSnapshot.capture(files);
        assertThat(workflow).doesNotExist();

        // when
        Throwable thrown = catchThrowable(() -> fixture.status(fixture.statusWorkflowRequest(workflow)));

        // then
        assertMissingExplicitWorkflowRejected(thrown);
        before.assertUnchanged(files);
        verifyNoMissingWorkflowSideEffects(fixture);
    }

    @MethodSource("missingWorkflowArtifactScenarios")
    @ParameterizedTest(name = "{0} rejects missing explicit workflow before stop side effects")
    void stopWorkflowSelectorRejectsMissingWorkflowDespiteState(MissingWorkflowArtifactScenario scenario)
            throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path workflow = fixture.paths.configDir().resolve("missing.WORKFLOW.md");
        ManagedProcessStore.ManagedProcessFiles files = store.files(workflow);
        scenario.create(fixture, files);
        StateSnapshot before = StateSnapshot.capture(files);
        assertThat(workflow).doesNotExist();

        // when
        Throwable thrown = catchThrowable(() -> fixture.stop(fixture.stopWorkflowRequest(workflow)));

        // then
        assertMissingExplicitWorkflowRejected(thrown);
        before.assertUnchanged(files);
        verifyNoMissingWorkflowSideEffects(fixture);
    }

    @MethodSource("missingWorkflowArtifactScenarios")
    @ParameterizedTest(name = "{0} rejects missing explicit workflow before log side effects")
    void logsWorkflowSelectorRejectsMissingWorkflowDespiteState(MissingWorkflowArtifactScenario scenario)
            throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path workflow = fixture.paths.configDir().resolve("missing.WORKFLOW.md");
        ManagedProcessStore.ManagedProcessFiles files = store.files(workflow);
        scenario.create(fixture, files);
        StateSnapshot before = StateSnapshot.capture(files);
        assertThat(workflow).doesNotExist();

        // when
        Throwable thrown = catchThrowable(() -> fixture.logs(fixture.logsWorkflowRequest(workflow)));

        // then
        assertMissingExplicitWorkflowRejected(thrown);
        before.assertUnchanged(files);
        verifyNoMissingWorkflowSideEffects(fixture);
    }

    private static Stream<MissingWorkflowArtifactScenario> missingWorkflowArtifactScenarios() {
        return Stream.of(
                new MissingWorkflowArtifactScenario("no state", (fixture, files) -> {}),
                new MissingWorkflowArtifactScenario("live managed pid", (fixture, files) -> {
                    writePid(files, MISSING_WORKFLOW_STATE_PID);
                    when(fixture.platform.isAlive(MISSING_WORKFLOW_STATE_PID)).thenReturn(true);
                }),
                new MissingWorkflowArtifactScenario("live foreign pid", (fixture, files) -> {
                    writePid(files, MISSING_WORKFLOW_STATE_PID);
                    when(fixture.platform.isAlive(MISSING_WORKFLOW_STATE_PID)).thenReturn(true);
                }),
                new MissingWorkflowArtifactScenario("dead pid", (fixture, files) -> {
                    writePid(files, MISSING_WORKFLOW_STATE_PID);
                    when(fixture.platform.isAlive(MISSING_WORKFLOW_STATE_PID)).thenReturn(false);
                }),
                new MissingWorkflowArtifactScenario("empty pid", (fixture, files) -> writePid(files, "")),
                new MissingWorkflowArtifactScenario("malformed pid", (fixture, files) -> writePid(files, "not-a-pid")),
                new MissingWorkflowArtifactScenario("pid path is a directory", (fixture, files) -> {
                    Files.createDirectories(files.pidFile());
                    assertThat(files.pidFile()).isDirectory();
                }),
                new MissingWorkflowArtifactScenario(
                        "stdout log only", (fixture, files) -> writeFile(files.stdoutLog(), "out\n")),
                new MissingWorkflowArtifactScenario(
                        "stderr log only", (fixture, files) -> writeFile(files.stderrLog(), "err\n")),
                new MissingWorkflowArtifactScenario(
                        "empty stdout log", (fixture, files) -> writeFile(files.stdoutLog(), "")),
                new MissingWorkflowArtifactScenario("both logs", (fixture, files) -> {
                    writeFile(files.stdoutLog(), "out\n");
                    writeFile(files.stderrLog(), "err\n");
                }),
                new MissingWorkflowArtifactScenario("log path is a directory", (fixture, files) -> {
                    Files.createDirectories(files.stdoutLog());
                    assertThat(files.stdoutLog()).isDirectory();
                }),
                new MissingWorkflowArtifactScenario(
                        "lock only", (fixture, files) -> writeFile(files.processLockFile(), "lock\n")),
                new MissingWorkflowArtifactScenario("mixed stale artifacts", (fixture, files) -> {
                    writePid(files, "bad-pid");
                    writeFile(files.stdoutLog(), "out\n");
                    writeFile(files.stderrLog(), "err\n");
                    writeFile(files.processLockFile(), "lock\n");
                }));
    }

    private static void writePid(ManagedProcessStore.ManagedProcessFiles files, String text) throws IOException {
        writeFile(files.pidFile(), text);
    }

    private static void writePid(ManagedProcessStore.ManagedProcessFiles files, long pid) throws IOException {
        writePid(files, Long.toString(pid));
    }

    private static void writeFile(Path path, String text) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        Path parent = link.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available in this test environment: " + e.getMessage());
        }
        assumeTrue(Files.isSymbolicLink(link), "symbolic link precondition must hold");
    }

    @Test
    void lifecycleWorkflowSelectorsRejectDirectoryWorkflowBeforeActing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflowDirectory = fixture.paths.configDir().resolve("directory.WORKFLOW.md");
        Files.createDirectories(workflowDirectory);

        // when
        assertLifecycleRejectsExplicitWorkflow(
                fixture,
                workflowDirectory,
                "--workflow must point to a regular workflow file.",
                "workflow path is not a regular workflow file");

        // then
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void lifecycleWorkflowSelectorsRejectMissingWorkflowPathAliasesBeforeActing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path nested = fixture.paths.configDir().resolve("nested");
        Files.createDirectories(nested);
        List<Path> missingAliases = List.of(
                fixture.paths
                        .configDir()
                        .resolve("absolute-missing.WORKFLOW.md")
                        .toAbsolutePath(),
                nested.resolve(".").resolve("relative-missing.WORKFLOW.md"),
                nested.resolve("child").resolve("..").resolve("normalized-missing.WORKFLOW.md"),
                Path.of(fixture.paths.configDir() + "//repeated-separator.WORKFLOW.md"));

        for (Path workflow : missingAliases) {

            // when
            Throwable status = catchThrowable(() -> fixture.status(fixture.statusWorkflowRequest(workflow)));
            Throwable stop = catchThrowable(() -> fixture.stop(fixture.stopWorkflowRequest(workflow)));
            Throwable logs = catchThrowable(() -> fixture.logs(fixture.logsWorkflowRequest(workflow)));

            // then
            assertMissingExplicitWorkflowRejected(status);
            assertMissingExplicitWorkflowRejected(stop);
            assertMissingExplicitWorkflowRejected(logs);
        }
        verifyNoMissingWorkflowSideEffects(fixture);
    }

    @Test
    void lifecycleWorkflowSelectorsRejectDanglingWorkflowSymlinkBeforeActing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path target = fixture.paths.configDir().resolve("missing-target.WORKFLOW.md");
        Path link = fixture.paths.configDir().resolve("dangling-link.WORKFLOW.md");
        createSymbolicLinkOrSkip(link, target);
        assertThat(link).isSymbolicLink();
        assertThat(target).doesNotExist();

        // when
        Throwable status = catchThrowable(() -> fixture.status(fixture.statusWorkflowRequest(link)));
        Throwable stop = catchThrowable(() -> fixture.stop(fixture.stopWorkflowRequest(link)));
        Throwable logs = catchThrowable(() -> fixture.logs(fixture.logsWorkflowRequest(link)));

        // then
        assertMissingExplicitWorkflowRejected(status);
        assertMissingExplicitWorkflowRejected(stop);
        assertMissingExplicitWorkflowRejected(logs);
        verifyNoMissingWorkflowSideEffects(fixture);
    }

    @Test
    void lifecycleWorkflowSelectorsRejectParentSymlinkToMissingWorkflowBeforeActing() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path realParent = fixture.paths.configDir().resolve("real-parent");
        Path linkedParent = fixture.paths.configDir().resolve("linked-parent");
        Files.createDirectories(realParent);
        createSymbolicLinkOrSkip(linkedParent, realParent);
        assertThat(linkedParent).isSymbolicLink();
        Path workflow = linkedParent.resolve("missing.WORKFLOW.md");
        assertThat(workflow).doesNotExist();

        // when
        Throwable status = catchThrowable(() -> fixture.status(fixture.statusWorkflowRequest(workflow)));
        Throwable stop = catchThrowable(() -> fixture.stop(fixture.stopWorkflowRequest(workflow)));
        Throwable logs = catchThrowable(() -> fixture.logs(fixture.logsWorkflowRequest(workflow)));

        // then
        assertMissingExplicitWorkflowRejected(status);
        assertMissingExplicitWorkflowRejected(stop);
        assertMissingExplicitWorkflowRejected(logs);
        verifyNoMissingWorkflowSideEffects(fixture);
    }

    @Test
    void lifecycleWorkflowSelectorsAcceptSymlinkToRegularWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path link = fixture.paths.configDir().resolve("workflow-link.md");
        createSymbolicLinkOrSkip(link, board.workflowPath());
        assertThat(link).isSymbolicLink().isRegularFile();

        // when
        WorkerRunResult status = fixture.status(fixture.statusWorkflowRequest(link));

        // then
        status.assertSuccess().stdoutContains("stopped \"workflow-link.md\"");
    }

    @Test
    void lifecycleWorkflowSelectorsRejectSymlinkToDirectoryAsNonRegularWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path directory = fixture.paths.configDir().resolve("workflow-dir");
        Files.createDirectories(directory);
        Path link = fixture.paths.configDir().resolve("workflow-dir-link.md");
        createSymbolicLinkOrSkip(link, directory);
        assertThat(link).isSymbolicLink().isDirectory();

        // when
        assertLifecycleRejectsExplicitWorkflow(
                fixture,
                link,
                "--workflow must point to a regular workflow file.",
                "workflow path is not a regular workflow file");

        // then
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @MethodSource("unusableWorkflowContents")
    @ParameterizedTest(name = "{0}")
    void startWorkflowSelectorRejectsUnusableWorkflowContentButRecoveryCommandsCanStillAct(
            UnusableWorkflowContent invalidWorkflow) throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        Path workflow = fixture.paths.configDir().resolve(invalidWorkflow.fileName());
        Files.writeString(workflow, invalidWorkflow.content(), StandardCharsets.UTF_8);

        // when
        writeManagedLog(fixture, workflow);
        Throwable start = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(workflow)));
        WorkerRunResult status = fixture.status(fixture.statusWorkflowRequest(workflow));
        WorkerRunResult stop = fixture.stop(fixture.stopWorkflowRequest(workflow));
        WorkerRunResult logs = fixture.logs(fixture.logsWorkflowRequest(workflow));

        // then
        assertThat(start).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage())
                    .contains("Invalid workflow configuration:", "Workflow has no YAML front matter.");
        });
        status.assertSuccess().stdoutContains("invalid \"" + workflow.getFileName() + "\"");
        stop.assertSuccess()
                .stdoutContains("Symphony for Trello is already stopped for \"" + workflow.getFileName() + "\"");
        logs.assertSuccess();
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    private static Stream<UnusableWorkflowContent> unusableWorkflowContents() {
        return Stream.of(
                new UnusableWorkflowContent("empty workflow", "empty.WORKFLOW.md", ""),
                new UnusableWorkflowContent("plain workflow", "plain.WORKFLOW.md", "plain body\n"));
    }

    @MethodSource("invalidWorkflowLaunchCases")
    @ParameterizedTest(name = "{0}")
    void startWorkflowReportsInvalidContentThroughLaunchValidation(InvalidWorkflowCase invalidCase) throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        fixture.writeEnv(fixture.paths.defaultEnvPath());
        Path workflow = fixture.paths.configDir().resolve(invalidCase.fileName());
        Files.writeString(workflow, invalidCase.content(), StandardCharsets.UTF_8);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(workflow)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo(invalidCase.expectedCode());
            assertThat(failure.getMessage()).contains(invalidCase.expectedCause());
        });
        assertThat(new ManagedProcessStore(fixture.paths.stateHome())
                        .readPid(new ManagedProcessStore(fixture.paths.stateHome())
                                .files(workflow)
                                .pidFile()))
                .isNull();
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    private static Stream<InvalidWorkflowCase> invalidWorkflowLaunchCases() {
        return Stream.of(
                new InvalidWorkflowCase(
                        "unclosed workflow",
                        "unclosed.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "malformed workflow",
                        "malformed.WORKFLOW.md",
                        "---\ntracker: [unclosed\n---\nBody\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "non-map front matter",
                        "nonmap.WORKFLOW.md",
                        "---\n- first\n- second\n---\nBody\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "null front matter with generated body",
                        "null-generated.WORKFLOW.md",
                        "---\n~\n---\n## Operating Posture\n\n## Pull Request Publication\n\n## Trello List Routing\n",
                        "setup_workflow_invalid",
                        "Workflow front matter must be a YAML map."),
                new InvalidWorkflowCase(
                        "bad server port",
                        "badport.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: \"not-a-port\"\n---\nBody\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "server port above range",
                        "port-above-range.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: 70000\n---\nBody\n",
                        "setup_workflow_invalid",
                        "server.port"),
                new InvalidWorkflowCase(
                        "negative server port",
                        "port-negative.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: -1\n---\nBody\n",
                        "setup_workflow_invalid",
                        "server.port"),
                new InvalidWorkflowCase(
                        "fractional server port",
                        "port-fractional.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: 18080.5\n---\nBody\n",
                        "setup_workflow_invalid",
                        "server.port"));
    }

    @Test
    void startWorkflowDoesNotLeakMissingSecretFilePathFromInvalidWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        fixture.writeEnv(fixture.paths.defaultEnvPath());
        Path privateDir = tempDir.resolve("Users").resolve("Jane Doe").resolve("Secrets");
        Path secretPath = privateDir.resolve("trello-key.txt");
        Path workflowDir = tempDir.resolve("Users").resolve("Jane Doe").resolve("External Workflow");
        Path workflow = workflowDir.resolve("WORKFLOW.secret-missing.md");
        String secretFileToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), secretPath);
        Files.createDirectories(workflowDir);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                  api_key: "file:%s"
                  api_token: "$TRELLO_API_TOKEN"
                server:
                  port: 18080
                ---
                Body
                """
                        .formatted(secretPath),
                StandardCharsets.UTF_8);
        ConnectedBoard board = ConnectedBoardBuilder.connectedBoard(
                        workflow.toAbsolutePath().normalize())
                .withBoardId("board-1")
                .withBoardKey("board-1")
                .withBoardName("Queue")
                .withBoardUrl("https://trello.com/b/board-1")
                .withEnvPath(fixture.paths.defaultEnvPath())
                .withWorkspaceRoot(fixture.paths.workspaceRoot())
                .build();
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage())
                    .contains(
                            "Invalid workflow configuration:",
                            "tracker.api_key secret file cannot be read.",
                            "secret_file_token=" + secretFileToken,
                            PrivateContextTokens.lookupCommand(secretFileToken),
                            "selected workflow file")
                    .doesNotContain(
                            secretPath.toString(),
                            workflow.toString(),
                            workflowDir.toString(),
                            privateDir.toString(),
                            tempDir.toString(),
                            "Jane Doe");
            assertThat(failure.getCause()).hasMessageContaining(secretPath.toString());
        });
        assertThat(workflowDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).doesNotExist();
        String privateLookup = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner())
                .renderReport(
                        new SetupDiagnosticReporter.DiagnosticsRequest(
                                Optional.of("Queue"),
                                Optional.empty(),
                                false,
                                false,
                                Optional.empty(),
                                Optional.of(fixture.paths.configDir()),
                                Optional.of(fixture.paths.workspaceRoot()),
                                Optional.of(fixture.paths.stateHome()),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(secretFileToken)),
                        true);
        assertThat(privateLookup)
                .contains(
                        "- **lookup_status:** found",
                        "| secret_file | tracker.api_key | " + secretFileToken + " | " + secretPath + " |")
                .doesNotContain("test-key", "test-token");
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startAllDoesNotLeakOversizedSecretFilePathFromInvalidWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        Path privateDir = tempDir.resolve("Users").resolve("Jane Doe").resolve("Secrets");
        Path secretPath = privateDir.resolve("trello-token.txt");
        String secretFileToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), secretPath);
        Files.createDirectories(privateDir);
        Files.writeString(secretPath, "x".repeat(70_000), StandardCharsets.UTF_8);
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                  api_key: "$TRELLO_API_KEY"
                  api_token: "file:%s"
                server:
                  port: 18080
                ---
                Body
                """
                        .formatted(secretPath),
                StandardCharsets.UTF_8);
        fixture.save(board);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startAllRequest()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage())
                    .contains(
                            "Invalid workflow configuration:",
                            "tracker.api_token secret file is too large.",
                            "secret_file_token=" + secretFileToken,
                            PrivateContextTokens.lookupCommand(secretFileToken),
                            "selected workflow file")
                    .doesNotContain(secretPath.toString(), privateDir.toString(), tempDir.toString(), "Jane Doe");
            assertThat(failure.getCause()).hasMessageContaining(secretPath.toString());
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
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
                  kind: trello
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
        when(fixture.healthChecker.waitForSameWorkflow(any(), eq(19090), any()))
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
                        eq(19090),
                        any());
    }

    @Test
    void explicitWorkflowResolvesBoardIdFromDefaultEnvBeforeHealthMatching() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.direct-env-board.md");
        Path env = fixture.paths.defaultEnvPath();
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(
                env,
                """
                TRELLO_API_KEY=test-key
                TRELLO_API_TOKEN=test-token
                DIRECT_BOARD_ID=resolved-direct-board
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: $DIRECT_BOARD_ID
                server:
                  port: 19092
                ---
                # Direct
                """,
                StandardCharsets.UTF_8);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(any(), anyInt(), nullable(Path.class)))
                .thenReturn(19092);
        when(fixture.healthChecker.waitForSameWorkflow(any(), eq(19092), any()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        19092,
                        Optional.of(workflow.toString()),
                        Optional.of("resolved-direct-board")));
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
                        argThat(board ->
                                "resolved-direct-board".equals(board.boardId()) && board.serverPort() == 19092),
                        eq(19092),
                        any());
    }

    @Test
    void startExplicitWorkflowUsesExplicitEnvOverrideForWorkflowValidation() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-override", "Override");
        fixture.save(board);
        Path overrideEnv = fixture.paths.configDir().resolve("override.env");
        Files.writeString(
                overrideEnv,
                """
                TRELLO_API_KEY=test-key
                TRELLO_API_TOKEN=test-token
                WORKER_PORT=19093
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-override
                server:
                  port: $WORKER_PORT
                ---
                # Override
                """,
                StandardCharsets.UTF_8);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), overrideEnv))
                .thenReturn(19093);
        when(fixture.healthChecker.waitForSameWorkflow(eq(board), eq(19093), any()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        19093,
                        Optional.of(board.workflowPath().toString()),
                        Optional.of("board-override")));
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.start(fixture.startWorkflowRequest(board.workflowPath(), overrideEnv));

        // then
        result.assertSuccess();
        verify(fixture.healthChecker).managedHealthPort(board.workflowPath(), board.serverPort(), overrideEnv);
    }

    @Test
    void startExplicitWorkflowUsesConnectedBoardEnvPathForWorkflowValidation() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-custom-env", "Custom Env");
        Path customEnv = fixture.paths.configDir().resolve("custom.env");
        fixture.writeEnv(customEnv);
        Files.deleteIfExists(fixture.paths.defaultEnvPath());
        Files.createDirectory(fixture.paths.defaultEnvPath());
        ConnectedBoard boardWithCustomEnv =
                ConnectedBoardBuilder.from(board).withEnvPath(customEnv).build();
        fixture.save(boardWithCustomEnv);
        fixture.stubHealthyStartedWorker(boardWithCustomEnv, 42);

        // when
        WorkerRunResult result = fixture.start(fixture.startWorkflowRequest(board.workflowPath()));

        // then
        result.assertSuccess();
        verify(fixture.healthChecker)
                .managedHealthPort(boardWithCustomEnv.workflowPath(), boardWithCustomEnv.serverPort(), customEnv);
    }

    @Test
    void statusHandlesExplicitWorkflowWithoutManifestEntryOrEnvPath() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.direct-status.md");
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: direct-status-board
                server:
                  port: 19091
                ---
                # Direct status
                """,
                StandardCharsets.UTF_8);
        when(fixture.healthChecker.boardHealth(any()))
                .thenReturn(new BoardHealth(BoardHealthKind.STOPPED, 19091, Optional.empty(), Optional.empty()));

        // when
        WorkerRunResult result = fixture.status(fixture.statusWorkflowRequest(workflow));

        // then
        result.assertSuccess().stdoutContains("stopped \"WORKFLOW.direct-status.md\"");
    }

    @Test
    void statusResolvesExplicitWorkflowFromDefaultEnvWhenManifestHasNoEntry() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.direct-status-env.md");
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(
                fixture.paths.defaultEnvPath(),
                """
                DIRECT_STATUS_BOARD_ID=resolved-status-board
                DIRECT_STATUS_PORT=19093
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: $DIRECT_STATUS_BOARD_ID
                server:
                  port: $DIRECT_STATUS_PORT
                ---
                # Direct status
                """,
                StandardCharsets.UTF_8);
        when(fixture.healthChecker.boardHealth(argThat(
                        board -> "resolved-status-board".equals(board.boardId()) && board.serverPort() == 19093)))
                .thenReturn(new BoardHealth(BoardHealthKind.STOPPED, 19093, Optional.empty(), Optional.empty()));

        // when
        WorkerRunResult result = fixture.status(fixture.statusWorkflowRequest(workflow));

        // then
        result.assertSuccess().stdoutContains("stopped \"WORKFLOW.direct-status-env.md\"");
    }

    @Test
    void stopResolvesExplicitWorkflowFromDefaultEnvWhenManifestHasNoEntry() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.direct-stop-env.md");
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(
                fixture.paths.defaultEnvPath(),
                """
                DIRECT_STOP_BOARD_ID=resolved-stop-board
                DIRECT_STOP_PORT=19094
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: $DIRECT_STOP_BOARD_ID
                server:
                  port: $DIRECT_STOP_PORT
                ---
                # Direct stop
                """,
                StandardCharsets.UTF_8);
        ConnectedBoard expectedBoard = ConnectedBoardBuilder.connectedBoard(
                        workflow.toAbsolutePath().normalize())
                .withBoardId("resolved-stop-board")
                .withBoardKey("resolved-stop-board")
                .withBoardName("WORKFLOW.direct-stop-env.md")
                .withBoardUrl("")
                .withEnvPath(null)
                .withWorkspaceRoot(TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT)
                .withServerPort(19094)
                .build();
        fixture.writeManagedPid(expectedBoard, 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(
                        42, fixture.paths.appHome(), workflow.toAbsolutePath().normalize()))
                .thenReturn(true);
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopWorkflowRequest(workflow));

        // then
        result.assertSuccess().stdoutContains("Stopped Symphony for Trello for \"WORKFLOW.direct-stop-env");
    }

    @Test
    void startDoesNotStopReusedPidBelongingToAnotherWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        StalePidScenario scenario = stalePidScenario(fixture);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("State file belongs to another process")
                .hasMessageContaining("selected workflow file")
                .hasMessageContaining("pid_file_token=%s", scenario.pidToken())
                .hasMessageContaining(PrivateContextTokens.lookupCommand(scenario.pidToken()))
                .hasMessageNotContaining(scenario.board().workflowPath().toString())
                .hasMessageNotContaining(scenario.files().pidFile().toString())
                .hasMessageNotContaining(fixture.paths.stateHome().toString())
                .hasMessageNotContaining(".log")
                .hasMessageNotContaining(".err");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void startValidatesWorkflowConfigBeforeDeletingStalePid() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(board, 42);
        writeWorkflowWithInvalidSandboxPolicy(board.workflowPath());
        when(fixture.platform.isAlive(42)).thenReturn(false);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertInvalidWorkflowStartFailure(thrown, board, "codex.turn_sandbox_policy");
        assertThat(new ManagedProcessStore(fixture.paths.stateHome()).readPid(files.pidFile()))
                .isEqualTo(42);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startValidatesWorkflowConfigBeforeStoppingManagedProcess() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(board, 42);
        writeWorkflowWithInvalidSandboxPolicy(board.workflowPath());
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startRequest("Queue")));

        // then
        assertInvalidWorkflowStartFailure(thrown, board, "codex.turn_sandbox_policy");
        assertThat(new ManagedProcessStore(fixture.paths.stateHome()).readPid(files.pidFile()))
                .isEqualTo(42);
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    private void assertInvalidWorkflowStartFailure(Throwable thrown, ConnectedBoard board, String expectedProblem) {
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage())
                    .contains(expectedProblem, "selected workflow file")
                    .doesNotContain(board.workflowPath().toString(), tempDir.toString());
        });
    }

    @Test
    void stopDoesNotStopReusedPidBelongingToAnotherWorkflow() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        StalePidScenario scenario = stalePidScenario(fixture);

        // when
        WorkerRunResult firstStop = fixture.stop(fixture.stopRequest("Queue"));
        WorkerRunResult secondStop = fixture.stop(fixture.stopRequest("Queue"));

        // then
        firstStop
                .assertSuccess()
                .stdoutContains(
                        "Skipped unmanaged stale pid for \"Queue\" pid=42",
                        "pid_file_token=" + scenario.pidToken(),
                        "Removed the stale managed pid file.")
                .stdoutDoesNotContain("Skipped unmanaged stale pid WORKFLOW");
        secondStop.assertSuccess().stdoutDoesNotContain("Skipped unmanaged stale pid");
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        assertThat(store.readPid(store.files(scenario.board().workflowPath()).pidFile()))
                .isNull();
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    @Test
    void stopReportsFailedStalePidCleanupActionablyWithoutKillingTheProcess() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        Path pidFile = fixture.paths.stateHome().resolve("WORKFLOW.private.abc123.pid");
        Files.createDirectories(pidFile.getParent());
        Files.writeString(pidFile, "42", StandardCharsets.US_ASCII);
        assertThat(pidFile).exists();
        IOException deleteFailure = new IOException("denied " + pidFile);
        when(fixture.platform.isAlive(42L)).thenReturn(true);
        when(fixture.platform.isManaged(42L, fixture.paths.appHome())).thenReturn(false);
        String pidToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), pidFile);

        // when
        WorkerRunResult result;
        try (MockedConstruction<ManagedProcessStore> mockedStores =
                mockConstruction(ManagedProcessStore.class, (store, ignored) -> {
                    when(store.pidFiles()).thenReturn(List.of(pidFile));
                    when(store.readPid(pidFile)).thenReturn(42L);
                    when(store.deletePid(pidFile)).thenThrow(deleteFailure);
                })) {
            result = fixture.stop(fixture.stopAllRequest());

            // then
            ManagedProcessStore store = mockedStores.constructed().getFirst();
            verify(store).deletePid(pidFile);
        }
        result.assertSuccess()
                .stdoutContains(
                        "Skipped unmanaged stale pid WORKFLOW.private.abc123 pid=42",
                        "Could not remove the stale managed pid file.",
                        "The unrelated process was not stopped.",
                        "Remove the stale managed pid file manually, then rerun stop.",
                        "pid_file_token=" + pidToken,
                        PrivateContextTokens.lookupCommand(pidToken))
                .stdoutDoesNotContain(
                        "Removed the stale managed pid file.",
                        pidFile.toString(),
                        fixture.paths.stateHome().toString(),
                        fixture.paths.appHome().toString(),
                        tempDir.toString(),
                        ".log",
                        ".err");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
        assertThat(pidFile).exists();
    }

    @Test
    void stopAllReportsHashFreeLabelsAndSharesStaleCleanupSemantics() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path managedWorkflow = fixture.paths.configDir().resolve("WORKFLOW.managed.md");
        Path staleWorkflow = fixture.paths.configDir().resolve("WORKFLOW.stale.md");
        store.writePid(store.files(managedWorkflow).pidFile(), 41);
        store.writePid(store.files(staleWorkflow).pidFile(), 42);
        when(fixture.platform.isAlive(41L)).thenReturn(true);
        when(fixture.platform.isAlive(42L)).thenReturn(true);
        when(fixture.platform.isManaged(41L, fixture.paths.appHome())).thenReturn(true);
        when(fixture.platform.isManaged(42L, fixture.paths.appHome())).thenReturn(false);
        when(fixture.platform.stop(eq(41L), any(Duration.class), any(Duration.class)))
                .thenReturn(true);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopAllRequest());

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Stopped WORKFLOW.managed.md",
                        "Skipped unmanaged stale pid WORKFLOW.stale.md pid=42",
                        "Removed the stale managed pid file.")
                .stdoutDoesNotContain("WORKFLOW.managed.md.", "WORKFLOW.stale.md.");
        verify(fixture.platform, never()).stop(eq(42L), any(Duration.class), any(Duration.class));
    }

    @Test
    void statusFallbackReportsUnverifiedManagedProcessWithoutClaimingRuntimeHealth() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.fallback.md");
        store.writePid(store.files(workflow).pidFile(), 41);
        when(fixture.platform.isAlive(41L)).thenReturn(true);
        when(fixture.platform.isManaged(41L, fixture.paths.appHome())).thenReturn(true);

        // when
        WorkerRunResult result = fixture.status(fixture.statusAllRequest());

        // then
        result.assertSuccess()
                .stdoutContains(
                        "untracked WORKFLOW.fallback.md pid=41",
                        "no connected workflow metadata; runtime identity not verified")
                .stdoutDoesNotContain("running WORKFLOW.fallback.md", "untracked WORKFLOW.fallback.md.");
    }

    @Test
    void statusAllReportsStalePidFileTokenWithoutRawPath() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.fallback-stale.md");
        Path pidFile = store.files(workflow).pidFile();
        store.writePid(pidFile, 42);
        when(fixture.platform.isAlive(42L)).thenReturn(true);
        when(fixture.platform.isManaged(42L, fixture.paths.appHome())).thenReturn(false);
        String pidToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), pidFile);

        // when
        WorkerRunResult result = fixture.status(fixture.statusAllRequest());

        // then
        result.assertSuccess()
                .stdoutContains(
                        "stale WORKFLOW.fallback-stale.md pid=42 does not belong to this install",
                        "pid_file_token=" + pidToken)
                .stdoutDoesNotContain(
                        "WORKFLOW.fallback-stale.md.",
                        pidFile.toString(),
                        fixture.paths.stateHome().toString(),
                        tempDir.toString());
        assertThat(pidFile).exists().content(StandardCharsets.UTF_8).isEqualTo("42");
    }

    @Test
    void statusFallbackLeavesDeadManagedPidStateUnchanged() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.dead.md");
        Path pidFile = store.files(workflow).pidFile();
        store.writePid(pidFile, 42);

        // when
        WorkerRunResult result = fixture.status(fixture.statusAllRequest());

        // then
        result.assertSuccess().stdoutContains("stopped WORKFLOW.dead.md");
        assertThat(pidFile).exists().content(StandardCharsets.UTF_8).isEqualTo("42");
    }

    @Test
    void stopAllTreatsConcurrentlyRemovedStalePidFileAsAlreadyCleaned() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ManagedProcessStore store = new ManagedProcessStore(fixture.paths.stateHome());
        Path workflow = fixture.paths.configDir().resolve("WORKFLOW.vanishing.md");
        Path pidFile = store.files(workflow).pidFile();
        store.writePid(pidFile, 42);
        when(fixture.platform.isAlive(42L)).thenAnswer(invocation -> {
            Files.deleteIfExists(pidFile);
            return true;
        });
        when(fixture.platform.isManaged(42L, fixture.paths.appHome())).thenReturn(false);

        // when
        WorkerRunResult result = fixture.stop(fixture.stopAllRequest());

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Skipped unmanaged stale pid WORKFLOW.vanishing.md pid=42",
                        "The stale managed pid file was already removed.")
                .stdoutDoesNotContain("Could not remove the stale managed pid file");
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    private void assertDuplicateWorkflowSelector(Throwable thrown, ConnectedBoard first, ConnectedBoard second) {
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_workflow_ambiguous");
            assertThat(failure)
                    .hasMessage("Multiple connected-board rows reference --workflow. Repair "
                            + ConnectedBoardManifest.FILE_NAME + ", then rerun the command.");
            assertThat(failure.getMessage())
                    .doesNotContain(
                            first.boardId(),
                            first.boardName(),
                            first.boardUrl(),
                            first.workflowPath().toString(),
                            second.boardId(),
                            second.boardName(),
                            second.boardUrl(),
                            tempDir.toString());
        });
    }

    private void assertLifecycleRejectsExplicitWorkflow(
            LocalWorkerManagerTestFixture fixture, Path workflow, String expectedMessage, String expectedStartCause) {
        Throwable status = catchThrowable(() -> fixture.status(fixture.statusWorkflowRequest(workflow)));
        Throwable start = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(workflow)));
        Throwable stop = catchThrowable(() -> fixture.stop(fixture.stopWorkflowRequest(workflow)));
        Throwable logs = catchThrowable(() -> fixture.logs(fixture.logsWorkflowRequest(workflow)));

        assertInvalidExplicitWorkflowSelector(status, expectedMessage);
        assertWorkflowInvalidForLaunch(start, expectedStartCause);
        assertInvalidExplicitWorkflowSelector(stop, expectedMessage);
        assertInvalidExplicitWorkflowSelector(logs, expectedMessage);
    }

    private void assertWorkflowInvalidForLaunch(Throwable thrown, String expectedCause) {
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage()).contains("Invalid workflow configuration:", expectedCause);
        });
    }

    private void assertInvalidExplicitWorkflowSelector(Throwable thrown, String expectedMessage) {
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_arguments");
            assertThat(failure).hasMessage(expectedMessage);
            assertThat(failure.getMessage()).doesNotContain(tempDir.toString());
        });
    }

    private void assertMissingExplicitWorkflowRejected(Throwable thrown) {
        assertInvalidExplicitWorkflowSelector(thrown, "--workflow must point to an existing workflow file.");
    }

    private void verifyNoMissingWorkflowSideEffects(LocalWorkerManagerTestFixture fixture) throws Exception {
        verify(fixture.platform, never()).isAlive(anyLong());
        verify(fixture.platform, never()).isManaged(anyLong(), any());
        verify(fixture.platform, never()).isManaged(anyLong(), any(), any());
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
        verify(fixture.healthChecker, never()).boardHealth(any());
        verify(fixture.healthChecker, never()).managedHealthPort(any(), anyInt(), any());
        verify(fixture.healthChecker, never()).workflowHealth(any(), any(), any(), anyInt());
        verify(fixture.healthChecker, never()).waitForSameWorkflow(any(), anyInt(), any());
    }

    private record MissingWorkflowArtifactScenario(String name, MissingWorkflowArtifactWriter writer) {
        void create(LocalWorkerManagerTestFixture fixture, ManagedProcessStore.ManagedProcessFiles files)
                throws Exception {
            writer.create(fixture, files);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    private interface MissingWorkflowArtifactWriter {
        void create(LocalWorkerManagerTestFixture fixture, ManagedProcessStore.ManagedProcessFiles files)
                throws Exception;
    }

    private record StateSnapshot(
            FileSnapshot pid,
            FileSnapshot stdout,
            FileSnapshot stderr,
            FileSnapshot lock,
            boolean stateDirectoryExists) {
        static StateSnapshot capture(ManagedProcessStore.ManagedProcessFiles files) throws IOException {
            Path parent = files.pidFile().getParent();
            return new StateSnapshot(
                    FileSnapshot.capture(files.pidFile()),
                    FileSnapshot.capture(files.stdoutLog()),
                    FileSnapshot.capture(files.stderrLog()),
                    FileSnapshot.capture(files.processLockFile()),
                    parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS));
        }

        void assertUnchanged(ManagedProcessStore.ManagedProcessFiles files) throws IOException {
            pid.assertUnchanged(files.pidFile());
            stdout.assertUnchanged(files.stdoutLog());
            stderr.assertUnchanged(files.stderrLog());
            lock.assertUnchanged(files.processLockFile());
            Path parent = files.pidFile().getParent();
            assertThat(parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS))
                    .isEqualTo(stateDirectoryExists);
        }
    }

    private record FileSnapshot(boolean exists, boolean directory, String content) {
        static FileSnapshot capture(Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new FileSnapshot(false, false, "");
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return new FileSnapshot(true, true, "");
            }
            return new FileSnapshot(true, false, Files.readString(path, StandardCharsets.UTF_8));
        }

        void assertUnchanged(Path path) throws IOException {
            assertThat(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).isEqualTo(exists);
            if (!exists) {
                return;
            }
            assertThat(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).isEqualTo(directory);
            if (!directory) {
                assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo(content);
            }
        }
    }

    @Test
    void canStopRunningWorkerCoversHealthyUntrackedManagedWorker() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = stubHealthyUntrackedWorker(fixture, 7331L, true);

        // when
        boolean canStop = fixture.manager.canStopRunningWorker(fixture.paths, board);

        // then
        assertThat(canStop)
                .as("healthy untracked worker with verified managed pid")
                .isTrue();
    }

    @Test
    void canStopRunningWorkerRejectsUnverifiableUntrackedWorker() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = stubHealthyUntrackedWorker(fixture, 7331L, false);

        // when
        boolean canStop = fixture.manager.canStopRunningWorker(fixture.paths, board);

        // then
        assertThat(canStop)
                .as("unverifiable untracked pid keeps manual recovery behavior")
                .isFalse();
    }

    private static ConnectedBoard stubHealthyUntrackedWorker(
            LocalWorkerManagerTestFixture fixture, long workerPid, boolean managed) throws Exception {
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        when(fixture.healthChecker.boardHealth(board))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        board.serverPort(),
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId()),
                        Optional.of(workerPid)));
        when(fixture.platform.isAlive(workerPid)).thenReturn(true);
        when(fixture.platform.isManaged(workerPid, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(managed);
        return board;
    }

    private static void stubManagedSameWorkflow(LocalWorkerManagerTestFixture fixture, ConnectedBoard board, long pid)
            throws Exception {
        fixture.writeManagedPid(board, pid);
        when(fixture.platform.isAlive(pid)).thenReturn(true);
        when(fixture.platform.isManaged(pid, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(board.serverPort());
        stubSameWorkflowHealthProbe(fixture, board);
    }

    private static void stubSameWorkflowHealthProbe(LocalWorkerManagerTestFixture fixture, ConnectedBoard board) {
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(fixture.sameWorkflow(board));
    }

    private static void writeWorkflowWithMissingFileSecret(ConnectedBoard board, Path missingSecret)
            throws IOException {
        assertThat(missingSecret).doesNotExist();
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                  api_key: "file:%s"
                  api_token: literal-token
                  board_id: %s
                server:
                  port: %d
                ---
                # Queue
                """
                        .formatted(missingSecret, board.boardId(), board.serverPort()),
                StandardCharsets.UTF_8);
    }

    private static List<Throwable> lifecycleCommandFailures(LocalWorkerManagerTestFixture fixture) {
        return List.of(
                catchThrowable(() -> fixture.status(fixture.statusRequest("Queue"))),
                catchThrowable(() -> fixture.start(fixture.startRequest("Queue"))),
                catchThrowable(() -> fixture.stop(fixture.stopRequest("Queue"))),
                catchThrowable(() -> fixture.logs(fixture.logsRequest("Queue"))));
    }

    private static void assertLifecycleManifestRejections(
            LocalWorkerManagerTestFixture fixture, List<Throwable> failures, String label) throws IOException {
        for (Throwable thrown : failures) {
            assertThat(thrown).as(label).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
                assertThat(failure.code()).as(label).isEqualTo("setup_manifest_unavailable");
                assertThat(failure.getMessage())
                        .as(label)
                        .contains(ConnectedBoardManifest.FILE_NAME)
                        .doesNotContain("Cannot invoke", "NullPointerException");
            });
        }
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    private static void writeManagedLog(LocalWorkerManagerTestFixture fixture, Path workflow) throws Exception {
        Files.createDirectories(fixture.paths.stateHome());
        ManagedProcessStore.ManagedProcessFiles files =
                new ManagedProcessStore(fixture.paths.stateHome()).files(workflow);
        Files.writeString(files.stdoutLog(), "worker log line\n", StandardCharsets.UTF_8);
    }

    private static ConnectedBoard saveBoardWithManagedPid(LocalWorkerManagerTestFixture fixture) throws Exception {
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        fixture.stubManagedPid(board, 42);
        return board;
    }

    private PostStopSameWorkflow postStopSameWorkflowWithReportedPid(boolean managed) throws Exception {
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = saveBoardWithManagedPid(fixture);
        long reportedPid = 99L;
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(portUsed(board), fixture.sameWorkflowWithPid(board, reportedPid));
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);
        when(fixture.platform.isAlive(reportedPid)).thenReturn(true);
        when(fixture.platform.isManaged(reportedPid, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(managed);
        return new PostStopSameWorkflow(fixture, board, reportedPid);
    }

    private void assertNoPostStopUntrackedResult(
            WorkerRunResult result, LocalWorkerManagerTestFixture fixture, ConnectedBoard board) throws Exception {
        assertNoLifecyclePrivateOutput(result, fixture, board, "Restored missing managed worker tracking");
        assertThat(pidFiles(fixture.paths.stateHome())).isEmpty();
        verifyPostStopStoppedWithoutLaunch(fixture);
    }

    private void assertNoLifecyclePrivateOutput(
            WorkerRunResult result,
            LocalWorkerManagerTestFixture fixture,
            ConnectedBoard board,
            String... additionalForbidden) {
        String[] forbidden = Stream.concat(
                        Stream.of(
                                "Started Symphony for Trello",
                                board.workflowPath().toString(),
                                fixture.paths.stateHome().toString(),
                                tempDir.toString(),
                                ".log",
                                ".err"),
                        Stream.of(additionalForbidden))
                .toArray(String[]::new);
        result.stdoutDoesNotContain(forbidden);
    }

    private static void verifyPostStopStoppedWithoutLaunch(LocalWorkerManagerTestFixture fixture) throws IOException {
        verify(fixture.platform).stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5));
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    private static void stubSuccessfulManagedRestart(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, BoardHealth initialHealth) throws Exception {
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(initialHealth, fixture.stopped(board));
        when(fixture.platform.stop(42, Duration.ofSeconds(15), Duration.ofSeconds(5)))
                .thenReturn(true);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(99));
        when(fixture.healthChecker.waitForSameWorkflow(eq(board), eq(board.serverPort()), any()))
                .thenReturn(fixture.sameWorkflow(board));
        when(fixture.platform.isAlive(99)).thenReturn(true);
        when(fixture.platform.isManaged(99, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
    }

    private static BoardHealth portUsed(ConnectedBoard board) {
        return new BoardHealth(BoardHealthKind.PORT_USED, board.serverPort(), Optional.empty(), Optional.empty());
    }

    private static void saveConnectedBoardsAndStubSuccessfulStarts(
            LocalWorkerManagerTestFixture fixture, String firstBoardName, String secondBoardName) throws Exception {
        ConnectedBoard first = fixture.connectedBoard("board-1", firstBoardName);
        ConnectedBoard second = fixture.connectedBoard("board-2", secondBoardName);
        saveConnectedBoardsAndStubSuccessfulStarts(fixture, first, second);
    }

    private static void saveConnectedBoardsAndStubSuccessfulStarts(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard first, ConnectedBoard second) throws Exception {
        fixture.save(first, second);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42), new ManagedProcessHandle(43));
        when(fixture.healthChecker.waitForSameWorkflow(any(), anyInt(), any()))
                .thenReturn(new BoardHealth(BoardHealthKind.SAME_WORKFLOW, 18080, Optional.empty(), Optional.empty()));
        when(fixture.platform.isAlive(anyLong())).thenReturn(true);
        when(fixture.platform.isManaged(anyLong(), eq(fixture.paths.appHome()), any()))
                .thenReturn(true);
    }

    private static ConnectedBoard unresolvedServerPortBoardWithHttpOverride(LocalWorkerManagerTestFixture fixture)
            throws Exception {
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue").withServerPort(19094);
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
                  port: $SYMPHONY_TEST_PORT
                ---
                # Queue
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                board.envPath(),
                """
                TRELLO_API_KEY=test-key
                TRELLO_API_TOKEN=test-token
                SYMPHONY_HTTP_PORT=19094
                """,
                StandardCharsets.UTF_8);
        fixture.save(board);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenReturn(new ManagedProcessHandle(42));
        when(fixture.healthChecker.managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath()))
                .thenReturn(19094);
        when(fixture.healthChecker.externalHttpPortOverrideSource(board.envPath()))
                .thenReturn(Optional.of("SYMPHONY_HTTP_PORT in " + board.envPath()));
        when(fixture.healthChecker.waitForSameWorkflow(eq(board), eq(19094), any()))
                .thenReturn(new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW,
                        19094,
                        Optional.of(board.workflowPath().toString()),
                        Optional.of(board.boardId())));
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(true);
        return board;
    }

    private static ConnectedBoard githubEnabled(ConnectedBoard board) {
        return ConnectedBoardBuilder.from(board).withGithubEnabled(true).build();
    }

    private static void writeExistingGithubWorkflowWithoutSandboxPolicy(
            ConnectedBoard board, String trackerFields, String codexFields) throws IOException {
        Files.writeString(
                board.workflowPath(),
                """
                ---
                tracker:
                  kind: trello
                %sserver:
                  port: %d
                codex:
                  command: codex app-server
                %s---
                ## Operating Posture

                Existing private workflow body.

                ## Pull Request Publication

                Run validation and record the review evidence.

                ## Trello List Routing

                Card URL: {{ card.url }}
                """
                        .formatted(trackerFields, board.serverPort(), codexFields),
                StandardCharsets.UTF_8);
    }

    private static void stubStartupLogRewrite(
            LocalWorkerManagerTestFixture fixture,
            ConnectedBoard board,
            ManagedProcessStore.ManagedProcessFiles files,
            String logText)
            throws Exception {
        fixture.stubStoppedStartedWorker(board, 42);
        when(fixture.platform.start(any(), eq(fixture.paths.appHome()), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Files.writeString(files.stdoutLog(), logText, StandardCharsets.UTF_8);
                    return new ManagedProcessHandle(42);
                });
    }

    private static void writeWorkflowWithInvalidSandboxPolicy(Path workflowPath) throws IOException {
        Files.writeString(
                workflowPath,
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: 18080
                codex:
                  command: codex app-server
                  turn_sandbox_policy: []
                ---
                Body
                """,
                StandardCharsets.UTF_8);
    }

    private static Thread startThread(
            WorkerRunAction action, AtomicReference<WorkerRunResult> result, AtomicReference<Throwable> error) {
        return Thread.ofPlatform().start(() -> {
            try {
                result.set(action.get());
            } catch (Exception | AssertionError thrown) {
                error.set(thrown);
            }
        });
    }

    private static boolean threadIsWaiting(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.BLOCKED || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    private static boolean threadIsWaitingForFileLock(Thread thread) {
        return Stream.of(thread.getStackTrace())
                .anyMatch(frame -> frame.getClassName().startsWith("sun.nio.ch.")
                        && frame.getMethodName().startsWith("lock"));
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("condition was not met before timeout");
    }

    private static StalePidScenario stalePidScenario(LocalWorkerManagerTestFixture fixture) throws Exception {
        ConnectedBoard board = fixture.connectedBoard("board-1", "Queue");
        fixture.save(board);
        ManagedProcessStore.ManagedProcessFiles files = fixture.writeManagedPid(board, 42);
        when(fixture.platform.isAlive(42)).thenReturn(true);
        when(fixture.platform.isManaged(42, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(false);
        String pidToken = PrivateContextTokens.pathToken(fixture.paths.configDir(), files.pidFile());
        return new StalePidScenario(board, files, pidToken);
    }

    @FunctionalInterface
    private interface WorkerRunAction {
        WorkerRunResult get() throws Exception;
    }

    private record UnresolvedServerPortStartSelector(
            String name, BiFunction<LocalWorkerManagerTestFixture, ConnectedBoard, StartWorkerRequest> requestFactory) {
        private StartWorkerRequest request(LocalWorkerManagerTestFixture fixture, ConnectedBoard board) {
            return requestFactory.apply(fixture, board);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record StartSelector(
            String name, BiFunction<LocalWorkerManagerTestFixture, ConnectedBoard, StartWorkerRequest> requestFactory) {
        private StartWorkerRequest request(LocalWorkerManagerTestFixture fixture, ConnectedBoard board) {
            return requestFactory.apply(fixture, board);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record PostStopSameWorkflow(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, long reportedPid) {}

    private record StalePidScenario(
            ConnectedBoard board, ManagedProcessStore.ManagedProcessFiles files, String pidToken) {}

    private record UnusableWorkflowContent(String name, String fileName, String content) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record InvalidWorkflowCase(
            String name, String fileName, String content, String expectedCode, String expectedCause) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Path onlyPidFile(Path stateHome) throws Exception {
        List<Path> files = pidFiles(stateHome);
        assertThat(files).hasSize(1);
        return files.getFirst();
    }

    private static void assertManagedWorkerStateUnchanged(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, String originalWorkflow, String expectedPid)
            throws Exception {
        assertThat(board.workflowPath()).content(StandardCharsets.UTF_8).isEqualTo(originalWorkflow);
        assertThat(Files.readString(onlyPidFile(fixture.paths.stateHome()))).isEqualTo(expectedPid);
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    private static List<Path> pidFiles(Path stateHome) throws Exception {
        try (Stream<Path> paths = Files.list(stateHome)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".pid"))
                    .toList();
        }
    }

    private static long stubUntrackedWorkerStartHealth(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, boolean managed) {
        long workerPid = 4242L;
        when(fixture.healthChecker.workflowHealth(
                        board.workflowPath(), board.boardId(), board.boardKey(), board.serverPort()))
                .thenReturn(fixture.sameWorkflowWithPid(board, workerPid));
        stubWorkerProcessState(fixture, board, workerPid, managed);
        return workerPid;
    }

    private static long stubUntrackedWorkerStopHealth(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, boolean managed) {
        long workerPid = 4242L;
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.sameWorkflowWithPid(board, workerPid));
        stubWorkerProcessState(fixture, board, workerPid, managed);
        return workerPid;
    }

    private static void stubUntrackedStatusHealth(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, long workerPid, boolean managed) {
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.sameWorkflowWithPid(board, workerPid));
        stubWorkerProcessState(fixture, board, workerPid, managed);
    }

    private static Stream<Arguments> statusHealthProbeFailures() {
        return Stream.of(
                Arguments.of(
                        "configuration failure",
                        new TrelloBoardSetupException("setup_invalid_server_port", "private context"),
                        "invalid \"Broken\" local status configuration (setup_invalid_server_port)"),
                Arguments.of(
                        "unexpected failure",
                        new IllegalStateException("private context"),
                        "invalid \"Broken\" local status evidence (setup_status_evidence_unavailable)"));
    }

    private static void assertIsolatedStatusEvidenceFailure(WorkerRunResult result, RuntimeException failure) {
        String failureRow = "invalid \"Broken\" local status evidence (setup_status_evidence_unavailable)";
        result.assertSuccess()
                .stdoutDoesNotContain(
                        failure.getMessage(),
                        failure.getClass().getSimpleName(),
                        "private workflow environment",
                        "local path context",
                        "private process evidence");
        assertThat(result.stdout().lines().toList()).containsExactly(failureRow, "stopped \"Stopped\"");
    }

    private static void assertStatusEvidenceFailureIsReadOnlyAndContinues(
            LocalWorkerManagerTestFixture fixture,
            ManagedProcessStore.ManagedProcessFiles files,
            ConnectedBoard stopped)
            throws IOException {
        assertThat(files.pidFile()).exists().content(StandardCharsets.UTF_8).isEqualTo("42");
        verify(fixture.healthChecker).boardHealth(stopped);
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
        verify(fixture.platform, never()).stop(anyLong(), any(Duration.class), any(Duration.class));
    }

    private static void stubWorkerProcessState(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, long workerPid, boolean managed) {
        when(fixture.platform.isAlive(workerPid)).thenReturn(true);
        when(fixture.platform.isManaged(workerPid, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(managed);
    }
}
