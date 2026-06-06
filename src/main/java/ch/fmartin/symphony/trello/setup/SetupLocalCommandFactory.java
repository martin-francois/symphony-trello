package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.LocalSetupRequest.Action;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

final class SetupLocalCommandFactory {
    private static final List<String> WORKFLOW_AND_ACCESS_OPTIONS = List.of(
            "--workspace-id",
            "--active",
            "--terminal",
            "--in-progress",
            "--no-in-progress",
            "--blocked",
            "--workflow",
            "--workspace-root",
            "--server-port",
            "--max-agents",
            "--codex-model",
            "--codex-reasoning-effort",
            "--env",
            "--add-path",
            "--allow-all-paths",
            "--danger-full-access",
            "--no-start");
    private static final List<String> CHECK_UNSUPPORTED_OPTIONS =
            optionNames(List.of("--dry-run", "--force", "--board-name"), WORKFLOW_AND_ACCESS_OPTIONS);
    private static final List<String> REPAIR_PORT_UNSUPPORTED_OPTIONS = optionNames(
            List.of("--force", "--key", "--token", "--board-name"),
            WORKFLOW_AND_ACCESS_OPTIONS,
            List.of("--endpoint", "--github", "--no-github"));
    private static final List<String> CONFIGURE_GITHUB_UNSUPPORTED_OPTIONS = List.of(
            "--dry-run",
            "--force",
            "--board-name",
            "--workspace-id",
            "--active",
            "--terminal",
            "--in-progress",
            "--no-in-progress",
            "--blocked",
            "--workflow",
            "--workspace-root",
            "--server-port",
            "--env",
            "--no-github");

    int execute(String[] args, LocalSetup setup, BufferedReader input, PrintStream out, PrintStream err) {
        CommandLine commandLine = new CommandLine(new SetupLocalCommand(setup, input, out, err));
        hideUnsupportedSubcommandOptions(commandLine);
        commandLine
                .setOut(new PrintWriter(out, true, StandardCharsets.UTF_8))
                .setErr(new PrintWriter(err, true, StandardCharsets.UTF_8))
                .setExecutionExceptionHandler((exception, ignored, parseResult) -> {
                    printExecutionFailure(err, exception, errorCode(exception));
                    if (!(exception instanceof ParameterException)) {
                        SetupDiagnosticReporter.reportSetupLocalFailure(exception, args, input, out, err);
                    }
                    return 2;
                })
                .setParameterExceptionHandler(usageErrors());
        return commandLine.execute(args);
    }

    @SafeVarargs
    private static List<String> optionNames(List<String>... groups) {
        List<String> names = new ArrayList<>();
        for (List<String> group : groups) {
            names.addAll(group);
        }
        return List.copyOf(names);
    }

    static void hideUnsupportedSubcommandOptions(CommandLine commandLine) {
        hideUnsupportedOptions(commandLine.getSubcommands().get("check"), CHECK_UNSUPPORTED_OPTIONS);
        hideUnsupportedOptions(commandLine.getSubcommands().get("repair-port"), REPAIR_PORT_UNSUPPORTED_OPTIONS);
        hideUnsupportedOptions(
                commandLine.getSubcommands().get("configure-github"), CONFIGURE_GITHUB_UNSUPPORTED_OPTIONS);
    }

    private static void hideUnsupportedOptions(CommandLine commandLine, List<String> optionNames) {
        CommandSpec commandSpec = commandLine.getCommandSpec();
        for (String optionName : optionNames) {
            OptionSpec option = commandSpec.optionsMap().get(optionName);
            if (option == null) {
                continue;
            }
            CommandSpec owner = option.command();
            owner.remove(option);
            owner.addOption(OptionSpec.builder(option).hidden(true).build());
        }
    }

    static void printExecutionFailure(PrintStream err, Exception exception, String errorCode) {
        err.println("setup_failed code=%s message=%s".formatted(errorCode, exception.getMessage()));
        SetupDiagnosticReporter.userActionHint(exception).ifPresent(hint -> err.println("Next step: " + hint));
    }

    static IParameterExceptionHandler usageErrors() {
        return (ParameterException exception, String[] ignored) -> {
            CommandLine commandLine = exception.getCommandLine();
            commandLine.getErr().println("setup_failed code=setup_invalid_arguments message=" + exception.getMessage());
            commandLine.getErr().println("Try '" + commandLine.getCommandName() + " --help' for usage.");
            return 2;
        };
    }

    private static String errorCode(Exception exception) {
        if (exception instanceof ParameterException) {
            return "setup_invalid_arguments";
        }
        return exception instanceof TrelloBoardSetupException setupException
                ? setupException.code()
                : "setup_local_failed";
    }

    @Command(
            name = "setup-local",
            description = "Set up local Trello-driven Codex automation.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true,
            subcommands = {CheckCommand.class, RepairPortCommand.class, ConfigureGithubCommand.class})
    static final class SetupLocalCommand implements Callable<Integer> {
        private final LocalSetup setup;
        private final BufferedReader input;
        private final PrintStream out;
        private final PrintStream err;

        @Mixin
        CommonOptions common = new CommonOptions();

        SetupLocalCommand(LocalSetup setup, BufferedReader input, PrintStream out, PrintStream err) {
            this.setup = setup;
            this.input = input;
            this.out = out;
            this.err = err;
        }

        @Override
        public Integer call() {
            return setup.run(common.request(Action.SETUP), input, out, err);
        }
    }

    @Command(
            name = "check",
            description = "Check prerequisites and connected Trello boards.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class CheckCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        SetupLocalCommand parent;

        @Mixin
        CommonOptions common = new CommonOptions();

        @Override
        public Integer call() {
            return parent.setup.run(
                    parent.common.merge(common).request(Action.CHECK), parent.input, parent.out, parent.err);
        }
    }

    @Command(
            name = "repair-port",
            description = "Move one connected board to a free local HTTP port.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class RepairPortCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        SetupLocalCommand parent;

        @Mixin
        CommonOptions common = new CommonOptions();

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            CommonOptions options = parent.common.merge(common);
            LocalSetupRequest request = options.request(Action.REPAIR_PORT);
            if (options.board.isEmpty()) {
                throw new ParameterException(spec.commandLine(), "Missing required option: '--board=<board>'");
            }
            return parent.setup.run(request, parent.input, parent.out, parent.err);
        }
    }

    @Command(
            name = "configure-github",
            description = "Add GitHub pull-request flow to a connected Trello board.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class ConfigureGithubCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        SetupLocalCommand parent;

        @Mixin
        CommonOptions common = new CommonOptions();

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            if (parent.common.explicitNoGithub() || common.explicitNoGithub()) {
                throw new ParameterException(spec.commandLine(), "--no-github cannot be used with configure-github.");
            }
            CommonOptions options = parent.common.merge(common);
            options.githubMode = Optional.of(true);
            return parent.setup.run(options.request(Action.CONFIGURE_GITHUB), parent.input, parent.out, parent.err);
        }
    }

    static final class CommonOptions {
        @Option(names = "--dry-run", description = "Print planned setup work without changing files or Trello.")
        boolean dryRun;

        @Option(names = "--non-interactive", description = "Do not prompt; require required values up front.")
        boolean nonInteractive;

        @Option(names = "--force", description = "Overwrite the selected workflow file when needed.")
        boolean force;

        @Option(names = "--key", description = "Trello API key. Defaults to TRELLO_API_KEY or .env.")
        Optional<String> apiKey = Optional.empty();

        @Option(names = "--token", description = "Trello API token. Defaults to TRELLO_API_TOKEN or .env.")
        Optional<String> apiToken = Optional.empty();

        @Option(names = "--board-name", description = "Trello board name when creating a board.")
        Optional<String> boardName = Optional.empty();

        @Option(names = "--board", description = "Trello board URL, short link, id, or connected board name.")
        Optional<String> board = Optional.empty();

        @Option(names = "--workspace-id", description = "Trello Workspace id for a new board.")
        Optional<String> workspaceId = Optional.empty();

        @Option(names = "--active", split = ",", description = "Existing-board queued-work list name.")
        List<String> activeStates = new ArrayList<>();

        @Option(names = "--terminal", split = ",", description = "Existing-board terminal list name.")
        List<String> terminalStates = new ArrayList<>();

        @Option(names = "--in-progress", description = "Existing-board in-progress list name.")
        String inProgressState;

        @Option(names = "--no-in-progress", description = "Do not configure an in-progress list.")
        boolean noInProgress;

        @Option(names = "--blocked", description = "Existing-board blocked list name.")
        String blockedState;

        @Option(names = "--workflow", description = "Workflow file to write.")
        Optional<Path> workflowPath = Optional.empty();

        Optional<Path> workspaceRoot = Optional.empty();

        @Option(names = "--workspace-root", description = "Directory for per-card workspaces.")
        void workspaceRoot(Path workspaceRoot) {
            this.workspaceRoot = Optional.of(workspaceRoot);
        }

        @Option(names = "--config-dir", description = "Directory for local .env, workflows, and board manifest.")
        Optional<Path> configDir = Optional.empty();

        @Option(names = "--manifest", description = "Connected-board manifest path.")
        Optional<Path> manifestPath = Optional.empty();

        @Option(names = "--server-port", description = "Local HTTP status port.")
        Optional<Integer> serverPort = Optional.empty();

        @Option(names = "--max-agents", description = "Maximum cards from this board that may run at once.")
        Optional<Integer> maxAgents = Optional.empty();

        @Option(names = "--codex-model", description = "Codex model to write into generated workflows.")
        Optional<String> codexModel = Optional.empty();

        @Option(
                names = "--codex-reasoning-effort",
                description = "Codex reasoning effort to write into generated workflows.")
        Optional<String> codexReasoningEffort = Optional.empty();

        @Option(names = "--env", description = "Ignored dotenv file for Trello credentials.")
        Optional<Path> envPath = Optional.empty();

        @Option(names = "--add-path", split = ",", description = "Allow sandboxed card runs to use this path.")
        List<Path> additionalWritableRoots = new ArrayList<>();

        @Option(names = "--allow-all-paths", description = "Allow --add-path / in non-interactive setup.")
        boolean allowAllPaths;

        @Option(
                names = "--danger-full-access",
                description = "Disable Codex command/filesystem sandbox for this workflow.")
        boolean dangerFullAccess;

        @Option(names = "--no-start", description = "Do not start the managed worker after setup.")
        boolean noStart;

        @Option(names = "--endpoint", description = "Trello API endpoint.")
        URI endpoint = TrelloBoardSetup.DEFAULT_ENDPOINT;

        @Mixin
        GitHubMode github = new GitHubMode();

        Optional<Boolean> githubMode = Optional.empty();

        CommonOptions merge(CommonOptions child) {
            return OptionMerger.merge(this, child);
        }

        LocalSetupRequest request(Action action) {
            validateLifecycleSharedOptions(action);
            rejectUnsupportedOptions(action);
            validateCliPaths();
            boardName.ifPresent(value -> CliInputValidation.rejectControlCharacters("--board-name", value));
            CliInputValidation.rejectControlCharactersInText("--board", board);
            CliInputValidation.rejectControlCharactersInText("--workspace-id", workspaceId);
            github.validate();
            validateCodexModelOverrides();
            Optional<Boolean> resolvedGithubMode = githubMode.or(() -> github.selected());
            List<Path> writableRoots = CliValueNormalizer.nonBlankTrimmedPaths(additionalWritableRoots);
            serverPort.ifPresent(LocalPort::validateCliServerPort);
            if (maxAgents.filter(value -> value < 1).isPresent()) {
                throw new TrelloBoardSetupException("setup_invalid_max_agents", "--max-agents must be at least 1.");
            }
            if (action == Action.SETUP && board.isPresent() && boardName.isPresent()) {
                throw new TrelloBoardSetupException(
                        "setup_invalid_arguments", "--board and --board-name cannot be used together.");
            }
            if (allowAllPaths && writableRoots.stream().noneMatch(LocalSetup::isBroadAccessPathForCli)) {
                throw new TrelloBoardSetupException(
                        "setup_allow_all_paths_without_root",
                        "--allow-all-paths is only valid together with --add-path /.");
            }
            if (noInProgress && inProgressState != null) {
                throw new TrelloBoardSetupException(
                        "setup_invalid_arguments", "--in-progress cannot be used with --no-in-progress.");
            }
            boolean detectInProgressState = !noInProgress && inProgressState == null;
            return new LocalSetupRequest(
                    action,
                    dryRun,
                    nonInteractive,
                    force,
                    action == Action.SETUP && hasExplicitBoardSetupRequest() && !hasCodexAccessUpdateRequest(),
                    resolvedGithubMode,
                    apiKey,
                    apiToken,
                    boardName,
                    board,
                    workspaceId,
                    CliValueNormalizer.nonBlankTrimmed(activeStates),
                    CliValueNormalizer.nonBlankTrimmed(terminalStates),
                    CliValueNormalizer.trimmedOrNull(inProgressState),
                    detectInProgressState,
                    CliValueNormalizer.trimmedOrNull(blockedState),
                    workflowPath,
                    workspaceRoot,
                    configDir,
                    manifestPath,
                    serverPort,
                    maxAgents.orElse(TrelloBoardSetup.DEFAULT_MAX_CONCURRENT_AGENTS),
                    maxAgents.isPresent(),
                    codexModel.map(String::strip).filter(value -> !value.isBlank()),
                    codexReasoningEffort.map(String::strip).filter(value -> !value.isBlank()),
                    envPath,
                    writableRoots,
                    allowAllPaths,
                    dangerFullAccess,
                    noStart,
                    TrelloApiEndpoint.normalize(endpoint));
        }

        private void validateLifecycleSharedOptions(Action action) {
            CliInputValidation.rejectBlankPath("--config-dir", configDir, "--config-dir must not be empty.");
            CliInputValidation.rejectBlankPath("--manifest", manifestPath, "--manifest must not be empty.");
            CliInputValidation.rejectControlCharacters("--config-dir", configDir);
            CliInputValidation.rejectControlCharacters("--manifest", manifestPath);
            configDir.ifPresent(path -> CliInputValidation.rejectExistingNonDirectoryPath("--config-dir", path));
            if (action == Action.REPAIR_PORT || action == Action.CONFIGURE_GITHUB) {
                CliInputValidation.rejectBlankText("--board", board);
                CliInputValidation.rejectControlCharactersInText("--board", board);
            }
        }

        private boolean hasExplicitBoardSetupRequest() {
            return boardName.isPresent() || board.isPresent();
        }

        private void rejectUnsupportedOptions(Action action) {
            List<String> unsupported =
                    switch (action) {
                        case SETUP -> List.of();
                        case CHECK -> unsupportedForCheck();
                        case REPAIR_PORT -> unsupportedForRepairPort();
                        case CONFIGURE_GITHUB -> unsupportedForConfigureGithub();
                    };
            if (!unsupported.isEmpty()) {
                throw new TrelloBoardSetupException(
                        "setup_invalid_arguments",
                        "setup-local %s does not support %s."
                                .formatted(commandName(action), String.join(", ", unsupported)));
            }
        }

        private List<String> unsupportedForCheck() {
            List<String> unsupported = new ArrayList<>();
            addIfPresent(unsupported, "--dry-run", dryRun);
            addIfPresent(unsupported, "--force", force);
            addIfPresent(unsupported, "--board-name", boardName);
            addWorkflowAndAccessOptions(unsupported);
            return unsupported;
        }

        private List<String> unsupportedForRepairPort() {
            List<String> unsupported = new ArrayList<>();
            addIfPresent(unsupported, "--force", force);
            addIfPresent(unsupported, "--key", apiKey);
            addIfPresent(unsupported, "--token", apiToken);
            addIfPresent(unsupported, "--board-name", boardName);
            addWorkflowAndAccessOptions(unsupported);
            addIfPresent(unsupported, "--endpoint", !TrelloBoardSetup.DEFAULT_ENDPOINT.equals(endpoint));
            addGithubModeIfPresent(unsupported);
            return unsupported;
        }

        private List<String> unsupportedForConfigureGithub() {
            List<String> unsupported = new ArrayList<>();
            addIfPresent(unsupported, "--dry-run", dryRun);
            addIfPresent(unsupported, "--force", force);
            addIfPresent(unsupported, "--board-name", boardName);
            addIfPresent(unsupported, "--workspace-id", workspaceId);
            addListIfPresent(unsupported, "--active", activeStates);
            addListIfPresent(unsupported, "--terminal", terminalStates);
            addIfPresent(unsupported, "--in-progress", inProgressState != null);
            addIfPresent(unsupported, "--no-in-progress", noInProgress);
            addIfPresent(unsupported, "--blocked", blockedState != null);
            addIfPresent(unsupported, "--workflow", workflowPath);
            addIfPresent(unsupported, "--workspace-root", workspaceRoot);
            addIfPresent(unsupported, "--server-port", serverPort);
            addIfPresent(unsupported, "--env", envPath);
            return unsupported;
        }

        private void addWorkflowAndAccessOptions(List<String> unsupported) {
            addIfPresent(unsupported, "--workspace-id", workspaceId);
            addListIfPresent(unsupported, "--active", activeStates);
            addListIfPresent(unsupported, "--terminal", terminalStates);
            addIfPresent(unsupported, "--in-progress", inProgressState != null);
            addIfPresent(unsupported, "--no-in-progress", noInProgress);
            addIfPresent(unsupported, "--blocked", blockedState != null);
            addIfPresent(unsupported, "--workflow", workflowPath);
            addIfPresent(unsupported, "--workspace-root", workspaceRoot);
            addIfPresent(unsupported, "--server-port", serverPort);
            addIfPresent(unsupported, "--max-agents", maxAgents);
            addIfPresent(unsupported, "--codex-model", codexModel);
            addIfPresent(unsupported, "--codex-reasoning-effort", codexReasoningEffort);
            addIfPresent(unsupported, "--env", envPath);
            addListIfPresent(unsupported, "--add-path", additionalWritableRoots);
            addIfPresent(unsupported, "--allow-all-paths", allowAllPaths);
            addIfPresent(unsupported, "--danger-full-access", dangerFullAccess);
            addIfPresent(unsupported, "--no-start", noStart);
        }

        private void addGithubModeIfPresent(List<String> unsupported) {
            githubMode
                    .or(() -> github.selected())
                    .ifPresent(selected -> unsupported.add(selected ? "--github" : "--no-github"));
        }

        private static void addIfPresent(List<String> unsupported, String optionName, Optional<?> value) {
            if (value.isPresent()) {
                unsupported.add(optionName);
            }
        }

        private static void addIfPresent(List<String> unsupported, String optionName, boolean present) {
            if (present) {
                unsupported.add(optionName);
            }
        }

        private static void addListIfPresent(List<String> unsupported, String optionName, List<?> values) {
            if (!values.isEmpty()) {
                unsupported.add(optionName);
            }
        }

        private static String commandName(Action action) {
            return switch (action) {
                case SETUP -> "setup";
                case CHECK -> "check";
                case REPAIR_PORT -> "repair-port";
                case CONFIGURE_GITHUB -> "configure-github";
            };
        }

        private void validateCliPaths() {
            CliInputValidation.rejectControlCharacters("--workflow", workflowPath);
            CliInputValidation.rejectBlankPath("--workflow", workflowPath);
            CliInputValidation.rejectBlankPath("--config-dir", configDir, "--config-dir must not be empty.");
            CliInputValidation.rejectBlankPath("--manifest", manifestPath, "--manifest must not be empty.");
            CliInputValidation.rejectControlCharacters("--workspace-root", workspaceRoot);
            CliInputValidation.rejectControlCharacters("--config-dir", configDir);
            CliInputValidation.rejectControlCharacters("--manifest", manifestPath);
            configDir.ifPresent(path -> CliInputValidation.rejectExistingNonDirectoryPath("--config-dir", path));
            TrelloCredentialStore.validateEnvPathOption(envPath);
            CliInputValidation.rejectControlCharactersInPaths("--add-path", additionalWritableRoots);
        }

        private void validateCodexModelOverrides() {
            CliInputValidation.rejectBlankText("--codex-model", codexModel);
            CliInputValidation.rejectBlankText("--codex-reasoning-effort", codexReasoningEffort);
            CliInputValidation.rejectControlCharactersInText("--codex-model", codexModel);
            CliInputValidation.rejectControlCharactersInText("--codex-reasoning-effort", codexReasoningEffort);
        }

        private boolean hasCodexAccessUpdateRequest() {
            return !additionalWritableRoots.isEmpty() || dangerFullAccess;
        }

        private boolean explicitNoGithub() {
            return githubMode
                    .or(() -> github.selected())
                    .filter(selected -> !selected)
                    .isPresent();
        }
    }

    private static final class OptionMerger {
        private OptionMerger() {}

        static CommonOptions merge(CommonOptions parent, CommonOptions child) {
            CommonOptions merged = copy(parent);
            merged.dryRun = merged.dryRun || child.dryRun;
            merged.nonInteractive = merged.nonInteractive || child.nonInteractive;
            merged.force = merged.force || child.force;
            merged.apiKey = child.apiKey.or(() -> merged.apiKey);
            merged.apiToken = child.apiToken.or(() -> merged.apiToken);
            merged.boardName = child.boardName.or(() -> merged.boardName);
            merged.board = child.board.or(() -> merged.board);
            merged.workspaceId = child.workspaceId.or(() -> merged.workspaceId);
            merged.activeStates.addAll(child.activeStates);
            merged.terminalStates.addAll(child.terminalStates);
            merged.inProgressState = child.inProgressState == null ? merged.inProgressState : child.inProgressState;
            merged.noInProgress = merged.noInProgress || child.noInProgress;
            merged.blockedState = child.blockedState == null ? merged.blockedState : child.blockedState;
            merged.workflowPath = child.workflowPath.or(() -> merged.workflowPath);
            merged.workspaceRoot = child.workspaceRoot.or(() -> merged.workspaceRoot);
            merged.configDir = child.configDir.or(() -> merged.configDir);
            merged.manifestPath = child.manifestPath.or(() -> merged.manifestPath);
            merged.serverPort = child.serverPort.or(() -> merged.serverPort);
            merged.maxAgents = child.maxAgents.or(() -> merged.maxAgents);
            merged.codexModel = child.codexModel.or(() -> merged.codexModel);
            merged.codexReasoningEffort = child.codexReasoningEffort.or(() -> merged.codexReasoningEffort);
            merged.envPath = child.envPath.or(() -> merged.envPath);
            merged.additionalWritableRoots.addAll(child.additionalWritableRoots);
            merged.allowAllPaths = merged.allowAllPaths || child.allowAllPaths;
            merged.dangerFullAccess = merged.dangerFullAccess || child.dangerFullAccess;
            merged.noStart = merged.noStart || child.noStart;
            merged.endpoint =
                    TrelloBoardSetup.DEFAULT_ENDPOINT.equals(child.endpoint) ? merged.endpoint : child.endpoint;
            merged.githubMode =
                    child.githubMode.or(() -> child.github.selected()).or(() -> merged.githubMode);
            return merged;
        }

        private static CommonOptions copy(CommonOptions source) {
            CommonOptions copy = new CommonOptions();
            copy.dryRun = source.dryRun;
            copy.nonInteractive = source.nonInteractive;
            copy.force = source.force;
            copy.apiKey = source.apiKey;
            copy.apiToken = source.apiToken;
            copy.boardName = source.boardName;
            copy.board = source.board;
            copy.workspaceId = source.workspaceId;
            copy.activeStates = new ArrayList<>(source.activeStates);
            copy.terminalStates = new ArrayList<>(source.terminalStates);
            copy.inProgressState = source.inProgressState;
            copy.noInProgress = source.noInProgress;
            copy.blockedState = source.blockedState;
            copy.workflowPath = source.workflowPath;
            copy.workspaceRoot = source.workspaceRoot;
            copy.configDir = source.configDir;
            copy.manifestPath = source.manifestPath;
            copy.serverPort = source.serverPort;
            copy.maxAgents = source.maxAgents;
            copy.codexModel = source.codexModel;
            copy.codexReasoningEffort = source.codexReasoningEffort;
            copy.envPath = source.envPath;
            copy.additionalWritableRoots = new ArrayList<>(source.additionalWritableRoots);
            copy.allowAllPaths = source.allowAllPaths;
            copy.dangerFullAccess = source.dangerFullAccess;
            copy.noStart = source.noStart;
            copy.endpoint = source.endpoint;
            copy.githubMode = source.githubMode.or(() -> source.github.selected());
            return copy;
        }
    }
}
