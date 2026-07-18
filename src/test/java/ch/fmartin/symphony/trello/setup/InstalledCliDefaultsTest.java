package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InstalledCliDefaultsTest {
    private final Path configDir = Path.of("/opt/symphony/config");
    private final Path workspaceRoot = Path.of("/opt/symphony/workspaces");
    private final Path stateHome = Path.of("/opt/symphony/state");
    private final Path appHome = Path.of("/opt/symphony/app");

    @Test
    void addsInstalledDefaultsForLifecycleCommands() {
        // given
        InstalledCliDefaults.InstalledPaths paths = installedPaths();

        // when
        List<String> args = InstalledCliDefaults.apply(List.of("status"), paths);

        // then
        assertThat(args)
                .containsExactly(
                        "status",
                        "--config-dir",
                        configDir.toString(),
                        "--workspace-root",
                        workspaceRoot.toString(),
                        "--state-home",
                        stateHome.toString(),
                        "--app-home",
                        appHome.toString());
    }

    @Test
    void derivesIsolatedLifecycleDefaultsFromExplicitConfigDir() {
        // given
        InstalledCliDefaults.InstalledPaths paths = installedPaths();
        Path isolatedConfigDir = Path.of("/tmp/isolated-config");

        // when
        List<String> args =
                InstalledCliDefaults.apply(List.of("diagnostics", "--config-dir", isolatedConfigDir.toString()), paths);

        // then
        assertThat(args)
                .containsExactly(
                        "diagnostics",
                        "--workspace-root",
                        isolatedConfigDir.resolve("workspaces").toString(),
                        "--state-home",
                        isolatedConfigDir.resolveSibling("state").toString(),
                        "--app-home",
                        appHome.toString(),
                        "--config-dir",
                        isolatedConfigDir.toString());
    }

    @Test
    void environmentOverridesWinOverIsolatedLifecycleDefaults() {
        // given
        Path environmentWorkspaceRoot = Path.of("/tmp/environment-workspaces");
        Path environmentStateHome = Path.of("/tmp/environment-state");
        var paths = new InstalledCliDefaults.InstalledPaths(
                Optional.of(configDir.toString()),
                Optional.of(environmentWorkspaceRoot.toString()),
                Optional.of(environmentStateHome.toString()),
                Optional.of(appHome.toString()),
                Optional.of(workspaceRoot.toString()),
                Optional.of(stateHome.toString()));

        // when
        List<String> args = InstalledCliDefaults.apply(List.of("status", "--config-dir", "/tmp/other-config"), paths);

        // then
        assertThat(args)
                .containsExactly(
                        "status",
                        "--workspace-root",
                        environmentWorkspaceRoot.toString(),
                        "--state-home",
                        environmentStateHome.toString(),
                        "--app-home",
                        appHome.toString(),
                        "--config-dir",
                        "/tmp/other-config");
    }

    @Test
    void setupLocalLifecycleSubcommandsDoNotReceiveWorkspaceRootDefaults() {
        // given
        InstalledCliDefaults.InstalledPaths paths = installedPaths();

        // when
        List<String> args = InstalledCliDefaults.apply(List.of("setup-local", "check"), paths);

        // then
        assertThat(args).containsExactly("setup-local", "--config-dir", configDir.toString(), "check");
    }

    @Test
    void setupLocalSetupReceivesWorkspaceRootDefault() {
        // given
        InstalledCliDefaults.InstalledPaths paths = installedPaths();

        // when
        List<String> args = InstalledCliDefaults.apply(List.of("setup-local", "--board", "SYNTH001"), paths);

        // then
        assertThat(args)
                .containsExactly(
                        "setup-local",
                        "--config-dir",
                        configDir.toString(),
                        "--workspace-root",
                        workspaceRoot.toString(),
                        "--board",
                        "SYNTH001");
    }

    @Test
    void boardSetupReceivesWorkspaceRootAndInstalledManifestDefaults() {
        // given
        InstalledCliDefaults.InstalledPaths paths = installedPaths();

        // when
        List<String> args = InstalledCliDefaults.apply(List.of("new-board", "--name", "Synthetic Board"), paths);

        // then
        assertThat(args)
                .containsExactly(
                        "new-board",
                        "--workspace-root",
                        workspaceRoot.toString(),
                        "--manifest",
                        configDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                        "--name",
                        "Synthetic Board");
    }

    @Test
    void boardSetupKeepsExplicitManifestOption() {
        // given
        InstalledCliDefaults.InstalledPaths paths = installedPaths();

        // when
        List<String> args = InstalledCliDefaults.apply(
                List.of("import-board", "--board", "SYNTH001", "--manifest", "/tmp/custom-manifest.json"), paths);

        // then
        assertThat(args)
                .containsExactly(
                        "import-board",
                        "--workspace-root",
                        workspaceRoot.toString(),
                        "--board",
                        "SYNTH001",
                        "--manifest",
                        "/tmp/custom-manifest.json");
    }

    @Test
    void reportsUserStateHomeOverrideWhenEnvironmentDiffersFromInstalledDefault() {
        // given
        Map<String, String> environment =
                Map.of("SYMPHONY_TRELLO_STATE_HOME", "/tmp/state", "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString());
        InstalledCliDefaults.InstalledPaths paths = InstalledCliDefaults.InstalledPaths.from(
                environment, Map.of(InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, stateHome.toString()));

        // when
        boolean userOverride = paths.stateHomeFromUserEnvironment();

        // then
        assertThat(userOverride)
                .as("the user environment overrides the installed state-home default")
                .isTrue();
    }

    private InstalledCliDefaults.InstalledPaths installedPaths() {
        return new InstalledCliDefaults.InstalledPaths(
                Optional.of(configDir.toString()),
                Optional.of(workspaceRoot.toString()),
                Optional.of(stateHome.toString()),
                Optional.of(appHome.toString()),
                Optional.of(workspaceRoot.toString()),
                Optional.of(stateHome.toString()));
    }
}
