package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fmartin.symphony.trello.telemetry.TelemetryOwnership.Phase;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TelemetryErasureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUDIENCE = "test:erasure";
    private static final TelemetryCredential CREDENTIAL =
            new TelemetryCredential(UUID.fromString("5095b32f-dbe1-4704-a625-5a241d331cd6"), "k1", "ab".repeat(32));

    @TempDir
    Path tempDir;

    private HttpServer server;
    private TelemetryStateStore store;
    private TelemetryInstallation installation;
    private TelemetryErasure erasure;
    private final TelemetryFixture.MutableClock clock = new TelemetryFixture.MutableClock(TelemetryFixture.NOON);
    private final AtomicReference<String> status = new AtomicReference<>("pending");
    private final AtomicReference<Runnable> onIssue = new AtomicReference<>(() -> {});
    private final AtomicReference<Runnable> onStatus = new AtomicReference<>(() -> {});
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        Path stateDir = TelemetryFixture.installedStateDir(tempDir);
        store = new TelemetryStateStore(stateDir);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        TelemetryDistribution distribution = new TelemetryDistribution(
                base.resolve("/i/v0/e/"),
                Optional.of(TelemetryFixture.TEST_TOKEN),
                Optional.empty(),
                Optional.of(new TelemetryErasureEndpoint(base.resolve("/ownership"), AUDIENCE)));
        installation = TelemetryFixture.installation(stateDir, TelemetryEnvironment.none(), distribution);
        erasure = new TelemetryErasure(installation, clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void queuedReceiptDoesNotAuthorizeResumeAndCompletedErasurePreservesInstallationWithNewPeriod() {
        // given
        erasure.maintain(store);
        store.update(state -> Update.write(state.withOwnership(state.ownership().markUsed()), null));
        TelemetryOwnership original = store.read().stateOrInitial().ownership();
        var output = new ByteArrayOutputStream();
        var out = new PrintStream(output, true, StandardCharsets.UTF_8);
        TelemetryService service = new TelemetryService(installation, TelemetryFixture.snapshots(installation), clock);

        // when
        int requested = service.erase(out, out);
        int prematureEnable = service.enable(out, out);
        TelemetryState pending = store.read().stateOrInitial();
        status.set("accepted");
        clock.advance(Duration.ofMinutes(1));
        erasure.maintain(store);
        TelemetryState accepted = store.read().stateOrInitial();
        status.set("complete");
        clock.advance(Duration.ofMinutes(1));
        erasure.maintain(store);
        TelemetryState complete = store.read().stateOrInitial();
        int enabled = service.enable(out, out);
        TelemetryState resumed = store.read().stateOrInitial();

        // then
        assertThat(requested).isZero();
        assertThat(prematureEnable).isEqualTo(1);
        assertThat(pending.mode()).isEqualTo(TelemetryMode.DISABLED);
        assertThat(pending.ownership().erasure().phase()).isEqualTo(Phase.REQUESTED);
        assertThat(accepted.ownership().erasure().phase()).isEqualTo(Phase.ACCEPTED);
        assertThat(complete.ownership().erasure().phase()).isEqualTo(Phase.COMPLETE);
        assertThat(complete.mode()).isEqualTo(TelemetryMode.DISABLED);
        assertThat(enabled).isZero();
        assertThat(resumed.ownership().credential()).isEqualTo(original.credential());
        assertThat(resumed.ownership().period()).isNotEqualTo(original.period());
        assertThat(resumed.ownership().erasure()).isNull();
        assertThat(requests).noneMatch(request -> request.contains(CREDENTIAL.secret()));
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(CREDENTIAL.secret());
    }

    @Test
    void concurrentDisableWinsOverIssuanceAndNoStateLockIsHeldDuringHttp() {
        // given
        onIssue.set(() -> store.update(state -> Update.write(state.withMode(TelemetryMode.DISABLED), null)));

        // when
        erasure.maintain(store);
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(state.mode()).isEqualTo(TelemetryMode.DISABLED);
        assertThat(state.installation()).isEmpty();
        assertThat(state.ownership()).isNull();
    }

    @Test
    void restartRetriesTheSamePeriodAndOperationWithoutSendingTheSecret() {
        // given
        erasure.maintain(store);
        store.update(state -> Update.write(state.withOwnership(state.ownership().markUsed()), null));
        erasure.request(store);
        TelemetryOwnership before = store.read().stateOrInitial().ownership();
        clock.advance(Duration.ofMinutes(1));

        // when
        new TelemetryErasure(installation, clock).maintain(store);
        TelemetryOwnership after = store.read().stateOrInitial().ownership();

        // then
        assertThat(after.period()).isEqualTo(before.period());
        assertThat(after.erasure().operation()).isEqualTo(before.erasure().operation());
        assertThat(requests)
                .filteredOn(request -> request.contains("h2|erase|"))
                .hasSize(2)
                .allSatisfy(request -> assertThat(request)
                        .contains(
                                before.period().toString(),
                                before.erasure().operation().toString())
                        .doesNotContain(CREDENTIAL.secret()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "", "pending"})
    void missingOrUnrecognizedStatusNeverCompletesErasure(String providerStatus) {
        // given
        erasure.maintain(store);
        store.update(state -> Update.write(state.withOwnership(state.ownership().markUsed()), null));
        status.set(providerStatus);

        // when
        erasure.request(store);

        // then
        assertThat(store.read().stateOrInitial().ownership().erasure().phase()).isEqualTo(Phase.REQUESTED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableErasureDisablesOnlyOnAnEraseRequest(boolean request) {
        // given
        TelemetryDistribution distribution = new TelemetryDistribution(
                installation.distribution().endpoint(), Optional.of(TelemetryFixture.TEST_TOKEN));
        TelemetryInstallation unavailable =
                TelemetryFixture.installation(store.stateFile().getParent(), TelemetryEnvironment.none(), distribution);
        TelemetryService service = new TelemetryService(unavailable, TelemetryFixture.snapshots(unavailable), clock);
        var output = new ByteArrayOutputStream();
        var out = new PrintStream(output, true, StandardCharsets.UTF_8);

        // when
        int result = request ? service.erase(out, out) : service.erasureStatus(out, out);

        // then
        assertThat(result).isEqualTo(1);
        assertThat(store.read().stateOrInitial().mode())
                .isEqualTo(request ? TelemetryMode.DISABLED : TelemetryMode.ENABLED);
        assertThat(requests).isEmpty();
    }

    @Test
    void legacyIdentityCannotBeClaimedThroughTheIssuer() {
        // given
        UUID legacy = UUID.randomUUID();
        store.update(state -> Update.write(state.withIdentity(legacy, LocalDate.of(2026, 9, 22)), null));

        // when
        org.assertj.core.api.ThrowableAssert.ThrowingCallable request = () -> erasure.request(store);

        // then
        assertThatThrownBy(request)
                .isInstanceOf(TelemetryStateException.class)
                .hasMessageContaining("maintainer-assisted");
        assertThat(requests).isEmpty();
        assertThat(store.read().stateOrInitial().installation()).contains(legacy);
    }

    @Test
    void lateStatusCannotReattachAnErasureToANewerReportingPeriod() {
        // given
        erasure.maintain(store);
        store.update(state -> Update.write(state.withOwnership(state.ownership().markUsed()), null));
        erasure.request(store);
        UUID oldPeriod = store.read().stateOrInitial().ownership().period();
        onStatus.set(() -> store.update(state -> {
            TelemetryOwnership owner = state.ownership();
            TelemetryOwnership completed = owner.withErasure(owner.erasure().next(Phase.COMPLETE, clock.instant()));
            return Update.write(state.withMode(TelemetryMode.ENABLED).withOwnership(completed.resume()), null);
        }));
        status.set("complete");
        clock.advance(Duration.ofMinutes(1));

        // when
        erasure.maintain(store);
        TelemetryState resumed = store.read().stateOrInitial();

        // then
        assertThat(resumed.mode()).isEqualTo(TelemetryMode.ENABLED);
        assertThat(resumed.ownership().period()).isNotEqualTo(oldPeriod);
        assertThat(resumed.ownership().erasure()).isNull();
    }

    @Test
    void workerPersistsOwnershipBeforeFirstHeartbeatAndStopsAfterErase() {
        // given
        HeartbeatReporter worker = worker(Runnable::run);

        // when
        worker.check();
        List<String> beforeGrace = List.copyOf(requests);
        clock.advance(TelemetryNotice.FIRST_REPORT_GRACE);
        worker.check();
        TelemetryState issued = store.read().stateOrInitial();
        worker.check();
        TelemetryState reported = store.read().stateOrInitial();
        erasure.request(store);
        int afterErase = requests.size();
        worker.check();

        // then
        assertThat(beforeGrace).isEmpty();
        assertThat(issued.ownership().credential()).isEqualTo(CREDENTIAL);
        assertThat(reported.lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(requests)
                .filteredOn(request -> request.contains("installation_heartbeat"))
                .singleElement()
                .satisfies(body -> assertThat(body)
                        .contains(reported.analyticsId().orElseThrow())
                        .doesNotContain(CREDENTIAL.secret(), "h2|"));
        assertThat(requests).hasSize(afterErase);
        assertThat(store.read().stateOrInitial().mode()).isEqualTo(TelemetryMode.DISABLED);
    }

    @Test
    void disableThenErasePreservesTheDispatchedHeartbeatDrainDeadline() {
        // given
        var deliveries = new ArrayDeque<Runnable>();
        HeartbeatReporter worker = worker(deliveries::add);
        worker.check();
        clock.advance(TelemetryNotice.FIRST_REPORT_GRACE);
        worker.check();
        deliveries.remove().run();
        worker.check();
        var drainUntil = store.read().stateOrInitial().claim().expiresAt();
        store.update(state -> Update.write(state.withMode(TelemetryMode.DISABLED), null));

        // when
        new TelemetryErasure(installation, clock).request(store);

        // then
        assertThat(store.read().stateOrInitial().ownership().erasure().notBefore())
                .isEqualTo(drainUntil);
        assertThat(requests).containsExactly("{\"payload\":\"issue-v1\"}");
    }

    @Test
    void completedErasureMakesNoFurtherBackgroundRequests() {
        // given
        erasure.maintain(store);
        store.update(state -> Update.write(state.withOwnership(state.ownership().markUsed()), null));
        erasure.request(store);
        status.set("complete");
        clock.advance(Duration.ofMinutes(1));
        erasure.maintain(store);
        int completedRequests = requests.size();
        clock.advance(Duration.ofDays(2));

        // when
        erasure.maintain(store);

        // then
        assertThat(requests).hasSize(completedRequests);
        assertThat(store.read().stateOrInitial().ownership().erasure().phase()).isEqualTo(Phase.COMPLETE);
    }

    @Test
    void delayedDeliveryDoesNotStartWithoutItsFullTransportBudget() {
        // given
        var deliveries = new ArrayDeque<Runnable>();
        HeartbeatReporter worker = worker(deliveries::add);
        worker.check();
        clock.advance(TelemetryNotice.FIRST_REPORT_GRACE);
        worker.check();
        deliveries.remove().run();
        worker.check();
        clock.advance(HeartbeatReporter.CLAIM_LIFETIME.minusSeconds(5));

        // when
        deliveries.remove().run();

        // then
        assertThat(requests).containsExactly("{\"payload\":\"issue-v1\"}");
        assertThat(store.read().stateOrInitial().lastReportedDate()).isNull();
    }

    private HeartbeatReporter worker(Executor executor) {
        return new HeartbeatReporter(
                installation,
                TelemetryFixture.snapshots(installation),
                new PostHogCaptureClient(installation.distribution().endpoint()),
                clock,
                executor,
                () -> 0,
                TelemetryOutput.none());
    }

    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        String response;
        int code = 200;
        if (exchange.getRequestURI().getPath().equals("/flags")) {
            onStatus.get().run();
            String key =
                    JSON.readTree(body).path("flag_keys_to_evaluate").path(0).asText();
            response = JSON.createObjectNode()
                    .put("errorsWhileComputingFlags", false)
                    .set(
                            "flags",
                            JSON.createObjectNode()
                                    .set(key, JSON.createObjectNode().put("variant", status.get())))
                    .toString();
        } else if (exchange.getRequestURI().getPath().equals("/i/v0/e/")) {
            response = "{\"status\":\"Ok\"}";
        } else if (body.equals("{\"payload\":\"issue-v1\"}")) {
            onIssue.get().run();
            response = JSON.createObjectNode()
                    .put("status", "issued")
                    .put("installation_id", CREDENTIAL.installationId().toString())
                    .put("key_version", CREDENTIAL.keyVersion())
                    .put("secret", CREDENTIAL.secret())
                    .toString();
        } else {
            code = 201;
            response = "{\"status\":\"queued\"}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
