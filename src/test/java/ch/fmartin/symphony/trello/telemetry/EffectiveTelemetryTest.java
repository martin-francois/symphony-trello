package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class EffectiveTelemetryTest {
    @CsvSource({
        "ENABLED, false, false, ENABLED",
        "ENABLED, true, false, DISABLED",
        "ENABLED, false, true, DEBUG",
        "ENABLED, true, true, DISABLED",
        "DISABLED, false, true, DISABLED",
        "DISABLED, false, false, DISABLED",
        "DEBUG, false, false, DEBUG",
        "DEBUG, true, false, DISABLED",
        "DEBUG, false, true, DEBUG"
    })
    @ParameterizedTest(name = "stored {0} with disabled={1} debug={2} is {3}")
    void environmentOverridesOnlyNarrowTheStoredPreference(
            TelemetryMode stored, boolean disabled, boolean debug, TelemetryMode expected) {
        // given
        TelemetryEnvironment environment = new TelemetryEnvironment(disabled, debug, false);

        // when
        EffectiveTelemetry effective = EffectiveTelemetry.resolve(stored, environment);

        // then
        assertThat(effective.effective()).isEqualTo(expected);
        assertThat(effective.stored()).isEqualTo(stored);
        assertThat(effective.overrideReason().isPresent())
                .as("an override reason is present exactly when the environment changed the mode")
                .isEqualTo(expected != stored);
    }

    @Test
    void logVariableAffectsOutputOnly() {
        // given
        TelemetryEnvironment environment = TelemetryEnvironment.from(
                Map.of(TelemetryEnvironment.LOG_VARIABLE, "1", TelemetryEnvironment.DISABLED_VARIABLE, "1"));

        // when
        EffectiveTelemetry effective = EffectiveTelemetry.resolve(TelemetryMode.DISABLED, environment);

        // then
        assertThat(environment.log()).as("LOG=1 is parsed").isTrue();
        assertThat(effective.sendsReports())
                .as("LOG never enables transmission")
                .isFalse();
        assertThat(effective.collectsCounters())
                .as("disabled installations do not count")
                .isFalse();
    }

    @CsvSource({"1, true", "true, true", "TRUE, true", "yes, true", "on, true", "0, false", "false, false", "'', false"
    })
    @ParameterizedTest(name = "value {0} is truthy: {1}")
    void truthyValuesFollowOneRule(String value, boolean expected) {
        // given
        Map<String, String> environment = Map.of(TelemetryEnvironment.DISABLED_VARIABLE, value);

        // when
        TelemetryEnvironment parsed = TelemetryEnvironment.from(environment);

        // then
        assertThat(parsed.disabled())
                .as("%s should parse as %s", value, expected)
                .isEqualTo(expected);
    }
}
