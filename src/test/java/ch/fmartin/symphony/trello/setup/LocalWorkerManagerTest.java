package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
            assertThat(failure.code()).isEqualTo("setup_workflow_unresolved_environment");
            assertThat(failure)
                    .hasMessage("Workflow server.port references missing environment variable SYMPHONY_TEST_PORT.");
        });
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startAllowsUnresolvedWorkflowServerPortWhenHttpPortOverrideIsConfigured() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        unresolvedServerPortBoardWithHttpOverride(fixture);

        // when
        WorkerRunResult result = fixture.start(fixture.startRequest("Queue"));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello: \"Queue\"");
    }

    @Test
    void startExplicitWorkflowAllowsUnresolvedWorkflowServerPortWhenHttpPortOverrideIsConfigured() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard board = unresolvedServerPortBoardWithHttpOverride(fixture);

        // when
        WorkerRunResult result = fixture.start(fixture.startWorkflowRequest(board.workflowPath()));

        // then
        result.assertSuccess().stdoutContains("Started Symphony for Trello: \"Queue\"");
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
            assertThat(failure.code()).isEqualTo("setup_workflow_unresolved_environment");
            assertThat(failure)
                    .hasMessage("Workflow tracker.board_id references missing environment variable MISSING_BOARD_ID.");
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
                    assertThat(releaseFirstStart.await(5, TimeUnit.SECONDS)).isTrue();
                    return new ManagedProcessHandle(42);
                });
        when(fixture.healthChecker.waitForSameWorkflow(board, board.serverPort()))
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
            awaitCondition(() -> startCalls.get() > 1 || threadIsWaiting(secondThread.get()));

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
                    .contains("not valid JSON", "connected-boards.json")
                    .doesNotContain("double-quote", "Unexpected character");
        });
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
        ConnectedBoard row = new ConnectedBoard(
                "000000000000000000000002",
                "000000000000000000000002",
                "Shortlink Queue",
                "https://trello.com/b/SYNTH002/synthetic-board",
                rowWorkflow.toAbsolutePath().normalize(),
                fixture.paths.defaultEnvPath(),
                fixture.paths.workspaceRoot(),
                ConfigDefaults.DEFAULT_SERVER_PORT,
                false,
                List.of(),
                false);
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
                .stdoutContains("Skipped workflow WORKFLOW.queue-stale.md", "already managed by the running workflow");
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
        when(fixture.healthChecker.waitForSameWorkflow(any(), anyInt())).thenReturn(fixture.stopped(18081));

        // when
        Throwable thrown = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(staleWorkflow)));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("did not report");
        verify(fixture.platform).start(any(), eq(fixture.paths.appHome()), any(), any(), any());
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
                            "WORKFLOW.other.md",
                            "Stop that worker or change the workflow server.port");
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
        fixture.stubStoppedStartedWorkerWithStartupLog(board, 42, "Configured Trello board is closed\n");

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
                        board.workflowPath().toString())
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
                .stdoutContains(
                        "invalid \"Queue\"",
                        "missing workflow file",
                        board.workflowPath().toString())
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
        when(fixture.healthChecker.boardHealth(board)).thenReturn(fixture.sameWorkflow(board));

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
                .stdoutDoesNotContain("Stopped WORKFLOW.");
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
                .stdoutDoesNotContain("Stopped WORKFLOW.");
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
        result.assertSuccess().stdoutContains("Stopped WORKFLOW.queue.md");
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
                    assertThat(releaseFirstStop.await(5, TimeUnit.SECONDS)).isTrue();
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
        assertThat(firstResult.get().stdout()).contains("Stopped WORKFLOW.");
        assertThat(secondResult.get().stdout())
                .contains("Symphony for Trello is already stopped for \"Queue\"")
                .doesNotContain("Stopped WORKFLOW.");
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
    void lifecycleWorkflowSelectorsRejectDuplicateManifestRows() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        ConnectedBoard first = fixture.connectedBoard("board-1", "Dup Workflow A", "shared-workflow");
        ConnectedBoard second = new ConnectedBoard(
                "board-2",
                "short-2",
                "Dup Workflow B",
                "https://trello.com/b/short-2/dup-workflow-b",
                first.workflowPath(),
                first.envPath(),
                fixture.paths.workspaceRoot(),
                first.serverPort(),
                false,
                List.of(),
                false);
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
    void startWorkflowSelectorRejectsMissingWorkflowFileButRecoveryCommandsCanStillAct() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path workflow = fixture.paths.configDir().resolve("missing.WORKFLOW.md");
        writeManagedLog(fixture, workflow);

        // when
        Throwable start = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(workflow)));
        WorkerRunResult status = fixture.status(fixture.statusWorkflowRequest(workflow));
        WorkerRunResult stop = fixture.stop(fixture.stopWorkflowRequest(workflow));
        WorkerRunResult logs = fixture.logs(fixture.logsWorkflowRequest(workflow));

        // then
        assertWorkflowInvalidForLaunch(start, "missing workflow file");
        status.assertSuccess().stdoutContains("invalid \"missing.WORKFLOW.md\"", "missing workflow file");
        stop.assertSuccess().stdoutContains("Symphony for Trello is already stopped for \"missing.WORKFLOW.md\"");
        logs.assertSuccess();
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
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
    void startWorkflowSelectorRejectsUnusableWorkflowContentButRecoveryCommandsCanStillAct() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Path emptyWorkflow = fixture.paths.configDir().resolve("empty.WORKFLOW.md");
        Path plainWorkflow = fixture.paths.configDir().resolve("plain.WORKFLOW.md");
        Files.createDirectories(fixture.paths.configDir());
        Files.writeString(emptyWorkflow, "", StandardCharsets.UTF_8);
        Files.writeString(plainWorkflow, "plain body\n", StandardCharsets.UTF_8);

        // when
        for (Path workflow : List.of(emptyWorkflow, plainWorkflow)) {
            writeManagedLog(fixture, workflow);
            Throwable start = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(workflow)));
            WorkerRunResult status = fixture.status(fixture.statusWorkflowRequest(workflow));
            WorkerRunResult stop = fixture.stop(fixture.stopWorkflowRequest(workflow));
            WorkerRunResult logs = fixture.logs(fixture.logsWorkflowRequest(workflow));

            assertThat(start).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
                assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
                assertThat(failure.getMessage())
                        .contains("Invalid workflow configuration:", "Workflow has no YAML front matter.");
            });
            status.assertSuccess().stdoutContains("invalid \"" + workflow.getFileName() + "\"");
            stop.assertSuccess()
                    .stdoutContains("Symphony for Trello is already stopped for \"" + workflow.getFileName() + "\"");
            logs.assertSuccess();
        }

        // then
        verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void startWorkflowReportsInvalidContentThroughLaunchValidation() throws Exception {
        // given
        LocalWorkerManagerTestFixture fixture = new LocalWorkerManagerTestFixture(tempDir);
        Files.createDirectories(fixture.paths.configDir());
        fixture.writeEnv(fixture.paths.defaultEnvPath());
        record InvalidWorkflowCase(String fileName, String content, String expectedCode, String expectedCause) {}
        List<InvalidWorkflowCase> cases = List.of(
                new InvalidWorkflowCase(
                        "unclosed.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "malformed.WORKFLOW.md",
                        "---\ntracker: [unclosed\n---\nBody\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "nonmap.WORKFLOW.md",
                        "---\n- first\n- second\n---\nBody\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "badport.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: \"not-a-port\"\n---\nBody\n",
                        "setup_workflow_invalid",
                        "Invalid workflow configuration:"),
                new InvalidWorkflowCase(
                        "port-above-range.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: 70000\n---\nBody\n",
                        "setup_workflow_invalid",
                        "server.port"),
                new InvalidWorkflowCase(
                        "port-negative.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: -1\n---\nBody\n",
                        "setup_workflow_invalid",
                        "server.port"),
                new InvalidWorkflowCase(
                        "port-fractional.WORKFLOW.md",
                        "---\ntracker:\n  kind: trello\n  board_id: board-1\nserver:\n  port: 18080.5\n---\nBody\n",
                        "setup_workflow_invalid",
                        "server.port"));

        for (InvalidWorkflowCase invalidCase : cases) {
            Path workflow = fixture.paths.configDir().resolve(invalidCase.fileName());
            Files.writeString(workflow, invalidCase.content(), StandardCharsets.UTF_8);

            // when
            Throwable thrown = catchThrowable(() -> fixture.start(fixture.startWorkflowRequest(workflow)));

            // then
            assertThat(thrown)
                    .as(invalidCase.fileName())
                    .isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
                        assertThat(failure.code()).as(invalidCase.fileName()).isEqualTo(invalidCase.expectedCode());
                        assertThat(failure.getMessage()).contains(invalidCase.expectedCause());
                    });
            assertThat(new ManagedProcessStore(fixture.paths.stateHome())
                            .readPid(new ManagedProcessStore(fixture.paths.stateHome())
                                    .files(workflow)
                                    .pidFile()))
                    .as(invalidCase.fileName())
                    .isNull();
        }
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
        when(fixture.healthChecker.waitForSameWorkflow(any(), eq(19092)))
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
                        eq(19092));
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
        when(fixture.healthChecker.waitForSameWorkflow(board, 19093))
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
        ConnectedBoard boardWithCustomEnv = new ConnectedBoard(
                board.boardId(),
                board.boardKey(),
                board.boardName(),
                board.boardUrl(),
                board.workflowPath(),
                customEnv,
                board.workspaceRoot(),
                board.serverPort(),
                board.githubEnabled(),
                board.additionalWritableRoots(),
                board.dangerFullAccess());
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
        ConnectedBoard expectedBoard = new ConnectedBoard(
                "resolved-stop-board",
                "resolved-stop-board",
                "WORKFLOW.direct-stop-env.md",
                "",
                workflow.toAbsolutePath().normalize(),
                null,
                TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT,
                19094,
                false,
                List.of(),
                false);
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
        result.assertSuccess().stdoutContains("Stopped WORKFLOW.direct-stop-env");
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

    private void assertDuplicateWorkflowSelector(Throwable thrown, ConnectedBoard first, ConnectedBoard second) {
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_worker_workflow_ambiguous");
            assertThat(failure)
                    .hasMessage(
                            "Multiple connected-board rows reference --workflow. Repair connected-boards.json, then rerun the command.");
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

    private void assertLifecycleRejectsExplicitWorkflow(LocalWorkerManagerTestFixture fixture, Path workflow) {
        assertLifecycleRejectsExplicitWorkflow(
                fixture,
                workflow,
                "--workflow must reference a readable workflow file with usable workflow front matter.",
                "Invalid workflow configuration:");
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
                        .contains("connected-boards.json")
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
        when(fixture.healthChecker.waitForSameWorkflow(any(), anyInt()))
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
        when(fixture.healthChecker.waitForSameWorkflow(board, 19094))
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

    @FunctionalInterface
    private interface WorkerRunAction {
        WorkerRunResult get() throws Exception;
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

    private static void stubWorkerProcessState(
            LocalWorkerManagerTestFixture fixture, ConnectedBoard board, long workerPid, boolean managed) {
        when(fixture.platform.isAlive(workerPid)).thenReturn(true);
        when(fixture.platform.isManaged(workerPid, fixture.paths.appHome(), board.workflowPath()))
                .thenReturn(managed);
    }
}
