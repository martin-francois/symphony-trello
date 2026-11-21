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
import ch.fmartin.symphony.trello.testsupport.MutableClock;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
    void windowsResponsesInheritTransportReadAccess() throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("acl"), "Windows ACL support required");
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        try (var server = fixture.server()) {
            var reader = WindowsTransportAclFixture.otherPrincipal(temporary, Files.getOwner(fixture.config()));
            WindowsTransportAclFixture.addGrant(
                    fixture.files().responses(),
                    WindowsTransportAclFixture.transportGrant(reader, AclEntryPermission.READ_DATA));
            Files.write(fixture.files().request(id), fixture.submission());

            // when
            server.processPending();

            // then
            assertThat(Files.getFileAttributeView(fixture.files().response(id), AclFileAttributeView.class)
                            .getAcl())
                    .extracting(AclEntry::principal)
                    .contains(reader);
        }
    }

    @Test
    void existingClaimPreventsExecutionWithoutAReply() throws Exception {
        // given
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        try (var server = fixture.server()) {
            Files.createFile(fixture.files().claimed(id));
            Files.write(fixture.files().request(id), fixture.submission());

            // when
            server.processPending();

            // then
            verify(fixture.commands(), never()).run(any(), any());
            assertThat(fixture.reply(id).failureCode()).isEqualTo("setup_card_helper_outcome_unknown");
        }
    }

    @Test
    void failedReplyPublicationRetainsTheClaimUntilRecovery() throws Exception {
        // given
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        Path savedResponses = temporary.resolve("saved-responses");
        try (var server = fixture.server()) {
            Files.write(fixture.files().request(id), fixture.submission());
            when(fixture.commands().run(any(), any()))
                    .thenAnswer(ignored -> interruptReplies(fixture.files(), id, savedResponses));

            // when
            Throwable failure = catchThrowable(server::processPending);

            // then
            assertThat(failure).isInstanceOf(IOException.class);
            assertThat(fixture.files().claimed(id)).isRegularFile();
            verify(fixture.commands()).run(any(), any());
            Files.delete(fixture.files().responses());
            Files.move(savedResponses, fixture.files().responses());
        }
        try (var restarted = fixture.server()) {
            restarted.processPending();
            assertThat(fixture.reply(id).failureCode()).isEqualTo("setup_card_helper_outcome_unknown");
            assertThat(fixture.files().claimed(id)).doesNotExist();
            assertThat(fixture.files().submission(id)).doesNotExist();
            verify(fixture.commands()).run(any(), any());
        }
    }

    private static int interruptReplies(CardHelperFiles files, UUID id, Path savedResponses) throws IOException {
        assertThat(files.request(id)).doesNotExist();
        assertThat(files.submission(id)).isRegularFile();
        Files.move(files.responses(), savedResponses);
        Files.writeString(files.responses(), "publication is unavailable");
        return 0;
    }

    @Test
    void windowsClaimsDoNotInheritCallerAccess() throws Exception {
        // given
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("acl"), "Windows ACL support required");
        Fixture fixture = fixture();
        UUID id = UUID.randomUUID();
        Path savedResponses = temporary.resolve("saved-responses");
        try (var server = fixture.server()) {
            var owner = Files.getOwner(fixture.config());
            var reader = WindowsTransportAclFixture.otherPrincipal(temporary, owner);
            Files.write(fixture.files().request(id), fixture.submission());
            WindowsTransportAclFixture.addGrant(
                    fixture.files().request(id),
                    WindowsTransportAclFixture.transportGrant(reader, AclEntryPermission.READ_DATA));
            when(fixture.commands().run(any(), any()))
                    .thenAnswer(ignored -> interruptReplies(fixture.files(), id, savedResponses));

            // when
            Throwable failure = catchThrowable(server::processPending);

            // then
            assertThat(failure).isInstanceOf(IOException.class);
            assertThat(Files.getOwner(fixture.files().claimed(id))).isEqualTo(owner);
            assertThat(Files.getFileAttributeView(fixture.files().claimed(id), AclFileAttributeView.class)
                            .getAcl())
                    .filteredOn(entry -> entry.type() == AclEntryType.ALLOW)
                    .extracting(AclEntry::principal)
                    .containsOnly(owner);
            Files.delete(fixture.files().responses());
            Files.move(savedResponses, fixture.files().responses());
        }
    }

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
        var clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));
        long now = clock.millis();
        UUID id = UUID.randomUUID();
        try (var server = fixture.server(clock, CardHelperProtocol.HEARTBEAT_INTERVAL)) {
            byte[] bytes = fixture.submission(now + CardHelperProtocol.REQUEST_LIFETIME.toMillis());
            Files.write(fixture.files().request(id), bytes);
            server.processPending();
            Files.setLastModifiedTime(fixture.files().response(id), FileTime.fromMillis(now));
            clock.advance(CardHelperProtocol.REQUEST_LIFETIME.plusMillis(1));
            Files.write(fixture.files().request(id), bytes);

            // when
            server.processPending();

            // then
            verify(fixture.commands()).run(any(), any());
            assertThat(fixture.reply(id).failureCode()).isEqualTo("setup_card_helper_outcome_unknown");
        }
    }

    @Test
    void heartbeatKeepsRefreshingWhileACardOperationBlocks() throws Exception {
        // given
        Fixture fixture = fixture();
        var clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));
        BlockedOperation operation = blockCommands(fixture);
        try (var server = fixture.server(clock, Duration.ofMillis(10));
                var executor = Executors.newSingleThreadExecutor()) {
            Files.write(
                    fixture.files().request(UUID.randomUUID()),
                    fixture.submission(clock.millis() + CardHelperProtocol.REQUEST_LIFETIME.toMillis()));
            var processing = executor.submit(() -> {
                server.processPending();
                return null;
            });
            operation.awaitStarted("the helper enters the card operation before the clock moves");

            // when
            clock.advance(CardHelperProtocol.HEARTBEAT_STALE_AFTER.multipliedBy(2));

            // then
            try {
                BoundedWait.until(
                        TIMEOUT,
                        () -> !heartbeatStale(fixture.files(), clock),
                        "a heartbeat recorded while the card operation blocks");
            } finally {
                operation.finish().countDown();
            }
            processing.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void unwritableReadinessRecordEndsTheHelperInsteadOfLeavingItSilentlyUnavailable() throws Exception {
        // given
        Fixture fixture = fixture();
        var clock = new MutableClock(Instant.parse("2026-10-31T12:00:00Z"));
        // The server closes first, so a serve() that ignores the failure ends instead of blocking executor close.
        try (var executor = Executors.newSingleThreadExecutor();
                var server = fixture.server(clock, Duration.ofMillis(10))) {
            Files.delete(fixture.files().ready());
            var serving = executor.submit(() -> {
                server.serve();
                return null;
            });

            // when
            Throwable thrown = catchThrowable(() -> serving.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            // then
            assertThat(thrown)
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cannot keep card helper ready");
        }
    }

    /// Stubs the card command to block inside the helper until the test releases it.
    private static BlockedOperation blockCommands(Fixture fixture) throws IOException {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(fixture.commands().run(any(), any())).thenAnswer(invocation -> {
            started.countDown();
            assertThat(finish.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the test releases the active card operation before its deadline")
                    .isTrue();
            return 0;
        });
        return new BlockedOperation(started, finish);
    }

    private record BlockedOperation(CountDownLatch started, CountDownLatch finish) {
        void awaitStarted(String expectation) throws InterruptedException {
            assertThat(started.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as(expectation)
                    .isTrue();
        }
    }

    private static boolean heartbeatStale(CardHelperFiles files, Clock clock) {
        try {
            return files.heartbeatStale(clock.millis());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
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
        BlockedOperation operation = blockCommands(fixture);
        try (var server = fixture.server();
                var executor = Executors.newFixedThreadPool(2)) {
            Files.write(fixture.files().request(UUID.randomUUID()), fixture.submission());
            var writing = executor.submit(() -> {
                server.processPending();
                return null;
            });
            operation.awaitStarted("the helper enters the card operation before shutdown");
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
                operation.finish().countDown();
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
                new CardHelperFiles(temporary.resolve("state/card-helper/installation")),
                mock(CardCommandRunner.class));
    }

    private record Fixture(Path config, CardHelperFiles files, CardCommandRunner commands) {
        CardHelperServer server() throws IOException {
            return new CardHelperServer(config, files, commands);
        }

        CardHelperServer server(Clock clock, Duration heartbeatInterval) throws IOException {
            return new CardHelperServer(config, files, commands, clock, heartbeatInterval);
        }

        byte[] submission() throws IOException {
            return submission(System.currentTimeMillis() + CardHelperProtocol.REQUEST_LIFETIME.toMillis());
        }

        byte[] submission(long expiresAtEpochMillis) throws IOException {
            var json = CardHelperProtocol.json();
            var ready = json.readValue(Files.readAllBytes(files.ready()), CardHelperProtocol.Ready.class);
            return json.writeValueAsBytes(new CardHelperProtocol.Submission(
                    ready.generation(),
                    expiresAtEpochMillis,
                    new CardHelperProtocol.Request(1, "board-1", null, true, "list", Map.of())));
        }

        CardHelperProtocol.Reply reply(UUID id) throws IOException {
            return CardHelperProtocol.json()
                    .readValue(Files.readAllBytes(files.response(id)), CardHelperProtocol.Reply.class);
        }
    }
}
