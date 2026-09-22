package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        Instant deadline = drainUntil != null && drainUntil.isAfter(expiresAt) ? drainUntil : expiresAt;
        return new TelemetryOwnership(credential, period, true, deadline, erasure);
    }

    public Instant drainedAt(Instant now) {
        return drainUntil != null && drainUntil.isAfter(now) ? drainUntil : now;
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
        if (erasure.phase() != Phase.COMPLETE) {
            throw new IllegalStateException("erasure is not complete");
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
        if (value == null || value.version() != 4 || value.variant() != 2) {
            throw new IllegalArgumentException("ownership state requires a random UUID");
        }
    }
}
