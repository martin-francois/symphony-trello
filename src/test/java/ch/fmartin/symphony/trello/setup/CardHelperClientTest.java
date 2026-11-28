package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.testsupport.BoundedWait;
import ch.fmartin.symphony.trello.testsupport.MutableClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CardHelperClientTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receivesCardOutputWithoutReadingConfigOrRequiringHostPidVisibility(boolean hiddenHostPid) throws Exception {
        // given
        Path config = Files.createDirectory(temporary.resolve("trusted-config"));
        var files = new CardHelperFiles(temporary.resolve("state/card-helper/installation"));
        var client = new CardHelperClient(Map.of(), TIMEOUT);
        try (var server = new CardHelperServer(config, files, (request, out) -> {
                    assertThat(request.configDir()).hasValue(config);
                    out.print("card result\n");
                    return 0;
                });
                var executor = Executors.newSingleThreadExecutor();
                var output = new ByteArrayOutputStream();
                var out = new PrintStream(output, false, StandardCharsets.UTF_8)) {
            if (hiddenHostPid) {
                var json = CardHelperProtocol.json();
                var ready = json.readValue(Files.readAllBytes(files.ready()), CardHelperProtocol.Ready.class);
                Files.write(
                        files.ready(),
                        json.writeValueAsBytes(
                                new CardHelperProtocol.Ready(ready.version(), ready.generation(), Long.MAX_VALUE)));
            }
            var serving = executor.submit(() -> {
                server.serve();
                return null;
            });

            // when
            int result;
            try (server) {
                result = client.run(request(), files, out);
            }
            serving.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // then
            assertThat(result).isZero();
            assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("card result\n");
        }
    }

    @Test
    void unavailableHelperDoesNotFallBackToDirectCredentialUse() throws Exception {
        // given
        var files = new CardHelperFiles(temporary.resolve("missing-helper"));
        var client = new CardHelperClient(Map.of(), TIMEOUT);
        try (var output = new ByteArrayOutputStream();
                var out = new PrintStream(output, false, StandardCharsets.UTF_8)) {

            // when
            Throwable thrown = catchThrowable(() -> client.run(request(), files, out));

            // then
            assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("trusted terminal");
            assertThat(files.root()).doesNotExist();
            assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        }
    }

    @Test
    void staleHeartbeatFailsFastAsUnavailableWithoutPublishingARequest() throws Exception {
        // given
        var clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));
        CardHelperFiles files = crashedHelper(clock);
        clock.advance(CardHelperProtocol.HEARTBEAT_STALE_AFTER.plusMillis(1));
        var client = new CardHelperClient(Map.of(), TIMEOUT, clock, Duration.ofMillis(10));
        try (var output = new ByteArrayOutputStream();
                var out = new PrintStream(output, false, StandardCharsets.UTF_8)) {

            // when
            Throwable thrown = catchThrowable(() -> client.run(request(), files, out));

            // then
            assertThat(thrown)
                    .isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.code())
                            .isEqualTo(CardHelperProtocol.UNAVAILABLE_CODE));
            assertThat(files.requests()).isEmptyDirectory();
        }
    }

    @Test
    void helperDeathWhileWaitingStopsTheWaitAndWithdrawsTheUnclaimedRequest() throws Exception {
        // given
        var clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));
        CardHelperFiles files = crashedHelper(clock);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> running = publishedRun(executor, files, clock);

            // when
            clock.advance(CardHelperProtocol.HEARTBEAT_STALE_AFTER.plusMillis(1));
            Throwable thrown = running.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // then
            assertThat(thrown)
                    .isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.code())
                            .isEqualTo(CardHelperProtocol.UNAVAILABLE_CODE));
            assertThat(files.requests()).isEmptyDirectory();
        }
    }

    @Test
    void claimedRequestWithoutReplyKeepsTheUncertainOutcomeWhenTheHelperDies() throws Exception {
        // given
        var clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));
        CardHelperFiles files = crashedHelper(clock);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> running = publishedRun(executor, files, clock);
            Path request = publishedRequest(files);
            var submission = CardHelperProtocol.json()
                    .readValue(Files.readAllBytes(request), CardHelperProtocol.Submission.class);
            // The helper claims a request by moving it out of the queue.
            Files.move(request, files.submission(CardHelperFiles.requestId(request)));

            // when
            clock.advance(CardHelperProtocol.HEARTBEAT_STALE_AFTER.plusMillis(1));
            Throwable thrown = running.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // then
            assertThat(submission.expiresAtEpochMillis())
                    .isEqualTo(
                            Instant.parse("2026-10-31T12:00:00Z").plus(TIMEOUT).toEpochMilli());
            assertThat(thrown)
                    .isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.code())
                            .isEqualTo(CardHelperProtocol.OUTCOME_UNKNOWN_CODE));
        }
    }

    @Test
    void ioFailureAfterPublicationIsAnUncertainOutcome() throws Exception {
        // given
        var clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));
        CardHelperFiles files = crashedHelper(clock);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Throwable> running = publishedRun(executor, files, clock);
            Path request = publishedRequest(files);
            // A non-empty directory under the request's name makes the withdrawal's delete fail.
            Files.delete(request);
            Files.createFile(Files.createDirectory(request).resolve("occupied"));

            // when
            clock.advance(CardHelperProtocol.HEARTBEAT_STALE_AFTER.plusMillis(1));
            Throwable thrown = running.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            // then
            assertThat(thrown)
                    .isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.code())
                            .isEqualTo(CardHelperProtocol.OUTCOME_UNKNOWN_CODE))
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    /// Starts a client request in the background and returns once the request is in the queue.
    private static Future<Throwable> publishedRun(ExecutorService executor, CardHelperFiles files, MutableClock clock)
            throws Exception {
        var client = new CardHelperClient(Map.of(), TIMEOUT, clock, Duration.ofMillis(10));
        Future<Throwable> running = executor.submit(() -> failureOf(client, files));
        BoundedWait.until(
                TIMEOUT, () -> files.requests().toFile().list().length > 0, "the client publishes its request");
        return running;
    }

    private static Throwable failureOf(CardHelperClient client, CardHelperFiles files) {
        try (var out = new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8)) {
            return catchThrowable(() -> client.run(request(), files, out));
        }
    }

    private static Path publishedRequest(CardHelperFiles files) {
        return files.requests().resolve(files.requests().toFile().list()[0]);
    }

    /// A helper killed without cleanup leaves its readiness record with its last heartbeat.
    private CardHelperFiles crashedHelper(MutableClock clock) throws Exception {
        Path config = Files.createDirectory(temporary.resolve("trusted-config"));
        var files = new CardHelperFiles(temporary.resolve("state/card-helper/installation"));
        files.initialize(config);
        Files.write(
                files.ready(),
                CardHelperProtocol.json()
                        .writeValueAsBytes(
                                new CardHelperProtocol.Ready(CardHelperProtocol.VERSION, UUID.randomUUID(), 12345)));
        files.recordHeartbeat(clock.millis());
        return files;
    }

    private static CardCommandRequest request() {
        return new CardCommandRequest(
                Optional.of("board-1"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true,
                new CardCommandRequest.ListCards(Optional.empty()));
    }
}
