package ch.fmartin.symphony.trello.telemetry;

/// The telemetry state file cannot be read, written, or locked. Reporting stays off until a
/// maintainer or the user repairs the file; other Symphony features are unaffected.
public final class TelemetryStateException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TelemetryStateException(String message) {
        super(message);
    }

    public TelemetryStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
