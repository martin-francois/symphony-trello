package ch.fmartin.symphony.trello.config;

import java.time.Duration;

public final class ConfigDefaults {
    public static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofSeconds(5);
    public static final long DEFAULT_POLLING_INTERVAL_MS = DEFAULT_POLLING_INTERVAL.toMillis();

    private ConfigDefaults() {}
}
