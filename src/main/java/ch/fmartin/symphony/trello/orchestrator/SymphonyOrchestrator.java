package ch.fmartin.symphony.trello.orchestrator;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import ch.fmartin.symphony.trello.Sha3;
import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentEventListener;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.agent.CodexUsageWorkpadSection;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolHandler;
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
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.google.common.collect.EvictingQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SymphonyOrchestrator {
    private static final Logger LOG = Logger.getLogger(SymphonyOrchestrator.class);
    private static final int IGNORED_WORKER_LIMIT = 1_000;
    private static final long INITIAL_RETRY_BACKOFF_MILLIS = 10_000L;
    private static final int MAX_RETRY_BACKOFF_SHIFT = 16;
    private static final int RECENT_EVENT_LIMIT = 50;
    private static final Duration IGNORED_WORKER_TTL = Duration.ofMinutes(30);
    private static final Duration CONTINUATION_DELAY = Duration.ofSeconds(1);
    private static final Duration USAGE_WORKPAD_CLEANUP_RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration USAGE_WORKPAD_CLEANUP_TTL = Duration.ofHours(1);
    private static final int USAGE_WORKPAD_CLEANUP_LIMIT = 100;
    private static final String CODEX_USAGE_LIMIT_PAUSE = "CODEX_USAGE_LIMIT";

    private final WorkflowLoader workflowLoader;
    private final ConfigResolver configResolver;
    private final TrackerClient tracker;
    private final AgentRunner agentRunner;
    private final PromptRenderer prompts;
    private final WorkspaceManager workspaces;
    private final Clock clock;
    private final Optional<TrelloHandoffToolHandler> usageWorkpads;
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

    private final Map<RuntimeCardKey, RunningEntry> running = new LinkedHashMap<>();
    private final Map<RuntimeCardKey, RetryEntry> retryAttempts = new LinkedHashMap<>();
    private final Set<RuntimeCardKey> claimed = new HashSet<>();
    private final Set<RuntimeCardKey> completed = new HashSet<>();
    private final LinkedHashMap<String, Instant> ignoredWorkers = new LinkedHashMap<>();
    // Bound each card's diagnostic history so long sessions cannot grow memory and API responses
    // indefinitely; EvictingQueue retains the newest events in chronological order.
    private final Map<RuntimeCardKey, EvictingQueue<CardDebugDetails.EventInfo>> recentEvents = new HashMap<>();
    private final Map<UsageWorkpadTarget, UsageWorkpadState> usageWorkpadMessages = new LinkedHashMap<>();
    private final LinkedHashMap<UsageWorkpadTarget, UsageWorkpadCleanup> pendingUsageWorkpadCleanup =
            new LinkedHashMap<>();
    private final Map<String, Object> rateLimitsByCommand = new HashMap<>();

    private volatile EffectiveConfig config;
    private WorkflowDefinition workflow;
    private Instant workflowLastModified;
    private ScheduledFuture<?> tickTimer;
    private ScheduledFuture<?> dispatchPauseTimer;
    private WatchService workflowWatchService;
    private long endedRuntimeMillis;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;
    private long retryGeneration;
    private long dispatchPauseGeneration;
    private final AtomicBoolean refreshRequested = new AtomicBoolean();
    private final AtomicBoolean workflowReloadRequested = new AtomicBoolean();
    private WorkflowProcessLock workflowProcessLock;
    private boolean tickRunning;
    private boolean started;
    private DispatchPause dispatchPause;

    /**
     * Runs inside finishTickAndScheduleNext between refresh consumption and the next schedule,
     * the exact boundary where a concurrent refresh used to be overwritten by the interval
     * schedule. Tests use it to pin the boundary contract; production keeps the no-op.
     */
    Runnable tickCompletionHookForTests = () -> {};

    Runnable dispatchPauseScheduleHookForTests = () -> {};
    BiConsumer<String, Long> retryEntryRescheduleHookForTests = (ignoredCardId, ignoredGeneration) -> {};
    Runnable retryTimerWaitingHookForTests = () -> {};
    Runnable workerExitCompletionHookForTests = () -> {};
    Runnable workerLaunchHookForTests = () -> {};
    Runnable executorsStoppedHookForTests = () -> {};

    @ConfigProperty(name = "symphony.workflow.path")
    volatile Path workflowPath;

    public SymphonyOrchestrator(
            WorkflowLoader workflowLoader,
            ConfigResolver configResolver,
            TrackerClient tracker,
            AgentRunner agentRunner,
            PromptRenderer prompts,
            WorkspaceManager workspaces) {
        this(
                workflowLoader,
                configResolver,
                tracker,
                agentRunner,
                prompts,
                workspaces,
                ApplicationClock.systemUtc(),
                Optional.empty());
    }

    @Inject
    public SymphonyOrchestrator(
            WorkflowLoader workflowLoader,
            ConfigResolver configResolver,
            TrackerClient tracker,
            AgentRunner agentRunner,
            PromptRenderer prompts,
            WorkspaceManager workspaces,
            Clock clock,
            TrelloHandoffToolHandler usageWorkpads) {
        this(
                workflowLoader,
                configResolver,
                tracker,
                agentRunner,
                prompts,
                workspaces,
                clock,
                Optional.of(usageWorkpads));
    }

    private SymphonyOrchestrator(
            WorkflowLoader workflowLoader,
            ConfigResolver configResolver,
            TrackerClient tracker,
            AgentRunner agentRunner,
            PromptRenderer prompts,
            WorkspaceManager workspaces,
            Clock clock,
            Optional<TrelloHandoffToolHandler> usageWorkpads) {
        this.workflowLoader = workflowLoader;
        this.configResolver = configResolver;
        this.tracker = tracker;
        this.agentRunner = agentRunner;
        this.prompts = prompts;
        this.workspaces = workspaces;
        this.clock = clock;
        this.usageWorkpads = usageWorkpads;
    }

    public void start() {
        operationLock.lock();
        boolean releaseLockOnFailure = false;
        boolean startupComplete = false;
        try {
            synchronized (this) {
                if (started) {
                    return;
                }
            }
            releaseLockOnFailure = true;
            reloadOrThrow();
            startWorkflowWatcher();
            startupTerminalWorkspaceCleanup();
            synchronized (this) {
                started = true;
                scheduleTick(Duration.ZERO);
            }
            startupComplete = true;
        } finally {
            try {
                if (releaseLockOnFailure && !startupComplete) {
                    releaseWorkflowProcessLock();
                }
            } finally {
                operationLock.unlock();
            }
        }
    }

    public void stop() {
        operationLock.lock();
        try {
            markStoppingAndCancelTick();
            stopWorkflowWatcher();
            List<RunningEntry> entries;
            List<RetryEntry> retries;
            synchronized (this) {
                entries = List.copyOf(running.values());
                retries = List.copyOf(retryAttempts.values());
                entries.forEach(entry ->
                        ignoredWorkers.put(entry.workerIdentity, clock.instant().plus(IGNORED_WORKER_TTL)));
                entries.forEach(this::addRuntime);
                running.clear();
                retryAttempts.clear();
                claimed.clear();
                trimIgnoredWorkers();
                usageWorkpadMessages.forEach((target, state) -> queueUsageWorkpadCleanup(target, state.ownerConfig()));
                usageWorkpadMessages.clear();
                dispatchPause = null;
            }
            retries.forEach(retry -> retry.timer().cancel(false));
            entries.forEach(entry -> {
                agentRunner.cancel(entry.workerIdentity);
                cancelWorkerTask(entry);
            });
            retryPendingUsageWorkpadCleanup(true);
            scheduler.shutdownNow();
            workers.shutdownNow();
            executorsStoppedHookForTests.run();
            releaseWorkflowProcessLock();
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
        if (dispatchPauseTimer != null) {
            dispatchPauseTimer.cancel(false);
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

    void stopWorkflowWatcherForTests() {
        stopWorkflowWatcher();
    }

    void retryNowForTests(String cardId) {
        RetryEntry retry;
        synchronized (this) {
            retry = retryAttempts.get(currentRuntimeKey(cardId));
        }
        if (retry != null) {
            onRetryTimer(runtimeKey(retry), retry.generation());
        }
    }

    void retryNowForTests(String cardId, long generation) {
        onRetryTimer(currentRuntimeKey(cardId), generation);
    }

    void dispatchPauseDeadlineNowForTests(Instant expectedUntil) {
        DispatchPause pause;
        synchronized (this) {
            pause = dispatchPause;
        }
        if (pause != null) {
            onDispatchPauseDeadline(pause.command(), pause.generation(), expectedUntil);
        }
    }

    void dispatchPauseDeadlineNowForTests(String expectedCommand, Instant expectedUntil) {
        long generation;
        synchronized (this) {
            generation = dispatchPause == null ? Long.MIN_VALUE : dispatchPause.generation();
        }
        onDispatchPauseDeadline(expectedCommand, generation, expectedUntil);
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
                retryPendingUsageWorkpadCleanup(false);
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
        acquireWorkflowProcessLock(workflow.path());
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

    private void acquireWorkflowProcessLock(Path selectedWorkflowPath) {
        if (workflowProcessLock == null) {
            workflowProcessLock = WorkflowProcessLock.acquire(selectedWorkflowPath);
        }
    }

    private void releaseWorkflowProcessLock() {
        if (workflowProcessLock == null) {
            return;
        }
        try {
            workflowProcessLock.close();
        } finally {
            workflowProcessLock = null;
        }
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
            applyValidReload(nextWorkflow, nextConfig, modified);
            LOG.infof("workflow=%s outcome=reloaded", nextWorkflow.path());
        } catch (RuntimeException e) {
            LOG.errorf("workflow=%s outcome=reload_failed reason=%s", config.workflowPath(), e.getMessage());
        }
    }

    private void applyValidReload(WorkflowDefinition nextWorkflow, EffectiveConfig nextConfig, Instant modified) {
        synchronized (this) {
            EffectiveConfig previousConfig = config;
            boolean commandChanged = !Objects.equals(
                    previousConfig.codex().command(), nextConfig.codex().command());
            boolean trackerTargetChanged = !TrackerTarget.from(previousConfig).equals(TrackerTarget.from(nextConfig));
            if (trackerTargetChanged) {
                rotateTrackerTarget(nextConfig, !commandChanged);
            }
            if (commandChanged) {
                rotateCodexCommandScope(previousConfig, nextConfig);
            }
            refreshUsageOwnership(nextConfig);
            workflow = nextWorkflow;
            config = nextConfig;
            workflowLastModified = modified;
        }
    }

    /** Called with the state monitor held after reload rotations have detached prior ownership. */
    private void refreshUsageOwnership(EffectiveConfig nextConfig) {
        TrackerTarget currentTarget = TrackerTarget.from(nextConfig);
        usageWorkpadMessages.replaceAll(
                (target, state) -> target.trackerTarget().equals(currentTarget)
                        ? new UsageWorkpadState(nextConfig, state.message())
                        : state);
        pendingUsageWorkpadCleanup.replaceAll(
                (target, cleanup) -> target.trackerTarget().equals(currentTarget)
                        ? new UsageWorkpadCleanup(nextConfig, cleanup.expiresAt(), cleanup.nextAttemptAt())
                        : cleanup);
    }

    /** Called with the state monitor held and the operation lock owned by the reload tick. */
    private void rotateTrackerTarget(EffectiveConfig nextConfig, boolean retainCommandPause) {
        TrackerTarget nextTarget = TrackerTarget.from(nextConfig);

        for (Map.Entry<UsageWorkpadTarget, UsageWorkpadState> entry : List.copyOf(usageWorkpadMessages.entrySet())) {
            if (!entry.getKey().trackerTarget().equals(nextTarget)) {
                queueUsageWorkpadCleanup(entry.getKey(), entry.getValue().ownerConfig());
                usageWorkpadMessages.remove(entry.getKey());
            }
        }

        for (RetryEntry retry : List.copyOf(retryAttempts.values())) {
            if (!retry.trackerTarget().equals(nextTarget)) {
                RuntimeCardKey retryKey = runtimeKey(retry);
                RetryEntry current = retryAttempts.remove(retryKey);
                if (current != null && current.generation() == retry.generation()) {
                    current.timer().cancel(false);
                    claimed.remove(retryKey);
                }
            }
        }

        if (retainCommandPause && dispatchPause != null) {
            Optional<UsageProbe> retainedProbe = dispatchPause
                    .probe()
                    .filter(probe -> probe.target().trackerTarget().equals(nextTarget));
            dispatchPause = new DispatchPause(
                    dispatchPause.command(),
                    dispatchPause.generation(),
                    dispatchPause.detectedAt(),
                    dispatchPause.until(),
                    retainedProbe);
            scheduleDispatchPauseDeadline(dispatchPause);
        }
    }

    /** Called with the state monitor held and the operation lock owned by the reload tick. */
    private void rotateCodexCommandScope(EffectiveConfig previousConfig, EffectiveConfig nextConfig) {
        String previousCommand = previousConfig.codex().command();
        if (dispatchPause != null && Objects.equals(dispatchPause.command(), previousCommand)) {
            dispatchPause = null;
            if (dispatchPauseTimer != null) {
                dispatchPauseTimer.cancel(false);
                dispatchPauseTimer = null;
            }
        }
        for (Map.Entry<UsageWorkpadTarget, UsageWorkpadState> entry : usageWorkpadMessages.entrySet()) {
            queueUsageWorkpadCleanup(entry.getKey(), entry.getValue().ownerConfig());
        }
        usageWorkpadMessages.clear();

        Instant now = clock.instant();
        TrackerTarget nextTarget = TrackerTarget.from(nextConfig);
        for (RetryEntry retry : List.copyOf(retryAttempts.values())) {
            if (Objects.equals(retry.codexCommand(), previousCommand)
                    && retry.trackerTarget().equals(nextTarget)) {
                rescheduleRetryForCommandChange(retry, nextConfig, now);
            }
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
        Map<EffectiveConfig, List<RunningEntry>> entriesByLaunchConfig = new LinkedHashMap<>();
        List.copyOf(running.values()).forEach(entry -> entriesByLaunchConfig
                .computeIfAbsent(entry.launchConfig, ignored -> new java.util.ArrayList<>())
                .add(entry));
        entriesByLaunchConfig.forEach((launchConfig, entries) -> {
            Map<String, CardLookupResult> refreshed;
            try {
                refreshed = tracker.fetchCardStatesByIds(
                        launchConfig,
                        entries.stream().map(entry -> entry.cardId).toList());
            } catch (RuntimeException e) {
                LOG.warnf("reconcile outcome=state_refresh_failed reason=%s", e.getMessage());
                return;
            }
            for (RunningEntry entry : entries) {
                RuntimeCardKey entryKey = runtimeKey(entry);
                String cardId = entry.cardId;
                CardLookupResult result = refreshed.get(cardId);
                if (result instanceof CardLookupResult.Missing) {
                    terminateRunning(entryKey, true, true, "card missing");
                } else if (result instanceof CardLookupResult.Failed failed) {
                    LOG.debugf(
                            "card_id=%s reconcile outcome=partial_refresh_failed reason=%s", cardId, failed.message());
                } else if (result instanceof CardLookupResult.Found found) {
                    Card card = found.card();
                    if (isOutOfBoardScope(card, launchConfig)) {
                        terminateRunning(entryKey, false, true, "card moved out of board");
                    } else if (TrelloClient.isTerminal(card, launchConfig)) {
                        terminateRunning(entryKey, true, true, "card terminal");
                    } else if (TrelloClient.isActive(card, launchConfig)) {
                        if (hasRequiredLabels(card, launchConfig)) {
                            synchronized (this) {
                                if (Objects.equals(running.get(entryKey), entry)) {
                                    entry.card = card;
                                }
                            }
                        } else {
                            terminateRunning(
                                    entryKey, false, false, "card is active but not currently dispatch-eligible");
                        }
                    } else {
                        terminateRunning(entryKey, false, true, "card no longer active");
                    }
                }
            }
        });
    }

    private void reconcileStalls() {
        Instant now = clock.instant();
        List<RuntimeCardKey> stalled = running.values().stream()
                .filter(entry -> entry.launchConfig.codex().stallTimeout().isPositive())
                .filter(entry -> Duration.between(entry.lastEventAt, now)
                                .compareTo(entry.launchConfig.codex().stallTimeout())
                        > 0)
                .map(SymphonyOrchestrator::runtimeKey)
                .toList();
        stalled.forEach(cardKey -> terminateRunning(cardKey, false, false, "stalled"));
    }

    private void terminateRunning(
            RuntimeCardKey cardKey, boolean cleanupWorkspace, boolean suppressRetry, String reason) {
        RunningEntry entry;
        boolean usageProbe;
        synchronized (this) {
            entry = running.remove(cardKey);
            if (entry == null) {
                return;
            }
            usageProbe = isBoundUsageProbe(entry.launchTarget, entry.cardId, entry.workerIdentity);
            ignoredWorkers.put(entry.workerIdentity, clock.instant().plus(IGNORED_WORKER_TTL));
            trimIgnoredWorkers();
            addRuntime(entry);
            if (suppressRetry) {
                claimed.remove(cardKey);
            }
        }
        if (usageProbe) {
            try {
                agentRunner.cancel(entry.workerIdentity);
                cancelWorkerTask(entry);
                if (cleanupWorkspace) {
                    workspaces.removeForIdentifierIfPresent(entry.identifier(), entry.launchConfig);
                }
                if (!suppressRetry) {
                    releaseCurrentFromDispatch(entry.launchConfig, entry.card, entry.dispatchSource, reason);
                }
            } finally {
                if (suppressRetry) {
                    retireUsageProbeWithoutResult(usageWorkpadTarget(entry.launchConfig, entry.cardId));
                } else {
                    rearmUsageProbeWithoutResult(
                            usageWorkpadTarget(entry.launchConfig, entry.cardId),
                            nextAttempt(entry.retryAttempt),
                            entry.identifier(),
                            entry.card.cardUrl(),
                            reason);
                }
            }
        } else {
            agentRunner.cancel(entry.workerIdentity);
            cancelWorkerTask(entry);
            if (cleanupWorkspace) {
                workspaces.removeForIdentifierIfPresent(entry.identifier(), entry.launchConfig);
            }
            if (!suppressRetry) {
                releaseCurrentFromDispatch(entry.launchConfig, entry.card, entry.dispatchSource, reason);
                if (isCurrentTrackerTarget(entry.launchTarget)) {
                    scheduleRetry(
                            entry.cardId,
                            nextAttempt(entry.retryAttempt),
                            entry.identifier(),
                            entry.card.cardUrl(),
                            reason,
                            false);
                } else {
                    removeClaim(cardKey);
                }
            }
        }
        LOG.infof(
                "card_id=%s card_identifier=%s outcome=terminated reason=%s", entry.cardId, entry.identifier(), reason);
    }

    private void dispatchCandidates() {
        if (isDispatchPaused()) {
            return;
        }
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

    private synchronized boolean isDispatchPaused() {
        return dispatchPause != null
                && Objects.equals(dispatchPause.command(), config.codex().command());
    }

    private Optional<Card> dispatch(Card card, Integer attempt) {
        EffectiveConfig launchConfig = config;
        RuntimeCardKey launchKey = runtimeKey(launchConfig, card.id());
        reconcileStaleUsageWorkpad(card);
        RetryEntry retry;
        synchronized (this) {
            claimed.add(launchKey);
            retry = retryAttempts.remove(launchKey);
        }
        if (retry != null) {
            retry.timer().cancel(false);
        }
        Card dispatchCard;
        try {
            dispatchCard = tracker.prepareForDispatch(launchConfig, card);
        } catch (RuntimeException e) {
            synchronized (this) {
                claimed.remove(launchKey);
            }
            releaseCurrentFromDispatch(card, card, "prepare for dispatch failed");
            scheduleRetry(card.id(), nextAttempt(attempt), card.identifier(), card.cardUrl(), e.getMessage(), false);
            return Optional.empty();
        }
        String workerIdentity = UUID.randomUUID().toString();
        RunningEntry entry =
                new RunningEntry(dispatchCard, card, workerIdentity, attempt, clock.instant(), launchConfig);
        RuntimeCardKey runningKey = runtimeKey(entry);
        synchronized (this) {
            if (!runningKey.equals(launchKey)) {
                claimed.remove(launchKey);
                claimed.add(runningKey);
            }
            running.put(runningKey, entry);
        }
        String prompt;
        try {
            prompt = prompts.render(workflow.promptTemplate(), dispatchCard, attempt);
        } catch (RuntimeException e) {
            synchronized (this) {
                running.remove(runningKey);
            }
            releaseFromDispatch(dispatchCard, card, "prompt render failed");
            scheduleRetry(
                    dispatchCard.id(),
                    nextAttempt(attempt),
                    dispatchCard.identifier(),
                    dispatchCard.cardUrl(),
                    e.getMessage(),
                    false);
            return Optional.empty();
        }
        bindUsageProbe(dispatchCard.id(), workerIdentity, launchConfig);
        try {
            entry.workerTask = workers.submit(() -> {
                try {
                    workerLaunchHookForTests.run();
                    AgentRunResult result = agentRunner.run(new AgentRunner.AgentRunRequest(
                            dispatchCard, attempt, prompt, launchConfig, workerIdentity, agentEventListener()));
                    onWorkerExit(runningKey, workerIdentity, result);
                } catch (RuntimeException e) {
                    onWorkerExitWithoutResult(runningKey, workerIdentity, e);
                }
            });
        } catch (RuntimeException e) {
            synchronized (this) {
                running.remove(runningKey);
            }
            releaseFromDispatch(dispatchCard, card, "worker submission failed");
            scheduleRetry(
                    dispatchCard.id(),
                    nextAttempt(attempt),
                    dispatchCard.identifier(),
                    dispatchCard.cardUrl(),
                    e.getMessage(),
                    false);
            return Optional.empty();
        }
        LOG.infof(
                "card_id=%s card_identifier=%s worker_identity=%s outcome=dispatched",
                dispatchCard.id(), dispatchCard.identifier(), workerIdentity);
        return Optional.of(dispatchCard);
    }

    private void reconcileStaleUsageWorkpad(Card card) {
        boolean hasManagedSection = card.comments().stream()
                .map(Card.Comment::text)
                .anyMatch(CodexUsageWorkpadSection::containsManagedSection);
        if (!hasManagedSection || isDispatchPaused()) {
            return;
        }
        queueUsageWorkpadCleanup(card.id());
        retryPendingUsageWorkpadCleanup(false);
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

    private void onWorkerExit(RuntimeCardKey cardKey, String workerIdentity, AgentRunResult result) {
        operationLock.lock();
        try {
            currentRunningEntry(cardKey, workerIdentity)
                    .ifPresent(entry -> handleWorkerExit(cardKey, workerIdentity, result, entry));
        } finally {
            try {
                workerExitCompletionHookForTests.run();
            } finally {
                operationLock.unlock();
            }
        }
    }

    private void onWorkerExitWithoutResult(RuntimeCardKey cardKey, String workerIdentity, RuntimeException failure) {
        operationLock.lock();
        try {
            currentRunningEntry(cardKey, workerIdentity).ifPresent(entry -> {
                String reason =
                        blank(failure.getMessage()) ? "agent runner failed without a result" : failure.getMessage();
                if (isBoundUsageProbe(entry.launchTarget, cardKey.cardId(), workerIdentity)) {
                    terminateRunning(cardKey, false, false, reason);
                } else {
                    handleWorkerExit(cardKey, workerIdentity, AgentRunResult.fail(reason), entry);
                }
            });
        } finally {
            operationLock.unlock();
        }
    }

    private void handleWorkerExit(
            RuntimeCardKey cardKey, String workerIdentity, AgentRunResult result, RunningEntry entry) {
        synchronized (this) {
            running.remove(cardKey);
            addRuntime(entry);
        }
        boolean currentTrackerTarget = isCurrentTrackerTarget(entry.launchTarget);
        boolean typedUsageLimit = result.failureCategory() == AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT;
        boolean commandScopedUsageLimit = typedUsageLimit && isCurrentCodexCommand(entry.launchCommand);
        DispatchPause usagePause = commandScopedUsageLimit ? installOrExtendUsagePause(entry, result) : null;
        if (!commandScopedUsageLimit && isBoundUsageProbe(entry.launchTarget, cardKey.cardId(), workerIdentity)) {
            clearUsagePauseAfterProbeResult(entry.launchTarget, cardKey.cardId(), workerIdentity);
        }
        WorkerExitState state = workerExitState(cardKey.cardId(), entry.identifier(), entry.launchConfig);
        if (state.retry() && !currentTrackerTarget) {
            releaseAfterFailedWorkerExit(entry, state, "tracker target changed during worker run");
            removeClaim(cardKey);
            return;
        }
        if (result.success()) {
            synchronized (this) {
                completed.add(cardKey);
            }
            if (state.retry()) {
                scheduleRetry(cardKey.cardId(), 1, entry.identifier(), entry.card.cardUrl(), null, true);
            } else {
                completeWorkerExit(cardKey, entry, state);
            }
        } else if (state.retry()) {
            releaseAfterFailedWorkerExit(entry, state, result.reason());
            if (commandScopedUsageLimit) {
                scheduleUsageLimitRetry(
                        cardKey.cardId(),
                        nextAttempt(entry.retryAttempt),
                        entry.identifier(),
                        entry.card.cardUrl(),
                        result.reason(),
                        usagePause);
            } else {
                scheduleRetry(
                        cardKey.cardId(),
                        nextAttempt(entry.retryAttempt),
                        entry.identifier(),
                        entry.card.cardUrl(),
                        result.reason(),
                        false);
            }
        } else {
            completeWorkerExit(cardKey, entry, state);
        }
    }

    private boolean isCurrentCodexCommand(String command) {
        return Objects.equals(command, config.codex().command());
    }

    private boolean isCurrentTrackerTarget(TrackerTarget target) {
        return target.equals(TrackerTarget.from(config));
    }

    private WorkerExitState workerExitState(String cardId, String identifier, EffectiveConfig workerConfig) {
        try {
            CardLookupResult result =
                    tracker.fetchCardStatesByIds(workerConfig, List.of(cardId)).get(cardId);
            return switch (result) {
                case CardLookupResult.Missing ignored -> WorkerExitState.complete(true, null);
                case CardLookupResult.Failed failed -> {
                    LOG.warnf(
                            "card_id=%s card_identifier=%s worker_exit_refresh=failed reason=%s",
                            cardId, identifier, failed.message());
                    yield WorkerExitState.retry(null);
                }
                case CardLookupResult.Found found -> workerExitState(found.card(), workerConfig);
                case null -> WorkerExitState.retry(null);
            };
        } catch (RuntimeException e) {
            LOG.warnf(
                    "card_id=%s card_identifier=%s worker_exit_refresh=failed reason=%s",
                    cardId, identifier, e.getMessage());
            return WorkerExitState.retry(null);
        }
    }

    private WorkerExitState workerExitState(Card card, EffectiveConfig workerConfig) {
        if (isOutOfBoardScope(card, workerConfig)) {
            return WorkerExitState.complete(false, card);
        }
        if (TrelloClient.isTerminal(card, workerConfig)) {
            return WorkerExitState.complete(true, card);
        }
        if (TrelloClient.isActive(card, workerConfig)) {
            return WorkerExitState.retry(card);
        }
        return WorkerExitState.complete(false, card);
    }

    private void completeWorkerExit(RuntimeCardKey cardKey, RunningEntry entry, WorkerExitState state) {
        synchronized (this) {
            claimed.remove(cardKey);
        }
        if (state.cleanupWorkspace()) {
            workspaces.removeForIdentifierIfPresent(entry.identifier(), entry.launchConfig);
        }
    }

    private void releaseAfterFailedWorkerExit(RunningEntry entry, WorkerExitState state, String reason) {
        if (state.card() == null) {
            releaseCurrentFromDispatch(entry.launchConfig, entry.card, entry.dispatchSource, reason);
            return;
        }
        releaseFromDispatch(entry.launchConfig, state.card(), entry.dispatchSource, reason);
    }

    private static void cancelWorkerTask(RunningEntry entry) {
        if (entry.workerTask != null) {
            entry.workerTask.cancel(true);
        }
    }

    private void onAgentEvent(AgentEvent event) {
        onAgentEventAndReportAccepted(event);
    }

    private boolean onAgentEventAndReportAccepted(AgentEvent event) {
        operationLock.lock();
        try {
            Optional<Map.Entry<RuntimeCardKey, RunningEntry>> ownedEntry = running.entrySet().stream()
                    .filter(entry -> entry.getValue().workerIdentity.equals(event.workerIdentity()))
                    .findAny();
            if (ownedEntry.isEmpty()) {
                return false;
            }
            RuntimeCardKey cardKey = ownedEntry.orElseThrow().getKey();
            Optional<RunningEntry> currentEntry = currentRunningEntry(cardKey, event.workerIdentity());
            if (currentEntry.isEmpty()) {
                return false;
            }
            applyAgentEvent(cardKey, currentEntry.orElseThrow(), event);
            return true;
        } finally {
            operationLock.unlock();
        }
    }

    private AgentEventListener agentEventListener() {
        return new AgentEventListener() {
            @Override
            public void onEvent(AgentEvent event) {
                SymphonyOrchestrator.this.onAgentEvent(event);
            }

            @Override
            public boolean onEventAndReportAccepted(AgentEvent event) {
                return SymphonyOrchestrator.this.onAgentEventAndReportAccepted(event);
            }
        };
    }

    private synchronized void applyAgentEvent(RuntimeCardKey cardKey, RunningEntry entry, AgentEvent event) {
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
        if ("account/rateLimits/updated".equals(event.event())
                && event.payload() != null
                && !event.payload().isNull()) {
            rateLimitsByCommand.put(entry.launchCommand, event.payload());
        }
        addRecentEvent(cardKey, new CardDebugDetails.EventInfo(event.timestamp(), event.event(), event.message()));
    }

    private synchronized Optional<RunningEntry> currentRunningEntry(RuntimeCardKey cardKey, String workerIdentity) {
        removeExpiredIgnoredWorkers();
        if (ignoredWorkers.remove(workerIdentity) != null) {
            LOG.debugf("card_id=%s worker_identity=%s outcome=ignored_stale_worker", cardKey.cardId(), workerIdentity);
            return Optional.empty();
        }
        RunningEntry entry = running.get(cardKey);
        if (entry == null || !entry.workerIdentity.equals(workerIdentity)) {
            LOG.debugf(
                    "card_id=%s worker_identity=%s outcome=ignored_unknown_worker", cardKey.cardId(), workerIdentity);
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

    private DispatchPause installOrExtendUsagePause(RunningEntry entry, AgentRunResult result) {
        String cardId = entry.cardId;
        String workerIdentity = entry.workerIdentity;
        EffectiveConfig currentConfig = config;
        EffectiveConfig workpadOwnerConfig =
                entry.launchTarget.equals(TrackerTarget.from(currentConfig)) ? currentConfig : entry.launchConfig;
        UsageWorkpadTarget workpadTarget = new UsageWorkpadTarget(entry.launchTarget, cardId);
        Instant detectedAt = clock.instant();
        Instant reportedReset = result.retryNotBefore()
                .filter(candidate -> candidate.isAfter(detectedAt))
                .orElseGet(() -> detectedAt.plus(usageProbeFallbackDelay(currentConfig)));
        String message = usageLimitMessage(result.reason());
        DispatchPause pause;
        Map<UsageWorkpadTarget, UsageWorkpadState> workpadsToUpdate;
        synchronized (this) {
            pendingUsageWorkpadCleanup.remove(workpadTarget);
            usageWorkpadMessages.put(workpadTarget, new UsageWorkpadState(workpadOwnerConfig, message));
            if (dispatchPause == null) {
                dispatchPauseGeneration++;
                dispatchPause = new DispatchPause(
                        entry.launchCommand, dispatchPauseGeneration, detectedAt, reportedReset, Optional.empty());
            } else {
                Optional<UsageProbe> retainedProbe =
                        dispatchPause.probe().filter(probe -> !probe.matches(workpadTarget, workerIdentity));
                Instant extendedUntil =
                        reportedReset.isAfter(dispatchPause.until()) ? reportedReset : dispatchPause.until();
                dispatchPause = new DispatchPause(
                        dispatchPause.command(),
                        dispatchPause.generation(),
                        dispatchPause.detectedAt(),
                        extendedUntil,
                        retainedProbe);
            }
            pause = dispatchPause;
            scheduleDispatchPauseDeadline(pause);
            for (RetryEntry retry : List.copyOf(retryAttempts.values())) {
                Instant deferredUntil = retry.dueAt().isAfter(pause.until()) ? retry.dueAt() : pause.until();
                rescheduleRetryEntry(retry, deferredUntil);
            }
            workpadsToUpdate = Map.copyOf(usageWorkpadMessages);
        }
        workpadsToUpdate.forEach((target, state) -> updateUsageWorkpad(
                state.ownerConfig(), target.cardId(), CodexUsageWorkpadSection.paused(state.message(), pause.until())));
        LOG.infof(
                "codex_dispatch_pause=%s outcome=active detected_at=%s until=%s",
                CODEX_USAGE_LIMIT_PAUSE, pause.detectedAt(), pause.until());
        return pause;
    }

    private static String usageLimitMessage(String reason) {
        int separator = reason.indexOf(": ");
        return separator < 0 ? reason : reason.substring(separator + 2);
    }

    private Duration usageProbeFallbackDelay() {
        return usageProbeFallbackDelay(config);
    }

    private static Duration usageProbeFallbackDelay(EffectiveConfig fallbackConfig) {
        Duration configured = fallbackConfig.agent().maxRetryBackoff();
        return configured.isZero() || configured.isNegative() ? CONTINUATION_DELAY : configured;
    }

    private synchronized void bindUsageProbe(String cardId, String workerIdentity, EffectiveConfig launchConfig) {
        if (dispatchPause == null
                || !Objects.equals(dispatchPause.command(), launchConfig.codex().command())) {
            return;
        }
        Optional<UsageProbe> probe = dispatchPause.probe();
        if (probe.isEmpty()) {
            return;
        }
        UsageProbe launching = probe.orElseThrow();
        UsageWorkpadTarget target = usageWorkpadTarget(launchConfig, cardId);
        if (launching.target().equals(target) && launching.workerIdentity().isEmpty()) {
            dispatchPause = dispatchPause.probing(target, workerIdentity);
        }
    }

    private synchronized boolean isUsageProbeCard(TrackerTarget trackerTarget, String cardId) {
        UsageWorkpadTarget target = new UsageWorkpadTarget(trackerTarget, cardId);
        return dispatchPause != null
                && dispatchPause
                        .probe()
                        .map(UsageProbe::target)
                        .map(target::equals)
                        .orElse(false);
    }

    private synchronized boolean isBoundUsageProbe(TrackerTarget trackerTarget, String cardId, String workerIdentity) {
        UsageWorkpadTarget target = new UsageWorkpadTarget(trackerTarget, cardId);
        return dispatchPause != null
                && dispatchPause
                        .probe()
                        .filter(probe -> probe.matches(target, workerIdentity))
                        .isPresent();
    }

    private synchronized String usageWorkpadMessage(UsageWorkpadTarget target) {
        UsageWorkpadState state = usageWorkpadMessages.get(target);
        return state == null ? "Codex usage is temporarily unavailable." : state.message();
    }

    private boolean updateUsageWorkpad(EffectiveConfig ownerConfig, String cardId, String section) {
        boolean updated = usageWorkpads
                .map(handler -> handler.updateCodexUsageSection(ownerConfig, cardId, section))
                .orElse(true);
        if (!updated) {
            LOG.warnf("card_id=%s codex_usage_workpad=failed", cardId);
        }
        return updated;
    }

    private synchronized void queueUsageWorkpadCleanup(String cardId) {
        queueUsageWorkpadCleanup(cardId, config);
    }

    private synchronized void queueUsageWorkpadCleanup(String cardId, EffectiveConfig ownerConfig) {
        queueUsageWorkpadCleanup(usageWorkpadTarget(ownerConfig, cardId), ownerConfig);
    }

    private synchronized void queueUsageWorkpadCleanup(UsageWorkpadTarget target, EffectiveConfig ownerConfig) {
        Instant now = clock.instant();
        pendingUsageWorkpadCleanup.compute(
                target,
                (ignored, existing) -> existing == null || !existing.expiresAt().isAfter(now)
                        ? new UsageWorkpadCleanup(ownerConfig, now.plus(USAGE_WORKPAD_CLEANUP_TTL), now)
                        : existing);
        while (pendingUsageWorkpadCleanup.size() > USAGE_WORKPAD_CLEANUP_LIMIT) {
            UsageWorkpadTarget oldestTarget =
                    pendingUsageWorkpadCleanup.keySet().iterator().next();
            pendingUsageWorkpadCleanup.remove(oldestTarget);
            LOG.warnf("card_id=%s codex_usage_workpad_cleanup=evicted", oldestTarget.cardId());
        }
    }

    private static UsageWorkpadTarget usageWorkpadTarget(EffectiveConfig ownerConfig, String cardId) {
        return new UsageWorkpadTarget(TrackerTarget.from(ownerConfig), cardId);
    }

    private void retryPendingUsageWorkpadCleanup(boolean force) {
        Instant now = clock.instant();
        List<PendingUsageWorkpadCleanup> pending;
        synchronized (this) {
            pendingUsageWorkpadCleanup
                    .entrySet()
                    .removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            pending = pendingUsageWorkpadCleanup.entrySet().stream()
                    .filter(entry -> force || !entry.getValue().nextAttemptAt().isAfter(now))
                    .map(entry -> new PendingUsageWorkpadCleanup(entry.getKey(), entry.getValue()))
                    .toList();
        }
        pending.forEach(pendingCleanup -> {
            UsageWorkpadTarget target = pendingCleanup.target();
            UsageWorkpadCleanup attempted = pendingCleanup.cleanup();
            boolean updated = updateUsageWorkpad(attempted.ownerConfig(), target.cardId(), null);
            synchronized (this) {
                if (updated) {
                    pendingUsageWorkpadCleanup.remove(target);
                } else {
                    pendingUsageWorkpadCleanup.put(
                            target,
                            new UsageWorkpadCleanup(
                                    attempted.ownerConfig(),
                                    attempted.expiresAt(),
                                    now.plus(USAGE_WORKPAD_CLEANUP_RETRY_DELAY)));
                }
            }
        });
    }

    private synchronized void scheduleDispatchPauseDeadline(DispatchPause pause) {
        if (dispatchPauseTimer != null) {
            dispatchPauseTimer.cancel(false);
        }
        long delayMillis = delayMillisUntil(pause.until());
        dispatchPauseTimer = scheduler.schedule(
                () -> onDispatchPauseDeadline(pause.command(), pause.generation(), pause.until()),
                delayMillis,
                TimeUnit.MILLISECONDS);
        dispatchPauseScheduleHookForTests.run();
    }

    private void onDispatchPauseDeadline(String expectedCommand, long expectedGeneration, Instant expectedUntil) {
        operationLock.lock();
        try {
            if (!isStarted()) {
                return;
            }
            synchronized (this) {
                if (dispatchPause == null
                        || !Objects.equals(dispatchPause.command(), expectedCommand)
                        || dispatchPause.generation() != expectedGeneration
                        || !dispatchPause.until().equals(expectedUntil)) {
                    return;
                }
                if (clock.instant().isBefore(expectedUntil)) {
                    scheduleDispatchPauseDeadline(dispatchPause);
                    return;
                }
                if (dispatchPause.probe().isPresent()) {
                    return;
                }
            }
            selectUsageProbeAtDeadline();
        } finally {
            operationLock.unlock();
        }
    }

    private void selectUsageProbeAtDeadline() {
        Optional<RetryEntry> selectedRetry;
        synchronized (this) {
            // Retry entries retain insertion order. Prefer the oldest due card that observed the
            // usage limit, then the oldest due deferred retry, before probing a new candidate.
            Instant now = clock.instant();
            selectedRetry = retryAttempts.values().stream()
                    .filter(retry -> Objects.equals(retry.codexCommand(), dispatchPause.command()))
                    .filter(retry -> retry.trackerTarget().equals(TrackerTarget.from(config)))
                    .filter(RetryEntry::codexUsageLimit)
                    .filter(retry -> !retry.dueAt().isAfter(now))
                    .findFirst()
                    .or(() -> retryAttempts.values().stream()
                            .filter(retry -> Objects.equals(retry.codexCommand(), dispatchPause.command()))
                            .filter(retry -> retry.trackerTarget().equals(TrackerTarget.from(config)))
                            .filter(retry -> !retry.dueAt().isAfter(now))
                            .findFirst());
            selectedRetry.filter(retry -> !retry.codexUsageLimit()).ifPresent(this::promoteToUsageProbeRetry);
        }
        selectedRetry.ifPresentOrElse(
                retry -> handleRetryTimer(runtimeKey(retry), retry.generation()),
                this::probeOneCandidateAtUsageDeadline);
    }

    private synchronized void promoteToUsageProbeRetry(RetryEntry retry) {
        RuntimeCardKey retryKey = runtimeKey(retry);
        RetryEntry current = retryAttempts.get(retryKey);
        if (retry.equals(current)) {
            retryAttempts.put(
                    retryKey,
                    new RetryEntry(
                            retry.cardId(),
                            retry.identifier(),
                            retry.cardUrl(),
                            retry.attempt(),
                            retry.dueAt(),
                            retry.naturalDueAt(),
                            retry.codexCommand(),
                            retry.trackerTarget(),
                            retry.generation(),
                            retry.timer(),
                            retry.error(),
                            true));
        }
    }

    private void probeOneCandidateAtUsageDeadline() {
        Optional<Card> candidate;
        try {
            List<Card> fetched = tracker.fetchCandidateCards(config);
            candidate = fetched.stream()
                    .sorted(TrelloClient.dispatchComparator(config, prerequisitePriorityOverrides(fetched)))
                    .filter(card -> shouldDispatch(card, false))
                    .findFirst();
        } catch (RuntimeException e) {
            LOG.warnf("codex_dispatch_pause=%s probe_candidate=failed", CODEX_USAGE_LIMIT_PAUSE);
            rearmUsagePauseWithoutProbe();
            return;
        }
        if (candidate.isEmpty()) {
            rearmUsagePauseWithoutProbe();
            return;
        }
        Card probeCard = candidate.orElseThrow();
        UsageWorkpadTarget workpadTarget = usageWorkpadTarget(config, probeCard.id());
        synchronized (this) {
            if (dispatchPause == null || dispatchPause.probe().isPresent()) {
                return;
            }
            usageWorkpadMessages.putIfAbsent(
                    workpadTarget, new UsageWorkpadState(config, "Codex usage is temporarily unavailable."));
            pendingUsageWorkpadCleanup.remove(workpadTarget);
            dispatchPause = dispatchPause.launching(workpadTarget);
        }
        updateUsageWorkpad(
                config, probeCard.id(), CodexUsageWorkpadSection.rechecking(usageWorkpadMessage(workpadTarget)));
        boolean workerStarted = false;
        try {
            workerStarted = refreshForDispatch(probeCard)
                    .flatMap(refreshed -> dispatch(refreshed, null))
                    .isPresent();
        } finally {
            if (!workerStarted) {
                rearmUsageProbeWithoutResult(
                        workpadTarget,
                        1,
                        probeCard.identifier(),
                        probeCard.cardUrl(),
                        "usage availability candidate probe did not start");
            }
        }
    }

    private void rearmUsagePauseWithoutProbe() {
        DispatchPause pause;
        Map<UsageWorkpadTarget, UsageWorkpadState> workpadsToUpdate;
        synchronized (this) {
            if (dispatchPause == null || dispatchPause.probe().isPresent()) {
                return;
            }
            Instant now = clock.instant();
            Instant fallbackDeadline = now.plus(usageProbeFallbackDelay());
            Instant nextDeadline = retryAttempts.values().stream()
                    .map(RetryEntry::dueAt)
                    .filter(dueAt -> dueAt.isAfter(now) && dueAt.isBefore(fallbackDeadline))
                    .min(Instant::compareTo)
                    .orElse(fallbackDeadline);
            dispatchPause = new DispatchPause(
                    dispatchPause.command(),
                    dispatchPause.generation(),
                    dispatchPause.detectedAt(),
                    nextDeadline,
                    Optional.empty());
            pause = dispatchPause;
            scheduleDispatchPauseDeadline(pause);
            for (RetryEntry retry : List.copyOf(retryAttempts.values())) {
                Instant deferredUntil = retry.dueAt().isAfter(nextDeadline) ? retry.dueAt() : nextDeadline;
                rescheduleRetryEntry(retry, deferredUntil);
            }
            workpadsToUpdate = Map.copyOf(usageWorkpadMessages);
        }
        workpadsToUpdate.forEach((target, state) -> updateUsageWorkpad(
                state.ownerConfig(), target.cardId(), CodexUsageWorkpadSection.paused(state.message(), pause.until())));
        LOG.infof(
                "codex_dispatch_pause=%s outcome=rearmed_without_candidate detected_at=%s until=%s",
                CODEX_USAGE_LIMIT_PAUSE, pause.detectedAt(), pause.until());
    }

    private void clearUsagePauseAfterProbeResult(TrackerTarget trackerTarget, String cardId, String workerIdentity) {
        if (!isBoundUsageProbe(trackerTarget, cardId, workerIdentity)) {
            return;
        }
        clearUsagePause();
    }

    private void clearUsagePause() {
        Map<UsageWorkpadTarget, UsageWorkpadState> workpadsToClear;
        synchronized (this) {
            if (dispatchPause == null) {
                return;
            }
            dispatchPause = null;
            if (dispatchPauseTimer != null) {
                dispatchPauseTimer.cancel(false);
                dispatchPauseTimer = null;
            }
            workpadsToClear = Map.copyOf(usageWorkpadMessages);
            for (Map.Entry<UsageWorkpadTarget, UsageWorkpadState> entry : workpadsToClear.entrySet()) {
                queueUsageWorkpadCleanup(entry.getKey(), entry.getValue().ownerConfig());
            }
            usageWorkpadMessages.clear();
            Instant now = clock.instant();
            for (RetryEntry retry : List.copyOf(retryAttempts.values())) {
                Instant dueAt = retry.naturalDueAt().isAfter(now) ? retry.naturalDueAt() : now;
                rescheduleRetryEntry(retry, dueAt);
            }
            if (started) {
                scheduleTick(Duration.ZERO);
            }
        }
        retryPendingUsageWorkpadCleanup(false);
        LOG.infof("codex_dispatch_pause=%s outcome=cleared", CODEX_USAGE_LIMIT_PAUSE);
    }

    private void rearmUsageProbeWithoutResult(
            UsageWorkpadTarget workpadTarget, int attempt, String identifier, String cardUrl, String error) {
        DispatchPause pause;
        RetryEntry queued;
        Map<UsageWorkpadTarget, UsageWorkpadState> workpadsToUpdate;
        synchronized (this) {
            if (!isUsageProbeCard(workpadTarget.trackerTarget(), workpadTarget.cardId())) {
                return;
            }
            queued = retryAttempts.remove(runtimeKey(workpadTarget.trackerTarget(), workpadTarget.cardId()));
            if (queued != null) {
                queued.timer().cancel(false);
            }
            Instant fallbackDeadline = clock.instant().plus(usageProbeFallbackDelay());
            Instant nextDeadline =
                    fallbackDeadline.isAfter(dispatchPause.until()) ? fallbackDeadline : dispatchPause.until();
            dispatchPause = new DispatchPause(
                    dispatchPause.command(),
                    dispatchPause.generation(),
                    dispatchPause.detectedAt(),
                    nextDeadline,
                    Optional.empty());
            pause = dispatchPause;
            scheduleDispatchPauseDeadline(pause);
            for (RetryEntry retry : List.copyOf(retryAttempts.values())) {
                Instant deferredUntil = retry.dueAt().isAfter(nextDeadline) ? retry.dueAt() : nextDeadline;
                rescheduleRetryEntry(retry, deferredUntil);
            }
            workpadsToUpdate = Map.copyOf(usageWorkpadMessages);
        }
        int retryAttempt = queued == null ? attempt : queued.attempt();
        String retryIdentifier = queued == null ? identifier : queued.identifier();
        String retryCardUrl = queued == null ? cardUrl : queued.cardUrl();
        String retryError = queued == null ? error : queued.error();
        scheduleRetryAt(
                workpadTarget.cardId(),
                retryAttempt,
                retryIdentifier,
                retryCardUrl,
                retryError,
                pause.until(),
                true,
                pause.command(),
                workpadTarget.trackerTarget());
        workpadsToUpdate.forEach((target, state) -> updateUsageWorkpad(
                state.ownerConfig(), target.cardId(), CodexUsageWorkpadSection.paused(state.message(), pause.until())));
        LOG.infof(
                "codex_dispatch_pause=%s outcome=rearmed detected_at=%s until=%s",
                CODEX_USAGE_LIMIT_PAUSE, pause.detectedAt(), pause.until());
    }

    private void scheduleRetry(
            String cardId, int attempt, String identifier, String cardUrl, String error, boolean continuation) {
        Duration delay = continuation ? CONTINUATION_DELAY : backoff(attempt);
        scheduleRetryAt(
                cardId,
                attempt,
                identifier,
                cardUrl,
                error,
                clock.instant().plus(delay),
                false,
                config.codex().command(),
                TrackerTarget.from(config));
    }

    private void scheduleUsageLimitRetry(
            String cardId, int attempt, String identifier, String cardUrl, String error, DispatchPause pause) {
        scheduleRetryAt(
                cardId,
                attempt,
                identifier,
                cardUrl,
                error,
                pause.until(),
                true,
                pause.command(),
                TrackerTarget.from(config));
    }

    private void scheduleRetryAt(
            String cardId,
            int attempt,
            String identifier,
            String cardUrl,
            String error,
            Instant naturalDueAt,
            boolean codexUsageLimit,
            String codexCommand,
            TrackerTarget trackerTarget) {
        Instant scheduledAt;
        long delayMillis;
        RuntimeCardKey retryKey = runtimeKey(trackerTarget, cardId);
        synchronized (this) {
            scheduledAt = dispatchPause != null
                            && Objects.equals(dispatchPause.command(), codexCommand)
                            && dispatchPause.until().isAfter(naturalDueAt)
                    ? dispatchPause.until()
                    : naturalDueAt;
            delayMillis = delayMillisUntil(scheduledAt);
            RetryEntry existing = retryAttempts.remove(retryKey);
            if (existing != null) {
                existing.timer().cancel(false);
            }
            retryGeneration++;
            long generation = retryGeneration;
            ScheduledFuture<?> timer =
                    scheduler.schedule(() -> onRetryTimer(retryKey, generation), delayMillis, TimeUnit.MILLISECONDS);
            retryAttempts.put(
                    retryKey,
                    new RetryEntry(
                            cardId,
                            identifier,
                            cardUrl,
                            attempt,
                            scheduledAt,
                            naturalDueAt,
                            codexCommand,
                            trackerTarget,
                            generation,
                            timer,
                            error,
                            codexUsageLimit));
            claimed.add(retryKey);
        }
        LOG.infof(
                "card_id=%s card_identifier=%s outcome=retrying attempt=%d delay_ms=%d",
                cardId, identifier, attempt, delayMillis);
    }

    private void onRetryTimer(RuntimeCardKey cardKey, long generation) {
        retryTimerWaitingHookForTests.run();
        operationLock.lock();
        try {
            if (!isStarted()) {
                return;
            }
            handleRetryTimer(cardKey, generation);
        } finally {
            operationLock.unlock();
        }
    }

    private void handleRetryTimer(RuntimeCardKey cardKey, long generation) {
        RetryEntry pendingRetry;
        synchronized (this) {
            pendingRetry = retryAttempts.get(cardKey);
        }
        if (pendingRetry == null
                || pendingRetry.generation() != generation
                || deferRetryForDispatchPause(pendingRetry)) {
            return;
        }
        RetryEntry retry;
        synchronized (this) {
            RetryEntry current = retryAttempts.get(cardKey);
            retry = current != null && current.generation() == generation ? retryAttempts.remove(cardKey) : null;
        }
        if (retry == null) {
            return;
        }
        retry.timer().cancel(false);
        String cardId = cardKey.cardId();
        UsageWorkpadTarget workpadTarget = new UsageWorkpadTarget(retry.trackerTarget(), cardId);
        boolean usageRecheck = isUsageProbeCard(retry.trackerTarget(), cardId);
        boolean recheckWorkerStarted = false;
        boolean retireUsageProbe = false;
        try {
            Map<String, CardLookupResult> refreshed;
            try {
                refreshed = usageRecheck
                        ? tracker.fetchCardStatesForPromptByIds(config, List.of(cardId))
                        : tracker.fetchCardStatesByIds(config, List.of(cardId));
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
                retireUsageProbe = usageRecheck;
                removeClaim(cardKey);
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
                    retireUsageProbe = usageRecheck;
                    removeClaim(cardKey);
                } else if (TrelloClient.isTerminal(card, config)) {
                    retireUsageProbe = usageRecheck;
                    removeClaim(cardKey);
                    workspaces.removeForIdentifierIfPresent(card.identifier(), config);
                } else if (!TrelloClient.isActive(card, config)) {
                    retireUsageProbe = usageRecheck;
                    removeClaim(cardKey);
                } else if (!shouldDispatchIgnoringSlots(card, true)) {
                    retireUsageProbe = usageRecheck;
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
                    removeClaim(cardKey);
                    recheckWorkerStarted = refreshForDispatch(card)
                            .flatMap(promptCard -> dispatch(promptCard, retry.attempt()))
                            .isPresent();
                }
            }
        } finally {
            if (usageRecheck && !recheckWorkerStarted) {
                if (retireUsageProbe) {
                    retireUsageProbeWithoutResult(workpadTarget);
                } else {
                    rearmUsageProbeWithoutResult(
                            workpadTarget,
                            retry.attempt(),
                            retry.identifier(),
                            retry.cardUrl(),
                            "usage availability recheck did not start");
                }
            }
        }
    }

    private void retireUsageProbeWithoutResult(UsageWorkpadTarget workpadTarget) {
        boolean cleanupWorkpad;
        synchronized (this) {
            if (!isUsageProbeCard(workpadTarget.trackerTarget(), workpadTarget.cardId())) {
                return;
            }
            UsageWorkpadState state = usageWorkpadMessages.remove(workpadTarget);
            cleanupWorkpad = state != null;
            if (cleanupWorkpad) {
                queueUsageWorkpadCleanup(workpadTarget, state.ownerConfig());
            }
            Instant transferAt = clock.instant();
            dispatchPause = new DispatchPause(
                    dispatchPause.command(),
                    dispatchPause.generation(),
                    dispatchPause.detectedAt(),
                    transferAt,
                    Optional.empty());
            scheduleDispatchPauseDeadline(dispatchPause);
        }
        if (cleanupWorkpad) {
            retryPendingUsageWorkpadCleanup(false);
        }
        LOG.infof("card_id=%s codex_dispatch_pause=%s probe=retired", workpadTarget.cardId(), CODEX_USAGE_LIMIT_PAUSE);
    }

    private boolean deferRetryForDispatchPause(RetryEntry retry) {
        DispatchPause pause;
        boolean beginRecheck = false;
        synchronized (this) {
            pause = dispatchPause;
            if (pause == null) {
                return false;
            }
            if (!Objects.equals(pause.command(), retry.codexCommand())) {
                return false;
            }
            if (pause.probe().isPresent()) {
                return true;
            }
            Instant now = clock.instant();
            if (now.isBefore(pause.until())) {
                rescheduleRetryEntry(retry, pause.until());
                return true;
            }
            if (!retry.codexUsageLimit()) {
                return true;
            }
            UsageWorkpadTarget workpadTarget = new UsageWorkpadTarget(retry.trackerTarget(), retry.cardId());
            usageWorkpadMessages.putIfAbsent(
                    workpadTarget, new UsageWorkpadState(config, "Codex usage is temporarily unavailable."));
            pendingUsageWorkpadCleanup.remove(workpadTarget);
            dispatchPause = pause.launching(workpadTarget);
            if (dispatchPauseTimer != null) {
                dispatchPauseTimer.cancel(false);
            }
            beginRecheck = true;
        }
        if (beginRecheck) {
            UsageWorkpadTarget workpadTarget = new UsageWorkpadTarget(retry.trackerTarget(), retry.cardId());
            UsageWorkpadState state = usageWorkpadMessages.get(workpadTarget);
            updateUsageWorkpad(
                    state.ownerConfig(),
                    retry.cardId(),
                    CodexUsageWorkpadSection.rechecking(usageWorkpadMessage(workpadTarget)));
        }
        return false;
    }

    private void rescheduleRetryEntry(RetryEntry retry, Instant dueAt) {
        retryEntryRescheduleHookForTests.accept(retry.cardId(), retry.generation());
        synchronized (this) {
            RetryEntry current = retryAttempts.get(runtimeKey(retry));
            if (current == null) {
                return;
            }
            retry.timer().cancel(false);
            replaceRetryEntry(
                    retry,
                    dueAt,
                    retry.naturalDueAt(),
                    retry.codexCommand(),
                    retry.trackerTarget(),
                    retry.codexUsageLimit());
        }
    }

    /** Called with the state monitor held. */
    private void rescheduleRetryForCommandChange(RetryEntry retry, EffectiveConfig nextConfig, Instant now) {
        RetryEntry current = retryAttempts.get(runtimeKey(retry));
        if (current == null || current.generation() != retry.generation()) {
            return;
        }
        retry.timer().cancel(false);
        Instant naturalDueAt = retry.codexUsageLimit() ? now : retry.naturalDueAt();
        Instant dueAt = naturalDueAt.isAfter(now) ? naturalDueAt : now;
        replaceRetryEntry(
                retry, dueAt, naturalDueAt, nextConfig.codex().command(), TrackerTarget.from(nextConfig), false);
    }

    /** Called with the state monitor held after the prior timer has been cancelled. */
    private void replaceRetryEntry(
            RetryEntry retry,
            Instant dueAt,
            Instant naturalDueAt,
            String codexCommand,
            TrackerTarget trackerTarget,
            boolean codexUsageLimit) {
        long delayMillis = delayMillisUntil(dueAt);
        retryGeneration++;
        long generation = retryGeneration;
        RuntimeCardKey previousKey = runtimeKey(retry);
        RuntimeCardKey replacementKey = runtimeKey(trackerTarget, retry.cardId());
        ScheduledFuture<?> timer =
                scheduler.schedule(() -> onRetryTimer(replacementKey, generation), delayMillis, TimeUnit.MILLISECONDS);
        if (!previousKey.equals(replacementKey)) {
            retryAttempts.remove(previousKey);
            claimed.remove(previousKey);
            claimed.add(replacementKey);
        }
        retryAttempts.put(
                replacementKey,
                new RetryEntry(
                        retry.cardId(),
                        retry.identifier(),
                        retry.cardUrl(),
                        retry.attempt(),
                        dueAt,
                        naturalDueAt,
                        codexCommand,
                        trackerTarget,
                        generation,
                        timer,
                        retry.error(),
                        codexUsageLimit));
    }

    private long delayMillisUntil(Instant dueAt) {
        Duration remaining = Duration.between(clock.instant(), dueAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return 0;
        }
        try {
            return Math.max(remaining.toMillis(), 1);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private synchronized void removeClaim(RuntimeCardKey cardKey) {
        claimed.remove(cardKey);
    }

    private boolean shouldDispatch(Card card, boolean ignoreClaim) {
        return shouldDispatchIgnoringSlots(card, ignoreClaim) && availableSlots() > 0 && perStateSlots(card) > 0;
    }

    private boolean canDispatch(Card card, DispatchBudget budget) {
        return shouldDispatchIgnoringSlots(card, false) && budget.hasSlotFor(card);
    }

    private boolean shouldDispatchIgnoringSlots(Card card, boolean ignoreClaim) {
        RuntimeCardKey cardKey = currentRuntimeKey(card.id());
        return isCandidateEligibleIgnoringRuntimeState(card)
                && (ignoreClaim || !claimed.contains(cardKey))
                && !running.containsKey(cardKey);
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
        return hasRequiredLabels(card, config);
    }

    private static boolean hasRequiredLabels(Card card, EffectiveConfig eligibilityConfig) {
        List<String> requiredLabels = eligibilityConfig.tracker().requiredLabels();
        if (requiredLabels.isEmpty()) {
            return true;
        }
        if (requiredLabels.stream().anyMatch(String::isBlank)) {
            return false;
        }
        Set<String> cardLabels =
                card.labels().stream().map(StateNames::normalize).collect(toImmutableSet());
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
        RuntimeCardKey cardKey = currentRuntimeKey(card.id());
        return running.containsKey(cardKey) || claimed.contains(cardKey);
    }

    private void releaseFromDispatch(Card card, String reason) {
        releaseFromDispatch(card, card, reason);
    }

    private void releaseFromDispatch(Card card, Card dispatchSource, String reason) {
        releaseFromDispatch(config, card, dispatchSource, reason);
    }

    private void releaseFromDispatch(EffectiveConfig releaseConfig, Card card, Card dispatchSource, String reason) {
        if (!isConfiguredInProgress(releaseConfig, card)) {
            return;
        }
        try {
            tracker.releaseFromDispatch(releaseConfig, card, dispatchSource);
            LOG.infof(
                    "card_id=%s card_identifier=%s outcome=released_from_in_progress reason=%s",
                    card.id(), card.identifier(), reason);
        } catch (RuntimeException e) {
            LOG.warnf(
                    "card_id=%s card_identifier=%s outcome=release_from_in_progress_failed reason=%s",
                    card.id(), card.identifier(), e.getMessage());
        }
    }

    private void releaseCurrentFromDispatch(Card card, Card dispatchSource, String reason) {
        releaseCurrentFromDispatch(config, card, dispatchSource, reason);
    }

    private void releaseCurrentFromDispatch(
            EffectiveConfig releaseConfig, Card card, Card dispatchSource, String reason) {
        if (blank(releaseConfig.tracker().inProgressState())) {
            return;
        }
        try {
            CardLookupResult result = tracker.fetchCardStatesByIds(releaseConfig, List.of(card.id()))
                    .get(card.id());
            if (result instanceof CardLookupResult.Found found) {
                releaseFromDispatch(releaseConfig, found.card(), dispatchSource, reason);
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
        return isConfiguredInProgress(config, card);
    }

    private static boolean isConfiguredInProgress(EffectiveConfig checkConfig, Card card) {
        return StateNames.normalize(checkConfig.tracker().inProgressState()).equals(StateNames.normalize(card.state()));
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
                .collect(Collectors.toMap(Function.identity(), state -> 1, Integer::sum, HashMap::new));
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
            Map<String, Integer> usedByState = runningEntries.stream()
                    .map(entry -> StateNames.normalize(entry.card.state()))
                    .collect(Collectors.toMap(Function.identity(), state -> 1, Integer::sum, HashMap::new));
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

    private static final class WorkflowProcessLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private WorkflowProcessLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static WorkflowProcessLock acquire(Path workflowPath) {
            Path lockPath = lockPath(workflowPath);
            Path lockDirectory = lockPath.getParent();
            if (lockDirectory == null) {
                throw new WorkflowException(
                        "workflow_lock_unavailable",
                        "Workflow runtime lock requires a configured state home or workflow directory.");
            }
            try {
                Files.createDirectories(lockDirectory);
                FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    FileLock lock = tryWorkflowLock(channel);
                    if (lock == null) {
                        closeQuietly(channel);
                        throw new WorkflowException(
                                "workflow_already_running",
                                "Another Symphony for Trello process is already using this workflow file.");
                    }
                    return new WorkflowProcessLock(channel, lock);
                } catch (IOException e) {
                    closeQuietly(channel);
                    throw e;
                }
            } catch (IOException e) {
                throw new WorkflowException(
                        "workflow_lock_unavailable", "Workflow runtime lock cannot be acquired.", e);
            }
        }

        private static Path lockPath(Path workflowPath) {
            try {
                Path canonicalWorkflowPath = workflowPath.toRealPath();
                String digest = Sha3.sha3_256(canonicalWorkflowPath.toString());
                return fallbackLockDirectory(canonicalWorkflowPath).resolve(digest + ".lock");
            } catch (IOException e) {
                throw new WorkflowException(
                        "workflow_lock_unavailable", "Workflow runtime lock cannot resolve the workflow file.", e);
            }
        }

        private static Path fallbackLockDirectory(Path canonicalWorkflowPath) {
            Path workflowParent = canonicalWorkflowPath.getParent();
            if (workflowParent == null) {
                throw new WorkflowException(
                        "workflow_lock_unavailable",
                        "Workflow runtime lock requires a configured state home or workflow directory.");
            }
            return workflowParent.resolve(".symphony-trello-locks");
        }

        private static FileLock tryWorkflowLock(FileChannel channel) throws IOException {
            try {
                return channel.tryLock();
            } catch (OverlappingFileLockException e) {
                return null;
            }
        }

        private static void closeQuietly(FileChannel channel) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Startup is already failing, and the OS will reclaim the descriptor on exit.
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException e) {
                LOG.warnf("workflow_runtime_lock outcome=release_failed reason=%s", e.getMessage());
            }
            closeQuietly(channel);
        }
    }

    private boolean isOutOfBoardScope(Card card) {
        return isOutOfBoardScope(card, config);
    }

    private static boolean isOutOfBoardScope(Card card, EffectiveConfig checkConfig) {
        return card.boardId() != null
                && !card.boardId().equals(checkConfig.tracker().resolvedBoardId());
    }

    private Duration backoff(int attempt) {
        long delay = INITIAL_RETRY_BACKOFF_MILLIS * (1L << Math.clamp(attempt - 1, 0, MAX_RETRY_BACKOFF_SHIFT));
        return Duration.ofMillis(
                Math.min(delay, config.agent().maxRetryBackoff().toMillis()));
    }

    private static int nextAttempt(Integer current) {
        return current == null ? 1 : current + 1;
    }

    private void addRuntime(RunningEntry entry) {
        endedRuntimeMillis += Duration.between(entry.startedAt, clock.instant()).toMillis();
    }

    private void addRecentEvent(RuntimeCardKey cardKey, CardDebugDetails.EventInfo event) {
        recentEvents
                .computeIfAbsent(cardKey, ignored -> EvictingQueue.create(RECENT_EVENT_LIMIT))
                .add(event);
    }

    private List<CardDebugDetails.EventInfo> recentEventsFor(RuntimeCardKey cardKey) {
        EvictingQueue<CardDebugDetails.EventInfo> events = recentEvents.get(cardKey);
        return events == null ? List.of() : List.copyOf(events);
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
        List<RuntimeSnapshot.RetryRow> retryRows =
                retryAttempts.values().stream().map(this::retryRow).toList();
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
                dispatchPause == null
                                || !Objects.equals(
                                        dispatchPause.command(), config.codex().command())
                        ? null
                        : new RuntimeSnapshot.DispatchPause(
                                CODEX_USAGE_LIMIT_PAUSE, dispatchPause.detectedAt(), dispatchPause.until()),
                config == null ? null : rateLimitsByCommand.get(config.codex().command()));
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
        TrackerTarget currentTarget = config == null ? null : TrackerTarget.from(config);
        Optional<CardDetailsSelection> selection = running.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getKey().trackerTarget(), currentTarget))
                .filter(entry -> entry.getValue().identifier().equals(cardIdentifier))
                .findFirst()
                .map(this::runningCardDetailsSelection)
                .or(() -> retryAttempts.entrySet().stream()
                        .filter(entry -> Objects.equals(entry.getKey().trackerTarget(), currentTarget))
                        .filter(entry -> entry.getValue().identifier().equals(cardIdentifier))
                        .findFirst()
                        .map(this::retryCardDetailsSelection))
                .or(() -> running.entrySet().stream()
                        .filter(entry -> entry.getValue().identifier().equals(cardIdentifier))
                        .findFirst()
                        .map(this::runningCardDetailsSelection))
                .or(() -> retryAttempts.entrySet().stream()
                        .filter(entry -> entry.getValue().identifier().equals(cardIdentifier))
                        .findFirst()
                        .map(this::retryCardDetailsSelection));
        return selection.map(detailsSelection -> new CardDebugDetails(
                cardIdentifier,
                detailsSelection.cardKey().cardId(),
                detailsSelection.status(),
                new CardDebugDetails.WorkspaceInfo(
                        detailsSelection.workspaceRoot().resolve(WorkspaceManager.sanitize(cardIdentifier))),
                new CardDebugDetails.AttemptInfo(0, detailsSelection.currentRetryAttempt()),
                detailsSelection.runningRow(),
                detailsSelection.retryRow(),
                new CardDebugDetails.LogInfo(List.of()),
                recentEventsFor(detailsSelection.cardKey()),
                detailsSelection.lastError(),
                Map.of()));
    }

    private CardDetailsSelection runningCardDetailsSelection(Map.Entry<RuntimeCardKey, RunningEntry> ownedEntry) {
        RunningEntry entry = ownedEntry.getValue();
        return new CardDetailsSelection(
                ownedEntry.getKey(),
                "running",
                entry.launchConfig.workspace().root(),
                null,
                runningRow(entry),
                null,
                null);
    }

    private CardDetailsSelection retryCardDetailsSelection(Map.Entry<RuntimeCardKey, RetryEntry> ownedRetry) {
        RetryEntry retry = ownedRetry.getValue();
        return new CardDetailsSelection(
                ownedRetry.getKey(),
                "retrying",
                config.workspace().root(),
                retry.attempt(),
                null,
                retryRow(retry),
                retry.error());
    }

    private RuntimeSnapshot.RetryRow retryRow(RetryEntry retry) {
        return new RuntimeSnapshot.RetryRow(
                retry.cardId(), retry.identifier(), retry.cardUrl(), retry.attempt(), retry.dueAt(), retry.error());
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

    private RuntimeCardKey currentRuntimeKey(String cardId) {
        return runtimeKey(TrackerTarget.from(config), cardId);
    }

    private static RuntimeCardKey runtimeKey(EffectiveConfig ownerConfig, String cardId) {
        return runtimeKey(TrackerTarget.from(ownerConfig), cardId);
    }

    private static RuntimeCardKey runtimeKey(RunningEntry entry) {
        return runtimeKey(entry.launchTarget, entry.cardId);
    }

    private static RuntimeCardKey runtimeKey(RetryEntry retry) {
        return runtimeKey(retry.trackerTarget(), retry.cardId());
    }

    private static RuntimeCardKey runtimeKey(TrackerTarget trackerTarget, String cardId) {
        return new RuntimeCardKey(trackerTarget, cardId);
    }

    private record RuntimeCardKey(TrackerTarget trackerTarget, String cardId) {}

    private record CardDetailsSelection(
            RuntimeCardKey cardKey,
            String status,
            Path workspaceRoot,
            Integer currentRetryAttempt,
            RuntimeSnapshot.RunningRow runningRow,
            RuntimeSnapshot.RetryRow retryRow,
            String lastError) {}

    private record DispatchPause(
            String command, long generation, Instant detectedAt, Instant until, Optional<UsageProbe> probe) {
        private DispatchPause launching(UsageWorkpadTarget target) {
            return new DispatchPause(
                    command, generation, detectedAt, until, Optional.of(new UsageProbe(target, Optional.empty())));
        }

        private DispatchPause probing(UsageWorkpadTarget target, String workerIdentity) {
            return new DispatchPause(
                    command,
                    generation,
                    detectedAt,
                    until,
                    Optional.of(new UsageProbe(target, Optional.of(workerIdentity))));
        }
    }

    private record UsageProbe(UsageWorkpadTarget target, Optional<String> workerIdentity) {
        private boolean matches(UsageWorkpadTarget expectedTarget, String expectedWorkerIdentity) {
            return target.equals(expectedTarget) && workerIdentity.equals(Optional.of(expectedWorkerIdentity));
        }
    }

    private record UsageWorkpadTarget(TrackerTarget trackerTarget, String cardId) {}

    private record UsageWorkpadState(EffectiveConfig ownerConfig, String message) {}

    private record UsageWorkpadCleanup(EffectiveConfig ownerConfig, Instant expiresAt, Instant nextAttemptAt) {}

    private record PendingUsageWorkpadCleanup(UsageWorkpadTarget target, UsageWorkpadCleanup cleanup) {}
}
