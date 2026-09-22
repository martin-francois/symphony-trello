package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.telemetry.ErasureExperiment.Outcome;
import ch.fmartin.symphony.trello.telemetry.ExperimentCheckpoint.Phase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Deterministic guards of the live erasure harness, run against an in-memory PostHog. They prove
/// the harness refuses the wrong project, foreign identities, and missing scopes, never mistakes
/// a queued or absent status for completion, resumes without reseeding, and keeps secrets out of
/// everything it writes. Nothing here reaches the network.
final class ErasureExperimentSafetyTest {
    private static final Instant START = Instant.parse("2026-09-22T09:00:00Z");
    private static final int REQUEST_BUDGET = 400;
    private static final Duration WAIT_BUDGET = Duration.ofMinutes(15);

    @TempDir
    Path tempDir;

    private FakePostHog posthog;
    private Path keyFile;
    private TelemetryFixture.MutableClock clock;
    private ByteArrayOutputStream console;

    @BeforeEach
    void start() throws IOException {
        posthog = new FakePostHog();
        keyFile = tempDir.resolve("key");
        Files.writeString(keyFile, FakePostHog.KEY + "\n");
        clock = new TelemetryFixture.MutableClock(START);
        posthog.clock = clock;
        console = new ByteArrayOutputStream();
    }

    @AfterEach
    void stop() {
        posthog.close();
    }

    @Test
    void aForbiddenProjectIsRefusedBeforeAnyRequest() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of(FakePostHog.PROJECT_ID));

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.message()).contains("forbidden");
        assertThat(posthog.requests()).isEmpty();
    }

    @Test
    void aProjectNameMismatchStopsBeforeAnyCapture() throws IOException {
        // given
        posthog.projectName = "Symphony for Trello";
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.message()).contains("identity mismatch");
        assertThat(posthog.count("capture")).isZero();
    }

    @Test
    void aProfileMappedToAForeignIdIsNeverDeleted() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());
        ErasureExperiment.prepare(binding, clock);
        ExperimentCheckpoint checkpoint =
                ExperimentCheckpoint.load(binding.experimentDir().resolve(ExperimentCheckpoint.FILE_NAME));
        posthog.mergedDistinctId =
                checkpoint.subject(ErasureExperiment.SUBJECT_A).distinctId();

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.message()).contains("refusing");
        assertThat(posthog.count("POST /api/projects/" + FakePostHog.PROJECT_ID + "/persons/bulk_delete/"))
                .isZero();
    }

    @Test
    void aMissingScopeFailsWithoutRetriesOrDeletion() throws IOException {
        // given
        posthog.personsForbidden = true;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.message()).contains("HTTP 403").contains("person:read");
        assertThat(posthog.count("POST /api/projects/" + FakePostHog.PROJECT_ID + "/persons/bulk_delete/"))
                .isZero();
        assertThat(posthog.count("GET /api/projects/" + FakePostHog.PROJECT_ID + "/persons/"))
                .isOne();
    }

    @Test
    void anEmptyDeletionStatusIsPendingNotComplete() throws IOException {
        // given
        posthog.emptyDeletionStatus = true;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.PENDING);
        assertThat(outcome.phase()).isEqualTo(Phase.ERASURE_REQUESTED);
        assertThat(outcome.message()).contains("event deletion not complete");
    }

    @Test
    void aDeletionErrorFailsTheRequestPhaseSoAResumeRetriesIt() throws IOException {
        // given
        posthog.deletionErrors = true;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.phase()).isEqualTo(Phase.DISABLED);
        assertThat(outcome.message()).contains("errors=1");
    }

    @Test
    void aCachedQueryAnswerIsRejected() throws IOException {
        // given
        posthog.staleQueries = true;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.message()).contains("cache");
    }

    @Test
    void anInterruptedRunResumesFromItsCheckpointWithoutReseeding() throws IOException {
        // given
        posthog.completeDeletionsImmediately = false;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());
        Outcome first = experiment(binding).run();
        long capturesBefore = posthog.count("capture");
        ExperimentCheckpoint pending =
                ExperimentCheckpoint.load(binding.experimentDir().resolve(ExperimentCheckpoint.FILE_NAME));
        posthog.completeDeletions();

        // when
        Outcome second = experiment(binding).run();
        ExperimentCheckpoint done =
                ExperimentCheckpoint.load(binding.experimentDir().resolve(ExperimentCheckpoint.FILE_NAME));

        // then
        assertThat(first.kind()).isEqualTo(Outcome.Kind.PENDING);
        assertThat(first.phase()).isEqualTo(Phase.ERASURE_REQUESTED);
        assertThat(capturesBefore).isEqualTo(5);
        assertThat(second.kind()).as("second run: %s", second.message()).isNotEqualTo(Outcome.Kind.FAILED);
        assertThat(done.subject(ErasureExperiment.SUBJECT_A).oldEventUuids())
                .isEqualTo(pending.subject(ErasureExperiment.SUBJECT_A).oldEventUuids());
        assertThat(done.subject(ErasureExperiment.SUBJECT_A).distinctId())
                .isEqualTo(pending.subject(ErasureExperiment.SUBJECT_A).distinctId());
        assertThat(done.phase().compareTo(Phase.REUSE_A_SENT))
                .as("resumed past the erasure")
                .isNotNegative();
        assertThat(posthog.count("capture"))
                .as("only the reuse heartbeats were added")
                .isBetween(capturesBefore + 2, capturesBefore + 4);
        assertThat(posthog.count("POST /api/projects/" + FakePostHog.PROJECT_ID + "/persons/bulk_delete/"))
                .isBetween(1L, 2L);
    }

    @Test
    void aCompleteRunPassesEveryRowAndKeepsSecretsOutOfItsOutput() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();
        String evidence = Files.readString(binding.experimentDir().resolve(EvidenceLog.FILE_NAME));
        String summary = Files.readString(binding.experimentDir().resolve("summary.md"));
        String checkpoint = Files.readString(binding.experimentDir().resolve(ExperimentCheckpoint.FILE_NAME));
        String printed = console.toString(StandardCharsets.UTF_8);

        // then
        assertThat(outcome.kind()).as(outcome.message()).isEqualTo(Outcome.Kind.COMPLETE);
        assertThat(summary)
                .contains("| 8. Reused ID erasable, fixtures cleaned up | PASS |")
                .doesNotContain("FAIL");
        for (String text : List.of(evidence, summary, checkpoint, printed, outcome.message())) {
            assertThat(text).doesNotContain(FakePostHog.KEY).doesNotContain(FakePostHog.TOKEN);
        }
        assertThat(evidence).contains("\"phase\":\"send\"").contains("\"http_status\":\"202\"");
        EvidenceLog redactor = new EvidenceLog(
                tempDir.resolve("unused"), clock, List.of(() -> FakePostHog.KEY, () -> FakePostHog.TOKEN));
        assertThat(redactor.redact("key " + FakePostHog.KEY + " token " + FakePostHog.TOKEN))
                .isEqualTo("key " + EvidenceLog.REDACTED + " token " + EvidenceLog.REDACTED);
    }

    @Test
    void theBindingNamesEveryMissingVariableAndRefusesAProjectToken() {
        // given
        Map<String, String> environment = Map.of(ExperimentBinding.ENABLE_VARIABLE, "1");

        // when
        Throwable thrown = catchThrowable(() -> ExperimentBinding.fromEnvironment(environment));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExperimentBinding.KEY_FILE_VARIABLE)
                .hasMessageContaining(ExperimentBinding.PROJECT_ID_VARIABLE)
                .hasMessageContaining(ExperimentBinding.FORBIDDEN_PROJECTS_VARIABLE);
        assertThat(ExperimentBinding.enabled(Map.of()))
                .as("normal builds never start the experiment")
                .isFalse();
    }

    @Test
    void aBrokenReuseReachesTheAfterEventResetWhileTheOtherSubjectsOutcomeSurvives() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());
        ErasureExperiment.prepare(binding, clock);
        ExperimentCheckpoint prepared = checkpoint(binding);
        String subjectB = prepared.subject(ErasureExperiment.SUBJECT_B).distinctId();
        posthog.brokenReuseDistinctIds.add(subjectB);

        // when
        Outcome outcome = experiment(binding).run();
        ExperimentCheckpoint done = checkpoint(binding);
        String summary = Files.readString(binding.experimentDir().resolve("summary.md"));

        // then
        assertThat(outcome.kind()).as(outcome.message()).isEqualTo(Outcome.Kind.COMPLETE);
        assertThat(done.subject(ErasureExperiment.SUBJECT_A).facts())
                .containsEntry("reuse.person_api", "resolved")
                .containsEntry("repair", "not needed");
        assertThat(done.subject(ErasureExperiment.SUBJECT_B).facts())
                .containsEntry("reuse.person_api", "resolved")
                .containsEntry("reuse.analytics_person", "resolved")
                .containsEntry("repair", "reset after the new event resolved the profile")
                .containsKey("repair.reset_requested_at");
        assertThat(posthog.resetRequests())
                .as("one reset before reuse (phase RESET_B_DONE) and one after the new event")
                .containsExactly(subjectB, subjectB);
        assertThat(summary)
                .contains("| 4. A: reuse without reset | PASS")
                .contains("| 8. Reused ID erasable, fixtures cleaned up | PASS |");
    }

    @Test
    void anApiProfileWithoutAnalyticsLinkageIsRepairedAfterTheEvent() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());
        ErasureExperiment.prepare(binding, clock);
        String subjectA =
                checkpoint(binding).subject(ErasureExperiment.SUBJECT_A).distinctId();
        posthog.analyticsOnlyBrokenDistinctIds.add(subjectA);

        // when
        Outcome outcome = experiment(binding).run();
        ExperimentCheckpoint done = checkpoint(binding);

        // then
        assertThat(outcome.kind()).as(outcome.message()).isEqualTo(Outcome.Kind.COMPLETE);
        assertThat(done.subject(ErasureExperiment.SUBJECT_A).facts())
                .containsEntry("reuse.analytics_person", "resolved")
                .containsEntry("repair", "reset after the new event resolved the profile");
        assertThat(posthog.resetRequests()).as("A had no reset before reuse").containsOnlyOnce(subjectA);
    }

    @Test
    void aMappingThatConvergesOnItsOwnIsNeverReset() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());
        ErasureExperiment.prepare(binding, clock);
        String subjectA =
                checkpoint(binding).subject(ErasureExperiment.SUBJECT_A).distinctId();
        posthog.brokenReuseDistinctIds.add(subjectA);
        posthog.resolveBrokenAfterPersonsReads = 2;

        // when
        Outcome outcome = experiment(binding).run();
        ExperimentCheckpoint done = checkpoint(binding);

        // then
        assertThat(outcome.kind()).as(outcome.message()).isEqualTo(Outcome.Kind.COMPLETE);
        assertThat(done.subject(ErasureExperiment.SUBJECT_A).facts())
                .containsEntry("reuse.person_api", "resolved")
                .containsEntry("repair", "not needed");
        assertThat(posthog.resetRequests()).doesNotContain(subjectA);
    }

    @Test
    void aRepairThatNeedsAnotherDayParksTheRunAndResumesWithOneMoreHeartbeat() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());
        ErasureExperiment.prepare(binding, clock);
        String subjectB =
                checkpoint(binding).subject(ErasureExperiment.SUBJECT_B).distinctId();
        posthog.brokenReuseDistinctIds.add(subjectB);
        posthog.resetHealsOnNextEvent = true;

        // when
        Outcome parked = experiment(binding).run();
        ExperimentCheckpoint parkedCheckpoint = checkpoint(binding);
        long capturesWhenParked = posthog.count("capture");
        long bulkDeletesWhenParked =
                posthog.count("POST /api/projects/" + FakePostHog.PROJECT_ID + "/persons/bulk_delete/");
        Outcome sameDay = experiment(binding).run();
        long capturesSameDay = posthog.count("capture");
        clock.advance(Duration.ofDays(1));
        Outcome nextDay = experiment(binding).run();
        ExperimentCheckpoint done = checkpoint(binding);

        // then
        assertThat(parked.kind()).isEqualTo(Outcome.Kind.PENDING);
        assertThat(parked.phase()).isEqualTo(Phase.REPAIR_AWAITING_HEARTBEAT);
        assertThat(parkedCheckpoint.subject(ErasureExperiment.SUBJECT_B).facts())
                .containsEntry("repair.awaiting_heartbeat", "true")
                .containsEntry("reuse.person_api", "missing");
        assertThat(bulkDeletesWhenParked)
                .as("cleanup waits for the repair outcome")
                .isEqualTo(1);
        assertThat(sameDay.kind()).isEqualTo(Outcome.Kind.PENDING);
        assertThat(sameDay.phase()).isEqualTo(Phase.REPAIR_AWAITING_HEARTBEAT);
        assertThat(capturesSameDay).as("no heartbeat on the same day").isEqualTo(capturesWhenParked);
        assertThat(posthog.count("capture"))
                .as("one more heartbeat on the next day")
                .isEqualTo(capturesWhenParked + 1);
        assertThat(nextDay.kind()).as(nextDay.message()).isEqualTo(Outcome.Kind.COMPLETE);
        assertThat(done.subject(ErasureExperiment.SUBJECT_B).newEventUuids()).hasSize(2);
        assertThat(done.subject(ErasureExperiment.SUBJECT_B).facts())
                .containsEntry("reuse.person_api", "resolved")
                .containsEntry("repair.awaiting_heartbeat", "");
        assertThat(posthog.resetRequests())
                .as("the parked resume observes, it does not reset again")
                .containsExactly(subjectB, subjectB);
    }

    @Test
    void resumingAfterEveryFewRequestsReachesCompletionWithoutDuplicateCapturesOrDeletions() throws IOException {
        // given
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());
        ErasureExperiment.prepare(binding, clock);
        String subjectB =
                checkpoint(binding).subject(ErasureExperiment.SUBJECT_B).distinctId();
        List<Phase> phases = new java.util.ArrayList<>();

        // when
        Outcome outcome = experiment(binding, 20).run();
        for (int attempt = 0; attempt < 40 && outcome.kind() != Outcome.Kind.COMPLETE; attempt++) {
            phases.add(outcome.phase());
            outcome = experiment(binding, 20).run();
        }

        // then
        assertThat(outcome.kind()).as(outcome.message()).isEqualTo(Outcome.Kind.COMPLETE);
        assertThat(phases).as("the run was interrupted at several phases").hasSizeGreaterThanOrEqualTo(3);
        assertThat(posthog.count("capture"))
                .as("five baseline and two reuse heartbeats")
                .isEqualTo(7);
        assertThat(posthog.count("POST /api/projects/" + FakePostHog.PROJECT_ID + "/persons/bulk_delete/"))
                .isEqualTo(2);
        assertThat(posthog.resetRequests()).as("only the reset before reuse").containsExactly(subjectB);
    }

    @Test
    void aCompletedRowFromAnEarlierOperationDoesNotCertifyAPendingDeletion() throws IOException {
        // given
        posthog.completeDeletionsImmediately = false;
        posthog.staleCompletedRows = true;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.PENDING);
        assertThat(outcome.phase()).isEqualTo(Phase.ERASURE_REQUESTED);
    }

    @Test
    void aStatusRowForAnotherPersonIsNotThisDeletion() throws IOException {
        // given
        posthog.wrongPersonRows = true;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.PENDING);
        assertThat(outcome.phase()).isEqualTo(Phase.ERASURE_REQUESTED);
    }

    @EnumSource(
            value = FakePostHog.VerifiedAtMode.class,
            names = {"ABSENT", "NULL", "BLANK", "GARBAGE"})
    @ParameterizedTest
    void aCompletionWithoutAValidTimestampStaysPending(FakePostHog.VerifiedAtMode mode) throws IOException {
        // given
        posthog.verifiedAtMode = mode;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.PENDING);
        assertThat(outcome.phase()).isEqualTo(Phase.ERASURE_REQUESTED);
    }

    @Test
    void theCompletedRowOfThisRequestSuppliesTheRecordedTimestamp() throws IOException {
        // given
        posthog.staleCompletedRows = true;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();
        ExperimentCheckpoint done = checkpoint(binding);

        // then
        assertThat(outcome.kind()).as(outcome.message()).isEqualTo(Outcome.Kind.COMPLETE);
        assertThat(done.subject(ErasureExperiment.SUBJECT_A).erasure())
                .as("the verification time comes from the row created for this request, not the stale one")
                .extracting(ExperimentCheckpoint.DeletionRecord::verifiedAt)
                .isEqualTo(START.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"results\": null}", "{\"results\": {\"count\": 0}}", "[]"})
    void malformedDeletionStatusEvidenceIsUnavailableNotEmpty(String body) throws IOException {
        // given
        posthog.deletionStatusBodyOverride = body;
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.message()).contains("evidence unavailable");
        assertThat(outcome.phase()).isEqualTo(Phase.ERASURE_REQUESTED);
    }

    @Test
    void aQueryThatOnlyReportsItsStatusIsUnavailableNotEmpty() throws IOException {
        // given
        posthog.queryBodyOverride = "{\"query_status\": {\"complete\": false, \"id\": \"abc\"}}";
        ExperimentBinding binding = binding(FakePostHog.PROJECT_ID, Set.of());

        // when
        Outcome outcome = experiment(binding).run();

        // then
        assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
        assertThat(outcome.message()).contains("evidence unavailable").contains("query still running");
        assertThat(posthog.count("POST /api/projects/" + FakePostHog.PROJECT_ID + "/persons/bulk_delete/"))
                .isZero();
    }

    private ExperimentCheckpoint checkpoint(ExperimentBinding binding) {
        return ExperimentCheckpoint.load(binding.experimentDir().resolve(ExperimentCheckpoint.FILE_NAME));
    }

    private ExperimentBinding binding(long projectId, Set<Long> forbidden) throws IOException {
        Path experimentDir = Files.createDirectories(tempDir.resolve("run"));
        return new ExperimentBinding(
                posthog.host(),
                posthog.captureEndpoint(),
                projectId,
                FakePostHog.PROJECT_NAME,
                forbidden,
                keyFile,
                experimentDir);
    }

    private ErasureExperiment experiment(ExperimentBinding binding) throws IOException {
        return experiment(binding, REQUEST_BUDGET);
    }

    private ErasureExperiment experiment(ExperimentBinding binding, int requestBudget) throws IOException {
        ErasureExperiment.prepare(binding, clock);
        ExperimentBudget budget = new ExperimentBudget(requestBudget, WAIT_BUDGET, clock::advance);
        PostHogManagementClient client = new PostHogManagementClient(
                binding.managementHost(), binding.projectId(), binding.keySupplier(), budget);
        return new ErasureExperiment(
                binding, client, clock, budget, new PrintStream(console, true, StandardCharsets.UTF_8));
    }
}
