package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.telemetry.TelemetryEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Telemetry shares the selected dotenv and non-blank process precedence of workflow settings.
final class TelemetryInstallationsEnvironmentTest {
    private static final String ENABLE_ALL_OVERRIDES = TelemetryEnvironment.DISABLED_VARIABLE + "=1\n"
            + TelemetryEnvironment.DEBUG_VARIABLE + "=true\n"
            + TelemetryEnvironment.LOG_VARIABLE + "=yes\n";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void managedWorkerReadsConfiguredOrInstalledDefaultDotenv(boolean configured) throws IOException {
        // given
        Map<String, String> environment = managedEnvironment();
        Path dotenv = configured ? tempDir.resolve("selected.env") : tempDir.resolve("config/.env");
        Files.writeString(dotenv, ENABLE_ALL_OVERRIDES);
        if (configured) {
            environment.put("SYMPHONY_TRELLO_DOTENV", dotenv.toString());
        }

        // when
        TelemetryEnvironment actual =
                TelemetryInstallations.forWorker(environment).environment();

        // then
        assertThat(actual).isEqualTo(new TelemetryEnvironment(true, true, true));
    }

    @CsvSource({"0,false", "false,false", "true,true", "' ',true", ",true"})
    @ParameterizedTest
    void processValuesOverrideDotenvOnlyWhenNonBlank(@Nullable String processValue, boolean enabled)
            throws IOException {
        // given
        Map<String, String> environment = managedEnvironment();
        Files.writeString(tempDir.resolve("config/.env"), ENABLE_ALL_OVERRIDES);
        if (processValue != null) {
            environment.put(TelemetryEnvironment.DISABLED_VARIABLE, processValue);
            environment.put(TelemetryEnvironment.DEBUG_VARIABLE, processValue);
            environment.put(TelemetryEnvironment.LOG_VARIABLE, processValue);
        }

        // when
        TelemetryEnvironment actual =
                TelemetryInstallations.forWorker(environment).environment();

        // then
        assertThat(actual).isEqualTo(new TelemetryEnvironment(enabled, enabled, enabled));
    }

    private Map<String, String> managedEnvironment() throws IOException {
        Map<String, String> environment = new HashMap<>();
        environment.put(
                LocalWorkerPaths.CONFIG_DIR_ENV,
                Files.createDirectories(tempDir.resolve("config")).toString());
        environment.put(
                LocalWorkerPaths.STATE_HOME_ENV,
                Files.createDirectories(tempDir.resolve("state")).toString());
        return environment;
    }
}
