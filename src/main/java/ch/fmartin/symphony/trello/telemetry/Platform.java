package ch.fmartin.symphony.trello.telemetry;

import org.jspecify.annotations.Nullable;

/// Normalized, allowlisted platform values as they appear in a heartbeat.
public record Platform(
        OsFamily osFamily, String osRelease, @Nullable String linuxDistribution, RuntimeArch runtimeArch) {
    public static final String UNKNOWN = "unknown";
    public static final String OTHER = "other";
    public static final String ROLLING = "rolling";

    /// Builds a platform from wire spellings for the shared test fixture, which still passes text.
    /// Remove it once `TelemetryFixture` passes the enum values.
    Platform(String osFamily, String osRelease, @Nullable String linuxDistribution, String runtimeArch) {
        this(
                WireVocabulary.fromWireName(OsFamily.class, osFamily),
                osRelease,
                linuxDistribution,
                WireVocabulary.fromWireName(RuntimeArch.class, runtimeArch));
    }

    public static Platform unknown() {
        return new Platform(OsFamily.UNKNOWN, UNKNOWN, null, RuntimeArch.UNKNOWN);
    }
}
