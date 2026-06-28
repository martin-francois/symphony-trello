package ch.fmartin.symphony.trello.orchestrator;

import static com.google.common.base.Preconditions.checkState;

import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.config.ConfigDefaults;
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
import java.util.Collection;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
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

    /**
     * Serializes the long-running operations (start, stop, tick, worker exit, retry timers, agent
     * events) against each other, exactly like the previous synchronized methods did. Trello and
     * filesystem I/O may run while holding this lock, but never while holding the instance
     * monitor: status reads take only the monitor, so they must never queue behind a Trello
     * round-trip. Every write to reader-visible state happens under both locks; reads inside
     * operations need no monitor because all writers hold this lock. Exception: config and
     * workflowPath are volatile and written under this lock only, because the lock-free
     * local-status getters read each as one immutable reference and need no cross-field
     * consistency; workflow and workflowLastModified are confined to lock holders and have no
     * lock-free readers.
     */
    private final ReentrantLock operationLock = new ReentrantLock();

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
    private volatile Object rateLimits;
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicBoolean workflowReloadRequested = new AtomicBoolean();
    private volatile boolean tickRunning;
    private boolean started;

    /**
     * Runs inside finishTickAndScheduleNext between refresh consumption and the next schedule,
     * the exact boundary where a concurrent refresh used to be overwritten by the interval
     * schedule. Tests use it to pin the boundary contract; production keeps the no-op.
     */
    Runnable tickCompletionHookForTests = () -> {};

    @ConfigProperty(name = "symphony.workflow.path")
    volatile Path workflowPath;

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

    public void start() {
        operationLock.lock();
        try {
            synchronized (this) {
                if (started) {
                    return;
                }
            }
            reloadOrThrow();
            startWorkflowWatcher();
            startupTerminalWorkspaceCleanup();
            synchronized (this) {
                started = true;
                scheduleTick(Duration.ZERO);
            }
        } finally {
            operationLock.unlock();
        }
    }

    public void stop() {
        operationLock.lock();
        try {
            markStoppingAndCancelTick();
            stopWorkflowWatcher();
            List<RunningEntry> entries;
            synchronized (this) {
                entries = List.copyOf(running.values());
            }
            entries.forEach(entry -> {
                agentRunner.cancel(entry.workerIdentity);
                cancelWorkerTask(entry);
            });
            scheduler.shutdownNow();
            workers.shutdownNow();
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * Marking not-started before anything else closes the refresh window: once this ran, a
     * concurrent requestRefresh() is a no-op and cannot schedule a tick against the scheduler
     * that stop is about to shut down.
     */
    private synchronized void markStoppingAndCancelTick() {
        started = false;
        if (tickTimer != null) {
            tickTimer.cancel(false);
        }
    }

    public void setWorkflowPath(Path workflowPath) {
        // The operation lock serializes this with start(): a path change requested while startup
        // is loading the workflow waits for the operation boundary and is then rejected.
        operationLock.lock();
        try {
            synchronized (this) {
                checkState(!started, "Workflow path cannot be changed after orchestrator start");
                this.workflowPath = workflowPath;
            }
        } finally {
            operationLock.unlock();
        }
    }

    public synchronized boolean isStarted() {
        return started;
    }

    // Lock-free on purpose: local-status health probes call these and must never wait for an
    // in-flight Trello poll. Both fields are volatile and the config value is immutable.
    public Path selectedWorkflowPath() {
        EffectiveConfig current = config;
        Path selected = current == null ? workflowPath : current.workflowPath();
        return selected.toAbsolutePath().normalize();
    }

    public String selectedBoardId() {
        EffectiveConfig current = config;
        return current == null ? null : current.tracker().resolvedBoardId();
    }

    public String selectedConfiguredBoardId() {
        EffectiveConfig current = config;
        return current == null ? null : current.tracker().boardId();
    }

    /** The configured card identifier prefix, or the documented default before configuration loads. */
    public String cardIdentifierPrefix() {
        EffectiveConfig current = config;
        return current == null
                ? ConfigDefaults.DEFAULT_CARD_IDENTIFIER_PREFIX
                : current.tracker().cardIdentifierPrefix();
    }

    public void requestRefresh() {
        refreshRequested.set(true);
        scheduleRefreshIfStartedAndIdle();
    }

    /**
     * The monitor makes this atomic with tick completion: it runs entirely before or entirely
     * after finishTickAndScheduleNext, so a refresh either gets consumed by the finishing tick or
     * replaces the interval schedule with a zero-delay tick, never the other way around. After
     * stop marked the orchestrator as not started, this is a no-op, so a late refresh cannot
     * schedule against the shut-down scheduler.
     */
    private synchronized void scheduleRefreshIfStartedAndIdle() {
        if (started && !tickRunning) {
            scheduleTick(Duration.ZERO);
        }
    }

    private boolean consumeRefreshRequest() {
        return refreshRequested.getAndSet(false);
    }

    public void tickNowForTests() {
        tick();
    }

    private void tick() {
        operationLock.lock();
        try {
            if (!beginTick()) {
                return;
            }
            try {
                consumeRefreshRequest();
                reloadIfChanged();
                reconcileRunningCards();
                dispatchCandidates();
            } catch (RuntimeException e) {
                LOG.errorf("tick outcome=skipped reason=%s", e.getMessage());
            } finally {
                finishTickAndScheduleNext();
            }
        } finally {
            operationLock.unlock();
        }
    }

    private synchronized boolean beginTick() {
        if (!started) {
            return false;
        }
        tickRunning = true;
        return true;
    }

    /**
     * Tick completion is atomic: clearing tickRunning, consuming the refresh flag, and scheduling
     * the next tick happen under one monitor section. A concurrent requestRefresh() therefore
     * runs entirely before this (and is consumed here as the zero-delay schedule) or entirely
     * after it (and replaces the interval schedule), so a refresh at the completion boundary can
     * never be overwritten by the normal polling interval.
     */
    private synchronized void finishTickAndScheduleNext() {
        tickRunning = false;
        boolean refreshRequestedDuringTick = consumeRefreshRequest();
        tickCompletionHookForTests.run();
        scheduleTick(
                refreshRequestedDuringTick ? Duration.ZERO : config.polling().interval());
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
                    if (hasRequiredLabels(card)) {
                        RunningEntry entry = running.get(card.id());
                        if (entry != null) {
                            synchronized (this) {
                                entry.card = card;
                            }
                        }
                    } else {
                        terminateRunning(card.id(), false, false, "card is active but not currently dispatch-eligible");
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
        RunningEntry entry;
        synchronized (this) {
            entry = running.remove(cardId);
            if (entry == null) {
                return;
            }
            ignoredWorkers.put(entry.workerIdentity, clock.instant().plus(IGNORED_WORKER_TTL));
            trimIgnoredWorkers();
            addRuntime(entry);
            if (suppressRetry) {
                claimed.remove(cardId);
            }
        }
        agentRunner.cancel(entry.workerIdentity);
        cancelWorkerTask(entry);
        if (cleanupWorkspace) {
            workspaces.removeForIdentifierIfPresent(entry.identifier(), config);
        }
        if (!suppressRetry) {
            releaseCurrentFromDispatch(entry.card, reason);
            scheduleRetry(
                    cardId, nextAttempt(entry.retryAttempt), entry.identifier(), entry.card.cardUrl(), reason, false);
        }
        LOG.infof("card_id=%s card_identifier=%s outcome=terminated reason=%s", cardId, entry.identifier(), reason);
    }

    private void dispatchCandidates() {
        configResolver.validateForDispatch(config);
        List<Card> fetchedCandidates = tracker.fetchCandidateCards(config);
        List<Card> candidates = fetchedCandidates.stream()
                .sorted(TrelloClient.dispatchComparator(config, prerequisitePriorityOverrides(fetchedCandidates)))
                .toList();
        Set<String> releasedCards = releaseIdleInProgressOverflow(candidates);
        DispatchBudget budget = DispatchBudget.from(config, running.values());
        for (Card card : candidates) {
            if (releasedCards.contains(card.id())) {
                continue;
            }
            if (!budget.hasAnySlot()) {
                break;
            }
            if (!canDispatch(card, budget)) {
                continue;
            }

            refreshForDispatch(card, budget)
                    .flatMap(refreshed -> dispatch(refreshed, null))
                    .ifPresent(budget::reserve);
        }
    }

    private Optional<Card> dispatch(Card card, Integer attempt) {
        RetryEntry retry;
        synchronized (this) {
            claimed.add(card.id());
            retry = retryAttempts.remove(card.id());
        }
        if (retry != null) {
            retry.timer().cancel(false);
        }
        Card dispatchCard;
        try {
            dispatchCard = tracker.prepareForDispatch(config, card);
        } catch (RuntimeException e) {
            synchronized (this) {
                claimed.remove(card.id());
            }
            releaseCurrentFromDispatch(card, "prepare for dispatch failed");
            scheduleRetry(card.id(), nextAttempt(attempt), card.identifier(), card.cardUrl(), e.getMessage(), false);
            return Optional.empty();
        }
        String workerIdentity = UUID.randomUUID().toString();
        RunningEntry entry = new RunningEntry(dispatchCard, workerIdentity, attempt, clock.instant());
        synchronized (this) {
            running.put(dispatchCard.id(), entry);
        }
        String prompt;
        try {
            prompt = prompts.render(workflow.promptTemplate(), dispatchCard, attempt);
        } catch (RuntimeException e) {
            synchronized (this) {
                running.remove(dispatchCard.id());
            }
            releaseFromDispatch(dispatchCard, "prompt render failed");
            scheduleRetry(
                    dispatchCard.id(),
                    nextAttempt(attempt),
                    dispatchCard.identifier(),
                    dispatchCard.cardUrl(),
                    e.getMessage(),
                    false);
            return Optional.empty();
        }
        entry.workerTask = workers.submit(() -> {
            AgentRunResult result = agentRunner.run(new AgentRunner.AgentRunRequest(
                    dispatchCard, attempt, prompt, config, workerIdentity, this::onAgentEvent));
            onWorkerExit(dispatchCard.id(), workerIdentity, result);
        });
        LOG.infof(
                "card_id=%s card_identifier=%s worker_identity=%s outcome=dispatched",
                dispatchCard.id(), dispatchCard.identifier(), workerIdentity);
        return Optional.of(dispatchCard);
    }

    private Optional<Card> refreshForDispatch(Card card) {
        return refreshForDispatch(card, DispatchBudget.from(config, running.values()));
    }

    private Optional<Card> refreshForDispatch(Card card, DispatchBudget budget) {
        try {
            CardLookupResult result = tracker.fetchCardStatesForPromptByIds(config, List.of(card.id()))
                    .get(card.id());
            if (result instanceof CardLookupResult.Found found && canDispatch(found.card(), budget)) {
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

    private void onWorkerExit(String cardId, String workerIdentity, AgentRunResult result) {
        operationLock.lock();
        try {
            currentRunningEntry(cardId, workerIdentity).ifPresent(entry -> handleWorkerExit(cardId, result, entry));
        } finally {
            operationLock.unlock();
        }
    }

    private void handleWorkerExit(String cardId, AgentRunResult result, RunningEntry entry) {
        synchronized (this) {
            running.remove(cardId);
            addRuntime(entry);
        }
        WorkerExitState state = workerExitState(cardId, entry.identifier());
        if (result.success()) {
            synchronized (this) {
                completed.add(cardId);
            }
            if (state.retry()) {
                scheduleRetry(cardId, 1, entry.identifier(), entry.card.cardUrl(), null, true);
            } else {
                completeWorkerExit(cardId, entry, state);
            }
        } else if (state.retry()) {
            releaseAfterFailedWorkerExit(entry, state, result.reason());
            scheduleRetry(
                    cardId,
                    nextAttempt(entry.retryAttempt),
                    entry.identifier(),
                    entry.card.cardUrl(),
                    result.reason(),
                    false);
        } else {
            completeWorkerExit(cardId, entry, state);
        }
    }

    private WorkerExitState workerExitState(String cardId, String identifier) {
        try {
            CardLookupResult result =
                    tracker.fetchCardStatesByIds(config, List.of(cardId)).get(cardId);
            return switch (result) {
                case CardLookupResult.Missing ignored -> WorkerExitState.complete(true, null);
                case CardLookupResult.Failed failed -> {
                    LOG.warnf(
                            "card_id=%s card_identifier=%s worker_exit_refresh=failed reason=%s",
                            cardId, identifier, failed.message());
                    yield WorkerExitState.retry(null);
                }
                case CardLookupResult.Found found -> workerExitState(found.card());
                case null -> WorkerExitState.retry(null);
            };
        } catch (RuntimeException e) {
            LOG.warnf(
                    "card_id=%s card_identifier=%s worker_exit_refresh=failed reason=%s",
                    cardId, identifier, e.getMessage());
            return WorkerExitState.retry(null);
        }
    }

    private WorkerExitState workerExitState(Card card) {
        if (isOutOfBoardScope(card)) {
            return WorkerExitState.complete(false, card);
        }
        if (TrelloClient.isTerminal(card, config)) {
            return WorkerExitState.complete(true, card);
        }
        if (TrelloClient.isActive(card, config)) {
            return WorkerExitState.retry(card);
        }
        return WorkerExitState.complete(false, card);
    }

    private void completeWorkerExit(String cardId, RunningEntry entry, WorkerExitState state) {
        synchronized (this) {
            claimed.remove(cardId);
        }
        if (state.cleanupWorkspace()) {
            workspaces.removeForIdentifierIfPresent(entry.identifier(), config);
        }
    }

    private void releaseAfterFailedWorkerExit(RunningEntry entry, WorkerExitState state, String reason) {
        if (state.card() == null) {
            releaseCurrentFromDispatch(entry.card, reason);
            return;
        }
        releaseFromDispatch(state.card(), reason);
    }

    private static void cancelWorkerTask(RunningEntry entry) {
        if (entry.workerTask != null) {
            entry.workerTask.cancel(true);
        }
    }

    private void onAgentEvent(AgentEvent event) {
        operationLock.lock();
        try {
            running.values().stream()
                    .filter(entry -> entry.workerIdentity.equals(event.workerIdentity()))
                    .map(entry -> entry.cardId)
                    .findAny()
                    .ifPresent(cardId -> currentRunningEntry(cardId, event.workerIdentity())
                            .ifPresent(entry -> applyAgentEvent(cardId, entry, event)));
        } finally {
            operationLock.unlock();
        }
    }

    private synchronized void applyAgentEvent(String cardId, RunningEntry entry, AgentEvent event) {
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

    private synchronized Optional<RunningEntry> currentRunningEntry(String cardId, String workerIdentity) {
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

    private void scheduleRetry(
            String cardId, int attempt, String identifier, String cardUrl, String error, boolean continuation) {
        Duration delay = continuation ? CONTINUATION_DELAY : backoff(attempt);
        ScheduledFuture<?> timer =
                scheduler.schedule(() -> onRetryTimer(cardId), delay.toMillis(), TimeUnit.MILLISECONDS);
        synchronized (this) {
            retryAttempts.put(
                    cardId,
                    new RetryEntry(
                            cardId,
                            identifier,
                            cardUrl,
                            attempt,
                            clock.instant().plus(delay),
                            timer,
                            error));
            claimed.add(cardId);
        }
        LOG.infof(
                "card_id=%s card_identifier=%s outcome=retrying attempt=%d delay_ms=%d",
                cardId, identifier, attempt, delay.toMillis());
    }

    private void onRetryTimer(String cardId) {
        operationLock.lock();
        try {
            handleRetryTimer(cardId);
        } finally {
            operationLock.unlock();
        }
    }

    private void handleRetryTimer(String cardId) {
        RetryEntry retry;
        synchronized (this) {
            retry = retryAttempts.remove(cardId);
        }
        if (retry == null) {
            return;
        }
        Map<String, CardLookupResult> refreshed;
        try {
            refreshed = tracker.fetchCardStatesByIds(config, List.of(cardId));
        } catch (RuntimeException e) {
            scheduleRetry(
                    cardId,
                    retry.attempt() + 1,
                    retry.identifier(),
                    retry.cardUrl(),
                    "retry card refresh failed",
                    false);
            return;
        }
        CardLookupResult result = refreshed.get(cardId);
        if (result instanceof CardLookupResult.Missing) {
            removeClaim(cardId);
            workspaces.removeForIdentifierIfPresent(retry.identifier(), config);
        } else if (result instanceof CardLookupResult.Failed) {
            scheduleRetry(
                    cardId,
                    retry.attempt() + 1,
                    retry.identifier(),
                    retry.cardUrl(),
                    "retry card refresh failed",
                    false);
        } else if (result instanceof CardLookupResult.Found found) {
            Card card = found.card();
            if (isOutOfBoardScope(card)) {
                removeClaim(cardId);
            } else if (TrelloClient.isTerminal(card, config)) {
                removeClaim(cardId);
                workspaces.removeForIdentifierIfPresent(card.identifier(), config);
            } else if (!TrelloClient.isActive(card, config)) {
                removeClaim(cardId);
            } else if (!shouldDispatchIgnoringSlots(card, true)) {
                releaseFromDispatch(card, "card is active but not currently dispatch-eligible");
                scheduleRetry(
                        cardId,
                        retry.attempt() + 1,
                        card.identifier(),
                        card.cardUrl(),
                        "card is active but not currently dispatch-eligible",
                        false);
            } else if (availableSlots() == 0) {
                releaseFromDispatch(card, "no available orchestrator slots");
                scheduleRetry(
                        cardId,
                        retry.attempt() + 1,
                        card.identifier(),
                        card.cardUrl(),
                        "no available orchestrator slots",
                        false);
            } else if (perStateSlots(card) == 0) {
                releaseFromDispatch(card, "no available orchestrator slots for card state");
                scheduleRetry(
                        cardId,
                        retry.attempt() + 1,
                        card.identifier(),
                        card.cardUrl(),
                        "no available orchestrator slots for card state",
                        false);
            } else {
                removeClaim(cardId);
                refreshForDispatch(card).ifPresent(promptCard -> dispatch(promptCard, retry.attempt()));
            }
        }
    }

    private synchronized void removeClaim(String cardId) {
        claimed.remove(cardId);
    }

    private boolean shouldDispatch(Card card, boolean ignoreClaim) {
        return shouldDispatchIgnoringSlots(card, ignoreClaim) && availableSlots() > 0 && perStateSlots(card) > 0;
    }

    private boolean canDispatch(Card card, DispatchBudget budget) {
        return shouldDispatchIgnoringSlots(card, false) && budget.hasSlotFor(card);
    }

    private boolean shouldDispatchIgnoringSlots(Card card, boolean ignoreClaim) {
        return isCandidateEligibleIgnoringRuntimeState(card)
                && (ignoreClaim || !claimed.contains(card.id()))
                && !running.containsKey(card.id());
    }

    private boolean isCandidateEligibleIgnoringRuntimeState(Card card) {
        return card.hasRequiredDispatchFields()
                && !isOutOfBoardScope(card)
                && TrelloClient.isActive(card, config)
                && !TrelloClient.isTerminal(card, config)
                && hasRequiredLabels(card)
                && blockersAllowDispatch(card);
    }

    private boolean hasRequiredLabels(Card card) {
        List<String> requiredLabels = config.tracker().requiredLabels();
        if (requiredLabels.isEmpty()) {
            return true;
        }
        if (requiredLabels.stream().anyMatch(String::isBlank)) {
            return false;
        }
        Set<String> cardLabels =
                card.labels().stream().map(StateNames::normalize).collect(Collectors.toCollection(HashSet::new));
        return cardLabels.containsAll(requiredLabels);
    }

    private Map<String, Integer> prerequisitePriorityOverrides(List<Card> candidates) {
        Set<String> dispatchableCandidateIds = dispatchableCandidateIds(candidates);
        Map<String, Integer> overrides = HashMap.newHashMap(dispatchableCandidateIds.size());
        for (Card card : candidates) {
            if (blockersAllowDispatch(card)) {
                continue;
            }
            int inheritedPriority = priorityOrDefault(card);
            for (var blocker : card.blockedBy()) {
                if (dispatchableCandidateIds.contains(blocker.id())) {
                    overrides.merge(blocker.id(), inheritedPriority, Math::min);
                }
            }
        }
        return overrides;
    }

    private Set<String> dispatchableCandidateIds(List<Card> candidates) {
        Set<String> seen = HashSet.newHashSet(candidates.size());
        Set<String> dispatchableIds = HashSet.newHashSet(candidates.size());
        for (Card candidate : candidates) {
            if (seen.add(candidate.id()) && shouldDispatchIgnoringSlots(candidate, false)) {
                dispatchableIds.add(candidate.id());
            }
        }
        return dispatchableIds;
    }

    private static int priorityOrDefault(Card card) {
        return card.priority() == null ? Integer.MAX_VALUE : card.priority();
    }

    private Set<String> releaseIdleInProgressOverflow(List<Card> candidates) {
        if (blank(config.tracker().inProgressState())) {
            return Set.of();
        }

        DispatchBudget planned = DispatchBudget.from(config, running.values());
        Set<String> releasedCards = HashSet.newHashSet(candidates.size());
        for (Card card : candidates) {
            if (isAlreadyReserved(card)) {
                continue;
            }
            if (shouldDispatchIgnoringSlots(card, false) && planned.tryReserve(card)) {
                continue;
            }
            if (isConfiguredInProgress(card)) {
                releaseFromDispatch(card, "not currently running");
                releasedCards.add(card.id());
            }
        }
        return releasedCards;
    }

    private boolean isAlreadyReserved(Card card) {
        return running.containsKey(card.id()) || claimed.contains(card.id());
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
        if (!card.prerequisiteProblems().isEmpty()) {
            return false;
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
        return running.values().stream()
                .map(entry -> StateNames.normalize(entry.card.state()))
                .collect(Collectors.toMap(state -> state, state -> 1, Integer::sum, HashMap::new));
    }

    private static final class DispatchBudget {
        private final int maxTotal;
        private final Map<String, Integer> maxByState;
        private final Map<String, Integer> usedByState;
        private int usedTotal;

        private DispatchBudget(
                int maxTotal, Map<String, Integer> maxByState, int usedTotal, Map<String, Integer> usedByState) {
            this.maxTotal = maxTotal;
            this.maxByState = maxByState;
            this.usedTotal = usedTotal;
            this.usedByState = usedByState;
        }

        static DispatchBudget from(EffectiveConfig config, Collection<RunningEntry> runningEntries) {
            Map<String, Integer> usedByState = HashMap.newHashMap(runningEntries.size());
            for (RunningEntry entry : runningEntries) {
                usedByState.merge(StateNames.normalize(entry.card.state()), 1, Integer::sum);
            }
            return new DispatchBudget(
                    config.agent().maxConcurrentAgents(),
                    config.agent().maxConcurrentAgentsByState(),
                    runningEntries.size(),
                    usedByState);
        }

        boolean hasAnySlot() {
            return usedTotal < maxTotal;
        }

        boolean hasSlotFor(Card card) {
            String state = StateNames.normalize(card.state());
            return hasAnySlot() && usedByState.getOrDefault(state, 0) < limitFor(state);
        }

        boolean tryReserve(Card card) {
            if (!hasSlotFor(card)) {
                return false;
            }
            reserve(card);
            return true;
        }

        void reserve(Card card) {
            usedTotal++;
            usedByState.merge(StateNames.normalize(card.state()), 1, Integer::sum);
        }

        private int limitFor(String state) {
            return maxByState.getOrDefault(state, maxTotal);
        }
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

    private synchronized void removeExpiredIgnoredWorkers() {
        Instant now = clock.instant();
        ignoredWorkers.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    private synchronized void trimIgnoredWorkers() {
        while (ignoredWorkers.size() > IGNORED_WORKER_LIMIT) {
            String first = ignoredWorkers.keySet().iterator().next();
            ignoredWorkers.remove(first);
        }
    }

    private synchronized void scheduleTick(Duration delay) {
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
                        retry.cardId(),
                        retry.identifier(),
                        retry.cardUrl(),
                        retry.attempt(),
                        retry.dueAt(),
                        retry.error()))
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
        return runningRow
                .map(this::runningCardDetailsSelection)
                .or(() -> retryRow.map(this::retryCardDetailsSelection))
                .map(selection -> new CardDebugDetails(
                        cardIdentifier,
                        selection.cardId(),
                        selection.status(),
                        new CardDebugDetails.WorkspaceInfo(
                                config.workspace().root().resolve(WorkspaceManager.sanitize(cardIdentifier))),
                        new CardDebugDetails.AttemptInfo(0, selection.currentRetryAttempt()),
                        selection.runningRow(),
                        selection.retryRow(),
                        new CardDebugDetails.LogInfo(List.of()),
                        List.copyOf(recentEvents.getOrDefault(selection.cardId(), new ArrayDeque<>())),
                        selection.lastError(),
                        Map.of()));
    }

    private CardDetailsSelection runningCardDetailsSelection(RuntimeSnapshot.RunningRow row) {
        return new CardDetailsSelection(row.cardId(), "running", null, row, null, null);
    }

    private CardDetailsSelection retryCardDetailsSelection(RuntimeSnapshot.RetryRow row) {
        return new CardDetailsSelection(row.cardId(), "retrying", row.attempt(), null, row, row.error());
    }

    private RuntimeSnapshot.RunningRow runningRow(RunningEntry entry) {
        return new RuntimeSnapshot.RunningRow(
                entry.cardId,
                entry.identifier(),
                entry.card.cardUrl(),
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

    private record CardDetailsSelection(
            String cardId,
            String status,
            Integer currentRetryAttempt,
            RuntimeSnapshot.RunningRow runningRow,
            RuntimeSnapshot.RetryRow retryRow,
            String lastError) {}
}
