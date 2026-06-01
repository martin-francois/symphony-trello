package ch.fmartin.symphony.trello.orchestrator;

import static com.google.common.base.Preconditions.checkState;

import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SymphonyOrchestrator {
    private static final Logger LOG = Logger.getLogger(SymphonyOrchestrator.class);
    private static final int IGNORED_WORKER_LIMIT = 1_000;
    private static final Duration IGNORED_WORKER_TTL = Duration.ofMinutes(30);
    private static final Duration CONTINUATION_DELAY = Duration.ofSeconds(1);

    private final WorkflowLoader workflowLoader;
    private final ConfigResolver configResolver;
    private final TrackerClient tracker;
    private final AgentRunner agentRunner;
    private final PromptRenderer prompts;
    private final WorkspaceManager workspaces;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RunningEntry> running = new LinkedHashMap<>();
    private final Map<String, RetryEntry> retryAttempts = new LinkedHashMap<>();
    private final Set<String> claimed = new HashSet<>();
    private final Set<String> completed = new HashSet<>();
    private final LinkedHashMap<String, Instant> ignoredWorkers = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<CardDebugDetails.EventInfo>> recentEvents = new HashMap<>();

    private volatile EffectiveConfig config;
    private WorkflowDefinition workflow;
    private Instant workflowLastModified;
    private ScheduledFuture<?> tickTimer;
    private WatchService workflowWatchService;
    private long endedRuntimeMillis;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;
    private Object rateLimits;
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicBoolean workflowReloadRequested = new AtomicBoolean();
    private volatile boolean tickRunning;
    private boolean started;

    @ConfigProperty(name = "symphony.workflow.path")
    Path workflowPath;

    public SymphonyOrchestrator(
            WorkflowLoader workflowLoader,
            ConfigResolver configResolver,
            TrackerClient tracker,
            AgentRunner agentRunner,
            PromptRenderer prompts,
            WorkspaceManager workspaces) {
        this(workflowLoader, configResolver, tracker, agentRunner, prompts, workspaces, ApplicationClock.systemUtc());
    }

    @Inject
    public SymphonyOrchestrator(
            WorkflowLoader workflowLoader,
            ConfigResolver configResolver,
            TrackerClient tracker,
            AgentRunner agentRunner,
            PromptRenderer prompts,
            WorkspaceManager workspaces,
            Clock clock) {
        this.workflowLoader = workflowLoader;
        this.configResolver = configResolver;
        this.tracker = tracker;
        this.agentRunner = agentRunner;
        this.prompts = prompts;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        reloadOrThrow();
        startWorkflowWatcher();
        startupTerminalWorkspaceCleanup();
        started = true;
        scheduleTick(Duration.ZERO);
    }

    public synchronized void stop() {
        if (tickTimer != null) {
            tickTimer.cancel(false);
        }
        stopWorkflowWatcher();
        running.values().forEach(entry -> {
            agentRunner.cancel(entry.workerIdentity);
            cancelWorkerTask(entry);
        });
        scheduler.shutdownNow();
        workers.shutdownNow();
        started = false;
    }

    public synchronized void setWorkflowPath(Path workflowPath) {
        checkState(!started, "Workflow path cannot be changed after orchestrator start");
        this.workflowPath = workflowPath;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized Path selectedWorkflowPath() {
        Path selected = config == null ? workflowPath : config.workflowPath();
        return selected.toAbsolutePath().normalize();
    }

    public synchronized String selectedBoardId() {
        return config == null ? null : config.tracker().resolvedBoardId();
    }

    public synchronized String selectedConfiguredBoardId() {
        return config == null ? null : config.tracker().boardId();
    }

    public void requestRefresh() {
        refreshRequested.set(true);
        if (tickRunning) {
            return;
        }
        synchronized (this) {
            if (started && !tickRunning) {
                scheduleTick(Duration.ZERO);
            }
        }
    }

    private boolean consumeRefreshRequest() {
        return refreshRequested.getAndSet(false);
    }

    private void requestRefreshAfterCurrentTickIfNeeded() {
        if (consumeRefreshRequest()) {
            scheduleTick(Duration.ZERO);
        } else {
            scheduleTick(config.polling().interval());
        }
    }

    public void tickNowForTests() {
        tick();
    }

    private void tick() {
        synchronized (this) {
            if (!started) {
                return;
            }
            tickRunning = true;
            consumeRefreshRequest();
            reloadIfChanged();
            reconcileRunningCards();
            try {
                configResolver.validateForDispatch(config);
                List<Card> candidates = tracker.fetchCandidateCards(config).stream()
                        .sorted(TrelloClient.dispatchComparator(config))
                        .toList();
                Set<String> releasedCards = releaseIdleInProgressOverflow(candidates);
                for (Card card : candidates) {
                    if (releasedCards.contains(card.id())) {
                        continue;
                    }
                    if (availableSlots() <= 0) {
                        break;
                    }
                    if (shouldDispatch(card, false)) {
                        refreshForDispatch(card).ifPresent(refreshed -> dispatch(refreshed, null));
                    }
                }
            } catch (RuntimeException e) {
                LOG.errorf("dispatch outcome=skipped reason=%s", e.getMessage());
            } finally {
                tickRunning = false;
                requestRefreshAfterCurrentTickIfNeeded();
            }
        }
    }

    private void reloadOrThrow() {
        workflow = workflowLoader.load(workflowPath);
        config = resolveBoardScope(configResolver.resolve(workflow));
        configResolver.validateForDispatch(config);
        workflowLastModified = lastModified(workflow.path());
        if (config.tracker().activeListIds().isEmpty()
                && workflow.config().get("tracker") instanceof Map<?, ?> trackerMap) {
            if (!trackerMap.containsKey("active_states")) {
                LOG.warn(
                        "tracker active selection uses starter defaults; configure active_states or active_list_ids for production");
            }
        }
        LOG.infof("workflow=%s outcome=loaded", workflow.path());
    }

    private EffectiveConfig resolveBoardScope(EffectiveConfig raw) {
        String resolvedBoardId = tracker.resolveBoardId(raw);
        return raw.withResolvedBoardId(resolvedBoardId);
    }

    private void reloadIfChanged() {
        Instant modified = lastModified(config.workflowPath());
        boolean forced = workflowReloadRequested.getAndSet(false);
        if (!forced && workflowLastModified != null && !modified.isAfter(workflowLastModified)) {
            return;
        }
        try {
            WorkflowDefinition nextWorkflow = workflowLoader.load(config.workflowPath());
            EffectiveConfig nextConfig = resolveBoardScope(configResolver.resolve(nextWorkflow));
            configResolver.validateForDispatch(nextConfig);
            workflow = nextWorkflow;
            config = nextConfig;
            workflowLastModified = modified;
            LOG.infof("workflow=%s outcome=reloaded", nextWorkflow.path());
        } catch (RuntimeException e) {
            LOG.errorf("workflow=%s outcome=reload_failed reason=%s", config.workflowPath(), e.getMessage());
        }
    }

    private static Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private void startWorkflowWatcher() {
        stopWorkflowWatcher();
        Path parent = config.workflowPath().getParent();
        if (parent == null) {
            return;
        }
        try {
            workflowWatchService = parent.getFileSystem().newWatchService();
            parent.register(
                    workflowWatchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            Thread.ofVirtual().name("symphony-workflow-watch").start(this::watchWorkflowChanges);
        } catch (IOException e) {
            LOG.warnf("workflow=%s watcher=disabled reason=%s", config.workflowPath(), e.getMessage());
        }
    }

    private void stopWorkflowWatcher() {
        if (workflowWatchService != null) {
            try {
                workflowWatchService.close();
            } catch (IOException e) {
                LOG.debugf("workflow_watcher outcome=close_failed reason=%s", e.getMessage());
            }
            workflowWatchService = null;
        }
    }

    private void watchWorkflowChanges() {
        Path fileName = config.workflowPath().getFileName();
        while (workflowWatchService != null) {
            try {
                WatchKey key = workflowWatchService.take();
                boolean changed = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path changedPath && changedPath.equals(fileName)) {
                        changed = true;
                    }
                }
                key.reset();
                if (changed) {
                    workflowReloadRequested.set(true);
                    requestRefresh();
                }
            } catch (ClosedWatchServiceException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.warnf("workflow_watcher outcome=failed reason=%s", e.getMessage());
                return;
            }
        }
    }

    private void startupTerminalWorkspaceCleanup() {
        try {
            tracker.fetchTerminalCards(config)
                    .forEach(card -> workspaces.removeForIdentifierIfPresent(card.identifier(), config));
        } catch (RuntimeException e) {
            LOG.warnf("startup_cleanup outcome=failed reason=%s", e.getMessage());
        }
    }

    private void reconcileRunningCards() {
        removeExpiredIgnoredWorkers();
        reconcileStalls();
        if (running.isEmpty()) {
            return;
        }
        List<String> cardIds = List.copyOf(running.keySet());
        Map<String, CardLookupResult> refreshed;
        try {
            refreshed = tracker.fetchCardStatesByIds(config, cardIds);
        } catch (RuntimeException e) {
            LOG.warnf("reconcile outcome=state_refresh_failed reason=%s", e.getMessage());
            return;
        }
        for (String cardId : cardIds) {
            CardLookupResult result = refreshed.get(cardId);
            if (result instanceof CardLookupResult.Missing) {
                terminateRunning(cardId, true, true, "card missing");
            } else if (result instanceof CardLookupResult.Failed failed) {
                LOG.debugf("card_id=%s reconcile outcome=partial_refresh_failed reason=%s", cardId, failed.message());
            } else if (result instanceof CardLookupResult.Found found) {
                Card card = found.card();
                if (isOutOfBoardScope(card)) {
                    terminateRunning(card.id(), false, true, "card moved out of board");
                } else if (TrelloClient.isTerminal(card, config)) {
                    terminateRunning(card.id(), true, true, "card terminal");
                } else if (TrelloClient.isActive(card, config)) {
                    RunningEntry entry = running.get(card.id());
                    if (entry != null) {
                        entry.card = card;
                    }
                } else {
                    terminateRunning(card.id(), false, true, "card no longer active");
                }
            }
        }
    }

    private void reconcileStalls() {
        if (config.codex().stallTimeout().isZero()
                || config.codex().stallTimeout().isNegative()) {
            return;
        }
        Instant now = clock.instant();
        List<String> stalled = running.values().stream()
                .filter(entry -> Duration.between(entry.lastEventAt, now)
                                .compareTo(config.codex().stallTimeout())
                        > 0)
                .map(entry -> entry.cardId)
                .toList();
        stalled.forEach(cardId -> terminateRunning(cardId, false, false, "stalled"));
    }

    private void terminateRunning(String cardId, boolean cleanupWorkspace, boolean suppressRetry, String reason) {
        RunningEntry entry = running.remove(cardId);
        if (entry == null) {
            return;
        }
        ignoredWorkers.put(entry.workerIdentity, clock.instant().plus(IGNORED_WORKER_TTL));
        trimIgnoredWorkers();
        agentRunner.cancel(entry.workerIdentity);
        cancelWorkerTask(entry);
        addRuntime(entry);
        if (cleanupWorkspace) {
            workspaces.removeForIdentifierIfPresent(entry.identifier(), config);
        }
        if (suppressRetry) {
            claimed.remove(cardId);
        } else {
            releaseCurrentFromDispatch(entry.card, reason);
            scheduleRetry(cardId, nextAttempt(entry.retryAttempt), entry.identifier(), reason, false);
        }
        LOG.infof("card_id=%s card_identifier=%s outcome=terminated reason=%s", cardId, entry.identifier(), reason);
    }

    private void dispatch(Card card, Integer attempt) {
        claimed.add(card.id());
        RetryEntry retry = retryAttempts.remove(card.id());
        if (retry != null) {
            retry.timer().cancel(false);
        }
        Card dispatchCard;
        try {
            dispatchCard = tracker.prepareForDispatch(config, card);
        } catch (RuntimeException e) {
            claimed.remove(card.id());
            releaseCurrentFromDispatch(card, "prepare for dispatch failed");
            scheduleRetry(card.id(), nextAttempt(attempt), card.identifier(), e.getMessage(), false);
            return;
        }
        String workerIdentity = UUID.randomUUID().toString();
        RunningEntry entry = new RunningEntry(dispatchCard, workerIdentity, attempt, clock.instant());
        running.put(dispatchCard.id(), entry);
        String prompt;
        try {
            prompt = prompts.render(workflow.promptTemplate(), dispatchCard, attempt);
        } catch (RuntimeException e) {
            running.remove(dispatchCard.id());
            releaseFromDispatch(dispatchCard, "prompt render failed");
            scheduleRetry(dispatchCard.id(), nextAttempt(attempt), dispatchCard.identifier(), e.getMessage(), false);
            return;
        }
        entry.workerTask = workers.submit(() -> {
            AgentRunResult result = agentRunner.run(new AgentRunner.AgentRunRequest(
                    dispatchCard, attempt, prompt, config, workerIdentity, this::onAgentEvent));
            onWorkerExit(dispatchCard.id(), workerIdentity, result);
        });
        LOG.infof(
                "card_id=%s card_identifier=%s worker_identity=%s outcome=dispatched",
                dispatchCard.id(), dispatchCard.identifier(), workerIdentity);
    }

    private Optional<Card> refreshForDispatch(Card card) {
        try {
            CardLookupResult result = tracker.fetchCardStatesForPromptByIds(config, List.of(card.id()))
                    .get(card.id());
            if (result instanceof CardLookupResult.Found found && shouldDispatch(found.card(), false)) {
                return Optional.of(found.card());
            }
            if (result instanceof CardLookupResult.Failed failed) {
                LOG.warnf(
                        "card_id=%s card_identifier=%s dispatch=skipped reason=%s",
                        card.id(), card.identifier(), failed.message());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.warnf("card_id=%s card_identifier=%s dispatch=skipped reason=%s", card.id(), card.identifier(), e);
            return Optional.empty();
        }
    }

    private synchronized void onWorkerExit(String cardId, String workerIdentity, AgentRunResult result) {
        Optional<RunningEntry> current = currentRunningEntry(cardId, workerIdentity);
        if (current.isEmpty()) {
            return;
        }
        RunningEntry entry = current.get();
        running.remove(cardId);
        addRuntime(entry);
        if (result.success()) {
            completed.add(cardId);
            scheduleRetry(cardId, 1, entry.identifier(), null, true);
        } else {
            releaseCurrentFromDispatch(entry.card, result.reason());
            scheduleRetry(cardId, nextAttempt(entry.retryAttempt), entry.identifier(), result.reason(), false);
        }
    }

    private static void cancelWorkerTask(RunningEntry entry) {
        if (entry.workerTask != null) {
            entry.workerTask.cancel(true);
        }
    }

    private synchronized void onAgentEvent(AgentEvent event) {
        String cardId = running.values().stream()
                .filter(entry -> entry.workerIdentity.equals(event.workerIdentity()))
                .map(entry -> entry.cardId)
                .findAny()
                .orElse(null);
        if (cardId == null) {
            return;
        }
        Optional<RunningEntry> current = currentRunningEntry(cardId, event.workerIdentity());
        if (current.isEmpty()) {
            return;
        }
        RunningEntry entry = current.get();
        entry.lastEvent = event.event();
        entry.lastMessage = event.message();
        entry.lastEventAt = event.timestamp();
        entry.threadId = event.threadId() == null ? entry.threadId : event.threadId();
        entry.turnId = event.turnId() == null ? entry.turnId : event.turnId();
        if (entry.threadId != null && entry.turnId != null) {
            entry.sessionId = entry.threadId + "-" + entry.turnId;
        }
        if ("turn/started".equals(event.event())
                || "session_started".equals(event.event())
                || "session_continued".equals(event.event())) {
            entry.turnCount++;
        }
        applyUsage(entry, event.usage());
        if ("account/rateLimits/updated".equals(event.event())) {
            rateLimits = event.payload();
        }
        addRecentEvent(cardId, new CardDebugDetails.EventInfo(event.timestamp(), event.event(), event.message()));
    }

    private Optional<RunningEntry> currentRunningEntry(String cardId, String workerIdentity) {
        removeExpiredIgnoredWorkers();
        if (ignoredWorkers.remove(workerIdentity) != null) {
            LOG.debugf("card_id=%s worker_identity=%s outcome=ignored_stale_worker", cardId, workerIdentity);
            return Optional.empty();
        }
        RunningEntry entry = running.get(cardId);
        if (entry == null || !entry.workerIdentity.equals(workerIdentity)) {
            LOG.debugf("card_id=%s worker_identity=%s outcome=ignored_unknown_worker", cardId, workerIdentity);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private void applyUsage(RunningEntry entry, Map<String, Long> usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        long input = usage.getOrDefault("input_tokens", 0L);
        long output = usage.getOrDefault("output_tokens", 0L);
        long total = usage.getOrDefault("total_tokens", input + output);
        totalInputTokens += Math.max(0, input - entry.lastReportedInputTokens);
        totalOutputTokens += Math.max(0, output - entry.lastReportedOutputTokens);
        totalTokens += Math.max(0, total - entry.lastReportedTotalTokens);
        entry.lastReportedInputTokens = input;
        entry.lastReportedOutputTokens = output;
        entry.lastReportedTotalTokens = total;
        entry.inputTokens = input;
        entry.outputTokens = output;
        entry.totalTokens = total;
    }

    private void scheduleRetry(String cardId, int attempt, String identifier, String error, boolean continuation) {
        Duration delay = continuation ? CONTINUATION_DELAY : backoff(attempt);
        ScheduledFuture<?> timer =
                scheduler.schedule(() -> onRetryTimer(cardId), delay.toMillis(), TimeUnit.MILLISECONDS);
        retryAttempts.put(
                cardId,
                new RetryEntry(cardId, identifier, attempt, clock.instant().plus(delay), timer, error));
        claimed.add(cardId);
        LOG.infof(
                "card_id=%s card_identifier=%s outcome=retrying attempt=%d delay_ms=%d",
                cardId, identifier, attempt, delay.toMillis());
    }

    private synchronized void onRetryTimer(String cardId) {
        RetryEntry retry = retryAttempts.remove(cardId);
        if (retry == null) {
            return;
        }
        Map<String, CardLookupResult> refreshed;
        try {
            refreshed = tracker.fetchCardStatesByIds(config, List.of(cardId));
        } catch (RuntimeException e) {
            scheduleRetry(cardId, retry.attempt() + 1, retry.identifier(), "retry card refresh failed", false);
            return;
        }
        CardLookupResult result = refreshed.get(cardId);
        if (result instanceof CardLookupResult.Missing) {
            claimed.remove(cardId);
            workspaces.removeForIdentifierIfPresent(retry.identifier(), config);
        } else if (result instanceof CardLookupResult.Failed) {
            scheduleRetry(cardId, retry.attempt() + 1, retry.identifier(), "retry card refresh failed", false);
        } else if (result instanceof CardLookupResult.Found found) {
            Card card = found.card();
            if (isOutOfBoardScope(card)) {
                claimed.remove(cardId);
            } else if (TrelloClient.isTerminal(card, config)) {
                claimed.remove(cardId);
                workspaces.removeForIdentifierIfPresent(card.identifier(), config);
            } else if (!TrelloClient.isActive(card, config)) {
                claimed.remove(cardId);
            } else if (!shouldDispatchIgnoringSlots(card, true)) {
                releaseFromDispatch(card, "card is active but not currently dispatch-eligible");
                scheduleRetry(
                        cardId,
                        retry.attempt() + 1,
                        card.identifier(),
                        "card is active but not currently dispatch-eligible",
                        false);
            } else if (availableSlots() == 0) {
                releaseFromDispatch(card, "no available orchestrator slots");
                scheduleRetry(cardId, retry.attempt() + 1, card.identifier(), "no available orchestrator slots", false);
            } else if (perStateSlots(card) == 0) {
                releaseFromDispatch(card, "no available orchestrator slots for card state");
                scheduleRetry(
                        cardId,
                        retry.attempt() + 1,
                        card.identifier(),
                        "no available orchestrator slots for card state",
                        false);
            } else {
                claimed.remove(cardId);
                refreshForDispatch(card).ifPresent(promptCard -> dispatch(promptCard, retry.attempt()));
            }
        }
    }

    private boolean shouldDispatch(Card card, boolean ignoreClaim) {
        return shouldDispatchIgnoringSlots(card, ignoreClaim) && availableSlots() > 0 && perStateSlots(card) > 0;
    }

    private boolean shouldDispatchIgnoringSlots(Card card, boolean ignoreClaim) {
        return card.hasRequiredDispatchFields()
                && !isOutOfBoardScope(card)
                && TrelloClient.isActive(card, config)
                && !TrelloClient.isTerminal(card, config)
                && (ignoreClaim || !claimed.contains(card.id()))
                && !running.containsKey(card.id())
                && blockersAllowDispatch(card);
    }

    private Set<String> releaseIdleInProgressOverflow(List<Card> candidates) {
        if (blank(config.tracker().inProgressState())) {
            return Set.of();
        }

        int plannedRunning = running.size();
        Map<String, Integer> plannedRunningByState = runningCountsByState();
        Set<String> releasedCards = new HashSet<>();
        for (Card card : candidates) {
            if (running.containsKey(card.id()) || claimed.contains(card.id())) {
                continue;
            }
            String state = StateNames.normalize(card.state());
            int plannedStateRunning = plannedRunningByState.getOrDefault(state, 0);
            if (shouldDispatchIgnoringSlots(card, false)
                    && plannedRunning < config.agent().maxConcurrentAgents()
                    && plannedStateRunning < perStateLimit(card)) {
                plannedRunning++;
                plannedRunningByState.put(state, plannedStateRunning + 1);
                continue;
            }
            if (isConfiguredInProgress(card)) {
                releaseFromDispatch(card, "not currently running");
                releasedCards.add(card.id());
            }
        }
        return releasedCards;
    }

    private void releaseFromDispatch(Card card, String reason) {
        if (!isConfiguredInProgress(card)) {
            return;
        }
        try {
            tracker.releaseFromDispatch(config, card);
            LOG.infof(
                    "card_id=%s card_identifier=%s outcome=released_from_in_progress reason=%s",
                    card.id(), card.identifier(), reason);
        } catch (RuntimeException e) {
            LOG.warnf(
                    "card_id=%s card_identifier=%s outcome=release_from_in_progress_failed reason=%s",
                    card.id(), card.identifier(), e.getMessage());
        }
    }

    private void releaseCurrentFromDispatch(Card card, String reason) {
        if (blank(config.tracker().inProgressState())) {
            return;
        }
        try {
            CardLookupResult result =
                    tracker.fetchCardStatesByIds(config, List.of(card.id())).get(card.id());
            if (result instanceof CardLookupResult.Found found) {
                releaseFromDispatch(found.card(), reason);
            } else if (result instanceof CardLookupResult.Failed failed) {
                LOG.warnf(
                        "card_id=%s card_identifier=%s outcome=release_from_in_progress_refresh_failed reason=%s",
                        card.id(), card.identifier(), failed.message());
            }
        } catch (RuntimeException e) {
            LOG.warnf(
                    "card_id=%s card_identifier=%s outcome=release_from_in_progress_refresh_failed reason=%s",
                    card.id(), card.identifier(), e.getMessage());
        }
    }

    private boolean isConfiguredInProgress(Card card) {
        return StateNames.normalize(config.tracker().inProgressState()).equals(StateNames.normalize(card.state()));
    }

    private boolean blockersAllowDispatch(Card card) {
        if (!config.tracker().blockerEnforcedStates().contains(StateNames.normalize(card.state()))) {
            return true;
        }
        return card.blockedBy().stream()
                .allMatch(blocker -> blocker.state() != null
                        && config.tracker().terminalStates().contains(StateNames.normalize(blocker.state())));
    }

    private int availableSlots() {
        return Math.max(config.agent().maxConcurrentAgents() - running.size(), 0);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private int perStateSlots(Card card) {
        String state = StateNames.normalize(card.state());
        return Math.max(perStateLimit(card) - runningCountsByState().getOrDefault(state, 0), 0);
    }

    private int perStateLimit(Card card) {
        return config.agent()
                .maxConcurrentAgentsByState()
                .getOrDefault(StateNames.normalize(card.state()), config.agent().maxConcurrentAgents());
    }

    private Map<String, Integer> runningCountsByState() {
        Map<String, Integer> counts = new HashMap<>();
        running.values().stream()
                .map(entry -> StateNames.normalize(entry.card.state()))
                .forEach(state -> counts.merge(state, 1, Integer::sum));
        return counts;
    }

    private boolean isOutOfBoardScope(Card card) {
        return card.boardId() != null && !card.boardId().equals(config.tracker().resolvedBoardId());
    }

    private Duration backoff(int attempt) {
        long delay = 10_000L * (1L << Math.clamp(attempt - 1, 0, 16));
        return Duration.ofMillis(
                Math.min(delay, config.agent().maxRetryBackoff().toMillis()));
    }

    private static int nextAttempt(Integer current) {
        return current == null ? 1 : current + 1;
    }

    private void addRuntime(RunningEntry entry) {
        endedRuntimeMillis += Duration.between(entry.startedAt, clock.instant()).toMillis();
    }

    private void addRecentEvent(String cardId, CardDebugDetails.EventInfo event) {
        ArrayDeque<CardDebugDetails.EventInfo> events =
                recentEvents.computeIfAbsent(cardId, ignored -> new ArrayDeque<>());
        events.addLast(event);
        while (events.size() > 50) {
            events.removeFirst();
        }
    }

    private void removeExpiredIgnoredWorkers() {
        Instant now = clock.instant();
        ignoredWorkers.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    private void trimIgnoredWorkers() {
        while (ignoredWorkers.size() > IGNORED_WORKER_LIMIT) {
            String first = ignoredWorkers.keySet().iterator().next();
            ignoredWorkers.remove(first);
        }
    }

    private void scheduleTick(Duration delay) {
        if (tickTimer != null) {
            tickTimer.cancel(false);
        }
        tickTimer = scheduler.schedule(this::tick, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized RuntimeSnapshot snapshot() {
        Instant now = clock.instant();
        List<RuntimeSnapshot.RunningRow> runningRows =
                running.values().stream().map(this::runningRow).toList();
        List<RuntimeSnapshot.RetryRow> retryRows = retryAttempts.values().stream()
                .map(retry -> new RuntimeSnapshot.RetryRow(
                        retry.cardId(), retry.identifier(), retry.attempt(), retry.dueAt(), retry.error()))
                .toList();
        double activeSeconds = running.values().stream()
                        .mapToLong(
                                entry -> Duration.between(entry.startedAt, now).toMillis())
                        .sum()
                / 1000.0;
        return new RuntimeSnapshot(
                now,
                new RuntimeSnapshot.Counts(runningRows.size(), retryRows.size()),
                routing(),
                runningRows,
                retryRows,
                new RuntimeSnapshot.TokenTotals(
                        totalInputTokens, totalOutputTokens, totalTokens, endedRuntimeMillis / 1000.0 + activeSeconds),
                rateLimits);
    }

    private RuntimeSnapshot.Routing routing() {
        if (config == null) {
            return new RuntimeSnapshot.Routing(List.of(), List.of(), List.of());
        }
        return new RuntimeSnapshot.Routing(
                List.copyOf(config.tracker().activeStates()),
                List.copyOf(config.tracker().terminalStates()),
                List.copyOf(config.trelloTools().allowedMoveListNames()));
    }

    public synchronized Optional<CardDebugDetails> cardDetails(String cardIdentifier) {
        RuntimeSnapshot snapshot = snapshot();
        Optional<RuntimeSnapshot.RunningRow> runningRow = snapshot.running().stream()
                .filter(row -> row.cardIdentifier().equals(cardIdentifier))
                .findAny();
        Optional<RuntimeSnapshot.RetryRow> retryRow = snapshot.retrying().stream()
                .filter(row -> row.cardIdentifier().equals(cardIdentifier))
                .findAny();
        if (runningRow.isEmpty() && retryRow.isEmpty()) {
            return Optional.empty();
        }
        String cardId = runningRow.map(RuntimeSnapshot.RunningRow::cardId).orElseGet(() -> retryRow.get()
                .cardId());
        return Optional.of(new CardDebugDetails(
                cardIdentifier,
                cardId,
                runningRow.isPresent() ? "running" : "retrying",
                new CardDebugDetails.WorkspaceInfo(
                        config.workspace().root().resolve(WorkspaceManager.sanitize(cardIdentifier))),
                new CardDebugDetails.AttemptInfo(
                        0, retryRow.map(RuntimeSnapshot.RetryRow::attempt).orElse(null)),
                runningRow.orElse(null),
                retryRow.orElse(null),
                new CardDebugDetails.LogInfo(List.of()),
                List.copyOf(recentEvents.getOrDefault(cardId, new ArrayDeque<>())),
                retryRow.map(RuntimeSnapshot.RetryRow::error).orElse(null),
                Map.of()));
    }

    private RuntimeSnapshot.RunningRow runningRow(RunningEntry entry) {
        return new RuntimeSnapshot.RunningRow(
                entry.cardId,
                entry.identifier(),
                entry.card.state(),
                entry.sessionId,
                entry.turnCount,
                entry.lastEvent,
                entry.lastMessage,
                entry.startedAt,
                entry.lastEventAt,
                Map.of(
                        "input_tokens",
                        entry.inputTokens,
                        "output_tokens",
                        entry.outputTokens,
                        "total_tokens",
                        entry.totalTokens));
    }
}
