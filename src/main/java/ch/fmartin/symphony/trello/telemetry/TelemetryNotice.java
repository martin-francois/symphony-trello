package ch.fmartin.symphony.trello.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/// The short first-run disclosure. Its field summary is derived from the same catalog as the wire
/// format; the time line adapts to whether a worker deadline exists yet.
public final class TelemetryNotice {
    /// Bump when the notice must be shown again after a change in what is collected.
    public static final int REVISION = 1;
    public static final Duration FIRST_REPORT_GRACE = Duration.ofMinutes(5);
    public static final String MAINTAINER = "François Martin";
    public static final String PURPOSE_SENTENCE =
            MAINTAINER + " uses these reports only to improve symphony-trello, with processing through PostHog EU.";
    public static final String FIELD_SUMMARY =
            "Includes a random ID, registration date, app version, OS/architecture, and board/import/create counts.";
    public static final String DISABLE_COMMAND = "symphony-trello telemetry disable";
    public static final String PREVIEW_COMMAND = "symphony-trello telemetry preview";
    public static final String PRIVACY_COMMAND = "symphony-trello telemetry privacy";
    private static final long SECONDS_PER_MINUTE = 60;

    private TelemetryNotice() {}

    public static List<String> lines(Optional<Instant> firstWorkerDeadline, Instant now) {
        return List.of(
                "Optional usage reporting is enabled. " + timing(firstWorkerDeadline, now),
                PURPOSE_SENTENCE,
                "",
                FIELD_SUMMARY,
                "",
                "Disable: " + DISABLE_COMMAND,
                "Preview: " + PREVIEW_COMMAND,
                "Privacy: " + PRIVACY_COMMAND);
    }

    static String timing(Optional<Instant> firstWorkerDeadline, Instant now) {
        return firstWorkerDeadline
                .map(deadline -> timingUntil(Duration.between(now, deadline)))
                .orElse("First report " + FIRST_REPORT_GRACE.toMinutes()
                        + " minutes after the first worker starts, then daily while running.");
    }

    private static String timingUntil(Duration remaining) {
        if (remaining.isNegative() || remaining.isZero()) {
            return "Reports are sent daily while running.";
        }
        long minutes = Math.max(1, Math.ceilDiv(remaining.toSeconds(), SECONDS_PER_MINUTE));
        return "First report in " + minutes + (minutes == 1 ? " minute" : " minutes") + ", then daily while running.";
    }
}
