package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class LocalWorkerPathsTest {
    private static final Path INSTALLED_CONFIG_DIR = Path.of("/opt/symphony/config");
    private static final Path INSTALLED_STATE_HOME = Path.of("/opt/symphony/xdg-state/symphony-trello");

    @MethodSource("stateHomeScenarios")
    @ParameterizedTest
    void resolvesStateHomeForConfigAndEnvironment(Path configDir, Path environmentState, Path expectedState) {
        // given
        Map<String, String> environment = installedEnvironment(environmentState);

        // when
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                Optional.empty(),
                Optional.of(configDir),
                Optional.of(configDir.resolve("workspaces")),
                Optional.empty(),
                environment,
                installedPaths(environment));

        // then
        assertThat(paths.stateHome()).isEqualTo(expectedState.toAbsolutePath().normalize());
    }

    private static List<Arguments> stateHomeScenarios() {
        Path customConfigDir = Path.of("/tmp/isolated-config");
        Path userStateHome = Path.of("/tmp/user-state");
        return List.of(
                Arguments.of(INSTALLED_CONFIG_DIR, INSTALLED_STATE_HOME, INSTALLED_STATE_HOME),
                Arguments.of(customConfigDir, INSTALLED_STATE_HOME, customConfigDir.resolveSibling("state")),
                Arguments.of(customConfigDir, userStateHome, userStateHome));
    }

    @Test
    void setupWithCustomEnvironmentConfigIsolatesInheritedInstalledState() {
        // given
        Map<String, String> properties = installedProperties();

        // when
        int result = SetupSystemProperties.withLookup(properties::get, this::assertCustomSetupStateHome);

        // then
        assertThat(result).isZero();
    }

    private int assertCustomSetupStateHome() {
        Path config = Path.of("/tmp/custom/config");
        Map<String, String> environment = Map.of(
                SetupEnvironmentVariables.CONFIG_DIR_ENV, config.toString(),
                SetupEnvironmentVariables.STATE_HOME_ENV, INSTALLED_STATE_HOME.toString());
        LocalWorkerPaths paths = LocalWorkerPaths.fromSetup(config, config.resolve("workspaces"), environment);
        assertThat(paths.stateHome())
                .isEqualTo(config.resolveSibling("state").toAbsolutePath().normalize());
        return 0;
    }

    private Map<String, String> installedEnvironment(Path stateHome) {
        return Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                INSTALLED_CONFIG_DIR.toString(),
                "SYMPHONY_TRELLO_STATE_HOME",
                stateHome.toString());
    }

    private InstalledCliDefaults.InstalledPaths installedPaths(Map<String, String> environment) {
        return InstalledCliDefaults.InstalledPaths.from(environment, installedProperties());
    }

    private Map<String, String> installedProperties() {
        return Map.of(
                InstalledCliDefaults.INSTALLED_CONFIG_DIR_PROPERTY, INSTALLED_CONFIG_DIR.toString(),
                InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, INSTALLED_STATE_HOME.toString());
    }
}
