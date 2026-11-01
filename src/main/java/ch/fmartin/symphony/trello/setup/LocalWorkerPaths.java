package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

record LocalWorkerPaths(Path appHome, Path configDir, Path workspaceRoot, Path stateHome, Path manifestPath) {
    LocalWorkerPaths(Path appHome, Path configDir, Path workspaceRoot, Path stateHome) {
        this(appHome, configDir, workspaceRoot, stateHome, configDir.resolve(ConnectedBoardManifest.FILE_NAME));
    }

    LocalWorkerPaths withManifestPath(Path manifest) {
        return new LocalWorkerPaths(
                appHome,
                configDir,
                workspaceRoot,
                stateHome,
                manifest.toAbsolutePath().normalize());
    }

    private static final String APP_HOME_PROPERTY = "symphony.trello.app.home";
    static final String CONFIG_DIR_ENV = "SYMPHONY_TRELLO_CONFIG_DIR";
    private static final String WORKSPACE_ROOT_ENV = "SYMPHONY_TRELLO_WORKSPACE_ROOT";
    static final String STATE_HOME_ENV = "SYMPHONY_TRELLO_STATE_HOME";
    private static final String APP_HOME_ENV = "SYMPHONY_TRELLO_APP_HOME";

    /// Setup has already resolved its config directory. Keep the installation's state directory
    /// when setup uses that config; a separate config or manifest keeps its isolated state.
    static LocalWorkerPaths fromSetup(Path configDir, Path workspaceRoot, Map<String, String> environment) {
        Path resolvedConfigDir = configDir.toAbsolutePath().normalize();
        InstalledCliDefaults.InstalledPaths installed = InstalledCliDefaults.InstalledPaths.from(environment);
        Optional<Path> stateHome = installed
                .configDir()
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(resolvedConfigDir::equals)
                .flatMap(ignored -> installed.stateHome())
                .map(Path::of);
        return from(
                Optional.empty(), Optional.of(resolvedConfigDir), Optional.of(workspaceRoot), stateHome, environment);
    }

    static LocalWorkerPaths from(
            Optional<Path> appHome,
            Optional<Path> configDir,
            Optional<Path> workspaceRoot,
            Optional<Path> stateHome,
            Map<String, String> environment) {
        boolean explicitConfigDir = configDir.isPresent();
        Path resolvedConfigDir = configDir
                .or(() -> envPath(environment, CONFIG_DIR_ENV))
                .orElseGet(() -> Path.of("."))
                .toAbsolutePath()
                .normalize();
        Path resolvedAppHome = appHome.orElseGet(() -> defaultAppHome(environment))
                .toAbsolutePath()
                .normalize();
        Path resolvedWorkspaceRoot = workspaceRoot
                .or(() -> envPath(environment, WORKSPACE_ROOT_ENV))
                .orElseGet(() -> TrelloBoardSetup.defaultWorkspaceRoot(resolvedConfigDir, resolvedAppHome))
                .toAbsolutePath()
                .normalize();
        Path resolvedStateHome = stateHome
                .or(() -> isolatedStateHome(explicitConfigDir, environment, resolvedConfigDir))
                .or(() -> envPath(environment, STATE_HOME_ENV))
                .orElseGet(() -> resolvedConfigDir.resolveSibling("state"))
                .toAbsolutePath()
                .normalize();
        CliInputValidation.rejectExistingNonDirectoryPath("--config-dir", resolvedConfigDir);
        CliInputValidation.rejectExistingNonDirectoryPath("--workspace-root", resolvedWorkspaceRoot);
        CliInputValidation.rejectExistingNonDirectoryPath("--state-home", resolvedStateHome);
        CliInputValidation.rejectExistingNonDirectoryPath("--app-home", resolvedAppHome);
        Path manifest = envPath(environment, "SYMPHONY_TRELLO_MANIFEST")
                .orElseGet(() -> resolvedConfigDir.resolve(ConnectedBoardManifest.FILE_NAME));
        return new LocalWorkerPaths(resolvedAppHome, resolvedConfigDir, resolvedWorkspaceRoot, resolvedStateHome)
                .withManifestPath(manifest);
    }

    /// Managed workers inherit both directories from `LocalWorkerManager`; a process without them is
    /// a development or test run rather than part of an installation.
    static boolean managedWorkerEnvironmentPresent(Map<String, String> environment) {
        return envPath(environment, CONFIG_DIR_ENV).isPresent()
                && envPath(environment, STATE_HOME_ENV).isPresent();
    }

    /// Default connected-board manifest location for setup commands: the same configured or
    /// installed config directory that lifecycle commands resolve, never a path derived from a
    /// workflow file's parent directory.
    static Path defaultManifestPath(Map<String, String> environment) {
        return envPath(environment, CONFIG_DIR_ENV)
                .orElseGet(() -> Path.of("."))
                .toAbsolutePath()
                .normalize()
                .resolve(ConnectedBoardManifest.FILE_NAME);
    }

    Path defaultEnvPath() {
        return configDir.resolve(".env");
    }

    static Path defaultAppHome(Map<String, String> environment) {
        return propertyAppHome().or(() -> envPath(environment, APP_HOME_ENV)).orElseGet(() -> Path.of("."));
    }

    static Path defaultWorkspaceRoot(Path configDir, Map<String, String> environment) {
        return envPath(environment, WORKSPACE_ROOT_ENV)
                .orElseGet(() -> TrelloBoardSetup.defaultWorkspaceRoot(configDir, defaultAppHome(environment)));
    }

    private static Optional<Path> propertyAppHome() {
        String value = System.getProperty(APP_HOME_PROPERTY);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static Optional<Path> envPath(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static Optional<Path> isolatedStateHome(
            boolean explicitConfigDir, Map<String, String> environment, Path resolvedConfigDir) {
        if (!explicitConfigDir || InstalledCliDefaults.hasUserStateHomeOverride(environment)) {
            return Optional.empty();
        }
        return Optional.of(resolvedConfigDir.resolveSibling("state"));
    }
}
