package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

record LocalWorkerPaths(Path appHome, Path configDir, Path workspaceRoot, Path stateHome) {
    private static final String APP_HOME_PROPERTY = "symphony.trello.app.home";
    private static final String CONFIG_DIR_ENV = "SYMPHONY_TRELLO_CONFIG_DIR";
    private static final String WORKSPACE_ROOT_ENV = "SYMPHONY_TRELLO_WORKSPACE_ROOT";
    private static final String STATE_HOME_ENV = "SYMPHONY_TRELLO_STATE_HOME";
    private static final String APP_HOME_ENV = "SYMPHONY_TRELLO_APP_HOME";

    static LocalWorkerPaths from(
            Optional<Path> appHome,
            Optional<Path> configDir,
            Optional<Path> workspaceRoot,
            Optional<Path> stateHome,
            Map<String, String> environment) {
        Path resolvedConfigDir = configDir
                .or(() -> envPath(environment, CONFIG_DIR_ENV))
                .orElseGet(() -> Path.of("."))
                .toAbsolutePath()
                .normalize();
        Path resolvedWorkspaceRoot = workspaceRoot
                .or(() -> envPath(environment, WORKSPACE_ROOT_ENV))
                .orElse(TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT)
                .toAbsolutePath()
                .normalize();
        Path resolvedStateHome = stateHome
                .or(() -> envPath(environment, STATE_HOME_ENV))
                .orElseGet(() -> resolvedConfigDir.resolveSibling("state"))
                .toAbsolutePath()
                .normalize();
        Path resolvedAppHome = appHome.or(LocalWorkerPaths::propertyAppHome)
                .or(() -> envPath(environment, APP_HOME_ENV))
                .orElseGet(() -> Path.of("."))
                .toAbsolutePath()
                .normalize();
        return new LocalWorkerPaths(resolvedAppHome, resolvedConfigDir, resolvedWorkspaceRoot, resolvedStateHome);
    }

    Path manifestPath() {
        return configDir.resolve("connected-boards.json");
    }

    Path defaultEnvPath() {
        return configDir.resolve(".env");
    }

    private static Optional<Path> propertyAppHome() {
        String value = System.getProperty(APP_HOME_PROPERTY);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static Optional<Path> envPath(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }
}
