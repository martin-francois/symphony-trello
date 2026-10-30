package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.tracker.TrackerTarget;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/// The orchestrator state that status readers see, as one immutable value.
///
/// Operations build a new view under the operation lock and publish it through one volatile
/// reference, so `snapshot()`, `cardDetails()` and `isStarted()` take no lock and never see a map
/// while an operation changes it. A view is a copy: values that depend on the read time, such as
/// the active runtime, are computed when a reader asks. See
/// docs/adr/0105-orchestrator-published-read-view.md.
///
/// @param config the effective config, or `null` before the first workflow load
/// @param running running cards in dispatch order
/// @param retrying retry entries in scheduling order
/// @param dispatchPause the dispatch pause, if any; status readers see it only while it belongs to
///     the current Codex command
/// @param rateLimits the latest rate-limit payload of the current Codex command, or `null`
/// @param polling the adaptive polling status at publication, or `null` before workflow loading
record StatusView(
        boolean started,
        @Nullable EffectiveConfig config,
        List<RunningCard> running,
        List<RetryingCard> retrying,
        CodexTotals codexTotals,
        Optional<SymphonyOrchestrator.DispatchPause> dispatchPause,
        @Nullable Object rateLimits,
        RuntimeSnapshot.@Nullable Polling polling) {

    static final StatusView NOT_STARTED =
            new StatusView(false, null, List.of(), List.of(), CodexTotals.NONE, Optional.empty(), null, null);

    StatusView {
        running = List.copyOf(running);
        retrying = List.copyOf(retrying);
    }

    /// One running card as it was when the view was published.
    ///
    /// @param workspaceRoot the workspace root of the config the card was launched with
    /// @param handoffCancelledAt when an expired handoff deadline claimed the card, or `null`. Status
    ///     rows and card details leave a claimed card out while its cleanup finishes, and its
    ///     runtime stops at the claim.
    record RunningCard(
            TrackerTarget trackerTarget,
            RuntimeSnapshot.RunningRow row,
            Path workspaceRoot,
            List<CardDebugDetails.EventInfo> recentEvents,
            @Nullable Instant handoffCancelledAt) {
        RunningCard {
            recentEvents = List.copyOf(recentEvents);
        }

        boolean visible() {
            return handoffCancelledAt == null;
        }

        long runtimeMillis(Instant now) {
            return Duration.between(row.startedAt(), Objects.requireNonNullElse(handoffCancelledAt, now))
                    .toMillis();
        }
    }

    /// One retry entry as it was when the view was published.
    record RetryingCard(RetryEntry retry, List<CardDebugDetails.EventInfo> recentEvents) {
        RetryingCard {
            recentEvents = List.copyOf(recentEvents);
        }

        RuntimeSnapshot.RetryRow row() {
            return new RuntimeSnapshot.RetryRow(
                    retry.cardId(), retry.identifier(), retry.cardUrl(), retry.attempt(), retry.dueAt(), retry.error());
        }
    }

    /// Token counters and the runtime of runs that already ended.
    record CodexTotals(long inputTokens, long outputTokens, long totalTokens, long endedRuntimeMillis) {
        static final CodexTotals NONE = new CodexTotals(0, 0, 0, 0);
    }

    RuntimeSnapshot snapshot(Instant now) {
        List<RuntimeSnapshot.RunningRow> runningRows = running.stream()
                .filter(RunningCard::visible)
                .map(RunningCard::row)
                .toList();
        List<RuntimeSnapshot.RetryRow> retryRows =
                retrying.stream().map(RetryingCard::row).toList();
        // Hidden claimed cards still count their runtime, which stopped at the claim, until their
        // cleanup retires them.
        double activeSeconds =
                running.stream().mapToLong(card -> card.runtimeMillis(now)).sum() / 1000.0;
        return new RuntimeSnapshot(
                now,
                new RuntimeSnapshot.Counts(runningRows.size(), retryRows.size()),
                routing(),
                runningRows,
                retryRows,
                new RuntimeSnapshot.TokenTotals(
                        codexTotals.inputTokens(),
                        codexTotals.outputTokens(),
                        codexTotals.totalTokens(),
                        codexTotals.endedRuntimeMillis() / 1000.0 + activeSeconds),
                currentCommandPause().orElse(null),
                rateLimits,
                polling);
    }

    private Optional<RuntimeSnapshot.DispatchPause> currentCommandPause() {
        return dispatchPause
                .filter(pause -> config != null
                        && Objects.equals(pause.command(), config.codex().command()))
                .map(SymphonyOrchestrator.DispatchPause::status);
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

    /// Prefers a running card of the current tracker target, then a retry of the current target,
    /// then the same for any older target, so a card that moved with a reload is found first under
    /// the target it belongs to now.
    Optional<CardDebugDetails> cardDetails(String cardIdentifier) {
        TrackerTarget currentTarget = config == null ? null : TrackerTarget.from(config);
        Predicate<TrackerTarget> current = target -> Objects.equals(target, currentTarget);
        Predicate<TrackerTarget> anyTarget = target -> true;
        return runningDetails(cardIdentifier, current)
                .or(() -> retryDetails(cardIdentifier, current))
                .or(() -> runningDetails(cardIdentifier, anyTarget))
                .or(() -> retryDetails(cardIdentifier, anyTarget));
    }

    private Optional<CardDebugDetails> runningDetails(String cardIdentifier, Predicate<TrackerTarget> target) {
        return running.stream()
                .filter(RunningCard::visible)
                .filter(card -> target.test(card.trackerTarget()))
                .filter(card -> card.row().cardIdentifier().equals(cardIdentifier))
                .findFirst()
                .map(card -> new CardSelection(
                                card.row().cardId(),
                                "running",
                                card.workspaceRoot(),
                                null,
                                card.row(),
                                null,
                                card.recentEvents(),
                                null)
                        .details(cardIdentifier));
    }

    private Optional<CardDebugDetails> retryDetails(String cardIdentifier, Predicate<TrackerTarget> target) {
        return retrying.stream()
                .filter(card -> target.test(card.retry().trackerTarget()))
                .filter(card -> card.retry().identifier().equals(cardIdentifier))
                .findFirst()
                .map(card -> new CardSelection(
                                card.retry().cardId(),
                                "retrying",
                                Objects.requireNonNull(config).workspace().root(),
                                card.retry().attempt(),
                                null,
                                card.row(),
                                card.recentEvents(),
                                card.retry().error())
                        .details(cardIdentifier));
    }

    private record CardSelection(
            String cardId,
            String status,
            Path workspaceRoot,
            @Nullable Integer currentRetryAttempt,
            RuntimeSnapshot.@Nullable RunningRow runningRow,
            RuntimeSnapshot.@Nullable RetryRow retryRow,
            List<CardDebugDetails.EventInfo> recentEvents,
            @Nullable String lastError) {
        CardDebugDetails details(String cardIdentifier) {
            return new CardDebugDetails(
                    cardIdentifier,
                    cardId,
                    status,
                    new CardDebugDetails.WorkspaceInfo(
                            workspaceRoot.resolve(WorkspaceManager.sanitize(cardIdentifier))),
                    new CardDebugDetails.AttemptInfo(0, currentRetryAttempt),
                    runningRow,
                    retryRow,
                    new CardDebugDetails.LogInfo(List.of()),
                    recentEvents,
                    lastError,
                    Map.of());
        }
    }
}
