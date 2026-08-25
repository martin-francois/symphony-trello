package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LocalWorkerPathsTest {
    @Test
    void defaultInstalledConfigDirUsesInstalledStateHome() {
        // given
        Path configDir = Path.of("/opt/symphony/config");
        Path workspaceRoot = Path.of("/opt/symphony/workspaces");
        Path installedStateHome = Path.of("/opt/symphony/state");
        Map<String, String> environment = Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                configDir.toString(),
                "SYMPHONY_TRELLO_STATE_HOME",
                installedStateHome.toString());
        String previousStateHome = System.getProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY);

        // when
        LocalWorkerPaths paths;
        try {
            System.setProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, installedStateHome.toString());
            paths = LocalWorkerPaths.from(
                    Optional.empty(), Optional.of(configDir), Optional.of(workspaceRoot), Optional.empty(), environment);
        } finally {
            restoreProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, previousStateHome);
        }

        // then
        assertThat(paths.stateHome()).isEqualTo(installedStateHome.toAbsolutePath().normalize());
    }

    @Test
    void customConfigDirKeepsIsolatedSiblingStateHome() {
        // given
        Path installedConfigDir = Path.of("/opt/symphony/config");
        Path customConfigDir = Path.of("/tmp/isolated-config");
        Path workspaceRoot = Path.of("/tmp/isolated-config/workspaces");
        Path installedStateHome = Path.of("/opt/symphony/state");
        Map<String, String> environment = Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                installedConfigDir.toString(),
                "SYMPHONY_TRELLO_STATE_HOME",
                installedStateHome.toString());
        String previousStateHome = System.getProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY);

        // when
        LocalWorkerPaths paths;
        try {
            System.setProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, installedStateHome.toString());
            paths = LocalWorkerPaths.from(
                    Optional.empty(),
                    Optional.of(customConfigDir),
                    Optional.of(workspaceRoot),
                    Optional.empty(),
                    environment);
        } finally {
            restoreProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, previousStateHome);
        }

        // then
        assertThat(paths.stateHome())
                .isEqualTo(customConfigDir.resolveSibling("state").toAbsolutePath().normalize());
    }

    @Test
    void customConfigDirHonorsUserStateHomeOverride() {
        // given
        Path installedConfigDir = Path.of("/opt/symphony/config");
        Path customConfigDir = Path.of("/tmp/isolated-config");
        Path workspaceRoot = Path.of("/tmp/isolated-config/workspaces");
        Path installedStateHome = Path.of("/opt/symphony/state");
        Path userStateHome = Path.of("/tmp/user-state");
        Map<String, String> environment = Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                installedConfigDir.toString(),
                "SYMPHONY_TRELLO_STATE_HOME",
                userStateHome.toString());
        String previousStateHome = System.getProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY);

        // when
        LocalWorkerPaths paths;
        try {
            System.setProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, installedStateHome.toString());
            paths = LocalWorkerPaths.from(
                    Optional.empty(),
                    Optional.of(customConfigDir),
                    Optional.of(workspaceRoot),
                    Optional.empty(),
                    environment);
        } finally {
            restoreProperty(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, previousStateHome);
        }

        // then
        assertThat(paths.stateHome()).isEqualTo(userStateHome.toAbsolutePath().normalize());
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
