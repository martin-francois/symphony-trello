package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.TelemetryOwnership.Erasure;
import ch.fmartin.symphony.trello.telemetry.TelemetryOwnership.Phase;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/// Coordinates local durable state and the native service without holding a state lock during IO.
final class TelemetryErasure {
    private final TelemetryInstallation installation;
    private final Clock clock;

    TelemetryErasure(TelemetryInstallation installation, Clock clock) {
        this.installation = installation;
        this.clock = clock;
    }

    boolean needsMaintenance(TelemetryState state) {
        if (installation.distribution().erasure().isEmpty()) {
            return false;
        }
        TelemetryOwnership ownership = state.ownership();
        Erasure job = ownership == null ? null : ownership.erasure();
        if (job != null) {
            return job.phase() != Phase.COMPLETE
                    && job.phase() != Phase.REFUSED
                    && !job.notBefore().isAfter(clock.instant());
        }
        return !state.hasIdentity() && installation.effective(state.mode()).sendsReports();
    }

    void maintain(TelemetryStateStore store) {
        installation.distribution().erasure().ifPresent(endpoint -> {
            try (var client = new TelemetryErasureClient(endpoint, installation.distribution())) {
                maintain(store, client);
            }
        });
    }

    private void maintain(TelemetryStateStore store, TelemetryErasureClient client) {
        TelemetryState state = readable(store);
        if (!needsMaintenance(state)) {
            return;
        }
        if (!state.hasIdentity()) {
            TelemetryCredential credential = client.issue();
            store.update(current -> {
                if (current.hasIdentity()
                        || current.preferenceRevision() != state.preferenceRevision()
                        || !installation.effective(current.mode()).sendsReports()) {
                    return Update.unchanged(null);
                }
                return Update.write(
                        current.withIdentity(
                                        credential.installationId(),
                                        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
                                .withOwnership(TelemetryOwnership.issued(credential)),
                        null);
            });
            return;
        }
        TelemetryOwnership ownership = Objects.requireNonNull(state.ownership());
        Erasure job = Objects.requireNonNull(ownership.erasure());
        // Reserve the next attempt before IO. A failed or interrupted process cannot spin on every tick.
        Erasure attempt = job.next(job.phase(), clock.instant().plusSeconds(60));
        if (!replace(store, ownership, job, attempt)) {
            return;
        }
        client.request(attempt.phase() == Phase.REQUESTED ? "erase" : "status", ownership, attempt, clock.instant());
        JsonNode reply = client.status(ownership);
        Phase phase =
                switch (reply.path("status").asText()) {
                    case "complete" -> Phase.COMPLETE;
                    case "accepted" -> Phase.ACCEPTED;
                    case "refused" -> Phase.REFUSED;
                    default -> attempt.phase();
                };
        boolean saved = replace(
                store, ownership, attempt, attempt.next(phase, clock.instant().plusSeconds(60)));
        if (saved && phase == Phase.COMPLETE) {
            acknowledge(client, ownership, attempt);
        }
    }

    // Completion is already durable. One best-effort acknowledgment avoids an indefinite network loop.
    // Failed flag cleanup belongs to the operator procedure and must not undo successful erasure.
    @SuppressWarnings("PMD.EmptyCatchBlock")
    private void acknowledge(TelemetryErasureClient client, TelemetryOwnership ownership, Erasure erasure) {
        try {
            client.request("ack", ownership, erasure, clock.instant());
        } catch (TelemetryStateException ignored) {
            // The operator can archive the completed status flag; the client remains disabled.
        }
    }

    void request(TelemetryStateStore store) {
        boolean owned = store.update(state -> {
            TelemetryOwnership owner = state.ownership();
            TelemetryState disabled = state.withMode(TelemetryMode.DISABLED);
            if (owner == null) {
                return Update.write(disabled, false);
            }
            if (owner.erasure() != null) {
                return Update.write(disabled, true);
            }
            Instant now = clock.instant();
            // Allow an already dispatched heartbeat's bounded transport to finish before looking up its profile.
            Instant notBefore = owner.drainedAt(now);
            Phase phase = owner.used() ? Phase.REQUESTED : Phase.COMPLETE;
            Erasure job = new Erasure(UUID.randomUUID(), now, notBefore, phase);
            return Update.write(disabled.withOwnership(owner.withErasure(job)), true);
        });
        if (!owned) {
            throw new TelemetryStateException(
                    "reporting is off; this installation has no ownership credential; use maintainer-assisted erasure");
        }
        maintain(store);
    }

    static TelemetryState readable(TelemetryStateStore store) {
        TelemetryStateStore.StateRead read = store.read();
        if (read.unreadable()) {
            throw new TelemetryStateException("telemetry state is unreadable; reporting remains off");
        }
        return read.stateOrInitial();
    }

    private static boolean replace(
            TelemetryStateStore store, TelemetryOwnership owner, Erasure expected, Erasure next) {
        return store.update(state -> {
            TelemetryOwnership current = state.ownership();
            if (current == null
                    || !current.period().equals(owner.period())
                    || !Objects.equals(current.erasure(), expected)) {
                return Update.unchanged(false);
            }
            return Update.write(state.withOwnership(current.withErasure(next)), true);
        });
    }
}
