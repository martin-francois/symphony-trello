package ch.fmartin.symphony.trello.telemetry;

import java.util.Locale;

/// The stored or effective reporting mode of one installation.
public enum TelemetryMode {
    /// Daily heartbeats are sent while a worker runs.
    ENABLED,
    /// Nothing is sent and the board counters are frozen.
    DISABLED,
    /// Local-only: workers print what would be sent and send nothing; counters keep counting.
    DEBUG;

    public String displayName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
