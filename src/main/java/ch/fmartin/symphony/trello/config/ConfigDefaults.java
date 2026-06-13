package ch.fmartin.symphony.trello.config;

import java.time.Duration;

public final class ConfigDefaults {
    // Runtime fallback when a workflow omits polling.interval_ms: matches the upstream Symphony
    // config-schema default of 30 seconds. Generated workflows instead write
    // GENERATED_WORKFLOW_POLLING_INTERVAL, matching the upstream reference implementation's own
    // generated WORKFLOW.md, so freshly created boards stay responsive while a hand-written
    // workflow that omits the field behaves exactly like upstream.
    public static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofSeconds(30);
    public static final long DEFAULT_POLLING_INTERVAL_MS = DEFAULT_POLLING_INTERVAL.toMillis();
    public static final Duration GENERATED_WORKFLOW_POLLING_INTERVAL = Duration.ofSeconds(5);
    public static final long GENERATED_WORKFLOW_POLLING_INTERVAL_MS = GENERATED_WORKFLOW_POLLING_INTERVAL.toMillis();
    public static final int DEFAULT_SERVER_PORT = 18080;
    public static final String DEFAULT_CARD_IDENTIFIER_PREFIX = "TRELLO";

    public static final Duration DEFAULT_TRACKER_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    public static final long DEFAULT_TRACKER_REQUEST_TIMEOUT_MS = DEFAULT_TRACKER_REQUEST_TIMEOUT.toMillis();
    public static final int DEFAULT_TRACKER_MAX_API_RETRIES = 3;
    public static final Duration DEFAULT_TRACKER_API_RETRY_BASE_DELAY = Duration.ofSeconds(1);
    public static final long DEFAULT_TRACKER_API_RETRY_BASE_DELAY_MS = DEFAULT_TRACKER_API_RETRY_BASE_DELAY.toMillis();

    public static final int DEFAULT_MAX_CONCURRENT_AGENTS = 1;
    public static final int DEFAULT_RUNTIME_MAX_CONCURRENT_AGENTS = DEFAULT_MAX_CONCURRENT_AGENTS;
    public static final Duration DEFAULT_AGENT_MAX_RETRY_BACKOFF = Duration.ofMinutes(5);
    public static final long DEFAULT_AGENT_MAX_RETRY_BACKOFF_MS = DEFAULT_AGENT_MAX_RETRY_BACKOFF.toMillis();

    public static final Duration DEFAULT_CODEX_TURN_TIMEOUT = Duration.ofHours(1);
    public static final long DEFAULT_CODEX_TURN_TIMEOUT_MS = DEFAULT_CODEX_TURN_TIMEOUT.toMillis();
    public static final String DEFAULT_CODEX_COMMAND = "codex app-server";
    public static final Duration DEFAULT_CODEX_READ_TIMEOUT = Duration.ofSeconds(5);
    public static final long DEFAULT_CODEX_READ_TIMEOUT_MS = DEFAULT_CODEX_READ_TIMEOUT.toMillis();
    public static final Duration DEFAULT_CODEX_STALL_TIMEOUT = Duration.ofMinutes(5);
    public static final long DEFAULT_CODEX_STALL_TIMEOUT_MS = DEFAULT_CODEX_STALL_TIMEOUT.toMillis();

    /*
     * Concurrency is per workflow/board. Separate connected Trello boards may run in parallel, but
     * each board starts with one active card unless the workflow opts into a higher value.
     */
    public static final int DEFAULT_SETUP_MAX_CONCURRENT_AGENTS = DEFAULT_MAX_CONCURRENT_AGENTS;

    private ConfigDefaults() {}
}
