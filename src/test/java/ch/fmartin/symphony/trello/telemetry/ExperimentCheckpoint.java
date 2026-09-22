package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// The private, resumable record of one experiment run: which phase it reached, which synthetic
/// IDs it owns, and what the provider answered. It holds run metadata only, never a credential.
/// Every destructive request is checked against the distinct IDs listed here.
record ExperimentCheckpoint(
        @JsonProperty("run_id") String runId,
        @JsonProperty("project_id") long projectId,
        @JsonProperty("phase") Phase phase,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("subjects") Map<String, Subject> subjects,
        @JsonProperty("notes") List<String> notes) {

    static final String FILE_NAME = "checkpoint.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    enum Phase {
        CREATED,
        BASELINE_SENT,
        BASELINE_VERIFIED,
        DISABLED,
        ERASURE_REQUESTED,
        ERASURE_VERIFIED,
        REUSE_A_SENT,
        RESET_B_DONE,
        REUSE_B_SENT,
        REUSE_OBSERVED,
        REPAIR_DONE,
        CLEANUP_REQUESTED,
        COMPLETE
    }

    static ExperimentCheckpoint load(Path file) {
        try {
            return JSON.readValue(Files.readString(file), ExperimentCheckpoint.class);
        } catch (IOException exception) {
            throw new IllegalStateException("checkpoint unreadable: " + file.getFileName(), exception);
        }
    }

    /// Written to a sibling file and moved into place so a crash never leaves a torn checkpoint.
    void save(Path file) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, JSON.writeValueAsString(this));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("checkpoint not saved: " + file.getFileName(), exception);
        }
    }

    ExperimentCheckpoint withPhase(Phase next, String now) {
        return new ExperimentCheckpoint(runId, projectId, next, startedAt, now, subjects, notes);
    }

    ExperimentCheckpoint withSubject(Subject subject, String now) {
        Map<String, Subject> updated = new LinkedHashMap<>(subjects);
        updated.put(subject.name(), subject);
        return new ExperimentCheckpoint(runId, projectId, phase, startedAt, now, updated, notes);
    }

    ExperimentCheckpoint withNote(String note, String now) {
        List<String> updated = new ArrayList<>(notes);
        updated.add(now + " " + note);
        return new ExperimentCheckpoint(runId, projectId, phase, startedAt, now, subjects, updated);
    }

    Subject subject(String name) {
        Subject subject = subjects.get(name);
        if (subject == null) {
            throw new IllegalStateException("checkpoint has no subject " + name);
        }
        return subject;
    }

    /// All installation IDs this run created; the only IDs any deletion may target.
    List<String> ownedDistinctIds() {
        return subjects.values().stream().map(Subject::distinctId).toList();
    }

    /// One synthetic installation. `personUuid` and `personId` are PostHog's own identifiers for
    /// the profile, distinct from the installation UUID the application sends as `distinct_id`.
    record Subject(
            @JsonProperty("name") String name,
            @JsonProperty("distinct_id") String distinctId,
            @JsonProperty("registered_on") String registeredOn,
            @JsonProperty("old_event_uuids") List<String> oldEventUuids,
            @JsonProperty("new_event_uuids") List<String> newEventUuids,
            @JsonProperty("baseline_person_uuid") @Nullable String baselinePersonUuid,
            @JsonProperty("baseline_person_id") @Nullable String baselinePersonId,
            @JsonProperty("erasure") @Nullable DeletionRecord erasure,
            @JsonProperty("cleanup") @Nullable DeletionRecord cleanup,
            @JsonProperty("facts") Map<String, String> facts) {

        static Subject create(String name, String distinctId, String registeredOn) {
            return new Subject(name, distinctId, registeredOn, List.of(), List.of(), null, null, null, null, Map.of());
        }

        Subject withOldEvent(String eventUuid) {
            List<String> updated = new ArrayList<>(oldEventUuids);
            updated.add(eventUuid);
            return new Subject(
                    name,
                    distinctId,
                    registeredOn,
                    updated,
                    newEventUuids,
                    baselinePersonUuid,
                    baselinePersonId,
                    erasure,
                    cleanup,
                    facts);
        }

        Subject withNewEvent(String eventUuid) {
            List<String> updated = new ArrayList<>(newEventUuids);
            updated.add(eventUuid);
            return new Subject(
                    name,
                    distinctId,
                    registeredOn,
                    oldEventUuids,
                    updated,
                    baselinePersonUuid,
                    baselinePersonId,
                    erasure,
                    cleanup,
                    facts);
        }

        Subject withBaselinePerson(String personUuid, String personId) {
            return new Subject(
                    name,
                    distinctId,
                    registeredOn,
                    oldEventUuids,
                    newEventUuids,
                    personUuid,
                    personId,
                    erasure,
                    cleanup,
                    facts);
        }

        Subject withErasure(DeletionRecord record) {
            return new Subject(
                    name,
                    distinctId,
                    registeredOn,
                    oldEventUuids,
                    newEventUuids,
                    baselinePersonUuid,
                    baselinePersonId,
                    record,
                    cleanup,
                    facts);
        }

        Subject withCleanup(DeletionRecord record) {
            return new Subject(
                    name,
                    distinctId,
                    registeredOn,
                    oldEventUuids,
                    newEventUuids,
                    baselinePersonUuid,
                    baselinePersonId,
                    erasure,
                    record,
                    facts);
        }

        Subject withFact(String key, String value) {
            Map<String, String> updated = new LinkedHashMap<>(facts);
            updated.put(key, value);
            return new Subject(
                    name,
                    distinctId,
                    registeredOn,
                    oldEventUuids,
                    newEventUuids,
                    baselinePersonUuid,
                    baselinePersonId,
                    erasure,
                    cleanup,
                    updated);
        }

        String fact(String key) {
            return facts.getOrDefault(key, "");
        }
    }

    /// What one bulk deletion request returned and when its event deletion was verified.
    record DeletionRecord(
            @JsonProperty("requested_at") String requestedAt,
            @JsonProperty("persons_found") int personsFound,
            @JsonProperty("persons_queued") int personsQueued,
            @JsonProperty("events_queued") boolean eventsQueued,
            @JsonProperty("deletion_errors") int deletionErrors,
            @JsonProperty("verified_at") @Nullable String verifiedAt) {

        DeletionRecord verified(String at) {
            return new DeletionRecord(requestedAt, personsFound, personsQueued, eventsQueued, deletionErrors, at);
        }
    }
}
