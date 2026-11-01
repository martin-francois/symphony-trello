package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.testsupport.BoundedWait;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CardHelperServerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temporary;

    @Test
    void boundedScansAdvancePastIgnoredDirectories() throws Exception {
        // given
        Fixture fixture = fixture();
        List<Path> responses = new ArrayList<>();
        try (var server = fixture.server()) {
            byte[] submission = fixture.submission();
            for (int index = 0; index < 1088; index++) {
                Files.write(fixture.files().request(new UUID(0, index)), submission);
            }
            List<Path> requests = new ArrayList<>();
            try (var entries = Files.newDirectoryStream(fixture.files().requests(), "*.request")) {
                for (Path entry : entries) {
                    requests.add(entry);
                }
            }
            for (Path request : requests.subList(0, 1024)) {
                Files.delete(request);
                Files.createDirectory(request);
            }
            for (Path request : requests.subList(1024, requests.size())) {
                responses.add(fixture.files().response(CardHelperFiles.requestId(request)));
            }

            // when
            for (int pass = 0; pass < 10; pass++) {
                server.processPending();
            }

            // then
            verify(fixture.commands(), times(64)).run(any(), any());
            assertThat(responses).allSatisfy(response -> assertThat(response).isRegularFile());
        }
    }

    @Test
    void duplicateRequestsExecuteOneWrite() throws Exception {
        // given
        Fixture fixture = fixture();
        when(fixture.commands().run(any(), any())).thenReturn(0);
        UUID id = UUID.randomUUID();
        try (var server = fixture.server()) {
            byte[] request = fixture.submission();
            Files.write(fixture.files().request(id), request);

            // when
            server.processPending();
            Files.write(fixture.files().request(id), request);
            server.processPending();

            // then
            verify(fixture.commands()).run(any(), any());
            assertThat(fixture.reply(id).exitCode()).isZero();
            assertThat(fixture.files().request(id)).doesNotExist();
        }
    }

    @Test
    void restartNeverReplaysAnAlreadyClaimedWrite() throws Exception {
        // given
        Fixture fixture = fixture();
        UUID claimed = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        try (var ignored = fixture.server()) {
            byte[] request = fixture.submission();
            Files.write(fixture.files().request(claimed), request);
            Files.move(
                    fixture.files().request(claimed), fixture.files().claimed(claimed), StandardCopyOption.ATOMIC_MOVE);
            Files.write(fixture.files().request(queued), request);
        }

        // when
        try (var restarted = fixture.server()) {
            restarted.processPending();

            // then
            verify(fixture.commands(), never()).run(any(), any());
            assertThat(fixture.reply(claimed).failureCode()).isEqualTo("setup_card_helper_outcome_unknown");
            assertThat(fixture.reply(queued).failureCode()).isEqualTo("setup_card_helper_outcome_unknown");
        }
    }

    @Test
    void rejectsMalformedInputWithoutLeakingItsContents() throws Exception {
        // given
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        try (var server = fixture.server()) {
            Files.writeString(fixture.files().request(id), "sensitive malformed input");

            // when
            server.processPending();

            // then
            verify(fixture.commands(), never()).run(any(), any());
            assertThat(fixture.reply(id))
                    .isEqualTo(CardHelperProtocol.Reply.failed("setup_card_helper_invalid_request"));
            assertThat(fixture.files().response(id))
                    .content(StandardCharsets.UTF_8)
                    .doesNotContain("sensitive");
        }
    }

    @Test
    void refusesOversizedRequestsBeforeExecutingThem() throws Exception {
        // given
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        try (var server = fixture.server()) {
            Files.write(fixture.files().request(id), new byte[CardHelperProtocol.MAX_REQUEST_BYTES + 1]);

            // when
            server.processPending();

            // then
            verify(fixture.commands(), never()).run(any(), any());
            assertThat(fixture.reply(id).failureCode()).isEqualTo("setup_card_helper_invalid_request");
        }
    }

    @Test
    void expiredResponseCleanupDoesNotPermitAnOldRequestToExecuteAgain() throws Exception {
        // given
        Fixture fixture = fixture();
        var clock = mock(Clock.class);
        long now = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli();
        when(clock.millis()).thenReturn(now);
        UUID id = UUID.randomUUID();
        try (var server = new CardHelperServer(fixture.config(), fixture.files(), fixture.commands(), clock)) {
            var json = CardHelperProtocol.json();
            var submission = json.readValue(fixture.submission(), CardHelperProtocol.Submission.class);
            byte[] bytes = json.writeValueAsBytes(new CardHelperProtocol.Submission(
                    submission.generation(),
                    now + CardHelperProtocol.REQUEST_LIFETIME.toMillis(),
                    submission.command()));
            Files.write(fixture.files().request(id), bytes);
            server.processPending();
            Files.setLastModifiedTime(fixture.files().response(id), FileTime.fromMillis(now));
            when(clock.millis()).thenReturn(now + CardHelperProtocol.REQUEST_LIFETIME.toMillis() + 1);
            Files.write(fixture.files().request(id), bytes);

            // when
            server.processPending();

            // then
            verify(fixture.commands()).run(any(), any());
            assertThat(fixture.reply(id).failureCode()).isEqualTo("setup_card_helper_outcome_unknown");
        }
    }

    @Test
    void boundsOutputEvenWhenTheCommandContinuesWriting() throws Exception {
        // given
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        when(fixture.commands().run(any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, PrintStream.class).print("x".repeat(CardHelperProtocol.MAX_RESPONSE_BYTES + 1));
            return 0;
        });
        try (var server = fixture.server()) {
            Files.write(fixture.files().request(id), fixture.submission());

            // when
            server.processPending();

            // then
            assertThat(fixture.reply(id))
                    .isEqualTo(CardHelperProtocol.Reply.failed("setup_card_helper_response_too_large"));
        }
    }

    @Test
    void ignoresRequestDirectoriesWithoutTraversingThem() throws Exception {
        // given
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        try (var server = fixture.server()) {
            Path nested = Files.createDirectories(fixture.files().request(id)).resolve("keep");
            Files.writeString(nested, "preserved");
            UUID valid = UUID.randomUUID();
            Files.write(fixture.files().request(valid), fixture.submission());

            // when
            server.processPending();

            // then
            assertThat(nested).hasContent("preserved");
            verify(fixture.commands()).run(any(), any());
            assertThat(fixture.files().response(valid)).isRegularFile();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"symlink", "fifo"})
    void refusesLinkedAndSpecialRequestsWithoutReadingTheirContents(String type) throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"), "POSIX filesystem required");
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        Path secret = Files.writeString(temporary.resolve("synthetic-secret"), "synthetic-secret-value");
        try (var server = fixture.server()) {
            Path request = fixture.files().request(id);
            if (type.equals("symlink")) {
                Files.createSymbolicLink(request, secret);
            } else {
                Process mkfifo = new ProcessBuilder("mkfifo", request.toString()).start();
                assertThat(mkfifo.waitFor(Duration.ofSeconds(5)))
                        .as("mkfifo finishes before the request runs")
                        .isTrue();
                assertThat(mkfifo.exitValue()).isZero();
            }

            // when
            server.processPending();

            // then
            verify(fixture.commands(), never()).run(any(), any());
            assertThat(fixture.reply(id))
                    .isEqualTo(CardHelperProtocol.Reply.failed("setup_card_helper_invalid_request"));
            assertThat(secret).hasContent("synthetic-secret-value");
            assertThat(fixture.files().response(id))
                    .content(StandardCharsets.UTF_8)
                    .doesNotContain("synthetic-secret-value");
        }
    }

    @Test
    void gracefulShutdownWithdrawsReadyBeforeAClientCanSubmitAnotherRequest() throws Exception {
        // given
        Fixture fixture = fixture();
        CardHelperServer server = fixture.server();

        // when
        try (server) {
            assertThat(fixture.files().ready()).isRegularFile();
        }

        // then
        assertThat(fixture.files().ready()).doesNotExist();
    }

    @Test
    void closingAnOldServerAgainKeepsItsReplacementsReadyRecord() throws Exception {
        // given
        Fixture fixture = fixture();
        CardHelperServer previous = fixture.server();
        try (previous) {
            // Complete the first helper lifetime before its replacement starts.
        }
        try (var ignored = fixture.server()) {
            byte[] replacement = Files.readAllBytes(fixture.files().ready());

            // when
            try (previous) {
                // Repeated cleanup must not withdraw the new helper's identity.
            }

            // then
            assertThat(fixture.files().ready()).hasBinaryContent(replacement);
        }
    }

    @Test
    void onlyOneHelperOwnsAnInstallation() throws Exception {
        // given
        Fixture fixture = fixture();
        try (var ignored = fixture.server()) {

            // when
            Throwable thrown = catchThrowable(fixture::server);

            // then
            assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("already owns");
        }
    }

    @Test
    void shutdownKeepsOwnershipUntilTheActiveCardWriteFinishes() throws Exception {
        // given
        Fixture fixture = fixture();
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(fixture.commands().run(any(), any())).thenAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the test releases the active card write before its deadline")
                    .isTrue();
            return 0;
        });
        try (var server = fixture.server();
                var executor = Executors.newFixedThreadPool(2)) {
            Files.write(fixture.files().request(UUID.randomUUID()), fixture.submission());
            var writing = executor.submit(() -> {
                server.processPending();
                return null;
            });
            assertThat(started.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the helper enters the card operation before shutdown")
                    .isTrue();
            var closingThread = new AtomicReference<Thread>();
            var closing = executor.submit(() -> {
                closingThread.set(Thread.currentThread());
                server.close();
                return null;
            });

            // when
            try {
                BoundedWait.until(
                        TIMEOUT,
                        () -> closing.isDone()
                                || (closingThread.get() != null
                                        && closingThread.get().getState() == Thread.State.BLOCKED),
                        "shutdown reaching the active operation");

                // then
                assertThatThrownBy(fixture::server)
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("already owns");
            } finally {
                finish.countDown();
            }
            writing.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            closing.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        try (var restarted = fixture.server()) {
            assertThat(restarted).isNotNull();
        }
    }

    private Fixture fixture() throws IOException {
        return new Fixture(
                Files.createDirectory(temporary.resolve("config")),
                new CardHelperFiles(temporary.resolve("state").resolve("helper")),
                mock(CardCommandRunner.class));
    }

    private record Fixture(Path config, CardHelperFiles files, CardCommandRunner commands) {
        CardHelperServer server() throws IOException {
            return new CardHelperServer(config, files, commands);
        }

        byte[] submission() throws IOException {
            var json = CardHelperProtocol.json();
            var ready = json.readValue(Files.readAllBytes(files.ready()), CardHelperProtocol.Ready.class);
            return json.writeValueAsBytes(new CardHelperProtocol.Submission(
                    ready.generation(),
                    System.currentTimeMillis() + CardHelperProtocol.REQUEST_LIFETIME.toMillis(),
                    new CardHelperProtocol.Request(1, "board-1", null, true, "list", Map.of())));
        }

        CardHelperProtocol.Reply reply(UUID id) throws IOException {
            return CardHelperProtocol.json()
                    .readValue(Files.readAllBytes(files.response(id)), CardHelperProtocol.Reply.class);
        }
    }
}
