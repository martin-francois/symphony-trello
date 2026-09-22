package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.telemetry.HeartbeatReporter.CheckResult;
import ch.fmartin.symphony.trello.telemetry.TelemetryFixture.MutableClock;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

final class HeartbeatReporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    @TempDir
    Path tempDir;

    private Path stateDir;
    private TelemetryStateStore store;
    private final MutableClock clock = new MutableClock(TelemetryFixture.NOON);
    private final PostHogCaptureClient client = mock();
    private final List<String> output = new ArrayList<>();
    /// Tasks run only when the test says so, which models a slow request.
    private final Deque<Runnable> queued = new ArrayDeque<>();
    private final Executor deferred = queued::add;

    @BeforeEach
    void setUp() throws IOException {
        stateDir = TelemetryFixture.installedStateDir(tempDir);
        store = new TelemetryStateStore(stateDir);
        when(client.endpoint()).thenReturn(TelemetryFixture.LOOPBACK_ENDPOINT);
    }

    @Test
    void firstReadyWorkerRegistersPrintsTheNoticeAndStartsTheGracePeriodOnce() {
        // given
        HeartbeatReporter first = reporter(TelemetryEnvironment.none(), Runnable::run);
        HeartbeatReporter second = reporter(TelemetryEnvironment.none(), Runnable::run);

        // when
        CheckResult firstResult = first.check();
        clock.advance(ONE_MINUTE);
        CheckResult secondResult = second.check();
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(firstResult).isEqualTo(CheckResult.GRACE);
        assertThat(secondResult).isEqualTo(CheckResult.GRACE);
        assertThat(state.hasIdentity())
                .as("identity created at the first ready worker")
                .isTrue();
        assertThat(state.registeredOn()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(state.firstWorkerDeadline())
                .isEqualTo(TelemetryFixture.NOON.plus(TelemetryNotice.FIRST_REPORT_GRACE));
        assertThat(state.noticeRevision()).isEqualTo(TelemetryNotice.REVISION);
        assertThat(output)
                .singleElement()
                .asString()
                .contains("First report in 5 minutes")
                .contains("Disable: ");
        verify(client, never()).capture(anyString());
    }

    @Test
    void firstHeartbeatIsSentAfterTheGraceDeadlineAndRecordedOnce() throws IOException {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = reporter(TelemetryEnvironment.none(), Runnable::run);
        reporter.check();

        // when
        clock.advance(TelemetryNotice.FIRST_REPORT_GRACE.minusSeconds(1));
        CheckResult beforeDeadline = reporter.check();
        clock.advance(Duration.ofSeconds(1));
        CheckResult atDeadline = reporter.check();
        CheckResult again = reporter.check();
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(beforeDeadline).isEqualTo(CheckResult.GRACE);
        assertThat(atDeadline).isEqualTo(CheckResult.DISPATCHED);
        assertThat(again).isEqualTo(CheckResult.DONE_TODAY);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).capture(body.capture());
        JsonNode sent = JSON.readTree(body.getValue());
        assertThat(sent.get("distinct_id").asText())
                .isEqualTo(state.installationId().toString());
        assertThat(sent.get("timestamp").asText()).isEqualTo("2026-09-22T12:05:00Z");
        assertThat(sent.get("properties").get("app_version").asText()).isEqualTo(TelemetryFixture.INSTALLED_VERSION);
        assertThat(sent.get("properties").get("connected_board_count").asInt()).isEqualTo(3);
        assertThat(state.lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(state.pendingReport()).isNull();
        assertThat(state.claim()).isNull();
    }

    @Test
    void identityCreatedInTheSameTransactionAsTheFirstClaimIsSent() throws IOException {
        // given
        // A state whose grace period ended but that was never registered, for example when the
        // deadline was stored by a worker that could not reach the network.
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        store.update(state -> Update.write(
                state.withFirstWorkerDeadline(TelemetryFixture.NOON.minusSeconds(1))
                        .withNotice(TelemetryNotice.REVISION, TelemetryFixture.NOON.minusSeconds(1)),
                null));
        HeartbeatReporter reporter = reporter(TelemetryEnvironment.none(), Runnable::run);

        // when
        CheckResult result = reporter.check();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).capture(body.capture());
        JsonNode sent = JSON.readTree(body.getValue());

        // then
        assertThat(result).isEqualTo(CheckResult.DISPATCHED);
        assertThat(sent.get("properties").get("registered_on").asText()).isEqualTo("2026-09-22");
        assertThat(sent.get("distinct_id").asText())
                .isEqualTo(store.read().stateOrInitial().installationId().toString());
    }

    @Test
    void restartsDuringGraceKeepTheOriginalDeadlineAndDoNotRepeatTheNotice() {
        // given
        HeartbeatReporter reporter = reporter(TelemetryEnvironment.none(), Runnable::run);
        reporter.check();
        Instant deadline = store.read().stateOrInitial().firstWorkerDeadline();

        // when
        clock.advance(Duration.ofMinutes(3));
        HeartbeatReporter restarted = reporter(TelemetryEnvironment.none(), Runnable::run);
        CheckResult result = restarted.check();

        // then
        assertThat(result).isEqualTo(CheckResult.GRACE);
        assertThat(store.read().stateOrInitial().firstWorkerDeadline()).isEqualTo(deadline);
        assertThat(output).hasSize(1);
    }

    @Test
    void oneAcceptedReportPerUtcDayAcrossMidnightAndRestarts() {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = readyReporter();

        // when
        CheckResult first = reporter.check();
        clock.advance(Duration.ofHours(6));
        CheckResult sameDayRestart =
                reporter(TelemetryEnvironment.none(), Runnable::run).check();
        clock.set(Instant.parse("2026-09-23T00:00:30Z"));
        CheckResult nextDay = reporter.check();

        // then
        assertThat(List.of(first, sameDayRestart, nextDay))
                .containsExactly(CheckResult.DISPATCHED, CheckResult.DONE_TODAY, CheckResult.DISPATCHED);
        verify(client, times(2)).capture(anyString());
        assertThat(store.read().stateOrInitial().lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 23));
    }

    @Test
    void transientFailuresRetryWithBoundedBackoffAndTheSameEventIdentity() throws IOException {
        // given
        when(client.capture(anyString()))
                .thenReturn(CaptureOutcome.transientFailure(Optional.of(503), Optional.empty(), "HTTP 503"))
                .thenReturn(CaptureOutcome.transientFailure(
                        Optional.of(429), Optional.of(Duration.ofSeconds(120)), "HTTP 429"))
                .thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = readyReporter();

        // when
        Instant firstAttempt = clock.instant();
        reporter.check();
        TelemetryState afterFirst = store.read().stateOrInitial();
        CheckResult tooEarly = reporter.check();
        clock.advance(Duration.ofSeconds(61));
        Instant secondAttemptAt = clock.instant();
        CheckResult secondAttempt = reporter.check();
        TelemetryState afterSecond = store.read().stateOrInitial();
        clock.advance(Duration.ofSeconds(121));
        CheckResult thirdAttempt = reporter.check();
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(client, times(3)).capture(bodies.capture());

        // then
        assertThat(afterFirst.retry()).satisfies(retry -> {
            assertThat(retry.attempts()).isEqualTo(1);
            assertThat(retry.notBefore()).isEqualTo(firstAttempt.plus(HeartbeatReporter.RETRY_BACKOFF.getFirst()));
        });
        assertThat(tooEarly).isEqualTo(CheckResult.WAITING);
        assertThat(secondAttempt).isEqualTo(CheckResult.DISPATCHED);
        assertThat(afterSecond.retry().notBefore())
                .as("Retry-After replaces the backoff step")
                .isEqualTo(secondAttemptAt.plusSeconds(120));
        assertThat(thirdAttempt).isEqualTo(CheckResult.DISPATCHED);
        List<JsonNode> sent = new ArrayList<>();
        for (String body : bodies.getAllValues()) {
            sent.add(JSON.readTree(body));
        }
        assertThat(sent)
                .extracting(node -> node.get("uuid").asText())
                .containsOnly(sent.getFirst().get("uuid").asText());
        assertThat(sent).extracting(node -> node.get("timestamp").asText()).containsOnly("2026-09-22T12:05:00Z");
        assertThat(store.read().stateOrInitial().lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    void anUnreadableResponseBodyIsRetriedWithTheSameEventAndAdvancesNothing() throws IOException {
        // given
        when(client.capture(anyString()))
                .thenReturn(
                        CaptureOutcome.transientFailure(Optional.of(200), Optional.empty(), "response body unreadable"))
                .thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = readyReporter();

        // when
        Instant firstAttempt = clock.instant();
        reporter.check();
        TelemetryState afterFailure = store.read().stateOrInitial();
        clock.advance(Duration.ofSeconds(61));
        reporter.check();
        TelemetryState afterRetry = store.read().stateOrInitial();
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).capture(bodies.capture());
        List<JsonNode> sent = new ArrayList<>();
        for (String body : bodies.getAllValues()) {
            sent.add(JSON.readTree(body));
        }

        // then
        assertThat(afterFailure.lastReported())
                .as("a body that never arrived whole proves nothing")
                .isEmpty();
        assertThat(afterFailure.pending())
                .map(pending -> pending.eventUuid().toString())
                .contains(sent.getFirst().get("uuid").asText());
        assertThat(afterFailure.retry()).satisfies(retry -> {
            assertThat(retry.reason()).isEqualTo("response body unreadable");
            assertThat(retry.attempts()).isEqualTo(1);
            assertThat(retry.notBefore()).isEqualTo(firstAttempt.plus(HeartbeatReporter.RETRY_BACKOFF.getFirst()));
        });
        assertThat(sent)
                .extracting(node -> node.get("uuid").asText())
                .containsOnly(sent.getFirst().get("uuid").asText());
        assertThat(afterRetry.lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(afterRetry.pending()).isEmpty();
    }

    @Test
    void stalePendingReportIsReplacedByAFreshSnapshotOnTheNextDay() throws IOException {
        // given
        when(client.capture(anyString()))
                .thenReturn(CaptureOutcome.transientFailure(Optional.empty(), Optional.empty(), "connection failed"))
                .thenReturn(CaptureOutcome.transientFailure(Optional.of(503), Optional.empty(), "HTTP 503"));
        HeartbeatReporter reporter = readyReporter();
        reporter.check();
        store.update(state -> Update.write(state.withCounters(9, 0), null));

        // when
        clock.set(Instant.parse("2026-09-23T08:00:00Z"));
        CheckResult nextDay = reporter.check();
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).capture(bodies.capture());
        JsonNode fresh = JSON.readTree(bodies.getAllValues().get(1));

        // then
        assertThat(nextDay).isEqualTo(CheckResult.DISPATCHED);
        assertThat(store.read().stateOrInitial().retry())
                .as("a fresh report starts its own backoff instead of inheriting the stale attempt count")
                .satisfies(retry -> {
                    assertThat(retry.attempts()).isEqualTo(1);
                    assertThat(retry.reason()).isEqualTo("HTTP 503");
                });
        assertThat(fresh.get("timestamp").asText()).isEqualTo("2026-09-23T08:00:00Z");
        assertThat(fresh.get("properties").get("board_imports_total").asInt()).isEqualTo(9);
        assertThat(fresh.get("uuid").asText())
                .isNotEqualTo(
                        JSON.readTree(bodies.getAllValues().get(0)).get("uuid").asText());
    }

    @Test
    void stalePendingReportIsReplacedEvenWhileItsBackoffIsStillRunning() {
        // given
        when(client.capture(anyString()))
                .thenReturn(CaptureOutcome.transientFailure(
                        Optional.of(429), Optional.of(PostHogCaptureClient.MAX_RETRY_AFTER), "HTTP 429"))
                .thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = readyReporter();
        clock.set(Instant.parse("2026-09-22T23:30:00Z"));
        reporter.check();

        // when
        clock.set(Instant.parse("2026-09-23T00:05:00Z"));
        CheckResult nextDay = reporter.check();
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(nextDay).isEqualTo(CheckResult.DISPATCHED);
        assertThat(state.lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(state.retry()).isNull();
    }

    @Test
    void aStoredDisableStaysDisabledAcrossRestartsAndTicksBeyondTheGracePeriod() {
        // given
        UUID id = UUID.randomUUID();
        store.update(state -> Update.write(
                state.withIdentity(id, LocalDate.of(2026, 9, 1))
                        .withCounters(4, 2)
                        .withFirstWorkerDeadline(TelemetryFixture.NOON.minusSeconds(1))
                        .withMode(TelemetryMode.DISABLED),
                null));

        // when
        List<CheckResult> results = new ArrayList<>();
        for (int restart = 0; restart < 3; restart++) {
            HeartbeatReporter worker = reporter(TelemetryEnvironment.none(), Runnable::run);
            results.add(worker.check());
            clock.advance(Duration.ofMinutes(6));
            results.add(worker.check());
        }
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(results).containsOnly(CheckResult.DISABLED);
        verify(client, never()).capture(anyString());
        assertThat(state.installationId())
                .as("the identity is neither replaced nor removed")
                .isEqualTo(id);
        assertThat(state.boardImportsTotal()).isEqualTo(4);
        assertThat(state.mode()).isEqualTo(TelemetryMode.DISABLED);
    }

    @Test
    void quotaLimitedResponseIsDeferredNotRecordedAsAccepted() {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.quotaLimited(200));
        HeartbeatReporter reporter = readyReporter();

        // when
        CheckResult first = reporter.check();
        clock.advance(Duration.ofHours(1));
        CheckResult later = reporter.check();
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(first).isEqualTo(CheckResult.DISPATCHED);
        assertThat(later)
                .as("no retry storm against a quota that would drop the event again")
                .isEqualTo(CheckResult.WAITING);
        assertThat(state.lastReportedDate())
                .as("a dropped event is not an accepted report")
                .isNull();
        assertThat(state.retry().reason()).isEqualTo("accepted but quota-limited");
        assertThat(state.retry().notBefore()).isEqualTo(Instant.parse("2026-09-23T00:00:00Z"));
        verify(client).capture(anyString());
    }

    @Test
    void firstWorkerPrintsTheNoticeWithTheRealDeadlineEvenAfterSetupDisclosure() {
        // given
        UUID id = UUID.randomUUID();
        store.update(state -> Update.write(
                state.withIdentity(id, LocalDate.of(2026, 9, 20))
                        .withNotice(TelemetryNotice.REVISION, TelemetryFixture.NOON.minusSeconds(600))
                        .withCounters(1, 0),
                null));
        HeartbeatReporter first = reporter(TelemetryEnvironment.none(), Runnable::run);
        HeartbeatReporter second = reporter(TelemetryEnvironment.none(), Runnable::run);

        // when
        CheckResult firstResult = first.check();
        clock.advance(Duration.ofMinutes(2));
        CheckResult secondResult = second.check();
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(List.of(firstResult, secondResult)).containsOnly(CheckResult.GRACE);
        assertThat(output).singleElement().asString().contains("First report in 5 minutes");
        assertThat(state.installationId())
                .as("setup identity and counters are kept")
                .isEqualTo(id);
        assertThat(state.boardImportsTotal()).isEqualTo(1);
        assertThat(state.firstWorkerDeadline())
                .isEqualTo(TelemetryFixture.NOON.plus(TelemetryNotice.FIRST_REPORT_GRACE));
    }

    @Test
    void aClockThatMovesBackwardsDoesNotProduceASecondReportForACoveredDay() {
        // given
        // The report for the 23rd was accepted shortly after midnight.
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = readyReporter();
        clock.set(Instant.parse("2026-09-23T00:30:00Z"));
        reporter.check();

        // when
        // The clock is corrected backwards into the 22nd, after the grace deadline.
        clock.set(Instant.parse("2026-09-22T23:50:00Z"));
        CheckResult yesterdayAgain = reporter.check();

        // then
        assertThat(yesterdayAgain).isEqualTo(CheckResult.DONE_TODAY);
        assertThat(store.read().stateOrInitial().lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        verify(client).capture(anyString());
    }

    @Test
    void anInFlightDeliveryBlocksFurtherClaimsUntilItFinishes() {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = reporterReady(TelemetryEnvironment.none(), deferred);

        // when
        CheckResult claimed = reporter.check();
        clock.advance(HeartbeatReporter.CLAIM_LIFETIME.plusSeconds(1));
        CheckResult whileStalled = reporter.check();
        queued.forEach(Runnable::run);
        CheckResult afterwards = reporter.check();

        // then
        assertThat(claimed).isEqualTo(CheckResult.DISPATCHED);
        assertThat(whileStalled)
                .as("no second attempt is queued behind a stalled one")
                .isEqualTo(CheckResult.WAITING);
        assertThat(queued).hasSize(1);
        assertThat(afterwards).isEqualTo(CheckResult.DONE_TODAY);
        verify(client).capture(anyString());
    }

    @Test
    void aStaleAttemptCannotRecordOverANewerClaimOfTheSameEvent() {
        // given
        // The takeover sends first and succeeds; the stale attempt runs afterwards and fails.
        when(client.capture(anyString()))
                .thenReturn(CaptureOutcome.accepted(200))
                .thenReturn(CaptureOutcome.transientFailure(Optional.of(503), Optional.empty(), "HTTP 503"));
        HeartbeatReporter stalled = reporterReady(TelemetryEnvironment.none(), deferred);
        HeartbeatReporter other = reporter(TelemetryEnvironment.none(), Runnable::run);
        stalled.check();
        Runnable staleAttempt = queued.pollFirst();

        // when
        // The first claim expires, another worker takes over and succeeds, then the stale attempt
        // finally completes with its old failure.
        clock.advance(HeartbeatReporter.CLAIM_LIFETIME.plusSeconds(1));
        CheckResult takeover = other.check();
        staleAttempt.run();
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(takeover).isEqualTo(CheckResult.DISPATCHED);
        assertThat(state.lastReportedDate()).as("the newer success stands").isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(state.retry())
                .as("the stale failure does not schedule a retry")
                .isNull();
    }

    @Test
    void permanentFailureDefersToTheNextUtcDayWithoutRetrying() {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.permanentFailure(401, "project token rejected"));
        HeartbeatReporter reporter = readyReporter();

        // when
        CheckResult first = reporter.check();
        clock.advance(Duration.ofHours(2));
        CheckResult later = reporter.check();
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(first).isEqualTo(CheckResult.DISPATCHED);
        assertThat(later).isEqualTo(CheckResult.WAITING);
        assertThat(state.pendingReport()).isNull();
        assertThat(state.retry().notBefore()).isEqualTo(Instant.parse("2026-09-23T00:00:00Z"));
        verify(client).capture(anyString());
    }

    @Test
    void otherWorkersWaitWhileAClaimIsActiveAndTakeOverWhenItExpires() {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter slow = reporterReady(TelemetryEnvironment.none(), deferred);
        HeartbeatReporter other = reporter(TelemetryEnvironment.none(), Runnable::run);

        // when
        CheckResult claimed = slow.check();
        CheckResult blocked = other.check();
        clock.advance(HeartbeatReporter.CLAIM_LIFETIME.plusSeconds(1));
        CheckResult takenOver = other.check();
        queued.forEach(Runnable::run);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(claimed).isEqualTo(CheckResult.DISPATCHED);
        assertThat(blocked).isEqualTo(CheckResult.WAITING);
        assertThat(takenOver).isEqualTo(CheckResult.DISPATCHED);
        verify(client).capture(anyString());
        assertThat(state.lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    void disableDuringAnInFlightRequestFencesTheLateCompletion() {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = reporterReady(TelemetryEnvironment.none(), deferred);
        reporter.check();

        // when
        store.update(state -> Update.write(state.withMode(TelemetryMode.DISABLED), null));
        queued.forEach(Runnable::run);
        TelemetryState state = store.read().stateOrInitial();

        // then
        verify(client, never()).capture(anyString());
        assertThat(state.mode()).isEqualTo(TelemetryMode.DISABLED);
        assertThat(state.lastReportedDate()).isNull();
        assertThat(state.pendingReport()).isNull();
    }

    @Test
    void aLateSuccessAfterANewerPreferenceCannotRewriteState() {
        // given
        var capturedBody = new AtomicReference<String>();
        var gate = new ArrayDeque<Runnable>();
        when(client.capture(anyString())).thenAnswer(invocation -> {
            capturedBody.set(invocation.getArgument(0));
            // The preference changes while the request is on the wire.
            store.update(state -> Update.write(state.withMode(TelemetryMode.DEBUG), null));
            return CaptureOutcome.accepted(200);
        });
        HeartbeatReporter reporter = reporterReady(TelemetryEnvironment.none(), gate::add);
        reporter.check();

        // when
        gate.forEach(Runnable::run);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(capturedBody.get()).as("the request was already dispatched").isNotNull();
        assertThat(state.mode()).isEqualTo(TelemetryMode.DEBUG);
        assertThat(state.lastReportedDate())
                .as("a fenced completion records nothing")
                .isNull();
        assertThat(state.claim()).isNull();
    }

    @Test
    void debugWorkersPrintAPreviewImmediatelyAndOnlyOnMeaningfulChange() {
        // given
        HeartbeatReporter reporter = reporter(new TelemetryEnvironment(false, true, false), Runnable::run);

        // when
        CheckResult first = reporter.check();
        clock.advance(ONE_MINUTE);
        CheckResult unchanged = reporter.check();
        store.update(state -> Update.write(state.withCounters(0, 1), null));
        CheckResult changed = reporter.check();

        // then
        assertThat(List.of(first, unchanged, changed)).containsOnly(CheckResult.DEBUG);
        assertThat(output).hasSize(2);
        assertThat(output.getFirst()).contains("not sent").contains("\"board_creations_total\" : 0");
        assertThat(output.getLast()).contains("\"board_creations_total\" : 1");
        verify(client, never()).capture(anyString());
        assertThat(store.read().stateOrInitial().hasIdentity())
                .as("debug creates no identity")
                .isFalse();
    }

    @Test
    void logModePrintsTheExactRequestAndASafeOutcome() {
        // given
        when(client.capture(anyString())).thenReturn(CaptureOutcome.accepted(200));
        HeartbeatReporter reporter = reporterReady(new TelemetryEnvironment(false, false, true), Runnable::run);

        // when
        reporter.check();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(client).capture(body.capture());

        // then
        assertThat(output).anySatisfy(line -> assertThat(line)
                .contains("telemetry request POST " + TelemetryFixture.LOOPBACK_ENDPOINT)
                .contains("User-Agent")
                .contains(body.getValue()));
        assertThat(output).anySatisfy(line -> assertThat(line).contains("telemetry response accepted status=200"));
    }

    @Test
    void environmentDisableAndMissingTokenAndDevelopmentRunsNeverRegister() throws IOException {
        // given
        Path developmentDir = Files.createDirectories(tempDir.resolve("dev"));
        HeartbeatReporter disabled = reporter(new TelemetryEnvironment(true, false, false), Runnable::run);
        HeartbeatReporter unconfigured = new HeartbeatReporter(
                TelemetryFixture.installation(
                        stateDir, TelemetryEnvironment.none(), TelemetryFixture.unconfiguredDistribution()),
                TelemetryFixture.snapshots(TelemetryFixture.installation(stateDir)),
                client,
                clock,
                Runnable::run,
                () -> 0,
                output::add);
        HeartbeatReporter development = new HeartbeatReporter(
                TelemetryFixture.installation(developmentDir),
                TelemetryFixture.snapshots(TelemetryFixture.installation(developmentDir)),
                client,
                clock,
                Runnable::run,
                () -> 0,
                output::add);

        // when
        List<CheckResult> results = List.of(disabled.check(), unconfigured.check(), development.check());

        // then
        assertThat(results)
                .containsExactly(CheckResult.DISABLED, CheckResult.NOT_CONFIGURED, CheckResult.NOT_INSTALLED);
        assertThat(store.read().status()).isEqualTo(TelemetryStateStore.StateRead.Status.ABSENT);
        assertThat(Files.exists(developmentDir.resolve(TelemetryStateStore.STATE_FILE)))
                .as("a development run writes no telemetry state")
                .isFalse();
        verify(client, never()).capture(anyString());
    }

    @Test
    void corruptStateSuppressesReportingWithoutRegeneratingIt() throws IOException {
        // given
        Files.writeString(store.stateFile(), "not json");
        HeartbeatReporter reporter = reporter(TelemetryEnvironment.none(), Runnable::run);

        // when
        CheckResult first = reporter.check();
        CheckResult second = reporter.check();

        // then
        assertThat(List.of(first, second)).containsOnly(CheckResult.UNREADABLE);
        assertThat(Files.readString(store.stateFile())).isEqualTo("not json");
        assertThat(output).singleElement().asString().contains("reporting is off");
        verify(client, never()).capture(anyString());
    }

    private HeartbeatReporter readyReporter() {
        return reporterReady(TelemetryEnvironment.none(), Runnable::run);
    }

    /// A reporter whose grace period already elapsed, so the next check is due immediately.
    private HeartbeatReporter reporterReady(TelemetryEnvironment environment, Executor executor) {
        HeartbeatReporter reporter = reporter(environment, executor);
        reporter.check();
        output.clear();
        clock.advance(TelemetryNotice.FIRST_REPORT_GRACE);
        return reporter;
    }

    private HeartbeatReporter reporter(TelemetryEnvironment environment, Executor executor) {
        TelemetryInstallation installation = TelemetryFixture.installation(stateDir, environment);
        return new HeartbeatReporter(
                installation, TelemetryFixture.snapshots(installation), client, clock, executor, () -> 0, output::add);
    }
}
