package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.ExternalFileLockHolder;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.StateRead;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class TelemetryStateStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WRITERS = 8;
    private static final int INCREMENTS_PER_WRITER = 25;
    private static final Duration SHORT_LOCK_WAIT = Duration.ofMillis(300);

    @TempDir
    Path tempDir;

    @Test
    void absentFileReadsAsAbsentAndUpdatesFromInitialState() {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir.resolve("state"));

        // when
        StateRead before = store.read();
        UUID id = store.update(state ->
                Update.write(state.withIdentity(UUID.randomUUID(), LocalDate.of(2026, 9, 22)), state.installationId()));
        StateRead after = store.read();

        // then
        assertThat(before.status()).isEqualTo(StateRead.Status.ABSENT);
        assertThat(id)
                .as("the transaction saw the initial state without an identity")
                .isNull();
        assertThat(after.state()).get().satisfies(state -> {
            assertThat(state.hasIdentity()).as("identity persisted").isTrue();
            assertThat(state.registeredOn()).isEqualTo(LocalDate.of(2026, 9, 22));
            assertThat(state.mode()).isEqualTo(TelemetryMode.ENABLED);
        });
        assertThat(store.stateFile()).hasFileName("telemetry.json");
    }

    @Test
    void roundTripsEveryFieldIncludingPendingReportAndClaim() {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        HeartbeatProperties properties =
                new HeartbeatProperties(1, "2026-09-22", "1.2.0", "linux", "13", "debian", "x64", 2, 1, 1, true, true);
        TelemetryState full = TelemetryState.initial()
                .withIdentity(UUID.fromString("74a69e87-089d-4fd1-8ac2-df8aab052cb1"), LocalDate.of(2026, 9, 22))
                .withNotice(1, Instant.parse("2026-09-22T10:00:00Z"))
                .withFirstWorkerDeadline(Instant.parse("2026-09-22T10:05:00Z"))
                .withCounters(4, 2)
                .withMode(TelemetryMode.DEBUG)
                .withReporting(
                        LocalDate.of(2026, 9, 21),
                        new TelemetryState.RetryState(2, Instant.parse("2026-09-22T11:00:00Z"), "HTTP 429"),
                        new TelemetryState.PendingHeartbeat(
                                UUID.fromString("95127c85-1c9e-460c-b652-0a3a7899cbde"),
                                Instant.parse("2026-09-22T09:15:00Z"),
                                LocalDate.of(2026, 9, 22),
                                1,
                                properties),
                        new TelemetryState.ReportClaim(
                                "worker-a",
                                UUID.fromString("95127c85-1c9e-460c-b652-0a3a7899cbde"),
                                UUID.fromString("0d6f1c2e-7b3a-4c5d-8e9f-a1b2c3d4e5f6"),
                                Instant.parse("2026-09-22T09:16:30Z")));

        // when
        store.update(state -> Update.write(full, null));
        StateRead read = store.read();

        // then
        assertThat(read.state()).contains(full);
        assertThat(read.state())
                .get()
                .extracting(TelemetryState::preferenceRevision)
                .isEqualTo(1L);
    }

    @Test
    void unchangedTransactionsDoNotTouchTheFile() throws IOException {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        store.update(state -> Update.write(state.withCounters(1, 0), null));
        Instant modified = Files.getLastModifiedTime(store.stateFile()).toInstant();
        String content = Files.readString(store.stateFile());

        // when
        String result = store.update(state -> Update.unchanged("kept"));

        // then
        assertThat(result).isEqualTo("kept");
        assertThat(Files.readString(store.stateFile())).isEqualTo(content);
        assertThat(Files.getLastModifiedTime(store.stateFile()).toInstant()).isEqualTo(modified);
    }

    @Test
    void corruptContentIsUnreadableAndBlocksUpdatesInsteadOfRegenerating() throws IOException {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        Files.writeString(store.stateFile(), "{\"format_version\": 1, \"mode\": \"ENABLED\", \"unexpected\": true");

        // when
        StateRead read = store.read();

        // then
        assertThat(read.unreadable()).as("truncated JSON is unreadable").isTrue();
        assertThatThrownBy(() -> store.update(state -> Update.write(state.withCounters(1, 1), null)))
                .isInstanceOf(TelemetryStateException.class)
                .hasMessageContaining("not valid");
        assertThat(Files.readString(store.stateFile())).startsWith("{\"format_version\": 1");
    }

    @Test
    void futureFormatVersionIsUnreadable() throws IOException {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        Files.writeString(
                store.stateFile(),
                TelemetryStateJson.write(TelemetryState.initial())
                        .replace("\"format_version\" : 1", "\"format_version\" : 2"));

        // when
        StateRead read = store.read();

        // then
        assertThat(read.problem()).get().asString().contains("format version 2");
    }

    @Test
    void unknownPropertiesMakeTheFileUnreadableRatherThanPartlyUnderstood() throws IOException {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        Files.writeString(
                store.stateFile(),
                TelemetryStateJson.write(TelemetryState.initial())
                        .replace("\"mode\"", "\"future_field\" : 1,\n  \"mode\""));

        // when
        StateRead read = store.read();

        // then
        assertThat(read.unreadable())
                .as("a newer field set is not silently accepted")
                .isTrue();
    }

    @MethodSource("inconsistentStates")
    @ParameterizedTest(name = "{0}")
    void inconsistentStateIsUnreadableAndNeverRepairedInPlace(String scenario, Consumer<ObjectNode> mutation)
            throws IOException {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        ObjectNode document = (ObjectNode) JSON.readTree(TelemetryStateJson.write(validStoredState()));
        mutation.accept(document);
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document);
        Files.writeString(store.stateFile(), json);

        // when
        StateRead read = store.read();
        Throwable failure = catchThrowable(() -> store.update(state -> Update.write(state.withCounters(9, 9), null)));

        // then
        assertThat(read.unreadable())
                .as("%s must not pass as healthy state", scenario)
                .isTrue();
        assertThat(read.problem()).get().asString().contains("inconsistent");
        assertThat(failure).isInstanceOf(TelemetryStateException.class);
        assertThat(Files.readString(store.stateFile()))
                .as("the original file is kept for diagnosis")
                .isEqualTo(json);
    }

    static Stream<Arguments> inconsistentStates() {
        return Stream.of(
                Arguments.of("identity without registration date", (Consumer<ObjectNode>)
                        node -> node.putNull("registered_on")),
                Arguments.of("negative counter", (Consumer<ObjectNode>) node -> node.put("board_imports_total", -1)),
                Arguments.of(
                        "claim without pending report", (Consumer<ObjectNode>) node -> node.putNull("pending_report")),
                Arguments.of("claim for another event", (Consumer<ObjectNode>) node ->
                        ((ObjectNode) node.get("claim")).put("event_uuid", "00000000-0000-4000-8000-000000000000")),
                Arguments.of("pending report with changed protocol flag", (Consumer<ObjectNode>) node ->
                        ((ObjectNode) node.get("pending_report").get("properties")).put("$geoip_disable", false)),
                Arguments.of("pending report with free-text platform", (Consumer<ObjectNode>)
                        node -> ((ObjectNode) node.get("pending_report").get("properties"))
                                .put("os_release", "Ubuntu 24.04 LTS (custom build)")),
                Arguments.of("pending report from a future revision", (Consumer<ObjectNode>)
                        node -> ((ObjectNode) node.get("pending_report")).put("preference_revision", 99)),
                Arguments.of("incomplete retry schedule", (Consumer<ObjectNode>)
                        node -> node.set("retry", JSON.createObjectNode().put("attempts", 0))));
    }

    /// A complete, consistent stored state: registered, mid-retry, with a claimed pending report.
    private static TelemetryState validStoredState() {
        UUID eventUuid = UUID.fromString("95127c85-1c9e-460c-b652-0a3a7899cbde");
        HeartbeatProperties properties =
                new HeartbeatProperties(1, "2026-09-22", "1.2.0", "linux", "13", "debian", "x64", 2, 1, 1, true, true);
        return TelemetryState.initial()
                .withIdentity(UUID.fromString("74a69e87-089d-4fd1-8ac2-df8aab052cb1"), LocalDate.of(2026, 9, 22))
                .withNotice(1, Instant.parse("2026-09-22T10:00:00Z"))
                .withFirstWorkerDeadline(Instant.parse("2026-09-22T10:05:00Z"))
                .withCounters(4, 2)
                .withReporting(
                        LocalDate.of(2026, 9, 21),
                        new TelemetryState.RetryState(1, Instant.parse("2026-09-22T11:00:00Z"), "HTTP 503"),
                        new TelemetryState.PendingHeartbeat(
                                eventUuid,
                                Instant.parse("2026-09-22T09:15:00Z"),
                                LocalDate.of(2026, 9, 22),
                                0,
                                properties),
                        new TelemetryState.ReportClaim(
                                "worker-a",
                                eventUuid,
                                UUID.fromString("0d6f1c2e-7b3a-4c5d-8e9f-a1b2c3d4e5f6"),
                                Instant.parse("2026-09-22T09:16:30Z")));
    }

    @Test
    void aTransactionStuckInThisJvmDoesNotBlockAnotherStoreForever() throws Exception {
        // given
        TelemetryStateStore first = new TelemetryStateStore(tempDir, SHORT_LOCK_WAIT);
        TelemetryStateStore second = new TelemetryStateStore(tempDir, SHORT_LOCK_WAIT);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> stuck = executor.submit(() -> first.update(state -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return Update.unchanged(null);
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("the first transaction is inside the lock")
                    .isTrue();

            // when
            Throwable failure = catchThrowable(() -> second.update(state -> Update.unchanged(null)));
            release.countDown();
            stuck.get(5, TimeUnit.SECONDS);

            // then
            assertThat(failure)
                    .isInstanceOf(TelemetryStateException.class)
                    .hasMessageContaining("held by another transaction of this process");
        }
    }

    @Test
    void stateFileIsOwnerOnlyOnPosix() throws IOException {
        // given
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        TelemetryStateStore store = new TelemetryStateStore(tempDir.resolve("state"));

        // when
        store.update(state -> Update.write(state.withCounters(1, 0), null));

        // then
        assertThat(Files.getPosixFilePermissions(store.stateFile()))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void concurrentIncrementsFromManyThreadsAreAllPreserved() throws Exception {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        var start = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(WRITERS)) {
            for (int writer = 0; writer < WRITERS; writer++) {
                writers.add(executor.submit(() -> {
                    start.await();
                    for (int increment = 0; increment < INCREMENTS_PER_WRITER; increment++) {
                        store.update(state -> Update.write(state.withCounters(state.boardImportsTotal() + 1, 0), null));
                    }
                    return null;
                }));
            }

            // when
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(30, TimeUnit.SECONDS);
            }
        }

        // then
        assertThat(store.read().state())
                .get()
                .extracting(TelemetryState::boardImportsTotal)
                .isEqualTo((long) WRITERS * INCREMENTS_PER_WRITER);
    }

    @Test
    void anExternalLockHolderBlocksTheTransactionUntilReleased() throws Exception {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir);
        store.update(state -> Update.write(state.withCounters(1, 0), null));
        Path lockFile = tempDir.resolve(TelemetryStateStore.LOCK_FILE);
        Process holder = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ExternalFileLockHolder.class.getName(),
                        lockFile.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertThat(holder.inputReader().readLine()).isEqualTo("locked");
            Long seen;
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Long> blocked = executor.submit(
                        () -> store.update(state -> Update.write(state.withCounters(2, 0), state.boardImportsTotal())));

                // when
                assertThatThrownBy(() -> blocked.get(300, TimeUnit.MILLISECONDS))
                        .as("the update waits while another process holds telemetry.lock")
                        .isInstanceOf(TimeoutException.class);
                holder.getOutputStream().close();
                seen = blocked.get(TelemetryStateStore.LOCK_WAIT.toSeconds() + 5, TimeUnit.SECONDS);
            }

            // then
            assertThat(seen).isEqualTo(1L);
            assertThat(store.read().state())
                    .get()
                    .extracting(TelemetryState::boardImportsTotal)
                    .isEqualTo(2L);
        } finally {
            if (holder.isAlive()) {
                holder.destroyForcibly();
                holder.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void lockWaitIsBoundedWhenTheHolderNeverReleases() throws IOException {
        // given
        TelemetryStateStore store = new TelemetryStateStore(tempDir, SHORT_LOCK_WAIT);
        Path lockFile = tempDir.resolve(TelemetryStateStore.LOCK_FILE);
        Files.createDirectories(tempDir);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {

            // when
            Throwable failure =
                    catchThrowable(() -> runOnOtherThread(() -> store.update(state -> Update.unchanged(null))));

            // then
            assertThat(failure)
                    .as("another thread of this JVM sees the lock as held and gives up after the bounded wait")
                    .hasCauseInstanceOf(TelemetryStateException.class)
                    .hasMessageContaining("held by another process");
        }
    }

    private static void runOnOtherThread(Runnable task) throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(task).get(10, TimeUnit.SECONDS);
        }
    }
}
