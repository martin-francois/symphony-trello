package ch.fmartin.symphony.trello.telemetry;

import java.util.Optional;

/// The mode that applies to this process: the stored preference unless an environment override
/// narrows it. Debug can never override a stored disable, and no override can enable reporting.
public record EffectiveTelemetry(TelemetryMode stored, TelemetryMode effective, Optional<String> overrideReason) {

    public static EffectiveTelemetry resolve(TelemetryMode stored, TelemetryEnvironment environment) {
        if (environment.disabled()) {
            return new EffectiveTelemetry(
                    stored, TelemetryMode.DISABLED, Optional.of(TelemetryEnvironment.DISABLED_VARIABLE + "=1"));
        }
        if (stored == TelemetryMode.DISABLED) {
            return new EffectiveTelemetry(stored, stored, Optional.empty());
        }
        if (environment.debug() && stored != TelemetryMode.DEBUG) {
            return new EffectiveTelemetry(
                    stored, TelemetryMode.DEBUG, Optional.of(TelemetryEnvironment.DEBUG_VARIABLE + "=1"));
        }
        return new EffectiveTelemetry(stored, stored, Optional.empty());
    }

    public boolean sendsReports() {
        return effective == TelemetryMode.ENABLED;
    }

    /// Counters accrue whenever reports may be sent or previewed locally, never while disabled.
    public boolean collectsCounters() {
        return effective != TelemetryMode.DISABLED;
    }
}
