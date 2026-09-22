package ch.fmartin.symphony.trello.telemetry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Builds heartbeat bodies from local state and platform facts. Preview and transmission use the
/// same builder so what the user sees is what is sent.
public final class HeartbeatSnapshots {
    private final TelemetryInstallation installation;
    private final PlatformDetector platform;

    public HeartbeatSnapshots(TelemetryInstallation installation, PlatformDetector platform) {
        this.installation = installation;
        this.platform = platform;
    }

    /// The production wiring: real platform detection, run lazily and cached per process.
    public static HeartbeatSnapshots detectingPlatform(TelemetryInstallation installation) {
        return new HeartbeatSnapshots(installation, new PlatformDetector());
    }

    public HeartbeatProperties properties(TelemetryState state) {
        return properties(state, observe());
    }

    /// The parts of a snapshot that need file or native reads. Callers take an observation before
    /// entering the state lock so the locked transaction stays short.
    public Observation observe() {
        OptionalInt boards = installation.boards().distinctBoardCount();
        return new Observation(
                platform.platform(),
                installation.installedVersion().get().orElse(null),
                boards.isPresent() ? boards.getAsInt() : null);
    }

    public HeartbeatProperties properties(TelemetryState state, Observation observation) {
        Platform detected = observation.platform();
        return new HeartbeatProperties(
                HeartbeatProperties.SCHEMA_VERSION,
                state.registration().map(Object::toString).orElse(null),
                observation.installedVersion(),
                detected.osFamily(),
                detected.osRelease(),
                detected.linuxDistribution(),
                detected.runtimeArch(),
                observation.connectedBoardCount(),
                state.boardImportsTotal(),
                state.boardCreationsTotal(),
                true,
                true);
    }

    /// Platform, installed release, and board count as seen at one moment.
    public record Observation(
            Platform platform, @Nullable String installedVersion, @Nullable Integer connectedBoardCount) {}

    /// A body built now for inspection. Missing identity fields stay `null`; such a body is not
    /// sendable and [HeartbeatEvent#requireSendable] rejects it.
    public HeartbeatEvent preview(TelemetryState state, Instant now) {
        return event(state, UUID.randomUUID(), now, properties(state));
    }

    public HeartbeatEvent event(
            TelemetryState state, UUID eventUuid, Instant timestamp, HeartbeatProperties properties) {
        return event(installation.distribution().projectToken().orElse(null), state, eventUuid, timestamp, properties);
    }

    private static HeartbeatEvent event(
            @Nullable String token,
            TelemetryState state,
            UUID eventUuid,
            Instant timestamp,
            HeartbeatProperties properties) {
        return new HeartbeatEvent(
                token,
                HeartbeatEvent.EVENT_NAME,
                state.installation().map(UUID::toString).orElse(null),
                eventUuid.toString(),
                // Whole seconds are enough for a daily report and keep the wire value short.
                timestamp.truncatedTo(ChronoUnit.SECONDS).toString(),
                properties);
    }
}
