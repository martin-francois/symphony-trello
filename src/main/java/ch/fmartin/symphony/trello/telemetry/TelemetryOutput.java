package ch.fmartin.symphony.trello.telemetry;

/// Where the reporter prints previews, request logs, and problems: a worker log or a test buffer.
@FunctionalInterface
public interface TelemetryOutput {
    void info(String message);

    static TelemetryOutput none() {
        return message -> {};
    }
}
