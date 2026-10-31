package ch.fmartin.symphony.trello.telemetry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Environment overrides. They apply only to processes that inherit the variables, never to the
/// stored installation-wide preference.
public record TelemetryEnvironment(boolean disabled, boolean debug, boolean log) {
    public static final String DISABLED_VARIABLE = "SYMPHONY_TRELLO_TELEMETRY_DISABLED";
    public static final String DEBUG_VARIABLE = "SYMPHONY_TRELLO_TELEMETRY_DEBUG";
    public static final String LOG_VARIABLE = "SYMPHONY_TRELLO_TELEMETRY_LOG";
    /// The values that turn a variable on, matched after stripping and lower-casing. install.sh and
    /// install.ps1 repeat them, and InstallerScriptTest runs both installers with every value.
    public static final Set<String> TRUE_VALUES = Set.of("1", "true", "yes", "on");

    public static TelemetryEnvironment from(Map<String, String> environment) {
        return new TelemetryEnvironment(
                isTrue(environment.get(DISABLED_VARIABLE)),
                isTrue(environment.get(DEBUG_VARIABLE)),
                isTrue(environment.get(LOG_VARIABLE)));
    }

    public static TelemetryEnvironment none() {
        return new TelemetryEnvironment(false, false, false);
    }

    static boolean isTrue(@Nullable String value) {
        return value != null && TRUE_VALUES.contains(value.strip().toLowerCase(Locale.ROOT));
    }
}
