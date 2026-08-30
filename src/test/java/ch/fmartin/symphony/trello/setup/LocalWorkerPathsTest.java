package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LocalWorkerPathsTest {
    private final Path installedConfigDir = Path.of("/opt/symphony/config");
    private final Path installedStateHome = Path.of("/opt/symphony/xdg-state/symphony-trello");

    @Test
    void defaultInstalledConfigDirUsesInstalledStateHome() {
        // given
        Path workspaceRoot = Path.of("/opt/symphony/workspaces");
        Map<String, String> environment = installedEnvironment(installedStateHome);

        // when
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                Optional.empty(),
                Optional.of(installedConfigDir),
                Optional.of(workspaceRoot),
                Optional.empty(),
                environment,
                installedPaths(environment));

        // then
        assertThat(paths.stateHome())
                .isEqualTo(installedStateHome.toAbsolutePath().normalize());
    }

    @Test
    void customConfigDirKeepsIsolatedSiblingStateHome() {
        // given
        Path customConfigDir = Path.of("/tmp/isolated-config");
        Path workspaceRoot = customConfigDir.resolve("workspaces");
        Map<String, String> environment = installedEnvironment(installedStateHome);

        // when
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                Optional.empty(),
                Optional.of(customConfigDir),
                Optional.of(workspaceRoot),
                Optional.empty(),
                environment,
                installedPaths(environment));

        // then
        assertThat(paths.stateHome())
                .isEqualTo(
                        customConfigDir.resolveSibling("state").toAbsolutePath().normalize());
    }

    @Test
    void customConfigDirHonorsUserStateHomeOverride() {
        // given
        Path customConfigDir = Path.of("/tmp/isolated-config");
        Path workspaceRoot = customConfigDir.resolve("workspaces");
        Path userStateHome = Path.of("/tmp/user-state");
        Map<String, String> environment = installedEnvironment(userStateHome);

        // when
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                Optional.empty(),
                Optional.of(customConfigDir),
                Optional.of(workspaceRoot),
                Optional.empty(),
                environment,
                installedPaths(environment));

        // then
        assertThat(paths.stateHome()).isEqualTo(userStateHome.toAbsolutePath().normalize());
    }

    private Map<String, String> installedEnvironment(Path stateHome) {
        return Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                installedConfigDir.toString(),
                "SYMPHONY_TRELLO_STATE_HOME",
                stateHome.toString());
    }

    private InstalledCliDefaults.InstalledPaths installedPaths(Map<String, String> environment) {
        return InstalledCliDefaults.InstalledPaths.from(
                environment, Map.of(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, installedStateHome.toString()));
    }
}
