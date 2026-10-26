package ch.fmartin.symphony.trello.agent;

import static java.nio.file.Files.getPosixFilePermissions;
import static java.nio.file.attribute.PosixFilePermissions.fromString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.Sha3;
import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class LocalFinalResponseTest {
    private static final int STICKY_SHARED_DIRECTORY_MODE = 1023;
    private static final long WAIT_SECONDS = 10;
    private static final String WORKER = "synthetic-worker";
    private static final String THREAD = "synthetic-thread";
    private static final String TURN = "synthetic-turn";
    private static final String RESPONSE =
            "Branch-only handoff, no pull request.\nCopy this complete handoff before Merging.\n";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void completedFinalResponseRemainsReadableAfterWorkerAndTaskWorkspaceEnd() throws Exception {
        // given
        EffectiveConfig config = config();
        Card card = TestCards.card("card-1", "TRELLO-SYNTH001", "In Progress");
        CodexAppServerClient codex = mock();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            AgentEventListener listener = invocation.getArgument(5);
            successfulAnswer(listener, WORKER, RESPONSE);
            return AgentRunResult.ok();
        });
        var workspaces = new WorkspaceManager(new HookRunner());
        var runner = new LocalAgentRunner(
                workspaces, new HookRunner(), codex, mock(TrackerClient.class), new PromptRenderer());

        // when
        AgentRunResult result =
                runner.run(new AgentRunner.AgentRunRequest(card, null, "Task", config, WORKER, event -> {}));
        workspaces.removeForIdentifierIfPresent(card.identifier(), config);

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        Path outputs = config.workspace().root().resolve(".symphony+final-responses");
        assertThat(outputs).isDirectory();
        List<Path> records = records(config);
        assertThat(records).singleElement().satisfies(path -> {
            var record = JSON.readTree(path.toFile());
            assertThat(record.path("status").asText()).isEqualTo("completed");
            assertThat(record.path("response").asText()).isEqualTo(RESPONSE);
            assertThat(record.path("worker_identity").asText()).isEqualTo(WORKER);
            assertThat(record.path("thread_id").asText()).isEqualTo(THREAD);
            assertThat(record.path("turn_id").asText()).isEqualTo(TURN);
            assertThat(record.path("card_id").asText()).isEqualTo(card.id());
        });
    }

    @EnumSource(IncompleteFinal.class)
    @ParameterizedTest
    void incompleteOrUntrustedTurnDoesNotPublishResponse(IncompleteFinal scenario) throws Exception {
        // given
        var store = new FinalResponseStore();
        var request = request(config(), WORKER);
        try (var recording = store.begin(request).orElseThrow()) {
            AgentEventListener delegate = new AgentEventListener() {
                @Override
                public void onEvent(AgentEvent event) {}

                @Override
                public boolean onEventAndReportAccepted(AgentEvent event) {
                    return !(scenario == IncompleteFinal.REJECTED_FINAL && "item/completed".equals(event.event()))
                            && !(scenario == IncompleteFinal.REJECTED_COMPLETION
                                    && "turn/completed".equals(event.event()));
                }
            };
            AgentEventListener listener = recording.listener(delegate);
            String text =
                    switch (scenario) {
                        case BLANK -> " \n\t";
                        case EMPTY -> "";
                        case OVERSIZED -> "x".repeat(FinalResponseStore.MAX_RESPONSE_BYTES + 1);
                        default -> RESPONSE;
                    };

            // when
            if (scenario == IncompleteFinal.PARTIAL_FINAL) {
                var partial = JSON.createObjectNode();
                partial.putObject("item")
                        .put("type", "agentMessage")
                        .put("phase", "final_answer")
                        .put("text", "Partial");
                listener.onEvent(event("item/started", WORKER, THREAD, TURN, partial));
                listener.onEvent(event(
                        "item/agentMessage/delta",
                        WORKER,
                        THREAD,
                        TURN,
                        JSON.createObjectNode().put("delta", "Partial")));
            } else if (scenario != IncompleteFinal.MISSING_FINAL) {
                finalAnswer(listener, WORKER, THREAD, TURN, text);
            }
            completedTurn(
                    listener,
                    WORKER,
                    scenario == IncompleteFinal.WRONG_THREAD ? "another-thread" : THREAD,
                    scenario == IncompleteFinal.WRONG_TURN ? "another-turn" : TURN,
                    scenario == IncompleteFinal.FAILED_STATUS ? "failed" : "completed");
            if (scenario != IncompleteFinal.NO_SUCCESS_CALLBACK) {
                listener.onSuccessfulTurn(THREAD, TURN);
            }
            AgentRunResult result = recording.finish(
                    scenario == IncompleteFinal.FAILED_RUN
                            ? AgentRunResult.fail("synthetic failure")
                            : AgentRunResult.ok());

            // then
            assertThat(result.success())
                    .as("incomplete or rejected output must fail delivery")
                    .isFalse();
            JsonNode record = readOnlyRecord(config());
            assertThat(record.path("status").asText()).isEqualTo("failed");
            assertThat(record.has("response"))
                    .as("failed output must not expose a current handoff")
                    .isFalse();
        }
    }

    @Test
    void lateDuplicateStartDoesNotEraseCompletedFinalForSameTurn() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        try (var recording = store.begin(request(config, WORKER)).orElseThrow()) {
            AgentEventListener listener = recording.listener(event -> {});

            // when
            finalAnswer(listener, WORKER, THREAD, TURN, RESPONSE);
            completedTurn(listener, WORKER, THREAD, TURN, "completed");
            listener.onEvent(event("session_started", WORKER, THREAD, TURN, JSON.createObjectNode()));
            listener.onSuccessfulTurn(THREAD, TURN);
            AgentRunResult result = recording.finish(AgentRunResult.ok());

            // then
            assertThat(result).isEqualTo(AgentRunResult.ok());
            assertThat(readOnlyRecord(config).path("response").asText()).isEqualTo(RESPONSE);
        }
    }

    @Test
    void anotherTurnCannotReuseEarlierCompletedAnswer() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        try (var recording = store.begin(request(config, WORKER)).orElseThrow()) {
            AgentEventListener listener = recording.listener(event -> {});
            successfulAnswer(listener, WORKER, RESPONSE);

            // when
            listener.onEvent(event("session_continued", WORKER, THREAD, "later-turn", JSON.createObjectNode()));
            listener.onSuccessfulTurn(THREAD, "later-turn");
            AgentRunResult result = recording.finish(AgentRunResult.ok());

            // then
            assertThat(result.success())
                    .as("a new turn needs its own final answer and accepted completion")
                    .isFalse();
            assertThat(readOnlyRecord(config).has("response"))
                    .as("prior-turn response must be absent")
                    .isFalse();
        }
    }

    @Test
    void nextAttemptInvalidatesCompletedResponseBeforeWorkStarts() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        complete(store, request(config, WORKER), RESPONSE);
        var next = request(config, "next-worker");

        // when
        var recording = store.begin(next).orElseThrow();
        try (recording) {

            // then
            JsonNode current = readOnlyRecord(config);
            assertThat(current.path("status").asText()).isEqualTo("running");
            assertThat(current.path("worker_identity").asText()).isEqualTo(next.workerIdentity());
            assertThat(current.has("response"))
                    .as("accepted attempt must clear prior completed output before work")
                    .isFalse();
        }
        assertThat(readOnlyRecord(config).path("status").asText()).isEqualTo("failed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ineligibleNextAttemptRemovesPreviousResponseWithoutCreatingUnrelatedOutput(boolean enableEvidence)
            throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig previous = config();
        complete(store, request(previous, WORKER), RESPONSE);
        EffectiveConfig next = config(enableEvidence ? "branch_only" : "create", enableEvidence);

        // when
        var recording = store.begin(request(next, "next-worker"));

        // then
        assertThat(recording).isEmpty();
        assertThat(records(next)).isEmpty();
        EffectiveConfig unrelated = configAt(tempDir.resolve("other"), "create", true);
        assertThat(store.begin(request(unrelated, "unrelated-worker"))).isEmpty();
        assertThat(unrelated.workspace().root()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.trello.com/1///", "HTTPS://API.TRELLO.COM.:443/1"})
    void equivalentEndpointNextAttemptInvalidatesPreviousResponse(String endpoint) throws Exception {
        // given
        var previous = completedResponse();
        EffectiveConfig next = configAt(tempDir, "branch_only", false, endpoint);

        // when
        var recording = previous.store().begin(request(next, "next-worker")).orElseThrow();
        try (recording) {

            // then
            assertThat(records(next)).containsExactly(previous.path());
            JsonNode current = JSON.readTree(previous.path().toFile());
            assertThat(current.path("status").asText()).isEqualTo("running");
            assertThat(current.path("worker_identity").asText()).isEqualTo("next-worker");
            assertThat(current.has("response"))
                    .as("an equivalent endpoint must not leave the previous handoff marked completed")
                    .isFalse();
        }
    }

    @CsvSource({
        "https://api.trello.com/1///, create, false",
        "HTTPS://API.TRELLO.COM.:443/1, create, false",
        "https://api.trello.com/1///, branch_only, true",
        "HTTPS://API.TRELLO.COM.:443/1, branch_only, true"
    })
    @ParameterizedTest
    void equivalentEndpointIneligibleAttemptRemovesPreviousResponse(
            String endpoint, String mode, boolean enableEvidence) throws Exception {
        // given
        var previous = completedResponse();
        EffectiveConfig next = configAt(tempDir, mode, enableEvidence, endpoint);

        // when
        var recording = previous.store().begin(request(next, "next-worker"));

        // then
        assertThat(recording).isEmpty();
        assertThat(previous.path()).doesNotExist();
        assertThat(records(next)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://other.example.test/1",
                "https://api.trello.com:8443/1",
                "https://api.trello.com/2",
                "https://api.trello.com/1?tenant=other"
            })
    void distinctEndpointAttemptsLeaveOtherTargetResponseIntact(String endpoint) throws Exception {
        // given
        var previous = completedResponse();
        EffectiveConfig ineligible = configAt(tempDir, "create", false, endpoint);
        EffectiveConfig eligible = configAt(tempDir, "branch_only", false, endpoint);

        // when
        var skippedRecording = previous.store().begin(request(ineligible, "ineligible-worker"));
        var recording = previous.store().begin(request(eligible, "next-worker")).orElseThrow();
        try (recording) {

            // then
            assertThat(skippedRecording).isEmpty();
            assertThat(records(previous.config())).hasSize(2).contains(previous.path());
            JsonNode old = JSON.readTree(previous.path().toFile());
            assertThat(old.path("status").asText()).isEqualTo("completed");
            assertThat(old.path("response").asText()).isEqualTo(RESPONSE);
        }
    }

    @Test
    void noPrLabelUsesExistingEffectiveHandoffResolution() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config("create", false);
        Card card = TestCards.cardWithLabels("card-1", "TRELLO-SYNTH001", "In Progress", List.of("No PR"));
        var request = new AgentRunner.AgentRunRequest(card, null, "Task", config, WORKER, event -> {});

        // when
        complete(store, request, RESPONSE);

        // then
        assertThat(readOnlyRecord(config).path("response").asText()).isEqualTo(RESPONSE);
    }

    @Test
    void staleWorkerCannotOverwriteNewerCompletedAttempt() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        var oldReady = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldResult = executor.submit(() -> {
                try (var recording = store.begin(request(config, WORKER)).orElseThrow()) {
                    successfulAnswer(recording.listener(event -> {}), WORKER, "Old response");
                    oldReady.countDown();
                    if (!releaseOld.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "cannot await newer fixture attempt: expected release before the deadline");
                    }
                    return recording.finish(AgentRunResult.ok());
                }
            });
            try {
                assertThat(oldReady.await(WAIT_SECONDS, TimeUnit.SECONDS))
                        .as("older worker must have a completed answer before newer attempt starts")
                        .isTrue();

                // when
                complete(store, request(config, "new-worker"), "New response");
                releaseOld.countDown();

                // then
                try {
                    oldResult.get(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
                }
                JsonNode current = readOnlyRecord(config);
                assertThat(current.path("worker_identity").asText()).isEqualTo("new-worker");
                assertThat(current.path("response").asText()).isEqualTo("New response");
            } finally {
                releaseOld.countDown();
            }
        }
    }

    @Test
    void retentionBoundsFinishedRecordsWithoutEvictingActiveAttempt() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        try (var active = store.begin(request(config, WORKER)).orElseThrow()) {

            // when
            for (int index = 0; index <= FinalResponseStore.MAX_FINISHED_RECORDS; index++) {
                Card card = TestCards.card("finished-" + index, "TRELLO-FINISHED" + index, "In Progress");
                complete(
                        store,
                        new AgentRunner.AgentRunRequest(card, null, "Task", config, "worker-" + index, event -> {}),
                        RESPONSE);
            }

            // then
            List<Path> retained = records(config);
            assertThat(retained)
                    .hasSize(FinalResponseStore.MAX_FINISHED_RECORDS + 1)
                    .extracting(
                            path -> JSON.readTree(path.toFile()).path("status").asText())
                    .filteredOn("running"::equals)
                    .hasSize(1);
            assertThat(active.finish(AgentRunResult.fail("synthetic cancellation"))
                            .success())
                    .as("cancelling the active attempt must not publish a response")
                    .isFalse();
        }
    }

    @EnumSource(InvalidRecord.class)
    @ParameterizedTest
    void invalidExistingRecordStopsAttemptBeforeAnyNewOutput(InvalidRecord invalid) throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        complete(store, request(config, WORKER), RESPONSE);
        Path file = records(config).getFirst();
        JsonNode original = JSON.readTree(file.toFile());
        switch (invalid) {
            case DIRECTORY -> {
                Files.delete(file);
                Files.createDirectory(file);
            }
            case MALFORMED -> Files.writeString(file, "{");
            case STATUS -> Files.writeString(file, "{}");
            case WRONG_CARD -> {
                ((ObjectNode) original).put("card_id", "other-card");
                JSON.writeValue(file.toFile(), original);
            }
            case TIMESTAMP -> {
                ((ObjectNode) original).put("updated_at", "not-an-instant");
                JSON.writeValue(file.toFile(), original);
            }
            case INCOMPLETE_RESPONSE -> {
                ((ObjectNode) original).put("status", "running");
                JSON.writeValue(file.toFile(), original);
            }
            case OVERSIZED -> Files.write(file, new byte[FinalResponseStore.MAX_RECORD_BYTES + 1]);
        }

        // when
        Throwable failure = catchThrowable(() -> store.begin(request(config, "next-worker")));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("cannot prepare local final response")
                .hasCauseInstanceOf(IOException.class);
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void symlinkedOutputAncestorIsRejectedWithoutWritingThroughIt() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        Files.createDirectories(config.workspace().root());
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.createSymbolicLink(config.workspace().root().resolve(FinalResponseStore.DIRECTORY), outside);

        // when
        Throwable failure = catchThrowable(() -> store.begin(request(config, WORKER)));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("cannot prepare local final response");
        assertThat(outside).isEmptyDirectory();
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void symlinkedRecordIsRejectedAndExistingTargetRemainsUntouched() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        complete(store, request(config, WORKER), RESPONSE);
        Path record = records(config).getFirst();
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "unrelated content");
        Files.delete(record);
        Files.createSymbolicLink(record, outside);

        // when
        Throwable failure = catchThrowable(() -> store.begin(request(config, "next-worker")));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("cannot prepare local final response");
        assertThat(outside).content().isEqualTo("unrelated content");
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void outputUsesOwnerOnlyPosixPermissions() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();

        // when
        complete(store, request(config, WORKER), RESPONSE);

        // then
        Path record = records(config).getFirst();
        assertThat(getPosixFilePermissions(record)).hasSameElementsAs(fromString("rw-------"));
        assertThat(getPosixFilePermissions(record.getParent())).hasSameElementsAs(fromString("rwx------"));
    }

    @CsvSource({"rwxrwx---, false", "rwx---rwx, false", "rwxrwx---, true", "rwx---rwx, true"})
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @ParameterizedTest
    void rejectsWritablePosixAncestorWithoutStickyProtection(String permissions, boolean workspaceRoot)
            throws Exception {
        // given
        Path shared = Files.createDirectory(tempDir.resolve("shared"));
        EffectiveConfig config = configAt(shared, "branch_only", false);
        Path unsafe = workspaceRoot ? Files.createDirectory(config.workspace().root()) : shared;
        Files.setPosixFilePermissions(unsafe, fromString(permissions));
        var store = new FinalResponseStore();

        try {

            // when
            Throwable failure = catchThrowable(() -> store.begin(request(config, WORKER)));

            // then
            assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("cannot prepare local final response")
                    .rootCause()
                    .hasMessage(
                            "cannot prepare local final response: writable POSIX ancestors require sticky protection");
            assertThat(config.workspace().root().resolve(FinalResponseStore.DIRECTORY))
                    .doesNotExist();
        } finally {
            Files.setPosixFilePermissions(unsafe, fromString("rwx------"));
        }
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void stickySharedPosixAncestorPreservesPrivateOutput() throws Exception {
        // given
        Path shared = Files.createDirectory(tempDir.resolve("shared"));
        Files.setAttribute(shared, "unix:mode", STICKY_SHARED_DIRECTORY_MODE);
        EffectiveConfig config = configAt(shared, "branch_only", false);
        var store = new FinalResponseStore();

        // when
        complete(store, request(config, WORKER), RESPONSE);

        // then
        assertThat(readOnlyRecord(config).path("response").asText()).isEqualTo(RESPONSE);
        assertThat((Integer) Files.getAttribute(shared, "unix:mode") & STICKY_SHARED_DIRECTORY_MODE)
                .isEqualTo(STICKY_SHARED_DIRECTORY_MODE);
        Path record = records(config).getFirst();
        assertThat(getPosixFilePermissions(record)).hasSameElementsAs(fromString("rw-------"));
        assertThat(getPosixFilePermissions(record.getParent())).hasSameElementsAs(fromString("rwx------"));
    }

    @Test
    void failedAtomicWriteDoesNotClaimCompletedDelivery() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        try (var recording = store.begin(request(config, WORKER)).orElseThrow()) {
            successfulAnswer(recording.listener(event -> {}), WORKER, RESPONSE);
            Path record = records(config).getFirst();
            Files.delete(record);
            Files.createDirectory(record);

            // when
            Throwable failure = catchThrowable(() -> recording.finish(AgentRunResult.ok()));

            // then
            assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("cannot save local final response");
            assertThat(record).isEmptyDirectory();
        }
    }

    @Test
    void failedSaveLogsControlledAttemptDiagnosticAfterAcceptedCompletion() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        String privateResponse = "synthetic-sensitive-response";
        complete(store, request(config, WORKER), "old-sensitive-response");
        try (var logs = new AttemptLogs();
                var recording = store.begin(request(config, WORKER)).orElseThrow()) {
            successfulAnswer(recording.listener(event -> {}), WORKER, privateResponse);
            Path path = records(config).getFirst();
            byte[] running = Files.readAllBytes(path);
            Files.delete(path);
            Files.createDirectory(path);

            // when
            try {
                assertThatThrownBy(() -> recording.finish(AgentRunResult.ok()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("cannot save local final response");
            } finally {
                Files.delete(path);
                Files.write(path, running);
            }
            recording.close();

            // then
            var record = readOnlyRecord(config);
            assertThat(record.path("status").asText()).isEqualTo("failed");
            assertThat(record.has("response"))
                    .as("a failed save after accepted completion must not expose the old or new answer")
                    .isFalse();
            assertThat(logs.records).singleElement().satisfies(log -> {
                assertThat(log.getMessage())
                        .startsWith("cannot save local final response:")
                        .contains("attempt=" + Sha3.sha3_256(WORKER))
                        .doesNotContain(privateResponse, "old-sensitive-response", WORKER, THREAD, TURN);
                assertThat(log.getThrown()).isNull();
            });
        }
    }

    @Test
    void failedPreparationLogsControlledDiagnosticWithoutParserContent() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        complete(store, request(config, WORKER), RESPONSE);
        Files.writeString(records(config).getFirst(), "synthetic-sensitive-malformed-json");
        try (var logs = new AttemptLogs()) {

            // when
            assertThatThrownBy(() -> store.begin(request(config, WORKER)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("cannot prepare local final response");

            // then
            assertThat(logs.records).singleElement().satisfies(log -> {
                assertThat(log.getMessage())
                        .startsWith("cannot prepare local final response:")
                        .contains("attempt=" + Sha3.sha3_256(WORKER))
                        .doesNotContain("synthetic-sensitive-malformed-json", WORKER);
                assertThat(log.getThrown()).isNull();
            });
        }
    }

    @Test
    void missingCompletedAnswerLogsControlledDiagnostic() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        try (var logs = new AttemptLogs();
                var recording = store.begin(request(config, WORKER)).orElseThrow()) {

            // when
            AgentRunResult result = recording.finish(AgentRunResult.ok());

            // then
            assertThat(result.success())
                    .as("successful protocol completion without a final answer must fail local delivery")
                    .isFalse();
            assertThat(logs.records).singleElement().satisfies(log -> {
                assertThat(log.getMessage())
                        .startsWith("cannot save local final response:")
                        .contains("expected an accepted complete final answer", "attempt=" + Sha3.sha3_256(WORKER))
                        .doesNotContain(WORKER, THREAD, TURN);
                assertThat(log.getThrown()).isNull();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void listenerPreservesTrustedMoveAndSuccessfulTurnCallbacks(boolean expectedAllowed) throws Exception {
        // given
        var store = new FinalResponseStore();
        var delegate = mock(AgentEventListener.class);
        var move = new CardHandoffMove("operation", WORKER, THREAD, TURN, "card-1", "board-1", "review-list");
        when(delegate.beforeHandoffMove(move)).thenReturn(expectedAllowed);
        try (var recording = store.begin(request(config(), WORKER)).orElseThrow()) {
            AgentEventListener listener = recording.listener(delegate);

            // when
            boolean allowed = listener.beforeHandoffMove(move);
            listener.afterHandoffMove(move, expectedAllowed);
            listener.onSuccessfulTurn(THREAD, TURN);

            // then
            assertThat(allowed)
                    .as("owner move decision must remain unchanged through the output listener")
                    .isEqualTo(expectedAllowed);
            verify(delegate).beforeHandoffMove(move);
            verify(delegate).afterHandoffMove(move, expectedAllowed);
            verify(delegate).onSuccessfulTurn(THREAD, TURN);
        }
    }

    @Test
    void cardIdentityCannotEscapePrivateOutputNamespace() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        Card card = TestCards.card("../../outside", "../../identifier", "In Progress");
        var request = new AgentRunner.AgentRunRequest(card, null, "Task", config, WORKER, event -> {});

        // when
        complete(store, request, RESPONSE);

        // then
        Path record = records(config).getFirst();
        assertThat(record.getParent()).hasParentRaw(config.workspace().root().resolve(FinalResponseStore.DIRECTORY));
        assertThat(record.getFileName().toString()).matches("[a-f0-9]{64}\\.json");
        assertThat(JSON.readTree(record.toFile()).path("card_id").asText()).isEqualTo(card.id());
        assertThat(tempDir.resolve("outside")).doesNotExist();
    }

    @Test
    void taskIdentifierMatchingOutputDirectoryCannotDeleteStoredResponse() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        Card card = TestCards.card("card-1", FinalResponseStore.DIRECTORY, "In Progress");
        complete(store, new AgentRunner.AgentRunRequest(card, null, "Task", config, WORKER, event -> {}), RESPONSE);
        var workspaces = new WorkspaceManager(new HookRunner());

        // when
        var workspace = workspaces.createForCard(card.identifier(), config);
        workspaces.removeForIdentifierIfPresent(card.identifier(), config);

        // then
        assertThat(workspace.path()).doesNotExist();
        assertThat(readOnlyRecord(config).path("response").asText()).isEqualTo(RESPONSE);
    }

    @Test
    void responseAtByteLimitIsPreservedWithoutTruncation() throws Exception {
        // given
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        String exactLimit = "a".repeat(FinalResponseStore.MAX_RESPONSE_BYTES);

        // when
        complete(store, request(config, WORKER), exactLimit);

        // then
        assertThat(readOnlyRecord(config).path("response").asText()).isEqualTo(exactLimit);
    }

    // Observe this test thread without changing logger levels, filters or other handlers.
    private static final class AttemptLogs extends Handler implements AutoCloseable {
        private final long ownerThreadId = Thread.currentThread().threadId();
        private final Logger logger = Logger.getLogger(FinalResponseStore.class.getName());
        private final List<LogRecord> records = new ArrayList<>();

        AttemptLogs() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (Thread.currentThread().threadId() == ownerThreadId) {
                records.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }

    private CompletedResponse completedResponse() throws IOException {
        var store = new FinalResponseStore();
        EffectiveConfig config = config();
        complete(store, request(config, WORKER), RESPONSE);
        return new CompletedResponse(store, config, records(config).getFirst());
    }

    private record CompletedResponse(FinalResponseStore store, EffectiveConfig config, Path path) {}

    private AgentRunner.AgentRunRequest request(EffectiveConfig config, String worker) {
        return new AgentRunner.AgentRunRequest(
                TestCards.card("card-1", "TRELLO-SYNTH001", "In Progress"), null, "Task", config, worker, event -> {});
    }

    private static void complete(FinalResponseStore store, AgentRunner.AgentRunRequest request, String response) {
        try (var recording = store.begin(request).orElseThrow()) {
            successfulAnswer(recording.listener(event -> {}), request.workerIdentity(), response);
            assertThat(recording.finish(AgentRunResult.ok())).isEqualTo(AgentRunResult.ok());
        }
    }

    private static void successfulAnswer(AgentEventListener listener, String worker, String response) {
        finalAnswer(listener, worker, THREAD, TURN, response);
        completedTurn(listener, worker, THREAD, TURN, "completed");
        listener.onSuccessfulTurn(THREAD, TURN);
    }

    private static void finalAnswer(
            AgentEventListener listener, String worker, String thread, String turn, String text) {
        var payload = JSON.createObjectNode();
        payload.putObject("item")
                .put("type", "agentMessage")
                .put("phase", "final_answer")
                .put("text", text);
        listener.onEvent(event("item/completed", worker, thread, turn, payload));
    }

    private static void completedTurn(
            AgentEventListener listener, String worker, String thread, String turn, String status) {
        var payload = JSON.createObjectNode();
        payload.putObject("turn").put("id", turn).put("status", status);
        listener.onEvent(event("turn/completed", worker, thread, turn, payload));
    }

    private static AgentEvent event(String name, String worker, String thread, String turn, JsonNode payload) {
        return new AgentEvent(
                name, Instant.parse("2026-01-01T00:00:00Z"), worker, 1L, thread, turn, null, Map.of(), payload);
    }

    private static List<Path> records(EffectiveConfig config) throws IOException {
        List<Path> records = new ArrayList<>();
        Path outputs = config.workspace().root().resolve(FinalResponseStore.DIRECTORY);
        if (!Files.isDirectory(outputs)) {
            return List.of();
        }
        try (var namespaces = Files.newDirectoryStream(outputs)) {
            for (Path namespace : namespaces) {
                try (var files = Files.newDirectoryStream(namespace, "*.json")) {
                    for (Path file : files) {
                        records.add(file);
                    }
                }
            }
        }
        return List.copyOf(records);
    }

    private static JsonNode readOnlyRecord(EffectiveConfig config) throws IOException {
        List<Path> paths = records(config);
        assertThat(paths).hasSize(1);
        return JSON.readTree(paths.getFirst().toFile());
    }

    enum IncompleteFinal {
        BLANK,
        EMPTY,
        OVERSIZED,
        MISSING_FINAL,
        PARTIAL_FINAL,
        WRONG_THREAD,
        WRONG_TURN,
        REJECTED_FINAL,
        REJECTED_COMPLETION,
        FAILED_STATUS,
        NO_SUCCESS_CALLBACK,
        FAILED_RUN
    }

    enum InvalidRecord {
        DIRECTORY,
        MALFORMED,
        STATUS,
        WRONG_CARD,
        TIMESTAMP,
        INCOMPLETE_RESPONSE,
        OVERSIZED
    }

    private EffectiveConfig config() {
        return config("branch_only", false);
    }

    private EffectiveConfig config(String mode, boolean allowComments) {
        return configAt(tempDir, mode, allowComments);
    }

    private static EffectiveConfig configAt(Path directory, String mode, boolean allowComments) {
        return configAt(directory, mode, allowComments, "https://api.trello.com/1");
    }

    private static EffectiveConfig configAt(Path directory, String mode, boolean allowComments, String endpoint) {
        return new ConfigResolver(ignored -> Optional.empty())
                .resolve(new WorkflowDefinition(
                        directory.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                        Map.of(
                                                "kind",
                                                "trello",
                                                "endpoint",
                                                endpoint,
                                                "api_key",
                                                "key",
                                                "api_token",
                                                "token",
                                                "board_id",
                                                "board-1"),
                                "workspace",
                                        Map.of(
                                                "root",
                                                directory.resolve("workspaces").toString()),
                                "github", Map.of("pull_request_mode", mode),
                                "trello_tools",
                                        Map.of("enabled", true, "allow_writes", true, "allow_comments", allowComments)),
                        ""));
    }
}
