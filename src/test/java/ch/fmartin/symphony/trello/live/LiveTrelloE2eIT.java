package ch.fmartin.symphony.trello.live;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.ImportBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.NewBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class LiveTrelloE2eIT {
    private static final URI TRELLO_ENDPOINT = TrelloBoardSetup.DEFAULT_ENDPOINT;
    private static final Path QUARKUS_RUNNER = Path.of("target/quarkus-app/quarkus-run.jar");
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration RUNNING_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration HANDOFF_TIMEOUT = Duration.ofMinutes(3);
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void deterministicFakeCodexCoversLiveTrelloSetupConcurrencyAndImports() throws Exception {
        // given
        assumeTrue(liveE2eEnabled(), "Set SYMPHONY_RUN_LIVE_E2E=1 to run live Trello E2E checks.");
        TrelloCredentials credentials = credentialsOrFail();
        assertThat(Files.isRegularFile(QUARKUS_RUNNER))
                .as("Run this through Maven verify so the Quarkus runner exists.")
                .isTrue();

        String runId = "live-e2e-it-" + RUN_ID_FORMAT.format(now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path runDir = Path.of("target/live-e2e-it", runId);
        Path workspaceRoot = runDir.resolve("workspaces");
        Files.createDirectories(runDir);
        LiveTrelloClient trello = new LiveTrelloClient(credentials);
        String workspaceId = workspaceIdOrFail(credentials);
        List<String> disposableBoardIds = new ArrayList<>();
        Throwable bodyFailure = null;

        try {
            TrelloBoardSetup setup = new TrelloBoardSetup(json);
            Path boardAWorkflow = runDir.resolve("board-a.WORKFLOW.md");
            Path importedAWorkflow = runDir.resolve("imported-a.WORKFLOW.md");
            Path boardBWorkflow = runDir.resolve("board-b.WORKFLOW.md");
            Path customWorkflow = runDir.resolve("custom-import.WORKFLOW.md");
            Path completions = runDir.resolve("fake-codex-completions.log");

            var boardA = setup.createRecommendedBoard(
                    newBoard(runId + " A", credentials, workspaceId, boardAWorkflow, workspaceRoot, 1));
            disposableBoardIds.add(boardA.boardId());
            var boardB = setup.createRecommendedBoard(
                    newBoard(runId + " B", credentials, workspaceId, boardBWorkflow, workspaceRoot, 2));
            disposableBoardIds.add(boardB.boardId());
            setup.importExistingBoard(importBoard(
                    credentials,
                    boardA.boardId(),
                    List.of(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    List.of("Done"),
                    TrelloBoardSetup.RECOMMENDED_BLOCKED_STATE,
                    importedAWorkflow,
                    workspaceRoot,
                    1,
                    true));
            String customBoardId = trello.createBoard(runId + " custom existing structure", workspaceId);
            disposableBoardIds.add(customBoardId);
            Map<String, String> customLists = trello.createLists(
                    customBoardId,
                    List.of(
                            "Intake",
                            "Queue for Codex",
                            "Escalated for Codex",
                            "Review",
                            "Blocked Work",
                            "Released",
                            "Parked"));
            setup.importExistingBoard(importBoard(
                    credentials,
                    customBoardId,
                    List.of("Queue for Codex", "Escalated for Codex"),
                    List.of("Released", "Parked"),
                    "Blocked Work",
                    customWorkflow,
                    workspaceRoot,
                    2,
                    true));

            patchWorkflow(boardAWorkflow, completions, TrelloBoardSetup.RECOMMENDED_REVIEW_STATE, 1, 7_000);
            patchWorkflow(importedAWorkflow, completions, TrelloBoardSetup.RECOMMENDED_REVIEW_STATE, 1, 250);
            patchWorkflow(boardBWorkflow, completions, TrelloBoardSetup.RECOMMENDED_REVIEW_STATE, 2, 7_000);
            patchWorkflow(customWorkflow, completions, "Review", 2, 7_000);

            Map<String, String> boardALists = trello.listIdsByName(boardA.boardId());
            Map<String, String> boardBLists = trello.listIdsByName(boardB.boardId());
            CardRef boardAOlder = trello.createCard(
                    boardALists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " sequential older",
                    "Disposable live E2E card that should wait after Trello reordering.");
            Thread.sleep(1_100);
            CardRef boardALater = trello.createCard(
                    boardALists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " sequential later moved first",
                    "Disposable live E2E card created later, then moved to the top of the active list.");
            trello.moveCardToTop(boardALater.id());

            CardRef boardBFirst = trello.createCard(
                    boardBLists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " concurrent first",
                    "Disposable live E2E concurrency card.");
            CardRef boardBSecond = trello.createCard(
                    boardBLists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " concurrent second",
                    "Disposable live E2E concurrency card.");
            CardRef boardBThird = trello.createCard(
                    boardBLists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " concurrent waiting third",
                    "Disposable live E2E card that should wait while the first two run.");

            // when
            try (SymphonyProcess processA = startProcess(runDir, boardAWorkflow, freePort());
                    SymphonyProcess processB = startProcess(runDir, boardBWorkflow, freePort())) {
                waitForStateEndpoint(processA);
                waitForStateEndpoint(processB);
                refresh(processA);
                refresh(processB);

                waitForRunningCards(processA, 1, List.of(boardALater.id()));
                waitForRunningCards(processB, 2, List.of(boardBFirst.id(), boardBSecond.id()));

                // then
                assertCardList(trello, boardAOlder, boardALists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE));
                assertCardList(trello, boardBThird, boardBLists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE));

                waitForHandoff(trello, boardALater, boardALists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                waitForHandoff(trello, boardBFirst, boardBLists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                waitForHandoff(trello, boardBSecond, boardBLists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                waitForSuccessfulFakeCodexTurns(completions, 3);

                refresh(processA);
                refresh(processB);

                waitForHandoff(trello, boardAOlder, boardALists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                waitForHandoff(trello, boardBThird, boardBLists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                waitForSuccessfulFakeCodexTurns(completions, 5);
                assertStateDrained(processA);
                assertStateDrained(processB);
                assertSuccessfulFakeCodexTurns(completions, 5);
            }

            CardRef importedCard = trello.createCard(
                    boardALists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " imported generated board",
                    "Disposable live E2E card for an imported generated board workflow.");
            try (SymphonyProcess importedProcess = startProcess(runDir, importedAWorkflow, freePort())) {
                waitForStateEndpoint(importedProcess);
                refresh(importedProcess);

                waitForHandoff(trello, importedCard, boardALists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                waitForSuccessfulFakeCodexTurns(completions, 6);
                assertStateDrained(importedProcess);
                assertSuccessfulFakeCodexTurns(completions, 6);
            }

            CardRef customQueue = trello.createCard(
                    customLists.get("Queue for Codex"),
                    runId + " custom queue",
                    "Disposable live E2E card for custom active list one.");
            CardRef customEscalated = trello.createCard(
                    customLists.get("Escalated for Codex"),
                    runId + " custom escalated",
                    "Disposable live E2E card for custom active list two.");
            try (SymphonyProcess customProcess = startProcess(runDir, customWorkflow, freePort())) {
                waitForStateEndpoint(customProcess);
                refresh(customProcess);
                waitForRunningCount(customProcess, 2);

                waitForHandoff(trello, customQueue, customLists.get("Review"));
                waitForHandoff(trello, customEscalated, customLists.get("Review"));
                waitForSuccessfulFakeCodexTurns(completions, 8);
                assertStateDrained(customProcess);
                assertSuccessfulFakeCodexTurns(completions, 8);
            }
        } catch (Exception e) {
            bodyFailure = e;
            throw e;
        } catch (AssertionError e) {
            bodyFailure = e;
            throw e;
        } finally {
            cleanupDisposableBoardsAfterTest(trello, workspaceId, runId, disposableBoardIds, bodyFailure);
        }
    }

    @Test
    void deterministicFakeCodexCoversLongExternalDockerProjectLifecycle() throws Exception {
        // given
        assumeTrue(liveE2eEnabled(), "Set SYMPHONY_RUN_LIVE_E2E=1 to run live Trello E2E checks.");
        TrelloCredentials credentials = credentialsOrFail();
        assertThat(Files.isRegularFile(QUARKUS_RUNNER))
                .as("Run this through Maven verify so the Quarkus runner exists.")
                .isTrue();

        String runId = "live-e2e-external-" + RUN_ID_FORMAT.format(now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path runDir = Path.of("target/live-e2e-it", runId);
        Path workspaceRoot = runDir.resolve("workspaces");
        Path externalProject = runDir.resolve("external-docker-project");
        Files.createDirectories(runDir);
        createExternalDockerProject(externalProject, runId);
        LiveTrelloClient trello = new LiveTrelloClient(credentials);
        String workspaceId = workspaceIdOrFail(credentials);
        List<String> disposableBoardIds = new ArrayList<>();
        Throwable bodyFailure = null;

        try {
            TrelloBoardSetup setup = new TrelloBoardSetup(json);
            Path workflow = runDir.resolve("external-docker.WORKFLOW.md");
            Path completions = runDir.resolve("fake-codex-external-completions.log");

            var board = setup.createRecommendedBoard(
                    newBoard(runId + " external docker", credentials, workspaceId, workflow, workspaceRoot, 1));
            disposableBoardIds.add(board.boardId());
            patchWorkflow(workflow, completions, TrelloBoardSetup.RECOMMENDED_REVIEW_STATE, 1, 30_000);

            Map<String, String> lists = trello.listIdsByName(board.boardId());
            CardRef card = trello.createCard(
                    lists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " long external docker task",
                    externalDockerDescription(externalProject, runId));

            // when
            try (SymphonyProcess process = startProcess(runDir, workflow, freePort())) {
                waitForStateEndpoint(process);
                refresh(process);

                waitForRunningCards(process, 1, List.of(card.id()));

                // then
                assertCardList(trello, card, lists.get(TrelloBoardSetup.RECOMMENDED_IN_PROGRESS_STATE));
                waitForHandoff(trello, card, lists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                waitForSuccessfulFakeCodexTurns(completions, 1);
                assertStateDrained(process);
                assertSuccessfulFakeCodexTurns(completions, 1);
            }
        } catch (Exception e) {
            bodyFailure = e;
            throw e;
        } catch (AssertionError e) {
            bodyFailure = e;
            throw e;
        } finally {
            cleanupDisposableBoardsAfterTest(trello, workspaceId, runId, disposableBoardIds, bodyFailure);
        }
    }

    @Test
    void realCodexCanRunLongExternalDockerProjectWhenExplicitlyEnabled() throws Exception {
        // given
        assumeTrue(
                realCodexDockerE2eEnabled(),
                "Set SYMPHONY_RUN_REAL_CODEX_DOCKER_E2E=1 to run the real Codex/Docker live E2E check.");
        assumeTrue(commandSucceeds("codex", "--version"), "Codex CLI must be available on PATH.");
        assumeTrue(commandSucceeds("docker", "version"), "Docker must be installed and usable by this user.");
        TrelloCredentials credentials = credentialsOrFail();
        assertThat(Files.isRegularFile(QUARKUS_RUNNER))
                .as("Run this through Maven verify so the Quarkus runner exists.")
                .isTrue();

        String runId = "real-codex-docker-" + RUN_ID_FORMAT.format(now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path runDir = Path.of("target/live-e2e-it", runId);
        Path workspaceRoot = runDir.resolve("workspaces");
        Path externalProject = runDir.resolve("external-docker-project");
        Path expectedOutput = externalProject.resolve("docker-output.txt");
        Files.createDirectories(runDir);
        createExternalDockerProject(externalProject, runId);
        LiveTrelloClient trello = new LiveTrelloClient(credentials);
        String workspaceId = workspaceIdOrFail(credentials);
        List<String> disposableBoardIds = new ArrayList<>();
        Throwable bodyFailure = null;

        try {
            TrelloBoardSetup setup = new TrelloBoardSetup(json);
            Path workflow = runDir.resolve("real-codex-docker.WORKFLOW.md");

            var board = setup.createRecommendedBoard(
                    newBoard(runId + " real codex docker", credentials, workspaceId, workflow, workspaceRoot, 1));
            disposableBoardIds.add(board.boardId());
            patchWorkflowForRealCodexDocker(workflow, externalProject);

            Map<String, String> lists = trello.listIdsByName(board.boardId());
            CardRef card = trello.createCard(
                    lists.get(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                    runId + " real codex docker task",
                    realCodexDockerDescription(externalProject, runId));

            // when
            try (SymphonyProcess process =
                    startProcess(runDir, workflow, freePort(), Map.of("SYMPHONY_CODEX_DANGER_FULL_ACCESS", "true"))) {
                waitForStateEndpoint(process);
                refresh(process);

                waitForRunningCards(process, 1, List.of(card.id()));

                // then
                assertCardList(trello, card, lists.get(TrelloBoardSetup.RECOMMENDED_IN_PROGRESS_STATE));
                waitForHandoff(trello, card, lists.get(TrelloBoardSetup.RECOMMENDED_REVIEW_STATE));
                assertStateDrained(process);
                assertThat(Files.readString(expectedOutput)).contains(runId);
            }
        } catch (Exception e) {
            bodyFailure = e;
            throw e;
        } catch (AssertionError e) {
            bodyFailure = e;
            throw e;
        } finally {
            cleanupDisposableBoardsAfterTest(trello, workspaceId, runId, disposableBoardIds, bodyFailure);
        }
    }

    private void cleanupDisposableBoardsAfterTest(
            LiveTrelloClient trello,
            String workspaceId,
            String runId,
            List<String> disposableBoardIds,
            Throwable bodyFailure) {
        try {
            cleanupDisposableBoards(trello, workspaceId, runId, disposableBoardIds);
        } catch (AssertionError e) {
            if (bodyFailure != null) {
                bodyFailure.addSuppressed(e);
                return;
            }
            throw e;
        }
    }

    private TrelloCredentials credentialsOrFail() {
        String apiKey = LocalEnvironment.get("TRELLO_API_KEY").orElse(null);
        String apiToken = LocalEnvironment.get("TRELLO_API_TOKEN").orElse(null);
        assertThat(apiKey).as("TRELLO_API_KEY is required for live Trello E2E.").isNotBlank();
        assertThat(apiToken)
                .as("TRELLO_API_TOKEN is required for live Trello E2E.")
                .isNotBlank();
        return new TrelloCredentials(apiKey, apiToken);
    }

    private static boolean liveE2eEnabled() {
        return LocalEnvironment.get("SYMPHONY_RUN_LIVE_E2E")
                .map(value -> value.equals("1") || Boolean.parseBoolean(value))
                .orElse(false);
    }

    private static boolean realCodexDockerE2eEnabled() {
        return LocalEnvironment.get("SYMPHONY_RUN_REAL_CODEX_DOCKER_E2E")
                .map(value -> value.equals("1") || Boolean.parseBoolean(value))
                .orElse(false);
    }

    private String workspaceIdOrFail(TrelloCredentials credentials) {
        String configured =
                LocalEnvironment.get("SYMPHONY_LIVE_E2E_TRELLO_WORKSPACE_ID").orElse(null);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        var workspaces = new TrelloBoardSetup(json)
                .listWorkspaces(new TrelloBoardSetup.WorkspaceListRequest(TRELLO_ENDPOINT, credentials));
        assertThat(workspaces)
                .as("A Trello Workspace is required for live E2E board creation.")
                .isNotEmpty();
        assertThat(workspaces)
                .as("Set SYMPHONY_LIVE_E2E_TRELLO_WORKSPACE_ID when the token can access multiple Workspaces.")
                .hasSize(1);
        return workspaces.getFirst().id();
    }

    private static NewBoardRequest newBoard(
            String name,
            TrelloCredentials credentials,
            String workspaceId,
            Path workflow,
            Path workspaceRoot,
            int maxConcurrentAgents) {
        return new NewBoardRequest(
                TRELLO_ENDPOINT,
                credentials,
                name,
                workspaceId,
                workflow,
                workspaceRoot,
                maxConcurrentAgents,
                false,
                false);
    }

    private static ImportBoardRequest importBoard(
            TrelloCredentials credentials,
            String boardId,
            List<String> activeStates,
            List<String> terminalStates,
            String blockedState,
            Path workflow,
            Path workspaceRoot,
            int maxConcurrentAgents,
            boolean force) {
        return new ImportBoardRequest(
                TRELLO_ENDPOINT,
                credentials,
                boardId,
                activeStates,
                terminalStates,
                blockedState,
                workflow,
                workspaceRoot,
                maxConcurrentAgents,
                force);
    }

    private static void patchWorkflow(
            Path workflow, Path completions, String reviewState, int maxConcurrentAgents, int sleepMs)
            throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"))
                .toAbsolutePath();
        Path fakeCodex = Path.of("scripts/FakeCodexAppServer.java").toAbsolutePath();
        String command =
                "SYMPHONY_FAKE_CODEX_SLEEP_MS=%d SYMPHONY_FAKE_CODEX_COMPLETIONS_FILE=%s SYMPHONY_FAKE_CODEX_REVIEW_STATE=%s %s --source 25 %s"
                        .formatted(
                                sleepMs,
                                shellQuote(completions.toAbsolutePath().toString()),
                                shellQuote(reviewState),
                                shellQuote(java.toString()),
                                shellQuote(fakeCodex.toString()));
        String patched = Files.readString(workflow)
                .replaceFirst("(?m)^  max_concurrent_agents: \\d+$", "  max_concurrent_agents: " + maxConcurrentAgents)
                .replaceFirst("(?m)^  command: codex app-server$", "  command: " + yamlScalar(command));
        Files.writeString(workflow, patched);
    }

    private static void patchWorkflowForRealCodexDocker(Path workflow, Path externalProject) throws IOException {
        String patched = Files.readString(workflow)
                .replaceFirst("(?m)^  max_concurrent_agents: \\d+$", "  max_concurrent_agents: 1")
                .replaceFirst(
                        "(?m)^  command: codex app-server$",
                        "  command: codex app-server\n  additional_writable_roots:\n    - "
                                + yamlScalar(externalProject
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString()))
                .replaceFirst("(?m)^  read_timeout_ms: 5000$", "  read_timeout_ms: 60000")
                .replaceFirst("(?m)^  stall_timeout_ms: 300000$", "  stall_timeout_ms: 900000");
        Files.writeString(workflow, patched);
    }

    private static void createExternalDockerProject(Path project, String runId) throws IOException {
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("Dockerfile"),
                """
                FROM alpine:3.22
                RUN sleep 30
                COPY expected.txt /expected.txt
                CMD ["cat", "/expected.txt"]
                """);
        Files.writeString(project.resolve("expected.txt"), runId + System.lineSeparator());
        Files.writeString(
                project.resolve("README.md"),
                """
                # Disposable Symphony Trello Live E2E Fixture

                This directory is generated by the live E2E test. It intentionally lives outside the
                Symphony workspace root so the test exercises external-project instructions without
                depending on any private repository.
                """);
    }

    private static String externalDockerDescription(Path externalProject, String runId) {
        return """
                Disposable live E2E task.

                External project path:
                `%s`

                This fixture contains a minimal Dockerfile whose build includes a 30-second operation.
                The deterministic fake Codex server will not run Docker, but Symphony must still move
                this card from Ready for Codex to In Progress while the long operation is simulated,
                then move it to Human Review.

                Run id: `%s`
                """
                .formatted(externalProject.toAbsolutePath().normalize(), runId);
    }

    private static String realCodexDockerDescription(Path externalProject, String runId) {
        return """
                Goal:
                - Work only in this disposable external project path: `%s`
                - From that directory, run: `docker build -t symphony-trello-live-e2e-%s .`
                - Then run: `docker run --rm symphony-trello-live-e2e-%s > docker-output.txt`
                - Verify that `docker-output.txt` contains `%s`.
                - Do not use or mention any private repositories or host-specific project names.
                - Add/update the Trello workpad with the commands and verification.
                - Add a concise Trello handoff comment.
                - Move this card to Human Review only after the Docker command and file verification succeed.

                The Dockerfile intentionally contains `RUN sleep 30` so the card should be visible in
                In Progress while the run is underway.
                """
                .formatted(
                        externalProject.toAbsolutePath().normalize(),
                        dockerTagSuffix(runId),
                        dockerTagSuffix(runId),
                        runId);
    }

    private static String dockerTagSuffix(String runId) {
        return runId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name;
    }

    private static String yamlScalar(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private void waitForStateEndpoint(SymphonyProcess process) {
        waitUntil(STARTUP_TIMEOUT, () -> process.isAlive() && process.state().isObject(), "state endpoint starts");
    }

    private void waitForRunningCards(SymphonyProcess process, int expectedCount, List<String> expectedCardIds) {
        waitUntil(
                RUNNING_TIMEOUT,
                () -> {
                    JsonNode state = process.state();
                    List<String> runningCardIds = runningCardIds(state);
                    return state.path("counts").path("running").asInt() == expectedCount
                            && runningCardIds.containsAll(expectedCardIds);
                },
                "expected running card ids " + expectedCardIds);
    }

    private void waitForRunningCount(SymphonyProcess process, int expectedCount) {
        waitUntil(
                RUNNING_TIMEOUT,
                () -> process.state().path("counts").path("running").asInt() == expectedCount,
                "expected running count " + expectedCount);
    }

    private void waitForHandoff(LiveTrelloClient trello, CardRef card, String expectedListId) {
        waitUntil(
                HANDOFF_TIMEOUT,
                () -> {
                    CardState state = trello.cardState(card.id());
                    return state.commentCount() >= 2
                            && state.workpadCount() == 1
                            && expectedListId.equals(state.listId());
                },
                "card reaches expected handoff list with one workpad and one handoff comment");
    }

    private void waitForSuccessfulFakeCodexTurns(Path completions, int expectedCount) {
        waitUntil(
                HANDOFF_TIMEOUT,
                () -> {
                    long count = successfulFakeCodexTurnCount(completions);
                    assertThat(count)
                            .as(
                                    "fake Codex should not complete duplicate turns before reaching %s completions",
                                    expectedCount)
                            .isLessThanOrEqualTo(expectedCount);
                    return count == expectedCount;
                },
                "fake Codex reports " + expectedCount + " successful completed turns");
    }

    private static void assertSuccessfulFakeCodexTurns(Path completions, int expectedCount) {
        assertThat(successfulFakeCodexTurnCount(completions))
                .as("fake Codex successful completed turns")
                .isEqualTo(expectedCount);
    }

    private static long successfulFakeCodexTurnCount(Path completions) {
        if (!Files.isRegularFile(completions)) {
            return 0;
        }
        try (var lines = Files.lines(completions)) {
            return lines.count();
        } catch (IOException e) {
            throw new AssertionError("Could not read fake Codex completion log", e);
        }
    }

    private void assertStateDrained(SymphonyProcess process) {
        waitUntil(
                HANDOFF_TIMEOUT,
                () -> {
                    JsonNode state = process.state();
                    return state.path("counts").path("running").asInt() == 0
                            && state.path("counts").path("retrying").asInt() == 0;
                },
                "state drains to zero running and retrying entries");
    }

    private void assertCardList(LiveTrelloClient trello, CardRef card, String expectedListId) {
        assertThat(trello.cardState(card.id()).listId()).isEqualTo(expectedListId);
    }

    private void refresh(SymphonyProcess process) {
        process.refresh();
    }

    private static List<String> runningCardIds(JsonNode state) {
        List<String> cardIds = new ArrayList<>();
        state.path("running").forEach(row -> cardIds.add(row.path("card_id").asText()));
        return cardIds;
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition, String description) {
        Instant deadline = now().plus(timeout);
        AssertionError lastFailure = null;
        while (now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (AssertionError e) {
                lastFailure = e;
            } catch (RuntimeException ignored) {
                // Poll again; live Trello and the local status endpoint may be briefly unavailable.
            }
            sleep(Duration.ofMillis(500));
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for live E2E condition", e);
        }
    }

    private static void cleanupDisposableBoards(
            LiveTrelloClient trello, String workspaceId, String runId, List<String> disposableBoardIds) {
        List<String> boardIds = new ArrayList<>(disposableBoardIds);
        List<Throwable> failures = new ArrayList<>();
        try {
            trello.openBoardIdsByNamePrefix(workspaceId, runId).stream()
                    .filter(boardId -> !boardIds.contains(boardId))
                    .forEach(boardIds::add);
        } catch (AssertionError | RuntimeException e) {
            failures.add(e);
        }

        for (String boardId : boardIds.reversed()) {
            try {
                trello.archiveBoard(boardId);
            } catch (AssertionError | RuntimeException e) {
                failures.add(e);
            }
        }

        if (!failures.isEmpty()) {
            AssertionError cleanupFailure =
                    new AssertionError("Live E2E cleanup could not archive every disposable board");
            failures.forEach(cleanupFailure::addSuppressed);
            throw cleanupFailure;
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError("Could not allocate a free local port", e);
        }
    }

    private SymphonyProcess startProcess(Path runDir, Path workflow, int port) throws IOException {
        return startProcess(runDir, workflow, port, Map.of());
    }

    private SymphonyProcess startProcess(Path runDir, Path workflow, int port, Map<String, String> environment)
            throws IOException {
        Path stdout = runDir.resolve("symphony-" + port + ".out.log");
        Path stderr = runDir.resolve("symphony-" + port + ".err.log");
        ProcessBuilder builder = new ProcessBuilder(List.of(
                        Path.of(System.getProperty("java.home"), "bin", executable("java"))
                                .toString(),
                        "-jar",
                        QUARKUS_RUNNER.toString(),
                        workflow.toString(),
                        "--port",
                        Integer.toString(port)))
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        return new SymphonyProcess(process, port);
    }

    private final class LiveTrelloClient {
        private final TrelloCredentials credentials;

        private LiveTrelloClient(TrelloCredentials credentials) {
            this.credentials = credentials;
        }

        String createBoard(String name, String workspaceId) {
            Map<String, String> query = orderedMap("name", name, "defaultLists", "false", "defaultLabels", "false");
            query.put("idOrganization", workspaceId);
            Map<String, Object> board = postMap("boards", query);
            return requiredText(board, "id");
        }

        Map<String, String> createLists(String boardId, List<String> names) {
            Map<String, String> created = new LinkedHashMap<>();
            for (String name : names) {
                Map<String, Object> list =
                        postMap("lists", orderedMap("name", name, "idBoard", boardId, "pos", "bottom"));
                created.put(name, requiredText(list, "id"));
            }
            return created;
        }

        Map<String, String> listIdsByName(String boardId) {
            return getList(
                            "boards/" + encodeSegment(boardId) + "/lists",
                            Map.of("filter", "open", "fields", "id,name,closed"))
                    .stream()
                    .filter(list -> !Boolean.parseBoolean(Objects.toString(list.get("closed"), "false")))
                    .collect(Collectors.toMap(
                            list -> requiredText(list, "name"),
                            list -> requiredText(list, "id"),
                            (left, right) -> left,
                            LinkedHashMap::new));
        }

        List<String> openBoardIdsByNamePrefix(String workspaceId, String namePrefix) {
            return getList(
                            "organizations/" + encodeSegment(workspaceId) + "/boards",
                            Map.of("filter", "open", "fields", "id,name"))
                    .stream()
                    .filter(board -> requiredText(board, "name").startsWith(namePrefix))
                    .map(board -> requiredText(board, "id"))
                    .toList();
        }

        CardRef createCard(String listId, String name, String description) {
            Map<String, Object> card =
                    postMap("cards", orderedMap("idList", listId, "name", name, "desc", description));
            return new CardRef(requiredText(card, "id"));
        }

        void moveCardToTop(String cardId) {
            putMap("cards/" + encodeSegment(cardId), Map.of("pos", "top"));
        }

        CardState cardState(String cardId) {
            Map<String, Object> card = getMap(
                    "cards/" + encodeSegment(cardId),
                    Map.of(
                            "fields",
                            "idList,name",
                            "actions",
                            "commentCard",
                            "actions_limit",
                            Integer.toString(TrelloClient.RECENT_COMMENT_ACTION_LIMIT)));
            Object actions = card.get("actions");
            List<?> comments = actions instanceof List<?> values ? values : List.of();
            long workpadCount =
                    comments.stream().filter(LiveTrelloClient::isWorkpadComment).count();
            return new CardState(requiredText(card, "idList"), comments.size(), workpadCount);
        }

        private static boolean isWorkpadComment(Object action) {
            if (!(action instanceof Map<?, ?> actionMap)) {
                return false;
            }
            Object data = actionMap.get("data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                return false;
            }
            Object text = dataMap.get("text");
            return text != null && text.toString().startsWith("## Codex Workpad");
        }

        void archiveBoard(String boardId) {
            putMap("boards/" + encodeSegment(boardId) + "/closed", Map.of("value", "true"));
        }

        private Map<String, Object> getMap(String path, Map<String, String> query) {
            return request("GET", path, query, MAP_TYPE);
        }

        private List<Map<String, Object>> getList(String path, Map<String, String> query) {
            return request("GET", path, query, LIST_MAP_TYPE);
        }

        private Map<String, Object> postMap(String path, Map<String, String> query) {
            return request("POST", path, query, MAP_TYPE);
        }

        private Map<String, Object> putMap(String path, Map<String, String> query) {
            return request("PUT", path, query, MAP_TYPE);
        }

        private <T> T request(String method, String path, Map<String, String> query, TypeReference<T> type) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path, query))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Authorization", TrelloClient.authorization(credentials.apiKey(), credentials.apiToken()));
            HttpRequest request =
                    switch (method) {
                        case "GET" -> builder.GET().build();
                        case "POST" ->
                            builder.POST(HttpRequest.BodyPublishers.noBody()).build();
                        case "PUT" ->
                            builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
                        default -> throw new IllegalArgumentException("Unsupported Trello method: " + method);
                    };
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AssertionError("Trello live E2E request failed with HTTP " + response.statusCode());
                }
                return json.readValue(response.body(), type);
            } catch (IOException e) {
                throw new AssertionError("Trello live E2E request failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Trello live E2E request interrupted", e);
            }
        }
    }

    private final class SymphonyProcess implements AutoCloseable {
        private final Process process;
        private final int port;

        private SymphonyProcess(Process process, int port) {
            this.process = process;
            this.port = port;
        }

        JsonNode state() {
            return getJson("http://127.0.0.1:" + port + "/api/v1/state");
        }

        void refresh() {
            post("http://127.0.0.1:" + port + "/api/v1/refresh");
        }

        boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private JsonNode getJson(String url) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return json.readTree(response.body());
            }
            throw new IllegalStateException("HTTP " + response.statusCode());
        } catch (IOException e) {
            throw new IllegalStateException("Status request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Status request interrupted", e);
        }
    }

    private void post(String url) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            checkState(response.statusCode() >= 200 && response.statusCode() < 300, "HTTP %s", response.statusCode());
        } catch (IOException e) {
            throw new IllegalStateException("Status refresh failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Status refresh interrupted", e);
        }
    }

    private static URI uri(String path, Map<String, String> query) {
        String queryString = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(TRELLO_ENDPOINT + "/" + path + (queryString.isBlank() ? "" : "?" + queryString));
    }

    private static Instant now() {
        return Instant.ofEpochMilli(System.currentTimeMillis());
    }

    private static Map<String, String> orderedMap(String... entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return values;
    }

    private static String requiredText(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        assertThat(raw).as("Trello response should contain %s", key).isNotNull();
        return raw.toString();
    }

    private static String encodeSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record CardRef(String id) {}

    private record CardState(String listId, int commentCount, long workpadCount) {}
}
