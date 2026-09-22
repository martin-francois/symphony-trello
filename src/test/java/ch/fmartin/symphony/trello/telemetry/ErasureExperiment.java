package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.EvidenceLog.Status;
import ch.fmartin.symphony.trello.telemetry.ExperimentCheckpoint.DeletionRecord;
import ch.fmartin.symphony.trello.telemetry.ExperimentCheckpoint.Phase;
import ch.fmartin.symphony.trello.telemetry.ExperimentCheckpoint.Subject;
import ch.fmartin.symphony.trello.telemetry.HeartbeatReporter.CheckResult;
import ch.fmartin.symphony.trello.telemetry.PostHogManagementClient.Response;
import ch.fmartin.symphony.trello.telemetry.TelemetryService.DisableRequest;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/// The live erasure and same-ID reuse experiment against the dedicated PostHog test project. It
/// drives the real application components (state store, service, reporter, capture client) for
/// three synthetic installations, requests deletion through the management API, and records what
/// the provider does. Every phase transition is checkpointed so an invocation that runs out of
/// budget, or waits for PostHog's batched event deletion, resumes where it stopped.
final class ErasureExperiment {
    static final String SUBJECT_A = "A";
    static final String SUBJECT_B = "B";
    static final String CONTROL = "C";
    static final String OLD_VERSION = "1.2.0";
    static final String NEW_VERSION = "1.3.0";
    static final long IMPORTS = 4;
    static final long CREATIONS = 2;
    static final int BOARDS = 3;
    static final int OLD_EVENTS_PER_SUBJECT = 2;
    static final Duration POLL_INTERVAL = Duration.ofSeconds(30);
    static final Duration INGESTION_WAIT = Duration.ofMinutes(5);
    static final Duration DELETION_WAIT = Duration.ofMinutes(3);
    static final Duration REPAIR_WAIT = Duration.ofMinutes(3);
    private static final Duration WINDOW_BEFORE_START = Duration.ofDays(3);
    private static final Duration TWO_DAYS = Duration.ofDays(2);
    private static final Duration ONE_DAY = Duration.ofDays(1);
    private static final Duration PAST_GRACE = TelemetryNotice.FIRST_REPORT_GRACE.plusMinutes(1);
    private static final DateTimeFormatter HOGQL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String REQUEST_LINE_PREFIX = "telemetry request POST ";
    private static final String FACT_PERSON_API = "reuse.person_api";
    private static final String FACT_ANALYTICS = "reuse.analytics_person";
    private static final String FACT_PERSON_UUID = "reuse.person_uuid";
    private static final String FACT_RESET_BEFORE = "reset.before_reuse";
    private static final String FACT_REPAIR = "repair";
    private static final String RESOLVED = "resolved";
    private static final String MISSING = "missing";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExperimentBinding binding;
    private final PostHogManagementClient client;
    private final Clock clock;
    private final ExperimentBudget budget;
    private final PrintStream out;
    private final Path checkpointFile;
    private final CachedToken token;
    private @Nullable EvidenceLog evidence;
    private ExperimentCheckpoint checkpoint;

    ErasureExperiment(
            ExperimentBinding binding,
            PostHogManagementClient client,
            Clock clock,
            ExperimentBudget budget,
            PrintStream out) {
        this.binding = binding;
        this.client = client;
        this.clock = clock;
        this.budget = budget;
        this.out = out;
        this.checkpointFile = binding.experimentDir().resolve(ExperimentCheckpoint.FILE_NAME);
        this.token = new CachedToken(binding, client);
        this.checkpoint = ExperimentCheckpoint.load(checkpointFile);
    }

    /// Creates the run directory and checkpoint if this is the first invocation.
    static void prepare(ExperimentBinding binding, Clock clock) throws IOException {
        Files.createDirectories(binding.experimentDir());
        Path file = binding.experimentDir().resolve(ExperimentCheckpoint.FILE_NAME);
        if (Files.exists(file)) {
            return;
        }
        String now = clock.instant().toString();
        Map<String, Subject> subjects = new LinkedHashMap<>();
        for (String name : List.of(SUBJECT_A, SUBJECT_B, CONTROL)) {
            subjects.put(
                    name,
                    Subject.create(
                            name,
                            UUID.randomUUID().toString(),
                            registrationDate(clock).toString()));
        }
        new ExperimentCheckpoint(
                        "erasure-" + UUID.randomUUID().toString().substring(0, 8),
                        binding.projectId(),
                        Phase.CREATED,
                        now,
                        now,
                        subjects,
                        List.of())
                .save(file);
    }

    private static LocalDate registrationDate(Clock clock) {
        return LocalDate.ofInstant(clock.instant().minus(WINDOW_BEFORE_START), ZoneOffset.UTC);
    }

    /// Advances as far as the provider and the budget allow. Never throws for a pending provider
    /// step; the outcome says what to do next.
    Outcome run() {
        if (checkpoint.projectId() != binding.projectId()) {
            return Outcome.failed(checkpoint.phase(), "checkpoint belongs to project " + checkpoint.projectId());
        }
        evidence = new EvidenceLog(
                binding.experimentDir().resolve(EvidenceLog.FILE_NAME), clock, List.of(binding.keySupplier(), token));
        try {
            token.verify();
            while (checkpoint.phase() != Phase.COMPLETE) {
                step();
            }
            return new Outcome(Phase.COMPLETE, Outcome.Kind.COMPLETE, "experiment complete, fixtures cleaned up");
        } catch (PendingException exception) {
            note("pending: " + exception.getMessage());
            return new Outcome(checkpoint.phase(), Outcome.Kind.PENDING, exception.getMessage());
        } catch (ExperimentBudget.BudgetExhaustedException exception) {
            note("budget: " + exception.getMessage());
            return new Outcome(checkpoint.phase(), Outcome.Kind.PENDING, exception.getMessage());
        } catch (ExperimentFailure | PostHogManagementClient.ManagementException | IllegalStateException exception) {
            String message = log().redact(String.valueOf(exception.getMessage()));
            note("failed: " + message);
            return Outcome.failed(checkpoint.phase(), message);
        } finally {
            writeSummary();
        }
    }

    private void step() {
        switch (checkpoint.phase()) {
            case CREATED -> sendBaseline();
            case BASELINE_SENT -> verifyBaseline();
            case BASELINE_VERIFIED -> disableSubjects();
            case DISABLED -> requestErasure();
            case ERASURE_REQUESTED -> verifyErasure();
            case ERASURE_VERIFIED -> reuse(SUBJECT_A, Phase.REUSE_A_SENT);
            case REUSE_A_SENT -> resetBeforeReuse();
            case RESET_B_DONE -> reuse(SUBJECT_B, Phase.REUSE_B_SENT);
            case REUSE_B_SENT -> observeReuse();
            case REUSE_OBSERVED -> repairIfNeeded();
            case REPAIR_DONE -> cleanup();
            case CLEANUP_REQUESTED -> verifyCleanup();
            case COMPLETE -> throw new IllegalStateException("already complete");
        }
    }

    // Phase 1: baseline.

    private void sendBaseline() {
        Instant start = Instant.parse(checkpoint.startedAt());
        for (String name : List.of(SUBJECT_A, SUBJECT_B)) {
            Subject subject = checkpoint.subject(name);
            if (subject.oldEventUuids().isEmpty()) {
                subject = subject.withOldEvent(sendHeartbeat(subject, OLD_VERSION, start.minus(TWO_DAYS)));
                save(checkpoint.withSubject(subject, now()));
            }
            if (subject.oldEventUuids().size() < OLD_EVENTS_PER_SUBJECT) {
                subject = subject.withOldEvent(sendHeartbeat(subject, OLD_VERSION, start.minus(ONE_DAY)));
                save(checkpoint.withSubject(subject, now()));
            }
        }
        Subject control = checkpoint.subject(CONTROL);
        if (control.oldEventUuids().isEmpty()) {
            save(checkpoint.withSubject(
                    control.withOldEvent(sendHeartbeat(control, OLD_VERSION, start.minus(ONE_DAY))), now()));
        }
        save(checkpoint.withPhase(Phase.BASELINE_SENT, now()));
    }

    private void verifyBaseline() {
        Observation observation = awaitObservation("baseline ingestion", INGESTION_WAIT, current -> {
            for (String name : List.of(SUBJECT_A, SUBJECT_B)) {
                Subject subject = checkpoint.subject(name);
                if (current.eventsFor(subject).size() != OLD_EVENTS_PER_SUBJECT
                        || !current.uuidsFor(subject).containsAll(subject.oldEventUuids())
                        || current.personsFor(subject).size() != 1) {
                    return false;
                }
            }
            return current.uuidsFor(checkpoint.subject(CONTROL))
                    .containsAll(checkpoint.subject(CONTROL).oldEventUuids());
        });
        for (String name : List.of(SUBJECT_A, SUBJECT_B)) {
            Subject subject = checkpoint.subject(name);
            JsonNode person = requireOwnedPerson(observation.personsFor(subject).getFirst(), subject);
            String personUuid = person.path("uuid").asText();
            List<String> analyticsPersons = observation.eventsFor(subject).stream()
                    .map(EventRow::personId)
                    .distinct()
                    .toList();
            require(
                    analyticsPersons.equals(List.of(personUuid)),
                    name + " events resolve to " + analyticsPersons + " in analytics, person API says " + personUuid);
            require(observation.personsTable().contains(personUuid), name + " person missing from the persons table");
            save(checkpoint.withSubject(
                    subject.withBaselinePerson(personUuid, person.path("id").asText()), now()));
            record(
                    "baseline",
                    name + " profile and events",
                    Status.OBSERVED,
                    Map.of(
                            "distinct_id", subject.distinctId(),
                            "person_uuid", personUuid,
                            "person_id", person.path("id").asText(),
                            "event_uuids", subject.oldEventUuids(),
                            "analytics_person_ids", analyticsPersons));
        }
        record(
                "baseline",
                "control event visible",
                Status.OBSERVED,
                Map.of(
                        "distinct_id", checkpoint.subject(CONTROL).distinctId(),
                        "event_uuids", checkpoint.subject(CONTROL).oldEventUuids()));
        save(checkpoint.withPhase(Phase.BASELINE_VERIFIED, now()));
    }

    // Phase 2: disable, then erase.

    private void disableSubjects() {
        for (String name : List.of(SUBJECT_A, SUBJECT_B)) {
            Subject subject = checkpoint.subject(name);
            Installation installation = installation(subject, OLD_VERSION, clock.instant());
            TelemetryState before = installation.store().read().stateOrInitial();
            int exit = installation.service().disable(DisableRequest.nonInteractive(), out, out);
            TelemetryState after = installation.store().read().stateOrInitial();
            require(exit == TelemetryService.EXIT_OK, name + " disable exited " + exit);
            require(after.mode() == TelemetryMode.DISABLED, name + " is not disabled");
            require(after.installation().equals(before.installation()), name + " identity changed on disable");
            require(after.registration().equals(before.registration()), name + " registration changed on disable");
            require(
                    after.boardImportsTotal() == IMPORTS && after.boardCreationsTotal() == CREATIONS,
                    name + " counters changed");
            require(after.pending().isEmpty(), name + " kept a pending report");
            // Ordinary worker restarts and checks past the grace period while disabled: no send.
            List<String> output = new ArrayList<>();
            HeartbeatReporter restarted = installation.reporter(
                    new TelemetryFixture.MutableClock(clock.instant().plus(PAST_GRACE)), output);
            CheckResult first = restarted.check();
            CheckResult second = installation
                    .reporter(new TelemetryFixture.MutableClock(clock.instant().plus(PAST_GRACE.plus(ONE_DAY))), output)
                    .check();
            require(
                    first == CheckResult.DISABLED && second == CheckResult.DISABLED,
                    name + " restart result " + first + "/" + second);
            require(
                    output.stream().noneMatch(line -> line.startsWith(REQUEST_LINE_PREFIX)),
                    name + " sent while disabled");
            TelemetryState afterRestarts = installation.store().read().stateOrInitial();
            require(
                    afterRestarts.installation().equals(before.installation()),
                    name + " identity changed by a restart");
            record(
                    "disable",
                    name + " disabled locally",
                    Status.OBSERVED,
                    Map.of(
                            "distinct_id", subject.distinctId(),
                            "mode", after.mode(),
                            "registered_on",
                                    after.registration().map(Object::toString).orElse(""),
                            "board_imports_total", after.boardImportsTotal(),
                            "restart_checks", first + "," + second,
                            "in_flight_requests", "none: the experiment's delivery executor runs inline"));
        }
        save(checkpoint.withPhase(Phase.DISABLED, now()));
    }

    private void requestErasure() {
        List<String> targets = List.of(
                checkpoint.subject(SUBJECT_A).distinctId(),
                checkpoint.subject(SUBJECT_B).distinctId());
        DeletionRecord record = bulkDelete("erasure", targets, targets.size());
        ExperimentCheckpoint updated = checkpoint;
        for (String name : List.of(SUBJECT_A, SUBJECT_B)) {
            updated = updated.withSubject(updated.subject(name).withErasure(record), now());
        }
        save(updated.withPhase(Phase.ERASURE_REQUESTED, now()));
    }

    private void verifyErasure() {
        List<Subject> erased = List.of(checkpoint.subject(SUBJECT_A), checkpoint.subject(SUBJECT_B));
        Observation observation = awaitObservation("event deletion", DELETION_WAIT, current -> erased.stream()
                .allMatch(subject -> current.eventsFor(subject).isEmpty()
                        && current.personsFor(subject).isEmpty()
                        && current.deletionCompleted(subject)));
        require(
                observation
                        .uuidsFor(checkpoint.subject(CONTROL))
                        .containsAll(checkpoint.subject(CONTROL).oldEventUuids()),
                "control event disappeared during the erasure");
        ExperimentCheckpoint updated = checkpoint;
        for (Subject subject : erased) {
            String verifiedAt = observation.deletionVerifiedAt(subject);
            updated = updated.withSubject(
                    subject.withErasure(requireErasure(subject).verified(verifiedAt)), now());
            record(
                    "erasure",
                    subject.name() + " deletion verified",
                    Status.OBSERVED,
                    Map.of(
                            "distinct_id", subject.distinctId(),
                            "person_uuid", String.valueOf(subject.baselinePersonUuid()),
                            "delete_verified_at", verifiedAt,
                            "old_events_remaining",
                                    observation.eventsFor(subject).size(),
                            "persons_remaining", observation.personsFor(subject).size()));
        }
        save(updated.withPhase(Phase.ERASURE_VERIFIED, now()));
    }

    // Phase 3: same-ID reuse, without and with a prior reset.

    private void reuse(String name, Phase next) {
        Subject subject = checkpoint.subject(name);
        Installation installation = installation(subject, NEW_VERSION, clock.instant());
        TelemetryState before = installation.store().read().stateOrInitial();
        int exit = installation.service().enable(out, out);
        TelemetryState after = installation.store().read().stateOrInitial();
        require(exit == TelemetryService.EXIT_OK, name + " enable exited " + exit);
        require(after.mode() == TelemetryMode.ENABLED, name + " is not enabled");
        require(after.installation().equals(before.installation()), name + " identity changed on enable");
        require(after.registration().equals(before.registration()), name + " registration changed on enable");
        require(
                after.boardImportsTotal() == IMPORTS && after.boardCreationsTotal() == CREATIONS,
                name + " counters changed");
        require(after.pending().isEmpty(), name + " replayed a pending report");
        String eventUuid = sendHeartbeat(subject, NEW_VERSION, clock.instant());
        save(checkpoint.withSubject(subject.withNewEvent(eventUuid), now()).withPhase(next, now()));
    }

    private void resetBeforeReuse() {
        Subject subject = checkpoint.subject(SUBJECT_B);
        Response response = client.resetPersonDistinctId(subject.distinctId());
        Response persons = client.persons(subject.distinctId());
        String outcome = "HTTP " + response.status() + ", persons after reset: "
                + persons.json().path("results").size();
        record(
                "reset",
                "B reset before any new event",
                response.success() ? Status.OBSERVED : Status.FAILED,
                Map.of(
                        "distinct_id", subject.distinctId(),
                        "http_status", response.status(),
                        "detail", response.detail(),
                        "persons_after_reset", persons.json().path("results").size()));
        require(response.success(), "reset before reuse failed: " + outcome);
        save(checkpoint
                .withSubject(subject.withFact(FACT_RESET_BEFORE, outcome), now())
                .withPhase(Phase.RESET_B_DONE, now()));
    }

    private void observeReuse() {
        List<Subject> reused = List.of(checkpoint.subject(SUBJECT_A), checkpoint.subject(SUBJECT_B));
        Observation observation = awaitObservation("reuse ingestion", INGESTION_WAIT, current -> reused.stream()
                .allMatch(subject -> current.uuidsFor(subject).containsAll(subject.newEventUuids())
                        && !current.personsFor(subject).isEmpty()
                        && current.eventsFor(subject).stream()
                                .allMatch(row -> current.personsTable().contains(row.personId()))));
        ExperimentCheckpoint updated = checkpoint;
        for (Subject subject : reused) {
            updated = updated.withSubject(recordReuse(subject, observation), now());
        }
        save(updated.withPhase(Phase.REUSE_OBSERVED, now()));
    }

    private Subject recordReuse(Subject subject, Observation observation) {
        List<EventRow> rows = observation.eventsFor(subject);
        require(
                rows.stream().noneMatch(row -> subject.oldEventUuids().contains(row.uuid())),
                subject.name() + " old event reappeared");
        require(
                observation.uuidsFor(subject).containsAll(subject.newEventUuids()),
                subject.name() + " new event not queryable");
        List<JsonNode> persons = observation.personsFor(subject);
        String apiPerson = persons.isEmpty() ? MISSING : RESOLVED;
        boolean analyticsResolved = !rows.isEmpty()
                && rows.stream().allMatch(row -> observation.personsTable().contains(row.personId()));
        String personUuid = persons.isEmpty()
                ? ""
                : requireOwnedPerson(persons.getFirst(), subject).path("uuid").asText();
        Subject updated = subject.withFact(FACT_PERSON_API, apiPerson)
                .withFact(FACT_ANALYTICS, analyticsResolved ? RESOLVED : MISSING)
                .withFact(FACT_PERSON_UUID, personUuid);
        record(
                "reuse",
                subject.name() + " after re-enable",
                Status.OBSERVED,
                Map.of(
                        "distinct_id", subject.distinctId(),
                        "new_event_uuids", subject.newEventUuids(),
                        "person_api", apiPerson,
                        "person_uuid", personUuid,
                        "person_uuid_changed",
                                String.valueOf(
                                        !personUuid.isEmpty() && !personUuid.equals(subject.baselinePersonUuid())),
                        "analytics_person_ids",
                                rows.stream().map(EventRow::personId).distinct().toList(),
                        "analytics_person_resolved", analyticsResolved,
                        "old_events_remaining",
                                rows.stream()
                                        .filter(row -> subject.oldEventUuids().contains(row.uuid()))
                                        .count()));
        return updated;
    }

    // Phase 4: conditional repair after a real new event.

    private void repairIfNeeded() {
        ExperimentCheckpoint updated = checkpoint;
        for (String name : List.of(SUBJECT_A, SUBJECT_B)) {
            Subject subject = checkpoint.subject(name);
            boolean broken =
                    !RESOLVED.equals(subject.fact(FACT_PERSON_API)) || !RESOLVED.equals(subject.fact(FACT_ANALYTICS));
            if (!broken) {
                updated = updated.withSubject(subject.withFact(FACT_REPAIR, "not needed"), now());
                record(
                        "repair",
                        name + " needs no repair",
                        Status.NOT_EXECUTED,
                        Map.of("distinct_id", subject.distinctId()));
                continue;
            }
            Response response = client.resetPersonDistinctId(subject.distinctId());
            require(response.success(), name + " reset after the new event failed with HTTP " + response.status());
            Observation observation = awaitObservationOrLast(
                    "repair of " + name,
                    REPAIR_WAIT,
                    current -> !current.personsFor(subject).isEmpty()
                            && current.eventsFor(subject).stream()
                                    .allMatch(row -> current.personsTable().contains(row.personId())));
            Subject repaired = recordReuse(subject, observation);
            boolean resolved =
                    RESOLVED.equals(repaired.fact(FACT_PERSON_API)) && RESOLVED.equals(repaired.fact(FACT_ANALYTICS));
            String summary = resolved
                    ? "reset after the new event resolved the profile"
                    : "reset after the new event did not resolve the profile within " + REPAIR_WAIT;
            if (!resolved
                    && LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).isAfter(lastReportedDate(subject))) {
                String eventUuid = sendHeartbeat(subject, NEW_VERSION, clock.instant());
                repaired = repaired.withNewEvent(eventUuid);
                Observation again = awaitObservationOrLast(
                        "repair of " + name + " after one more event",
                        REPAIR_WAIT,
                        current -> !current.personsFor(subject).isEmpty()
                                && current.uuidsFor(subject).contains(eventUuid));
                repaired = recordReuse(repaired, again);
                summary += "; one more heartbeat after the reset: person_api=" + repaired.fact(FACT_PERSON_API)
                        + ", analytics=" + repaired.fact(FACT_ANALYTICS);
            } else if (!resolved) {
                summary += "; a further heartbeat is due only on a later UTC day, resume then";
            }
            record(
                    "repair",
                    name + " reset after a new event",
                    Status.OBSERVED,
                    Map.of("distinct_id", subject.distinctId(), "http_status", response.status(), "result", summary));
            updated = updated.withSubject(repaired.withFact(FACT_REPAIR, summary), now());
        }
        save(updated.withPhase(Phase.REPAIR_DONE, now()));
    }

    private LocalDate lastReportedDate(Subject subject) {
        return installation(subject, NEW_VERSION, clock.instant())
                .store()
                .read()
                .stateOrInitial()
                .lastReported()
                .orElse(LocalDate.MIN);
    }

    // Phase 5: erasability after reuse, then cleanup.

    private void cleanup() {
        Observation observation = observe();
        List<String> targets = new ArrayList<>();
        int resolvable = 0;
        for (String name : List.of(SUBJECT_A, SUBJECT_B, CONTROL)) {
            Subject subject = checkpoint.subject(name);
            List<EventRow> rows = observation.eventsFor(subject);
            require(
                    rows.stream()
                            .noneMatch(row -> subject.oldEventUuids().contains(row.uuid()) && !CONTROL.equals(name)),
                    name + " old event present before cleanup");
            List<String> expected = CONTROL.equals(name) ? subject.oldEventUuids() : subject.newEventUuids();
            require(
                    observation.uuidsFor(subject).containsAll(expected),
                    name + " expected events missing before cleanup");
            require(rows.size() == expected.size(), name + " has unexpected extra events: " + rows.size());
            if (!observation.personsFor(subject).isEmpty()) {
                requireOwnedPerson(observation.personsFor(subject).getFirst(), subject);
                resolvable++;
            }
            if (!CONTROL.equals(name)) {
                Installation installation = installation(subject, NEW_VERSION, clock.instant());
                require(
                        installation.service().disable(DisableRequest.nonInteractive(), out, out)
                                == TelemetryService.EXIT_OK,
                        name + " could not be disabled before cleanup");
            }
            targets.add(subject.distinctId());
        }
        DeletionRecord record = bulkDelete("cleanup", targets, resolvable);
        ExperimentCheckpoint updated = checkpoint;
        for (String name : List.of(SUBJECT_A, SUBJECT_B, CONTROL)) {
            Subject subject = updated.subject(name);
            String personUuid = observation.personsFor(subject).isEmpty()
                    ? ""
                    : observation.personsFor(subject).getFirst().path("uuid").asText();
            updated =
                    updated.withSubject(subject.withCleanup(record).withFact("cleanup.person_uuid", personUuid), now());
            if (personUuid.isEmpty()) {
                updated = updated.withNote(
                        name + " had no resolvable profile at cleanup; deletion by distinct_id " + subject.distinctId()
                                + " may not cover orphaned events, check manually",
                        now());
            }
        }
        save(updated.withPhase(Phase.CLEANUP_REQUESTED, now()));
    }

    private void verifyCleanup() {
        List<Subject> all =
                List.of(checkpoint.subject(SUBJECT_A), checkpoint.subject(SUBJECT_B), checkpoint.subject(CONTROL));
        Observation observation = awaitObservation("cleanup deletion", DELETION_WAIT, current -> all.stream()
                .allMatch(subject -> current.eventsFor(subject).isEmpty()
                        && current.personsFor(subject).isEmpty()
                        && (subject.fact("cleanup.person_uuid").isEmpty()
                                || current.deletionCompleted(subject.fact("cleanup.person_uuid")))));
        ExperimentCheckpoint updated = checkpoint;
        for (Subject subject : all) {
            String verifiedAt = subject.fact("cleanup.person_uuid").isEmpty()
                    ? "no profile to verify"
                    : observation.deletionVerifiedAt(subject.fact("cleanup.person_uuid"));
            updated = updated.withSubject(
                    subject.withCleanup(requireCleanup(subject).verified(verifiedAt)), now());
            record(
                    "cleanup",
                    subject.name() + " fixtures deleted",
                    Status.OBSERVED,
                    Map.of(
                            "distinct_id", subject.distinctId(),
                            "delete_verified_at", verifiedAt,
                            "events_remaining", observation.eventsFor(subject).size()));
        }
        save(updated.withPhase(Phase.COMPLETE, now()));
    }

    // Shared mechanics.

    private DeletionRecord bulkDelete(String phase, List<String> targets, int expectedFound) {
        List<String> owned = checkpoint.ownedDistinctIds();
        require(owned.containsAll(targets), "deletion target outside the run's allowlist");
        for (String target : targets) {
            Response persons = client.persons(target);
            require(
                    persons.success(),
                    "person lookup before deletion failed with HTTP " + persons.status() + ": " + persons.detail());
            for (JsonNode person : persons.json().path("results")) {
                requireOwnedPerson(person, subjectByDistinctId(target));
            }
        }
        Response response = client.bulkDelete(targets);
        JsonNode body = response.json();
        int errors = body.path("deletion_errors").size();
        DeletionRecord record = new DeletionRecord(
                now(),
                body.path("persons_found").asInt(-1),
                body.path("persons_queued_for_deletion").asInt(-1),
                body.path("events_queued_for_deletion").asBoolean(false),
                errors,
                null);
        boolean accepted = response.status() == Response.HTTP_ACCEPTED
                && errors == 0
                && record.personsFound() == expectedFound
                && record.eventsQueued();
        record(
                phase,
                "bulk deletion requested",
                accepted ? Status.OBSERVED : Status.FAILED,
                Map.of(
                        "distinct_ids", targets,
                        "http_status", response.status(),
                        "persons_found", record.personsFound(),
                        "persons_queued_for_deletion", record.personsQueued(),
                        "persons_deleted", body.path("persons_deleted").asInt(-1),
                        "events_queued_for_deletion", record.eventsQueued(),
                        "deletion_errors", body.path("deletion_errors").toString(),
                        "detail", response.success() ? "" : response.detail()));
        require(
                accepted,
                phase + " deletion not accepted: HTTP " + response.status() + " found=" + record.personsFound()
                        + " expected=" + expectedFound + " errors=" + errors + " events_queued="
                        + record.eventsQueued());
        return record;
    }

    private Subject subjectByDistinctId(String distinctId) {
        return checkpoint.subjects().values().stream()
                .filter(subject -> subject.distinctId().equals(distinctId))
                .findFirst()
                .orElseThrow(() -> new ExperimentFailure("no subject owns " + distinctId));
    }

    /// A person may be deleted only when every distinct ID PostHog mapped to it is this subject's.
    private static JsonNode requireOwnedPerson(JsonNode person, Subject subject) {
        List<String> ids = new ArrayList<>();
        person.path("distinct_ids").forEach(id -> ids.add(id.asText()));
        require(
                ids.equals(List.of(subject.distinctId())),
                subject.name() + " person is mapped to " + ids + ", refusing");
        return person;
    }

    private String sendHeartbeat(Subject subject, String version, Instant at) {
        Installation installation = installation(subject, version, at);
        TelemetryFixture.MutableClock subjectClock = new TelemetryFixture.MutableClock(at);
        List<String> output = new ArrayList<>();
        HeartbeatReporter reporter = installation.reporter(subjectClock, output);
        CheckResult result = reporter.check();
        if (result == CheckResult.GRACE) {
            subjectClock.advance(PAST_GRACE);
            result = reporter.check();
        }
        TelemetryState state = installation.store().read().stateOrInitial();
        LocalDate day = LocalDate.ofInstant(subjectClock.instant(), ZoneOffset.UTC);
        boolean accepted = result == CheckResult.DISPATCHED
                && state.lastReported().map(day::equals).orElse(false)
                && state.pending().isEmpty();
        String eventUuid = output.stream()
                .filter(line -> line.startsWith(REQUEST_LINE_PREFIX))
                .map(ErasureExperiment::eventUuidOf)
                .findFirst()
                .orElse("");
        String response = output.stream()
                .filter(line -> line.startsWith("telemetry response "))
                .findFirst()
                .orElse("no response line");
        record(
                "send",
                subject.name() + " heartbeat",
                accepted ? Status.OBSERVED : Status.FAILED,
                Map.of(
                        "distinct_id", subject.distinctId(),
                        "event_uuid", eventUuid,
                        "timestamp", subjectClock.instant().toString(),
                        "installed_version", version,
                        "check_result", result,
                        "response", response,
                        "last_reported_date",
                                state.lastReported().map(Object::toString).orElse("")));
        require(
                accepted && !eventUuid.isEmpty(),
                subject.name() + " heartbeat not accepted: " + result + ", " + response);
        return eventUuid;
    }

    private static String eventUuidOf(String requestLine) {
        int bodyStart = requestLine.indexOf('\n');
        try {
            return JSON.readTree(requestLine.substring(bodyStart + 1))
                    .path(HeartbeatField.Names.UUID)
                    .asText("");
        } catch (IOException exception) {
            return "";
        }
    }

    /// A disposable installation for one subject: its own state directory under the experiment
    /// directory, the installer marker with the given version, synthetic platform and boards, and
    /// the verified test project token. Counters are seeded once so snapshots carry real numbers.
    private Installation installation(Subject subject, String version, Instant at) {
        Path stateDir = binding.experimentDir()
                .resolve("subjects")
                .resolve(subject.name())
                .resolve("state");
        try {
            Files.createDirectories(stateDir);
            Files.writeString(
                    InstalledVersion.installContextPath(stateDir),
                    "installer=install.sh\ninstall_format_version=2\napp_version=" + version + "\n");
        } catch (IOException exception) {
            throw new ExperimentFailure("subject state directory not writable: " + exception.getMessage(), exception);
        }
        require(!token.get().isEmpty(), "capture token not verified");
        TelemetryInstallation installation = TelemetryInstallation.of(
                stateDir,
                () -> OptionalInt.of(BOARDS),
                new TelemetryEnvironment(false, false, true),
                new TelemetryDistribution(binding.captureEndpoint(), Optional.of(token.get())));
        require(
                installation.installed() && installation.networkEligible(),
                subject.name() + " installation is not eligible");
        TelemetryStateStore store = installation.store().orElseThrow();
        store.update(state -> state.hasIdentity()
                ? Update.unchanged(null)
                : Update.write(
                        state.withIdentity(
                                        UUID.fromString(subject.distinctId()), LocalDate.parse(subject.registeredOn()))
                                .withCounters(IMPORTS, CREATIONS),
                        null));
        HeartbeatSnapshots snapshots = new HeartbeatSnapshots(installation, TelemetryFixture.fixedPlatform());
        return new Installation(
                installation,
                store,
                snapshots,
                new TelemetryService(installation, snapshots, Clock.fixed(at, ZoneOffset.UTC)),
                binding.captureEndpoint());
    }

    private record Installation(
            TelemetryInstallation installation,
            TelemetryStateStore store,
            HeartbeatSnapshots snapshots,
            TelemetryService service,
            java.net.URI captureEndpoint) {

        HeartbeatReporter reporter(Clock clock, List<String> output) {
            return new HeartbeatReporter(
                    installation,
                    snapshots,
                    new PostHogCaptureClient(captureEndpoint),
                    clock,
                    Runnable::run,
                    () -> 0,
                    output::add);
        }
    }

    /// Polls the provider until the condition holds; a provider that has not finished within the
    /// phase's wait raises a pending outcome, never a failure.
    private Observation awaitObservation(
            String what, Duration maxWait, java.util.function.Predicate<Observation> done) {
        Observation last = awaitObservationOrLast(what, maxWait, done);
        if (!done.test(last)) {
            throw new PendingException(what + " not complete after " + maxWait + "; resume later");
        }
        return last;
    }

    private Observation awaitObservationOrLast(
            String what, Duration maxWait, java.util.function.Predicate<Observation> done) {
        Instant deadline = clock.instant().plus(maxWait);
        Observation observation = observe();
        while (!done.test(observation) && clock.instant().isBefore(deadline)) {
            budget.await(POLL_INTERVAL, "waiting for " + what);
            observation = observe();
        }
        record(
                "poll",
                what,
                done.test(observation) ? Status.OBSERVED : Status.PENDING,
                observation.summary(checkpoint));
        return observation;
    }

    /// One consistent look at everything the run owns: every heartbeat for the owned IDs with its
    /// analytics person, the persons table rows for those persons, the person API answer per ID,
    /// and the deletion status per known person UUID.
    private Observation observe() {
        List<String> ids = checkpoint.ownedDistinctIds();
        ids.forEach(UUID::fromString);
        String window = HOGQL_TIME.format(Instant.parse(checkpoint.startedAt()).minus(WINDOW_BEFORE_START));
        Response events = client.query("SELECT distinct_id, toString(uuid), toString(person_id), toString(timestamp)"
                + " FROM events WHERE event = '" + HeartbeatEvent.EVENT_NAME + "' AND distinct_id IN (" + quoted(ids)
                + ")"
                + " AND timestamp >= toDateTime('" + window + "') ORDER BY timestamp LIMIT 100");
        require(events.success(), "event query failed with HTTP " + events.status() + ": " + events.detail());
        List<EventRow> rows = new ArrayList<>();
        for (JsonNode row : events.json().path("results")) {
            rows.add(new EventRow(
                    row.get(0).asText(),
                    row.get(1).asText(),
                    row.get(2).asText(),
                    row.get(3).asText()));
        }
        List<String> personIds =
                new ArrayList<>(rows.stream().map(EventRow::personId).distinct().toList());
        checkpoint.subjects().values().stream()
                .map(Subject::baselinePersonUuid)
                .filter(uuid -> uuid != null && !personIds.contains(uuid))
                .forEach(personIds::add);
        personIds.forEach(UUID::fromString);
        List<String> personsTable = new ArrayList<>();
        if (!personIds.isEmpty()) {
            Response persons = client.query("SELECT toString(id) FROM persons WHERE id IN (" + quoted(personIds) + ")");
            require(persons.success(), "persons query failed with HTTP " + persons.status() + ": " + persons.detail());
            persons.json()
                    .path("results")
                    .forEach(row -> personsTable.add(row.get(0).asText()));
        }
        Map<String, List<JsonNode>> personApi = new LinkedHashMap<>();
        Map<String, JsonNode> deletions = new LinkedHashMap<>();
        for (Subject subject : checkpoint.subjects().values()) {
            Response persons = client.persons(subject.distinctId());
            require(persons.success(), "person lookup failed with HTTP " + persons.status() + ": " + persons.detail());
            List<JsonNode> results = new ArrayList<>();
            persons.json().path("results").forEach(results::add);
            personApi.put(subject.distinctId(), results);
            for (String personUuid :
                    List.of(String.valueOf(subject.baselinePersonUuid()), subject.fact("cleanup.person_uuid"))) {
                if (personUuid.isEmpty() || "null".equals(personUuid) || deletions.containsKey(personUuid)) {
                    continue;
                }
                Response status = client.deletionStatus(personUuid);
                require(
                        status.success(),
                        "deletion status failed with HTTP " + status.status() + ": " + status.detail());
                deletions.put(personUuid, status.json().path("results"));
            }
        }
        return new Observation(rows, personsTable, personApi, deletions);
    }

    private static String quoted(List<String> values) {
        return values.stream().map(value -> "'" + value + "'").collect(Collectors.joining(", "));
    }

    private record EventRow(String distinctId, String uuid, String personId, String timestamp) {}

    private record Observation(
            List<EventRow> events,
            List<String> personsTable,
            Map<String, List<JsonNode>> personApi,
            Map<String, JsonNode> deletions) {

        List<EventRow> eventsFor(Subject subject) {
            return events.stream()
                    .filter(row -> row.distinctId().equals(subject.distinctId()))
                    .toList();
        }

        List<String> uuidsFor(Subject subject) {
            return eventsFor(subject).stream().map(EventRow::uuid).toList();
        }

        List<JsonNode> personsFor(Subject subject) {
            return personApi.getOrDefault(subject.distinctId(), List.of());
        }

        boolean deletionCompleted(Subject subject) {
            return subject.baselinePersonUuid() != null && deletionCompleted(subject.baselinePersonUuid());
        }

        /// Completed means PostHog itself set `delete_verified_at`; an absent status row is not
        /// completion.
        boolean deletionCompleted(String personUuid) {
            JsonNode rows = deletions.get(personUuid);
            if (rows == null || rows.isEmpty()) {
                return false;
            }
            for (JsonNode row : rows) {
                if ("completed".equals(row.path("status").asText())
                        && !row.path("delete_verified_at").isNull()) {
                    return true;
                }
            }
            return false;
        }

        String deletionVerifiedAt(Subject subject) {
            return deletionVerifiedAt(String.valueOf(subject.baselinePersonUuid()));
        }

        String deletionVerifiedAt(String personUuid) {
            for (JsonNode row : deletions.getOrDefault(personUuid, JSON.createArrayNode())) {
                if (!row.path("delete_verified_at").isNull()) {
                    return row.path("delete_verified_at").asText();
                }
            }
            return "";
        }

        Map<String, Object> summary(ExperimentCheckpoint checkpoint) {
            Map<String, Object> summary = new LinkedHashMap<>();
            for (Subject subject : checkpoint.subjects().values()) {
                summary.put(subject.name() + ".events", uuidsFor(subject));
                summary.put(
                        subject.name() + ".analytics_person_ids",
                        eventsFor(subject).stream()
                                .map(EventRow::personId)
                                .distinct()
                                .toList());
                summary.put(
                        subject.name() + ".person_api",
                        personsFor(subject).stream()
                                .map(person -> person.path("uuid").asText())
                                .toList());
                if (subject.baselinePersonUuid() != null) {
                    summary.put(
                            subject.name() + ".deletion_status",
                            String.valueOf(deletions.get(subject.baselinePersonUuid())));
                }
            }
            summary.put("persons_table", personsTable);
            return summary;
        }
    }

    private DeletionRecord requireErasure(Subject subject) {
        DeletionRecord record = subject.erasure();
        if (record == null) {
            throw new ExperimentFailure(subject.name() + " has no erasure record");
        }
        return record;
    }

    private DeletionRecord requireCleanup(Subject subject) {
        DeletionRecord record = subject.cleanup();
        if (record == null) {
            throw new ExperimentFailure(subject.name() + " has no cleanup record");
        }
        return record;
    }

    private void save(ExperimentCheckpoint next) {
        next.save(checkpointFile);
        checkpoint = next;
        out.println("checkpoint: " + next.phase());
    }

    private void note(String text) {
        save(checkpoint.withNote(text, now()));
    }

    private String now() {
        return clock.instant().toString();
    }

    private EvidenceLog log() {
        EvidenceLog current = evidence;
        if (current == null) {
            throw new IllegalStateException("evidence log not open");
        }
        return current;
    }

    private void record(String phase, String step, Status status, Map<String, ?> fields) {
        log().record(phase, step, status, fields);
        out.println(status + " " + phase + ": " + step);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ExperimentFailure(message);
        }
    }

    /// The result table in the shape the runbook reports, derived from the checkpoint only.
    private void writeSummary() {
        Phase phase = checkpoint.phase();
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(
                "1. Old event/profile baseline established",
                reached(phase, Phase.BASELINE_VERIFIED, Phase.BASELINE_SENT));
        rows.put(
                "2. Local disable preserves UUID and prevents transmission",
                reached(phase, Phase.DISABLED, Phase.BASELINE_VERIFIED));
        rows.put(
                "3. Old event deletion completed and independently verified",
                reached(phase, Phase.ERASURE_VERIFIED, Phase.ERASURE_REQUESTED));
        rows.put("4. A: reuse without reset", reuseRow(phase, SUBJECT_A, Phase.REUSE_A_SENT));
        rows.put("5. B: reset before first new event", reuseRow(phase, SUBJECT_B, Phase.RESET_B_DONE));
        rows.put("6. Conditional reset after a new event", repairRow(phase));
        rows.put(
                "7. Old events absent and new records usable",
                reached(phase, Phase.CLEANUP_REQUESTED, Phase.REPAIR_DONE));
        rows.put("8. Reused ID erasable, fixtures cleaned up", reached(phase, Phase.COMPLETE, Phase.CLEANUP_REQUESTED));
        StringBuilder text = new StringBuilder("# Erasure experiment ")
                .append(checkpoint.runId())
                .append("\n\n");
        text.append("Project ")
                .append(checkpoint.projectId())
                .append(", phase ")
                .append(phase)
                .append(", updated ")
                .append(checkpoint.updatedAt())
                .append(", requests ")
                .append(budget.requests())
                .append(", waited ")
                .append(budget.waited())
                .append("\n\n| Row | Status |\n| --- | --- |\n");
        rows.forEach((row, status) ->
                text.append("| ").append(row).append(" | ").append(status).append(" |\n"));
        text.append("\nSubjects:\n\n");
        for (Subject subject : checkpoint.subjects().values()) {
            text.append("- ")
                    .append(subject.name())
                    .append(' ')
                    .append(subject.distinctId())
                    .append(" old=")
                    .append(subject.oldEventUuids())
                    .append(" new=")
                    .append(subject.newEventUuids())
                    .append(" facts=")
                    .append(subject.facts())
                    .append('\n');
        }
        if (!checkpoint.notes().isEmpty()) {
            text.append("\nNotes:\n\n");
            checkpoint.notes().forEach(note -> text.append("- ")
                    .append(log().redact(note))
                    .append('\n'));
        }
        try {
            Files.writeString(binding.experimentDir().resolve("summary.md"), text.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            out.println("summary not written: " + exception.getMessage());
        }
        out.println(text);
    }

    private static String reached(Phase current, Phase passAt, Phase pendingAt) {
        if (current.compareTo(passAt) >= 0) {
            return "PASS";
        }
        return current.compareTo(pendingAt) >= 0 ? "PENDING" : "NOT RUN";
    }

    private String reuseRow(Phase current, String name, Phase sentAt) {
        if (current.compareTo(Phase.REUSE_OBSERVED) < 0) {
            return current.compareTo(sentAt) >= 0 ? "PENDING" : "NOT RUN";
        }
        Subject subject = checkpoint.subject(name);
        boolean resolved =
                RESOLVED.equals(subject.fact(FACT_PERSON_API)) && RESOLVED.equals(subject.fact(FACT_ANALYTICS));
        return (resolved ? "PASS" : "FAIL") + " (person_api=" + subject.fact(FACT_PERSON_API) + ", analytics="
                + subject.fact(FACT_ANALYTICS)
                + (SUBJECT_B.equals(name) ? ", reset before reuse: " + subject.fact(FACT_RESET_BEFORE) : "") + ")";
    }

    private String repairRow(Phase current) {
        if (current.compareTo(Phase.REPAIR_DONE) < 0) {
            return "NOT RUN";
        }
        String a = checkpoint.subject(SUBJECT_A).fact(FACT_REPAIR);
        String b = checkpoint.subject(SUBJECT_B).fact(FACT_REPAIR);
        return "A: " + a + "; B: " + b;
    }

    /// Holds the verified token for the redactor and the installations. Before verification it
    /// answers an empty string, so redaction can never trigger a network call.
    private static final class CachedToken implements Supplier<String> {
        private final ExperimentBinding binding;
        private final PostHogManagementClient client;
        private volatile String value = "";

        CachedToken(ExperimentBinding binding, PostHogManagementClient client) {
            this.binding = binding;
            this.client = client;
        }

        void verify() {
            value = binding.verifiedCaptureToken(client);
        }

        @Override
        public String get() {
            return value;
        }
    }

    record Outcome(Phase phase, Kind kind, String message) {
        enum Kind {
            COMPLETE,
            PENDING,
            FAILED
        }

        static Outcome failed(Phase phase, String message) {
            return new Outcome(phase, Kind.FAILED, message);
        }
    }

    /// A step's expectation did not hold; the checkpoint stays at the phase that failed.
    static final class ExperimentFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ExperimentFailure(String message) {
            super(message);
        }

        ExperimentFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /// The provider has not finished an asynchronous step; resume later from the checkpoint.
    static final class PendingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PendingException(String message) {
            super(message);
        }
    }
}
