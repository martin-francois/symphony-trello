package ch.fmartin.symphony.trello.orchestrator;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolHandler;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.BlockerRef;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

final class SymphonyOrchestratorTestSupport {
    static final Instant COMMENT_TIME = Instant.parse("2026-01-01T00:00:00Z");
    static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    static UsageProbeScenario usageProbeScenario(Path workflow, MutableClock clock, Card first, Card second) {
        var tracker = new FakeTracker(List.of(first, second));
        var runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (runs.incrementAndGet() == 1) {
                return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
            }
            tracker.setCardState(
                    TestCards.card(request.card().id(), request.card().identifier(), "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        return new UsageProbeScenario(tracker, runs, orchestrator);
    }

    static RuntimeSnapshot awaitUsageProbeRecovery(UsageProbeScenario scenario) throws Exception {
        waitUntil(() ->
                scenario.runs().get() == 2 && scenario.orchestrator().snapshot().dispatchPause() == null);
        RuntimeSnapshot recovered = scenario.orchestrator().snapshot();
        scenario.orchestrator().stop();
        return recovered;
    }

    record UsageProbeScenario(FakeTracker tracker, AtomicInteger runs, SymphonyOrchestrator orchestrator) {}

    static ConcurrentCommandScenario concurrentCommandScenario(Path workflow) throws Exception {
        writeWorkflowWithCommand(
                workflow,
                "command-a",
                """
                agent:
                  max_concurrent_agents: 2
                """);
        Card first = TestCards.card("card-a", "TRELLO-a", "Todo");
        Card second = TestCards.card("card-b", "TRELLO-b", "Todo");
        var tracker = new FakeTracker(List.of(first));
        var requests = new PublishedAgentRequests();
        var releaseWorkers = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            requests.publish(request);
            assertThat(releaseWorkers.await(5, TimeUnit.SECONDS))
                    .as("the concurrent-command workers should be released within 5 seconds")
                    .isTrue();
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(
                workflow, tracker, runner, Clock.fixed(COMMENT_TIME, ZoneOffset.UTC), successfulWorkpadHandler());
        return new ConcurrentCommandScenario(first, second, tracker, requests, releaseWorkers, orchestrator);
    }

    record ConcurrentCommandScenario(
            Card first,
            Card second,
            FakeTracker tracker,
            PublishedAgentRequests requests,
            CountDownLatch releaseWorkers,
            SymphonyOrchestrator orchestrator) {}

    static final class PublishedAgentRequests {
        private final Map<String, CompletableFuture<AgentRunner.AgentRunRequest>> requests = new ConcurrentHashMap<>();

        void publish(AgentRunner.AgentRunRequest request) {
            requests.computeIfAbsent(request.config().codex().command(), ignored -> new CompletableFuture<>())
                    .complete(request);
        }

        AgentRunner.AgentRunRequest await(String command) {
            CompletableFuture<AgentRunner.AgentRunRequest> request =
                    requests.computeIfAbsent(command, ignored -> new CompletableFuture<>());
            assertThat(request)
                    .as("the %s request should be published within 5 seconds", command)
                    .succeedsWithin(Duration.ofSeconds(5));
            return request.join();
        }
    }

    static AgentRunner commandScopedUsageLimitRunner(Instant now, CountDownLatch commandBReturnedUsage) {
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.config().codex().command().equals("command-b")) {
                commandBReturnedUsage.countDown();
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Command B is exhausted.", Optional.of(now.plusSeconds(120)));
            }
            return AgentRunResult.codexUsageLimit(
                    "turn_failed: Command A is exhausted.", Optional.of(now.plusSeconds(60)));
        });
        return runner;
    }

    static AgentRunResult finishBoardBRun(
            ScopedTracker tracker,
            String endpoint,
            CountDownLatch workerBStarted,
            CountDownLatch releaseWorkerB,
            AgentRunner.AgentRunRequest request)
            throws InterruptedException {
        workerBStarted.countDown();
        assertThat(releaseWorkerB.await(5, TimeUnit.SECONDS))
                .as("the board B worker should be released within 5 seconds")
                .isTrue();
        tracker.setCardState(
                endpoint,
                "board-b",
                cardOnBoard(request.card().id(), request.card().identifier(), "Human Review", "board-b"));
        return AgentRunResult.ok();
    }

    static BlockingRun blockRunnerUntilCancelled(AgentRunner runner) {
        var started = new CountDownLatch(1);
        var cancelled = new AtomicInteger();
        doAnswer(invocation -> {
                    started.countDown();
                    try {
                        blockUntilInterruptedOrTimedOut(Duration.ofSeconds(30));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return AgentRunResult.fail("interrupted");
                })
                .when(runner)
                .run(any());
        doAnswer(invocation -> {
                    cancelled.incrementAndGet();
                    return null;
                })
                .when(runner)
                .cancel(any());
        return new BlockingRun(started, cancelled);
    }

    record BlockingRun(CountDownLatch started, AtomicInteger cancelled) {}

    static String dispatchFirstCard(Path workflow, FakeTracker tracker) throws Exception {
        var dispatched = new AtomicReference<String>();
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    dispatched.set(request.card().identifier());
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.start();
        waitUntil(() -> dispatched.get() != null);
        orchestrator.stop();
        return dispatched.get();
    }

    static Card cardWithPriorityAndBlockers(String id, String identifier, Integer priority, List<BlockerRef> blockers) {
        return cardWithPriorityBlockersAndProblems(id, identifier, priority, blockers, List.of());
    }

    static Card cardWithPriorityBlockersAndProblems(
            String id,
            String identifier,
            Integer priority,
            List<BlockerRef> blockers,
            List<Card.PrerequisiteProblem> prerequisiteProblems) {
        return new Card(
                id,
                identifier,
                "Implement feature",
                "Description",
                priority,
                "Todo",
                "list",
                "list-todo",
                "Todo",
                false,
                "board-1",
                false,
                false,
                1,
                identifier,
                "https://trello.com/c/" + identifier,
                null,
                "https://trello.com/c/" + identifier,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                prerequisiteProblems,
                blockers,
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                null,
                false,
                BigDecimal.ONE);
    }

    static void writeWorkflow(Path workflow, String pollIntervalMs) throws Exception {
        writeWorkflow(workflow, pollIntervalMs, "");
    }

    static void writeWorkflow(Path workflow, String pollIntervalMs, String extraConfig) throws Exception {
        writeWorkflow(workflow, pollIntervalMs, extraConfig, "{{ card.title }}");
    }

    static void writeWorkflow(Path workflow, String pollIntervalMs, String extraConfig, String prompt)
            throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo]
                workspace:
                  root: work
                polling:
                  interval_ms: %s
                %s
                codex:
                  command: fake
                ---
                %s
                """
                        .formatted(pollIntervalMs, extraConfig, prompt));
    }

    static void writeWorkflowWithCommand(Path workflow, String command, String extraConfig) throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                %s
                codex:
                  command: %s
                ---
                {{ card.title }}
                """
                        .formatted(extraConfig, command));
    }

    static void rewriteWorkflowWithCommand(Path workflow, String command, String extraConfig) throws Exception {
        long previousModified = Files.getLastModifiedTime(workflow).toMillis();
        writeWorkflowWithCommand(workflow, command, extraConfig);
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(previousModified + 2_000));
    }

    static void writeWorkflowWithCommandAndBoard(Path workflow, String command, String boardId, String apiToken)
            throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: %s
                  board_id: %s
                  active_states: [Todo]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: %s
                ---
                {{ card.title }}
                """
                        .formatted(apiToken, boardId, command));
    }

    static void rewriteWorkflowWithCommandAndBoard(Path workflow, String command, String boardId, String apiToken)
            throws Exception {
        long previousModified = Files.getLastModifiedTime(workflow).toMillis();
        writeWorkflowWithCommandAndBoard(workflow, command, boardId, apiToken);
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(previousModified + 2_000));
    }

    static void writeWorkflowWithTarget(
            Path workflow, String endpoint, String boardId, String apiToken, String command, String workspaceRoot)
            throws Exception {
        writeWorkflowWithTarget(workflow, endpoint, boardId, apiToken, command, workspaceRoot, 60_000, 1);
    }

    static void writeWorkflowWithTarget(
            Path workflow,
            String endpoint,
            String boardId,
            String apiToken,
            String command,
            String workspaceRoot,
            long maxRetryBackoffMillis,
            int maxConcurrentAgents)
            throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  endpoint: %s
                  api_key: key
                  api_token: %s
                  board_id: %s
                  active_states: [Todo, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: %s
                polling:
                  interval_ms: 60000
                agent:
                  max_retry_backoff_ms: %d
                  max_concurrent_agents: %d
                codex:
                  command: %s
                ---
                {{ card.title }}
                """
                        .formatted(
                                endpoint,
                                apiToken,
                                boardId,
                                workspaceRoot,
                                maxRetryBackoffMillis,
                                maxConcurrentAgents,
                                command));
    }

    static void rewriteWorkflowWithTarget(
            Path workflow, String endpoint, String boardId, String apiToken, String command, String workspaceRoot)
            throws Exception {
        rewriteWorkflowWithTarget(workflow, endpoint, boardId, apiToken, command, workspaceRoot, 60_000, 1);
    }

    static void rewriteWorkflowWithTarget(
            Path workflow,
            String endpoint,
            String boardId,
            String apiToken,
            String command,
            String workspaceRoot,
            long maxRetryBackoffMillis,
            int maxConcurrentAgents)
            throws Exception {
        long previousModified = Files.getLastModifiedTime(workflow).toMillis();
        writeWorkflowWithTarget(
                workflow,
                endpoint,
                boardId,
                apiToken,
                command,
                workspaceRoot,
                maxRetryBackoffMillis,
                maxConcurrentAgents);
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(previousModified + 2_000));
    }

    static void writeWorkflowWithStallTimeout(Path workflow, long stallTimeoutMillis) throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: command
                  stall_timeout_ms: %d
                ---
                {{ card.title }}
                """
                        .formatted(stallTimeoutMillis));
    }

    static void rewriteWorkflowWithStallTimeout(Path workflow, long stallTimeoutMillis) throws Exception {
        long previousModified = Files.getLastModifiedTime(workflow).toMillis();
        writeWorkflowWithStallTimeout(workflow, stallTimeoutMillis);
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(previousModified + 2_000));
    }

    static TrelloHandoffToolHandler recordingWorkpads(List<String> events, Predicate<String> updateResult) {
        TrelloHandoffToolHandler workpads = mock();
        when(workpads.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    EffectiveConfig owner = invocation.getArgument(0);
                    String section = invocation.getArgument(2);
                    String event =
                            owner.tracker().endpoint() + "/" + owner.tracker().resolvedBoardId() + "/"
                                    + owner.tracker().apiToken() + (section == null ? ":clear" : ":set");
                    events.add(event);
                    return updateResult.test(event);
                });
        return workpads;
    }

    static String targetLabel(String endpoint, String boardId) {
        return endpoint + "|" + boardId;
    }

    static AgentEvent rateLimitEvent(AgentRunner.AgentRunRequest request, Instant timestamp, JsonNode payload) {
        return new AgentEvent(
                "account/rateLimits/updated",
                timestamp,
                request.workerIdentity(),
                123L,
                "thread-1",
                "turn-1",
                "rate limits",
                Map.of(),
                payload);
    }

    static AgentEvent agentEvent(AgentRunner.AgentRunRequest request, String event, String message) {
        return new AgentEvent(
                event,
                COMMENT_TIME,
                request.workerIdentity(),
                123L,
                "thread-1",
                "turn-1",
                message,
                Map.of(),
                new ObjectMapper().createObjectNode());
    }

    static Card cardOnBoard(String id, String identifier, String state, String boardId) {
        return new Card(
                id,
                identifier,
                "Implement feature",
                "Description",
                null,
                state,
                "list",
                "list-todo",
                state,
                false,
                boardId,
                false,
                false,
                1,
                identifier,
                "https://trello.com/c/" + identifier,
                null,
                "https://trello.com/c/" + identifier,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                null,
                false,
                BigDecimal.ONE);
    }

    static void writeRetryableInProgressWorkflow(Path workflow) throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                agent:
                  max_retry_backoff_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.state }}
                """);
    }

    static void assertCardReleasedForRetry(RuntimeSnapshot snapshot, FakeTracker tracker) {
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-abc"));
        assertThat(tracker.releasedCards).containsExactly("TRELLO-abc");
        assertThat(tracker.cardState("card-1").state()).isEqualTo("Todo");
    }

    record ReleaseRecord(String currentState, String sourceState) {}

    static SymphonyOrchestrator orchestrator(Path workflow, TrackerClient tracker, AgentRunner runner) {
        return orchestrator(workflow, tracker, runner, new WorkspaceManager(new HookRunner()));
    }

    static SymphonyOrchestrator orchestrator(
            Path workflow, TrackerClient tracker, AgentRunner runner, WorkspaceManager workspaces) {
        var orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(), new ConfigResolver(), tracker, runner, new PromptRenderer(), workspaces);
        orchestrator.workflowPath = workflow;
        return orchestrator;
    }

    static SymphonyOrchestrator orchestrator(
            Path workflow,
            TrackerClient tracker,
            AgentRunner runner,
            Clock clock,
            TrelloHandoffToolHandler usageWorkpads) {
        var orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()),
                clock,
                usageWorkpads);
        orchestrator.workflowPath = workflow;
        return orchestrator;
    }

    static TrelloHandoffToolHandler successfulWorkpadHandler() {
        TrelloHandoffToolHandler handler = mock();
        when(handler.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenReturn(true);
        return handler;
    }

    static RuntimeSnapshot runOnceAndSnapshotIdle(SymphonyOrchestrator orchestrator, AtomicInteger runs)
            throws Exception {
        orchestrator.start();
        waitUntil(() -> runs.get() == 1
                && orchestrator.snapshot().counts().running() == 0
                && orchestrator.snapshot().counts().retrying() == 0);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();
        return snapshot;
    }

    static void waitUntil(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            pollDelayForBoundedConditionWait();
        }
        throw new AssertionError("Condition was not met before timeout");
    }

    static boolean containsRetryingCard(RuntimeSnapshot snapshot, String cardIdentifier) {
        return snapshot.retrying().stream().anyMatch(row -> row.cardIdentifier().equals(cardIdentifier));
    }

    static void pollDelayForBoundedConditionWait() throws InterruptedException {
        Thread.sleep(POLL_INTERVAL);
    }

    static void blockUntilInterruptedOrTimedOut(Duration timeout) throws InterruptedException {
        new CountDownLatch(1).await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @FunctionalInterface
    interface Condition {
        boolean matches();
    }

    record ScopedRelease(String endpoint, String boardId, Path workspaceRoot, String cardId) {}

    static final class ScopedTracker implements TrackerClient {
        final Map<String, List<Card>> candidatesByTarget = new ConcurrentHashMap<>();
        final Map<String, Map<String, CardLookupResult>> statesByTarget = new ConcurrentHashMap<>();
        final List<String> candidateFetchTargets = new CopyOnWriteArrayList<>();
        final List<String> stateFetchTargets = new CopyOnWriteArrayList<>();
        final List<ScopedRelease> releases = new CopyOnWriteArrayList<>();

        void setCandidates(String endpoint, String boardId, List<Card> candidates) {
            String target = targetLabel(endpoint, boardId);
            candidatesByTarget.put(target, List.copyOf(candidates));
            Map<String, CardLookupResult> states = new ConcurrentHashMap<>();
            candidates.forEach(card -> states.put(card.id(), new CardLookupResult.Found(card)));
            statesByTarget.put(target, states);
        }

        void setCardState(String endpoint, String boardId, Card card) {
            states(endpoint, boardId).put(card.id(), new CardLookupResult.Found(card));
        }

        void removeCardState(String endpoint, String boardId, String cardId) {
            states(endpoint, boardId).remove(cardId);
        }

        Map<String, CardLookupResult> states(String endpoint, String boardId) {
            return statesByTarget.computeIfAbsent(targetLabel(endpoint, boardId), ignored -> new ConcurrentHashMap<>());
        }

        static String target(EffectiveConfig config) {
            return targetLabel(config.tracker().endpoint(), config.tracker().resolvedBoardId());
        }

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            return config.tracker().boardId();
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            String target = target(config);
            candidateFetchTargets.add(target);
            return candidatesByTarget.getOrDefault(target, List.of());
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            String target = target(config);
            stateFetchTargets.add(target);
            Map<String, CardLookupResult> states = statesByTarget.getOrDefault(target, Map.of());
            Map<String, CardLookupResult> result = new LinkedHashMap<>();
            cardIds.forEach(
                    cardId -> result.put(cardId, states.getOrDefault(cardId, new CardLookupResult.Missing(cardId))));
            return result;
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesForPromptByIds(
                EffectiveConfig config, List<String> cardIds) {
            return fetchCardStatesByIds(config, cardIds);
        }

        @Override
        public Card prepareForDispatch(EffectiveConfig config, Card card) {
            String state = config.tracker().inProgressState().isBlank()
                    ? card.state()
                    : config.tracker().inProgressState();
            Card prepared = cardOnBoard(
                    card.id(), card.identifier(), state, config.tracker().resolvedBoardId());
            setCardState(config.tracker().endpoint(), config.tracker().resolvedBoardId(), prepared);
            return prepared;
        }

        @Override
        public void releaseFromDispatch(EffectiveConfig config, Card card, Card dispatchSource) {
            releases.add(new ScopedRelease(
                    config.tracker().endpoint(),
                    config.tracker().resolvedBoardId(),
                    config.workspace().root(),
                    card.id()));
            Card released = cardOnBoard(
                    card.id(),
                    card.identifier(),
                    dispatchSource.state(),
                    config.tracker().resolvedBoardId());
            setCardState(config.tracker().endpoint(), config.tracker().resolvedBoardId(), released);
        }
    }

    static final class FakeTracker implements TrackerClient {
        volatile List<Card> candidates;
        volatile Map<String, CardLookupResult> cardStates;
        volatile Map<String, CardLookupResult> promptCardStateOverrides = Map.of();
        final AtomicInteger candidateFetches = new AtomicInteger();
        final AtomicInteger boardResolutions = new AtomicInteger();
        final AtomicInteger stateFetches = new AtomicInteger();
        final AtomicInteger promptStateFetches = new AtomicInteger();
        final AtomicInteger prepareForDispatchCalls = new AtomicInteger();
        final List<String> releasedCards = new CopyOnWriteArrayList<>();
        final List<ReleaseRecord> releases = new CopyOnWriteArrayList<>();
        volatile Card preparedCard;
        volatile RuntimeException resolveBoardIdFailure;
        volatile RuntimeException prepareForDispatchFailure;
        volatile Runnable stateFetchHook = () -> {};
        volatile boolean resolveConfiguredBoardId;

        FakeTracker(List<Card> candidates) {
            setCandidates(candidates);
        }

        void setCandidates(List<Card> candidates) {
            setCandidateList(candidates);
            this.cardStates = candidates.stream().collect(toImmutableMap(Card::id, CardLookupResult.Found::new));
        }

        void setCandidateList(List<Card> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        void setCardState(Card card) {
            Map<String, CardLookupResult> updated = new LinkedHashMap<>(this.cardStates);
            updated.put(card.id(), new CardLookupResult.Found(card));
            this.cardStates = updated;
        }

        void setPromptCardState(Card card) {
            Map<String, CardLookupResult> updated = new LinkedHashMap<>(this.promptCardStateOverrides);
            updated.put(card.id(), new CardLookupResult.Found(card));
            this.promptCardStateOverrides = Map.copyOf(updated);
        }

        Card cardState(String cardId) {
            return ((CardLookupResult.Found) cardStates.get(cardId)).card();
        }

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            boardResolutions.incrementAndGet();
            if (resolveBoardIdFailure != null) {
                throw resolveBoardIdFailure;
            }
            return resolveConfiguredBoardId ? config.tracker().boardId() : "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            candidateFetches.incrementAndGet();
            return candidates;
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            stateFetches.incrementAndGet();
            stateFetchHook.run();
            return cardStates.entrySet().stream()
                    .filter(entry -> cardIds.contains(entry.getKey()))
                    .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesForPromptByIds(
                EffectiveConfig config, List<String> cardIds) {
            promptStateFetches.incrementAndGet();
            Map<String, CardLookupResult> results = new LinkedHashMap<>(fetchCardStatesByIds(config, cardIds));
            cardIds.forEach(cardId -> {
                CardLookupResult promptOverride = promptCardStateOverrides.get(cardId);
                if (promptOverride != null) {
                    results.put(cardId, promptOverride);
                }
            });
            return Map.copyOf(results);
        }

        @Override
        public Card prepareForDispatch(EffectiveConfig config, Card card) {
            prepareForDispatchCalls.incrementAndGet();
            if (preparedCard == null) {
                return card;
            }
            setCardState(preparedCard);
            if (prepareForDispatchFailure != null) {
                throw prepareForDispatchFailure;
            }
            return preparedCard;
        }

        @Override
        public void releaseFromDispatch(EffectiveConfig config, Card card) {
            releaseFromDispatch(config, card, card);
        }

        @Override
        public void releaseFromDispatch(EffectiveConfig config, Card card, Card dispatchSource) {
            releases.add(new ReleaseRecord(card.state(), dispatchSource.state()));
            releasedCards.add(card.identifier());
            String releaseState = card.state().equals(dispatchSource.state()) ? "Todo" : dispatchSource.state();
            setCardState(TestCards.card(card.id(), card.identifier(), releaseState));
        }
    }

    static final class ThrowingWorkspaceManager extends WorkspaceManager {
        final AtomicInteger removalAttempts = new AtomicInteger();

        ThrowingWorkspaceManager() {
            super(new HookRunner());
        }

        @Override
        public void removeForIdentifierIfPresent(String cardIdentifier, EffectiveConfig config) {
            removalAttempts.incrementAndGet();
            throw new IllegalStateException("workspace cleanup failed");
        }
    }

    static final class StartBlockingTracker implements TrackerClient {
        final CountDownLatch terminalFetchStarted = new CountDownLatch(1);
        final CountDownLatch releaseTerminalFetch = new CountDownLatch(1);

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            return "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            terminalFetchStarted.countDown();
            try {
                assertThat(releaseTerminalFetch.await(5, TimeUnit.SECONDS))
                        .as("the blocked terminal-card fetch should be released within 5 seconds")
                        .isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            return Map.of();
        }
    }

    static final class BlockingTracker implements TrackerClient {
        final AtomicInteger candidateFetches = new AtomicInteger();
        final CountDownLatch firstFetchStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstFetch = new CountDownLatch(1);

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            return "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            if (candidateFetches.incrementAndGet() == 1) {
                firstFetchStarted.countDown();
                try {
                    assertThat(releaseFirstFetch.await(5, TimeUnit.SECONDS))
                            .as("the first blocked candidate-card fetch should be released within 5 seconds")
                            .isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return List.of();
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            return Map.of();
        }
    }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;
        final ZoneId zone;

        MutableClock(Instant now) {
            this(now, ZoneOffset.UTC);
        }

        MutableClock(Instant now, ZoneId zone) {
            this.now = new AtomicReference<>(now);
            this.zone = zone;
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private SymphonyOrchestratorTestSupport() {}
}
