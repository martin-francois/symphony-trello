package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// The credential survives erasure; a completed reporting period never sends another heartbeat.
public record TelemetryOwnership(
        @JsonProperty("credential") TelemetryCredential credential,
        @JsonProperty("period") UUID period,
        @JsonProperty("used") boolean used,
        @JsonProperty("drain_until") @Nullable Instant drainUntil,
        @JsonProperty("erasure") @Nullable Erasure erasure) {
    public TelemetryOwnership {
        Objects.requireNonNull(credential, "credential");
        requireRandom(period);
    }

    /// PostHog can store an accepted capture well after answering it. An erasure lookup waits this
    /// long after the drain deadline, so a first heartbeat still in ingestion is not mistaken for an
    /// empty period that completes without deleting it.
    static final Duration INGESTION_GRACE = Duration.ofHours(1);

    public static TelemetryOwnership issued(TelemetryCredential credential) {
        return new TelemetryOwnership(credential, UUID.randomUUID(), false, null, null);
    }

    public String analyticsId() {
        return credential.installationId() + "." + period;
    }

    public TelemetryOwnership markUsed() {
        return new TelemetryOwnership(credential, period, true, drainUntil, erasure);
    }

    public TelemetryOwnership markDispatched(Instant expiresAt) {
        return new TelemetryOwnership(credential, period, true, drainedAt(expiresAt), erasure);
    }

    /// The later of the stored drain deadline and the given instant.
    public Instant drainedAt(Instant instant) {
        return drainUntil != null && drainUntil.isAfter(instant) ? drainUntil : instant;
    }

    /// The earliest time an erasure may look up this period's profile.
    public Instant settledAt(Instant now) {
        if (drainUntil == null) {
            return now;
        }
        Instant settled = drainUntil.plus(INGESTION_GRACE);
        return settled.isAfter(now) ? settled : now;
    }

    public boolean settled(Instant now) {
        return !settledAt(now).isAfter(now);
    }

    public TelemetryOwnership withErasure(Erasure next) {
        return new TelemetryOwnership(credential, period, used, drainUntil, next);
    }

    public boolean blocksReporting() {
        return erasure != null;
    }

    public TelemetryOwnership resume() {
        if (erasure == null) {
            return this;
        }
        if (erasure.phase() != Phase.COMPLETE && erasure.phase() != Phase.REFUSED) {
            throw new IllegalStateException("erasure is still in progress");
        }
        return issued(credential);
    }

    public enum Phase {
        REQUESTED,
        ACCEPTED,
        COMPLETE,
        REFUSED
    }

    public record Erasure(
            @JsonProperty("operation") UUID operation,
            @JsonProperty("requested_at") Instant requestedAt,
            @JsonProperty("not_before") Instant notBefore,
            @JsonProperty("phase") Phase phase) {
        public Erasure {
            requireRandom(operation);
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(notBefore, "notBefore");
            Objects.requireNonNull(phase, "phase");
        }

        public Erasure next(Phase next, Instant retryAt) {
            return new Erasure(operation, requestedAt, retryAt, next);
        }
    }

    private static void requireRandom(UUID value) {
        if (!TelemetryCredential.isRandom(value)) {
            throw new IllegalArgumentException("ownership state requires a random UUID");
        }
    }
}
