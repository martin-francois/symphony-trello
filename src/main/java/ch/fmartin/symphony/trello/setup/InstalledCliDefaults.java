package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class InstalledCliDefaults {
    static final String INSTALLED_CONFIG_DIR_PROPERTY = "symphony.trello.installed.config.dir";
    static final String INSTALLED_WORKSPACE_ROOT_PROPERTY = "symphony.trello.installed.workspace.root";
    static final String INSTALLED_STATE_HOME_PROPERTY = "symphony.trello.installed.state.home";
    static final String INSTALLED_APP_HOME_PROPERTY = "symphony.trello.installed.app.home";

    private static final String CONFIG_DIR_ENV = "SYMPHONY_TRELLO_CONFIG_DIR";
    private static final String WORKSPACE_ROOT_ENV = "SYMPHONY_TRELLO_WORKSPACE_ROOT";
    private static final String STATE_HOME_ENV = "SYMPHONY_TRELLO_STATE_HOME";
    private static final String APP_HOME_ENV = "SYMPHONY_TRELLO_APP_HOME";
    private static final List<String> SETUP_LOCAL_LIFECYCLE_COMMANDS =
            List.of("check", "repair-port", "configure-github");
    private static final List<String> SETUP_LOCAL_VALUE_OPTIONS = List.of(
            "--key",
            "--token",
            "--board-name",
            "--board",
            "--workspace-id",
            "--active",
            "--terminal",
            "--in-progress",
            "--blocked",
            "--workflow",
            "--workspace-root",
            "--config-dir",
            "--manifest",
            "--server-port",
            "--max-agents",
            "--codex-model",
            "--codex-reasoning-effort",
            "--env",
            "--add-path",
            "--endpoint");

    private InstalledCliDefaults() {}

    static String[] apply(String[] args, Map<String, String> environment) {
        return apply(List.of(args), InstalledPaths.from(environment)).toArray(String[]::new);
    }

    static List<String> apply(List<String> args, InstalledPaths paths) {
        if (args.isEmpty() || paths.configDir().isEmpty()) {
            return args;
        }
        String command = args.getFirst();
        return switch (command) {
            case "setup-local" -> setupLocal(args, paths);
            case "new-board", "import-board" -> boardSetup(args, paths);
            case "start", "stop", "status", "logs", "diagnostics" -> lifecycle(args, paths);
            default -> args;
        };
    }

    static boolean hasUserStateHomeOverride(Map<String, String> environment) {
        return InstalledPaths.from(environment).stateHomeFromUserEnvironment();
    }

    private static List<String> setupLocal(List<String> args, InstalledPaths paths) {
        List<String> defaults = new ArrayList<>();
        boolean explicitConfigDir = hasOption(args, "--config-dir");
        Optional<String> explicitConfigDirValue = optionValue(args, "--config-dir");
        boolean lifecycle = setupLocalLifecycleSubcommand(args.subList(1, args.size()));
        if (!explicitConfigDir) {
            paths.configDir().ifPresent(value -> add(defaults, "--config-dir", value));
        }
        if (!lifecycle && !explicitConfigDir) {
            addIfMissing(defaults, args, "--workspace-root", paths.workspaceRoot());
        } else if (!lifecycle) {
            explicitConfigDirValue.ifPresent(configDir -> {
                Optional<String> workspaceRoot = paths.workspaceRootFromUserEnvironment()
                        ? paths.workspaceRoot()
                        : Optional.of(isolatedWorkspaceRoot(configDir));
                addIfMissing(defaults, args, "--workspace-root", workspaceRoot);
            });
        }
        return injectAfterCommand(args, defaults);
    }

    private static List<String> boardSetup(List<String> args, InstalledPaths paths) {
        List<String> defaults = new ArrayList<>();
        addIfMissing(defaults, args, "--workspace-root", paths.workspaceRoot());
        // Keep connected-board rows in the installed manifest even for explicit external workflow
        // paths, so default diagnostics and board-selector lifecycle commands keep seeing them.
        addIfMissing(defaults, args, "--manifest", paths.configDir().map(InstalledCliDefaults::installedManifest));
        return injectAfterCommand(args, defaults);
    }

    private static String installedManifest(String configDir) {
        return Path.of(configDir)
                .toAbsolutePath()
                .normalize()
                .resolve(ConnectedBoardManifest.FILE_NAME)
                .toString();
    }

    private static List<String> lifecycle(List<String> args, InstalledPaths paths) {
        List<String> defaults = new ArrayList<>();
        boolean explicitConfigDir = hasOption(args, "--config-dir");
        Optional<String> explicitConfigDirValue = optionValue(args, "--config-dir");
        if (!explicitConfigDir) {
            paths.configDir().ifPresent(value -> add(defaults, "--config-dir", value));
            addIfMissing(defaults, args, "--workspace-root", paths.workspaceRoot());
            addIfMissing(defaults, args, "--state-home", paths.stateHome());
        } else {
            explicitConfigDirValue.ifPresent(configDir -> {
                Optional<String> workspaceRoot = paths.workspaceRootFromUserEnvironment()
                        ? paths.workspaceRoot()
                        : Optional.of(isolatedWorkspaceRoot(configDir));
                Optional<String> stateHome = paths.stateHomeFromUserEnvironment()
                        ? paths.stateHome()
                        : Optional.of(isolatedStateHome(configDir));
                addIfMissing(defaults, args, "--workspace-root", workspaceRoot);
                addIfMissing(defaults, args, "--state-home", stateHome);
            });
        }
        addIfMissing(defaults, args, "--app-home", paths.appHome());
        return injectAfterCommand(args, defaults);
    }

    private static List<String> injectAfterCommand(List<String> args, List<String> defaults) {
        if (defaults.isEmpty()) {
            return args;
        }
        List<String> result = new ArrayList<>(1 + defaults.size() + Math.max(0, args.size() - 1));
        result.add(args.getFirst());
        result.addAll(defaults);
        result.addAll(args.subList(1, args.size()));
        return List.copyOf(result);
    }

    private static void addIfMissing(List<String> defaults, List<String> args, String name, Optional<String> value) {
        if (!hasOption(args, name)) {
            value.ifPresent(resolved -> add(defaults, name, resolved));
        }
    }

    private static void add(List<String> values, String name, String value) {
        values.add(name);
        values.add(value);
    }

    private static boolean hasOption(List<String> args, String option) {
        return args.stream().anyMatch(value -> value.equals(option) || value.startsWith(option + "="));
    }

    private static Optional<String> optionValue(List<String> args, String option) {
        for (int index = 0; index < args.size(); index++) {
            String value = args.get(index);
            if (value.equals(option)) {
                return index + 1 < args.size() ? Optional.of(args.get(index + 1)) : Optional.empty();
            }
            if (value.startsWith(option + "=")) {
                return Optional.of(value.substring(option.length() + 1));
            }
        }
        return Optional.empty();
    }

    private static boolean setupLocalLifecycleSubcommand(List<String> args) {
        int index = 0;
        while (index < args.size()) {
            String value = args.get(index);
            if (SETUP_LOCAL_LIFECYCLE_COMMANDS.contains(value)) {
                return true;
            }
            if (SETUP_LOCAL_VALUE_OPTIONS.contains(value)) {
                index += 2;
            } else if (!value.startsWith("--")) {
                return false;
            } else {
                index++;
            }
        }
        return false;
    }

    private static String isolatedWorkspaceRoot(String configDir) {
        return Path.of(configDir)
                .toAbsolutePath()
                .normalize()
                .resolve("workspaces")
                .toString();
    }

    private static String isolatedStateHome(String configDir) {
        Path parent = Path.of(configDir).toAbsolutePath().normalize().getParent();
        return (parent == null ? Path.of("state").toAbsolutePath().normalize() : parent.resolve("state")).toString();
    }

    record InstalledPaths(
            Optional<String> configDir,
            Optional<String> workspaceRoot,
            Optional<String> stateHome,
            Optional<String> appHome,
            Optional<String> installedWorkspaceRoot,
            Optional<String> installedStateHome) {
        static InstalledPaths from(Map<String, String> environment) {
            return from(environment, System::getProperty);
        }

        static InstalledPaths from(Map<String, String> environment, Map<String, String> properties) {
            return from(environment, properties::get);
        }

        private static InstalledPaths from(Map<String, String> environment, PropertyLookup properties) {
            return new InstalledPaths(
                    value(environment, CONFIG_DIR_ENV).or(() -> property(properties, INSTALLED_CONFIG_DIR_PROPERTY)),
                    value(environment, WORKSPACE_ROOT_ENV),
                    value(environment, STATE_HOME_ENV),
                    value(environment, APP_HOME_ENV).or(() -> property(properties, INSTALLED_APP_HOME_PROPERTY)),
                    property(properties, INSTALLED_WORKSPACE_ROOT_PROPERTY),
                    property(properties, INSTALLED_STATE_HOME_PROPERTY));
        }

        boolean workspaceRootFromUserEnvironment() {
            return workspaceRoot.isPresent() && !samePath(workspaceRoot, installedWorkspaceRoot);
        }

        boolean stateHomeFromUserEnvironment() {
            return stateHome.isPresent() && !samePath(stateHome, installedStateHome);
        }

        private static Optional<String> property(PropertyLookup properties, String name) {
            String value = properties.get(name);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        }

        private static Optional<String> value(Map<String, String> environment, String name) {
            String value = environment.get(name);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        }

        private static boolean samePath(Optional<String> first, Optional<String> second) {
            return first.flatMap(firstPath -> second.map(secondPath -> Path.of(firstPath)
                            .toAbsolutePath()
                            .normalize()
                            .equals(Path.of(secondPath).toAbsolutePath().normalize())))
                    .orElse(false);
        }
    }

    private interface PropertyLookup {
        String get(String name);
    }
}
