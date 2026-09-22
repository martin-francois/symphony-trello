package ch.fmartin.symphony.trello.telemetry;

import org.jspecify.annotations.Nullable;

/// Normalized, allowlisted platform values as they appear in a heartbeat.
public record Platform(String osFamily, String osRelease, @Nullable String linuxDistribution, String runtimeArch) {
    public static final String UNKNOWN = "unknown";
    public static final String OTHER = "other";
    public static final String ROLLING = "rolling";

    public static Platform unknown() {
        return new Platform(UNKNOWN, UNKNOWN, null, UNKNOWN);
    }
}
