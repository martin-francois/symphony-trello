package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SetupDiagnosticReporter {
    private static final String CONFIG_DIR_ENV = "SYMPHONY_TRELLO_CONFIG_DIR";
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final String HTTP_SCHEME = "http://";
    private static final String HTTPS_SCHEME = "https://";
    private static final String SSH_SCHEME = "ssh://";
    private static final String GITHUB_SSH_HOST = "git@github.com";
    private static final int BODY_LIMIT = 4_000;
    private static final int LOG_LINE_LIMIT = 80;
    private static final int LOG_BYTE_LIMIT = 128 * 1024;
    private static final List<String> DIAGNOSTIC_TOOL_COMMANDS =
            List.of("git", "java", "javac", "npm", "node", "codex", "gh", "docker");
    private static final List<String> SETUP_FAILURE_TOOL_COMMANDS = List.of(
            "git", "java", "javac", "mvn", "npm", "node", "codex", "gh", "apt-get", "sudo", "doas", "brew", "winget",
            "dnf", "yum", "pacman", "zypper", "docker");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:key|token|secret|password|api[_-]?key|api[_-]?token|oauth_consumer_key|oauth_token|authorization|bearer)[\"']?\\s*[:=]\\s*[\"']?)([^\\s,\"'}]+)");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern GITHUB_TOKEN =
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]+)\\b");
    private static final Pattern OPENAI_TOKEN = Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\\b");
    private static final Pattern TRELLO_TOKEN = Pattern.compile("\\bATTA[A-Za-z0-9_-]{20,}\\b");
    private static final Pattern TRELLO_API_KEY_CONTEXT =
            Pattern.compile("(?i)\\b((?:trello\\s+(?:api\\s+)?key|api[_ -]?key)\\s*[:=]?\\s+)([0-9a-f]{32})\\b");
    private static final Pattern GITHUB_HTTPS_URL = Pattern.compile("(?i)\\bhttps?://github\\.com/[^\\s)>'\"`]+");
    private static final Pattern TRELLO_URL = Pattern.compile("https://trello\\.com/\\S+");
    private static final Pattern TRELLO_CARD_FIELD = Pattern.compile(
            "(?i)([\"']?(?:card[_-]?id|card[_-]?identifier|cardId|cardIdentifier)[\"']?\\s*[:=]\\s*[\"']?)([^\\s,\"'}]+)");
    private static final Pattern YAML_PARSER_CONTINUATION_LINE = Pattern.compile("(?m)^(\\s*\\.\\.\\.\\s+)[^\\r\\n]+$");
    private static final Pattern PATH_ASSIGNMENT =
            Pattern.compile("(?i)(\\b(?:path|file|directory|dir|workspace|config|state|home)=)([^\\r\\n]+)");
    private static final Pattern QUOTED_POSIX_PATH = Pattern.compile("([\"'`])(/[^\"'`\\r\\n]+)\\1");
    private static final Pattern QUOTED_WINDOWS_PATH = Pattern.compile("(?i)([\"'`])([A-Z]:\\\\[^\"'`\\r\\n]+)\\1");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)\\b[A-Z]:\\\\[^\\r\\n:'\"<>|]+");
    private static final Pattern TRELLO_OBJECT_ID = Pattern.compile("\\b[0-9a-f]{24}\\b", Pattern.CASE_INSENSITIVE);
    private static final CharMatcher POSIX_PATH_START = CharMatcher.is('/');
    private static final CharMatcher URL_AUTHORITY_TERMINATOR =
            CharMatcher.whitespace().or(CharMatcher.anyOf("/?#")).precomputed();
    private static final CharMatcher TOKEN_TERMINATOR =
            CharMatcher.whitespace().or(CharMatcher.anyOf(")>'\"`")).precomputed();
    private static final CharMatcher ASCII_WORD_CHARACTER = CharMatcher.inRange('a', 'z')
            .or(CharMatcher.inRange('A', 'Z'))
            .or(CharMatcher.inRange('0', '9'))
            .or(CharMatcher.is('_'))
            .precomputed();
    private static final CharMatcher PATH_LIKE_TOKEN_BOUNDARY_BLOCKER =
            ASCII_WORD_CHARACTER.or(CharMatcher.anyOf(".:/-")).precomputed();
    private static final CharMatcher POSIX_PATH_TERMINATOR =
            CharMatcher.anyOf("\r\n:'\"<>|").precomputed();
    private static final Set<String> EXPECTED_SETUP_FAILURE_CODES = Set.of(
            "setup_active_state_required",
            "setup_allow_all_paths_without_root",
            "setup_board_selection_required",
            "setup_broad_path_requires_confirmation",
            "setup_codex_auth_required",
            "setup_codex_login_failed",
            "setup_env_path_not_ignored",
            "setup_env_write_failed",
            "setup_env_value_multiline",
            "setup_github_auth_required",
            "setup_github_cli_declined",
            "setup_github_cli_install_failed",
            "setup_github_cli_required",
            "setup_github_import_list_declined",
            "setup_github_upgrade_board_required",
            "setup_github_upgrade_list_declined",
            "setup_github_upgrade_not_found",
            "setup_invalid_arguments",
            "setup_invalid_http_port_override",
            "setup_invalid_max_agents",
            "setup_invalid_path",
            "setup_invalid_port",
            "setup_invalid_server_port",
            "setup_invalid_workspace_id",
            "setup_logs_missing",
            "setup_managed_port_required",
            "setup_manifest_unavailable",
            "setup_manifest_write_failed",
            "setup_missing_api_key",
            "setup_missing_api_token",
            "setup_missing_board_id",
            "setup_missing_board_name",
            "setup_missing_trello_credentials",
            "setup_mixed_codex_access_update",
            "setup_overlapping_list_roles",
            "setup_prerequisite_missing",
            "setup_repair_board_not_found",
            "setup_repair_board_required",
            "setup_repair_port_http_override",
            "setup_server_port_conflict",
            "setup_server_port_unavailable",
            "setup_workflow_invalid",
            "setup_worker_board_already_managed",
            "setup_worker_board_ambiguous",
            "setup_worker_board_not_found",
            "setup_worker_board_required",
            "setup_worker_missing_api_key",
            "setup_worker_missing_api_token",
            "setup_worker_missing_trello_credentials",
            "setup_worker_port_in_use",
            "setup_worker_selection_conflict",
            "setup_worker_untracked",
            "setup_worker_workflow_ambiguous",
            "setup_workspace_id_required",
            "setup_workspace_required",
            "setup_workflow_codex_missing",
            "setup_workflow_exists",
            "setup_workflow_frontmatter_missing",
            "setup_workflow_unresolved_environment",
            "trello_auth_failed",
            "trello_board_closed",
            "trello_invalid_request",
            "trello_permission_denied",
            "trello_resource_not_found");
    private static final Set<String> REDACTED_COMMAND_VALUE_OPTIONS = Set.of(
            "--key",
            "--token",
            "--name",
            "--board-name",
            "--board",
            "--existing-board-id",
            "--workspace-id",
            "--active",
            "--terminal",
            "--in-progress",
            "--blocked",
            "--workflow",
            "--workspace-root",
            "--config-dir",
            "--state-home",
            "--manifest",
            "--env",
            "--output",
            "--add-path",
            "--endpoint");

    private enum WorkflowPathResolution {
        CONFIG_DIR,
        CALLER_DIRECTORY
    }

    private enum DiagnosticsSelectorKind {
        NONE,
        BOARD,
        WORKFLOW
    }

    private enum ManifestStatus {
        LOADED,
        MISSING,
        UNREADABLE
    }

    private final Map<String, String> environment;
    private final CommandRunner commandRunner;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final RecentLogLister recentLogLister;
    private final Clock clock;
    private List<String> sensitiveValues = List.of();
    private boolean deepDiagnostics;
    private DiagnosticsTokenHasher tokenHasher = DiagnosticsTokenHasher.ephemeral();

    SetupDiagnosticReporter(Map<String, String> environment, CommandRunner commandRunner) {
        this(environment, commandRunner, Files::list);
    }

    SetupDiagnosticReporter(
            Map<String, String> environment, CommandRunner commandRunner, RecentLogLister recentLogLister) {
        this(environment, commandRunner, recentLogLister, ApplicationClock.systemUtc());
    }

    SetupDiagnosticReporter(
            Map<String, String> environment,
            CommandRunner commandRunner,
            RecentLogLister recentLogLister,
            Clock clock) {
        this.environment = Map.copyOf(environment);
        this.commandRunner = commandRunner;
        this.httpClient = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
        this.json = ConnectedBoardRepository.jsonMapper();
        this.recentLogLister = recentLogLister;
        this.clock = clock;
    }

    @FunctionalInterface
    interface RecentLogLister {
        Stream<Path> list(Path stateHome) throws IOException;
    }

    static Optional<Path> reportFailure(
            Exception exception, String[] args, BufferedReader input, PrintStream out, PrintStream err) {
        return reportFailure(exception, args, input, out, err, workflowPathResolution(List.of(args)));
    }

    static Optional<Path> reportSetupLocalFailure(
            Exception exception, String[] args, BufferedReader input, PrintStream out, PrintStream err) {
        return reportFailure(exception, args, input, out, err, WorkflowPathResolution.CONFIG_DIR);
    }

    private static Optional<Path> reportFailure(
            Exception exception,
            String[] args,
            BufferedReader input,
            PrintStream out,
            PrintStream err,
            WorkflowPathResolution workflowPathResolution) {
        if (!shouldReport(exception)) {
            return Optional.empty();
        }
        SetupDiagnosticReporter reporter = new SetupDiagnosticReporter(System.getenv(), new ProcessCommandRunner());
        List<String> arguments = List.of(args);
        if (arguments.contains("--dry-run")) {
            return Optional.empty();
        }
        return reporter.reportFailure(
                exception,
                arguments,
                new StreamTerminal(input, out, err),
                !isNonInteractive(arguments),
                workflowPathResolution);
    }

    static boolean shouldReport(Exception exception) {
        if (!(exception instanceof TrelloBoardSetupException setupException)) {
            return true;
        }
        return !setupException.code().startsWith("setup_ambiguous_")
                && !setupException.code().startsWith("setup_unknown_")
                && !EXPECTED_SETUP_FAILURE_CODES.contains(setupException.code());
    }

    static Optional<String> userActionHint(Exception exception) {
        return userActionHint(exception, Optional.empty());
    }

    static Optional<String> userActionHint(Exception exception, Optional<Path> dotenvPath) {
        if (!(exception instanceof TrelloBoardSetupException setupException)) {
            return Optional.empty();
        }
        return switch (setupException.code()) {
            case "setup_missing_api_key", "setup_missing_api_token", "setup_missing_trello_credentials" ->
                Optional.of(trelloCredentialHint(
                        setupException.dotenvPath().or(() -> dotenvPath).orElseGet(LocalEnvironment::defaultDotenv)));
            case "setup_worker_missing_api_key",
                    "setup_worker_missing_api_token",
                    "setup_worker_missing_trello_credentials" ->
                Optional.of(workerCredentialHint(
                        setupException,
                        setupException.dotenvPath().or(() -> dotenvPath).orElseGet(LocalEnvironment::defaultDotenv)));
            case "setup_env_write_failed" -> Optional.of("Choose a writable .env or .env.NAME file, then rerun setup.");
            case "setup_manifest_unavailable", "setup_manifest_write_failed" ->
                Optional.of("Check that the workflow directory is writable and connected-boards.json is valid JSON.");
            case "setup_prerequisite_missing" ->
                Optional.of("Install the missing prerequisites shown above, then rerun setup.");
            case "setup_github_cli_required", "setup_github_cli_install_failed" ->
                Optional.of("Install the GitHub CLI `gh`, then rerun setup or disable GitHub integration.");
            case "setup_github_auth_required" ->
                Optional.of("Run `gh auth login`, then rerun setup or disable GitHub integration.");
            case "trello_auth_failed" ->
                Optional.of(trelloAuthFailureHint(
                        setupException,
                        setupException.dotenvPath().or(() -> dotenvPath).orElseGet(LocalEnvironment::defaultDotenv)));
            case "setup_workspace_id_required" ->
                Optional.of(
                        "Re-run with --workspace-id, or use setup-local to choose a Trello Workspace interactively.");
            case "setup_worker_workflow_ambiguous" ->
                Optional.of(
                        "Remove duplicate rows for the same workflow from connected-boards.json in the active Symphony config directory, then rerun the command.");
            default -> Optional.empty();
        };
    }

    private static String trelloCredentialHint(Path dotenvPath) {
        return "Provide Trello credentials with --key and --token, set TRELLO_API_KEY and TRELLO_API_TOKEN, or add them to this .env credential file:\n  "
                + displayPath(dotenvPath);
    }

    private static String workerCredentialHint(TrelloBoardSetupException exception, Path dotenvPath) {
        List<String> assignments = workerCredentialAssignments(exception);
        String intro = assignments.size() == 1
                ? "Set this Trello credential variable in your shell or in this .env credential file:"
                : "Set these Trello credential variables in your shell or in this .env credential file:";
        return intro + "\n" + String.join("\n", assignments) + "\nFile:\n  " + displayPath(dotenvPath);
    }

    private static List<String> workerCredentialAssignments(TrelloBoardSetupException exception) {
        String apiKeyName = exception.trelloApiKeyEnvironmentName().orElse("TRELLO_API_KEY");
        String apiTokenName = exception.trelloApiTokenEnvironmentName().orElse("TRELLO_API_TOKEN");
        return switch (exception.code()) {
            case "setup_worker_missing_api_key" -> List.of(credentialAssignment(apiKeyName, "your Trello API key"));
            case "setup_worker_missing_api_token" -> List.of(credentialAssignment(apiTokenName, "your Trello token"));
            default ->
                List.of(
                        credentialAssignment(apiKeyName, "your Trello API key"),
                        credentialAssignment(apiTokenName, "your Trello token"));
        };
    }

    private static String credentialAssignment(String name, String placeholder) {
        return "  " + name + "=<" + placeholder + ">";
    }

    private static String trelloAuthFailureHint(TrelloBoardSetupException exception, Path dotenvPath) {
        String apiKeyName = exception.trelloApiKeyEnvironmentName().orElse("TRELLO_API_KEY");
        String apiTokenName = exception.trelloApiTokenEnvironmentName().orElse("TRELLO_API_TOKEN");
        return exception
                .trelloApiKeyCredentialSource()
                .flatMap(apiKeySource -> exception
                        .trelloApiTokenCredentialSource()
                        .map(apiTokenSource -> trelloAuthFailureHint(
                                apiKeyName, apiKeySource, apiTokenName, apiTokenSource, dotenvPath)))
                .orElse("Check the Trello API key and token, then rerun the command.");
    }

    private static String trelloAuthFailureHint(
            String apiKeyName,
            TrelloBoardSetupException.TrelloCredentialSource apiKeySource,
            String apiTokenName,
            TrelloBoardSetupException.TrelloCredentialSource apiTokenSource,
            Path dotenvPath) {
        if (apiKeySource == TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT
                && apiTokenSource == TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT) {
            return "Check " + apiKeyName + " and " + apiTokenName
                    + " from the shell environment. Shell variables take precedence over the .env file passed with --env.";
        }
        if (apiKeySource == TrelloBoardSetupException.TrelloCredentialSource.DOTENV_FILE
                && apiTokenSource == TrelloBoardSetupException.TrelloCredentialSource.DOTENV_FILE) {
            return "Check " + apiKeyName + " and " + apiTokenName + " in this .env credential file:\n  "
                    + displayPath(dotenvPath);
        }
        String hint = "Check these Trello credential sources:\n"
                + credentialSourceLine(apiKeyName, "tracker.api_key", apiKeySource, dotenvPath) + "\n"
                + credentialSourceLine(apiTokenName, "tracker.api_token", apiTokenSource, dotenvPath);
        if (apiKeySource == TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT
                || apiTokenSource == TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT) {
            hint += " Shell variables take precedence over the .env file passed with --env.";
        }
        return hint;
    }

    private static String credentialSourceLine(
            String name,
            String workflowField,
            TrelloBoardSetupException.TrelloCredentialSource source,
            Path dotenvPath) {
        return switch (source) {
            case SHELL_ENVIRONMENT -> "  " + name + ": shell environment";
            case DOTENV_FILE -> "  " + name + ": .env credential file\n    " + displayPath(dotenvPath);
            case WORKFLOW_CONFIG -> "  " + workflowField + ": workflow configuration";
            case MISSING -> "  " + name + ": missing";
        };
    }

    static String displayPath(Path path) {
        // Hints are local CLI guidance, so show the concrete resolved path instead of a $HOME
        // shorthand the user would have to expand. Diagnostics reports sanitize paths separately.
        return path.toAbsolutePath().normalize().toString();
    }

    Optional<Path> reportFailure(Exception exception, LocalSetupRequest request, Terminal terminal) {
        if (!shouldReport(exception)) {
            return Optional.empty();
        }
        if (request.dryRun()) {
            return Optional.empty();
        }
        Optional<Path> reportPath = write(exception, request);
        reportPath.ifPresent(path -> {
            terminal.err().println("Troubleshooting report written: " + path);
            terminal.err().println("Review it before sharing. It is intended to omit secrets and private identifiers.");
            if (!request.nonInteractive()) {
                offerGithubIssue(path, terminal);
            }
        });
        return reportPath;
    }

    private Optional<Path> reportFailure(
            Exception exception,
            List<String> args,
            Terminal terminal,
            boolean offerIssuePrompt,
            WorkflowPathResolution workflowPathResolution) {
        Optional<Path> reportPath = write(exception, args, workflowPathResolution);
        reportPath.ifPresent(path -> {
            terminal.err().println("Troubleshooting report written: " + path);
            terminal.err().println("Review it before sharing. It is intended to omit secrets and private identifiers.");
            if (offerIssuePrompt) {
                offerGithubIssue(path, terminal);
            }
        });
        return reportPath;
    }

    Optional<Path> write(Exception exception, List<String> args) {
        return write(exception, args, workflowPathResolution(args));
    }

    String renderReport(DiagnosticsRequest request, boolean privateContext) throws IOException {
        if (privateContext) {
            DiagnosticsTokenHasher sharedTokenHasher = diagnosticsTokenHasher(request);
            return renderPrivateContext(request, Optional.of(sharedTokenHasher));
        }
        return renderDiagnostics(request);
    }

    String renderDiagnostics(DiagnosticsRequest request) throws IOException {
        return renderDiagnostics(request, Optional.empty());
    }

    private String renderDiagnostics(DiagnosticsRequest request, Optional<DiagnosticsTokenHasher> sharedTokenHasher)
            throws IOException {
        DiagnosticsContext context = diagnosticsContext(request, sharedTokenHasher);
        List<String> args = diagnosticsArguments(request, false);
        sensitiveValues = sensitiveValues(context.manifest(), context.paths(), args);
        deepDiagnostics = request.deep();

        StringBuilder body = new StringBuilder();
        body.append("# Symphony for Trello Diagnostics\n\n");
        body.append("Review this output before sharing it. It is intended to omit secrets and private identifiers.\n");

        section(body, "Command");
        line(body, "time_utc", now().toString());
        line(body, "command", sanitizeCommand(args));
        line(body, "command_context", commandContext());
        appendSelectionMetadata(body, context);
        line(body, "deep", deepDiagnostics ? "enabled" : "disabled");
        appendDiagnosticsTokenKeyStatus(body);

        section(body, "System");
        appendSystemInfo(body, true);

        section(body, "Installer Context");
        appendInstallerContext(body, context.paths(), context.manifestPath());

        section(body, "Tool Availability");
        appendToolStatus(body, DIAGNOSTIC_TOOL_COMMANDS);

        section(body, "Connected Board Manifest");
        appendManifest(
                body,
                context.selectedManifest(),
                context.manifestSnapshot().status(),
                context.paths().defaultEnvPath());

        section(body, "Workflow Summary");
        appendWorkflows(
                body,
                context.selectedManifest(),
                context.selectedWorkflowPaths(),
                context.paths().defaultEnvPath());
        appendInvalidConnectedBoardWorkflows(
                body, context.selectedManifest(), context.paths().defaultEnvPath());
        appendInvalidWorkflowFiles(
                body,
                context.selectedManifest(),
                context.selectedWorkflowPaths(),
                context.paths().defaultEnvPath());

        section(body, "Local Health Probes");
        appendHealthProbes(body, context.selectedManifest(), context.selectedWorkflowPaths());

        section(body, "Recent Logs");
        appendRecentLogs(
                body,
                context.paths().stateHome(),
                context.selected() ? Optional.of(context.selectedWorkflowPaths()) : Optional.empty());

        return body.toString();
    }

    String renderPrivateContext(DiagnosticsRequest request) throws IOException {
        return renderPrivateContext(request, Optional.empty());
    }

    private String renderPrivateContext(DiagnosticsRequest request, Optional<DiagnosticsTokenHasher> sharedTokenHasher)
            throws IOException {
        DiagnosticsContext context = diagnosticsContext(request, sharedTokenHasher);

        StringBuilder body = new StringBuilder();
        body.append("# Symphony for Trello Private Context\n\n");
        body.append("Private diagnostics context. Do not paste this output into public issues.\n");
        body.append("It may include Trello board names, board ids, board URLs, and local paths.\n");
        body.append("It does not include credential values or worker log contents.\n");

        section(body, "Command");
        line(body, "time_utc", now().toString());
        line(body, "command", String.join(" ", diagnosticsArguments(request, true)));
        line(body, "command_context", commandContext());
        appendSelectionMetadata(body, context);
        appendDiagnosticsTokenKeyStatus(body);

        section(body, "Local Paths");
        appendLocalPathIdentifiers(body, context.paths(), context.manifestPath());

        section(body, "Connected Board Identifiers");
        appendLocalManifestIdentifiers(
                body,
                context.selectedManifest(),
                context.manifestSnapshot().status(),
                context.paths().defaultEnvPath());

        section(body, "Workflow Identifiers");
        appendLocalWorkflowIdentifiers(body, context.selectedWorkflowPaths());

        section(body, "Log Identifiers");
        appendLocalLogIdentifiers(
                body, context.paths().stateHome(), context.selectedWorkflowPaths(), context.selected());

        return body.toString();
    }

    private DiagnosticsContext diagnosticsContext(
            DiagnosticsRequest request, Optional<DiagnosticsTokenHasher> sharedTokenHasher) throws IOException {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        Path manifestPath = request.manifestPath()
                .map(path -> resolveUserDataPath(path, paths.configDir()))
                .orElseGet(paths::manifestPath);
        ManifestSnapshot manifestSnapshot = manifest(manifestPath);
        ConnectedBoardManifest manifest = manifestSnapshot.manifest();
        DiagnosticsSelection selection =
                selectDiagnostics(manifest, request.board(), request.workflow(), paths.configDir());
        tokenHasher = sharedTokenHasher.orElseGet(() -> diagnosticsTokenHasher(request, paths));
        ConnectedBoardManifest selectedManifest = new ConnectedBoardManifest(selection.boards());
        boolean selected = selection.kind() != DiagnosticsSelectorKind.NONE;
        SequencedSet<Path> selectedWorkflowPaths =
                reportWorkflowPaths(selectedManifest, selection.workflow(), paths.configDir(), !selected);
        return new DiagnosticsContext(
                paths, manifestPath, manifestSnapshot, manifest, selection, selectedManifest, selectedWorkflowPaths);
    }

    private void appendSelectionMetadata(StringBuilder body, DiagnosticsContext context) {
        line(body, "selector", context.selection().kind().name().toLowerCase(Locale.ROOT));
        line(
                body,
                "selected_manifest_board_count",
                context.selectedManifest().boards().size());
        long readableSelectedWorkflows = context.selectedWorkflowPaths().stream()
                .filter(Files::isRegularFile)
                .count();
        line(body, "selected_workflow_file_count", readableSelectedWorkflows);
        long missingSelectedWorkflows = context.selectedWorkflowPaths().size() - readableSelectedWorkflows;
        if (missingSelectedWorkflows > 0) {
            // A requested workflow selector that is missing or not a regular file must not look
            // like an included workflow file.
            line(body, "selected_workflow_missing_count", missingSelectedWorkflows);
        }
        appendSelectionMatch(body, context.selection());
    }

    private DiagnosticsTokenHasher diagnosticsTokenHasher(DiagnosticsRequest request) {
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        return diagnosticsTokenHasher(request, paths);
    }

    private DiagnosticsTokenHasher diagnosticsTokenHasher(DiagnosticsRequest request, LocalWorkerPaths paths) {
        if (shouldPersistDiagnosticsTokenKey(request, paths)) {
            return DiagnosticsTokenHasher.load(paths.configDir());
        }
        return DiagnosticsTokenHasher.loadExisting(paths.configDir());
    }

    private boolean shouldPersistDiagnosticsTokenKey(DiagnosticsRequest request, LocalWorkerPaths paths) {
        if (!Files.isDirectory(paths.configDir())) {
            return false;
        }
        return request.configDir().map(path -> !path.toString().isBlank()).orElse(true);
    }

    private Optional<Path> write(
            Exception exception, List<String> args, WorkflowPathResolution workflowPathResolution) {
        try {
            LocalWorkerPaths paths = LocalWorkerPaths.from(
                    pathOption(args, "--app-home"),
                    pathOption(args, "--config-dir"),
                    pathOption(args, "--workspace-root"),
                    pathOption(args, "--state-home"),
                    environment);
            Path manifestPath = pathOption(args, "--manifest")
                    .map(path -> resolveUserDataPath(path, paths.configDir()))
                    .orElseGet(paths::manifestPath);
            return write(exception, args, paths, manifestPath, workflowPathResolution);
        } catch (RuntimeException | IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> write(Exception exception, LocalSetupRequest request) {
        try {
            LocalWorkerPaths paths = LocalWorkerPaths.from(
                    Optional.empty(), request.configDir(), request.workspaceRoot(), Optional.empty(), environment);
            Path manifestPath = request.manifestPath()
                    .map(path -> resolveUserDataPath(path, paths.configDir()))
                    .orElseGet(paths::manifestPath);
            return write(exception, requestArguments(request), paths, manifestPath, WorkflowPathResolution.CONFIG_DIR);
        } catch (RuntimeException | IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> write(
            Exception exception,
            List<String> args,
            LocalWorkerPaths paths,
            Path manifestPath,
            WorkflowPathResolution workflowPathResolution)
            throws IOException {
        try {
            Path reportDir = paths.stateHome().resolve("troubleshooting");
            Files.createDirectories(reportDir);
            String content = render(exception, args, paths, manifestPath, workflowPathResolution);
            return Optional.of(writeUniqueReport(reportDir, FILE_TIMESTAMP.format(now()), content));
        } catch (RuntimeException | IOException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Creates the report with CREATE_NEW and a numeric suffix so two failures in the same second
     * cannot overwrite each other's report.
     */
    /**
     * More same-second failure reports than this means something is looping; give up instead of
     * scanning the directory forever. The caller treats the failure as report-write-unavailable.
     */
    private static final int MAX_REPORT_NAME_ATTEMPTS = 100;

    private static Path writeUniqueReport(Path reportDir, String timestamp, String content) throws IOException {
        for (int attempt = 1; attempt <= MAX_REPORT_NAME_ATTEMPTS; attempt++) {
            String suffix = attempt == 1 ? "" : "-" + attempt;
            Path report = reportDir.resolve("setup-failure-" + timestamp + suffix + ".md");
            try {
                Files.writeString(report, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return report;
            } catch (FileAlreadyExistsException retry) { // NOPMD - retry with the next numeric suffix
                continue;
            }
        }
        throw new IOException("Could not choose a unique troubleshooting report name after " + MAX_REPORT_NAME_ATTEMPTS
                + " attempts in " + reportDir);
    }

    private String render(
            Exception exception,
            List<String> args,
            LocalWorkerPaths paths,
            Path manifestPath,
            WorkflowPathResolution workflowPathResolution)
            throws IOException {
        ManifestSnapshot manifestSnapshot = manifest(manifestPath);
        ConnectedBoardManifest manifest = manifestSnapshot.manifest();
        tokenHasher = DiagnosticsTokenHasher.load(paths.configDir());
        sensitiveValues = sensitiveValues(manifest, paths, args);

        StringBuilder body = new StringBuilder();
        body.append("# Symphony for Trello Setup Failure\n\n");
        section(body, "Failure");
        line(body, "time_utc", now().toString());
        line(body, "command", sanitizeCommand(args));
        line(body, "command_context", commandContext());
        line(body, "error_code", errorCode(exception));
        line(body, "message", sanitizeExceptionMessage(exception));
        appendDiagnosticsTokenKeyStatus(body);

        section(body, "System");
        appendSystemInfo(body, false);

        section(body, "Installer Context");
        appendInstallerContext(body, paths, manifestPath);

        section(body, "Tool Availability");
        deepDiagnostics = true;
        appendToolStatus(body, SETUP_FAILURE_TOOL_COMMANDS);

        section(body, "Connected Board Manifest");
        appendManifest(body, manifest, manifestSnapshot.status(), paths.defaultEnvPath());

        section(body, "Workflow Summary");
        appendWorkflows(body, manifest, paths, args, workflowPathResolution);
        appendInvalidConnectedBoardWorkflows(body, manifest, paths.defaultEnvPath());
        appendInvalidWorkflowFiles(
                body,
                manifest,
                reportWorkflowPaths(manifest, paths, args, workflowPathResolution, true),
                paths.defaultEnvPath());

        section(body, "Local Health Probes");
        appendHealthProbes(body, manifest, paths, args, workflowPathResolution);

        section(body, "Recent Logs");
        appendRecentLogs(body, paths.stateHome());

        return body.toString();
    }

    private void appendSystemInfo(StringBuilder body, boolean includeProjectVersion) {
        if (includeProjectVersion) {
            line(body, "version", new TrelloBoardSetupMain.ProjectVersion().getVersion()[0]);
        }
        line(body, "os_name", System.getProperty("os.name"));
        line(body, "os_version", System.getProperty("os.version"));
        line(body, "os_distribution", sanitize(osDistribution()));
        line(body, "os_arch", System.getProperty("os.arch"));
        line(body, "java_version", System.getProperty("java.version"));
        line(body, "java_vendor", System.getProperty("java.vendor"));
        line(body, "container", Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv")));
        line(body, "shell", sanitize(environment.getOrDefault("SHELL", environment.getOrDefault("ComSpec", ""))));
    }

    private String commandContext() {
        return environment.containsKey("SYMPHONY_TRELLO_COMMAND")
                ? "effective command after installer wrapper defaults"
                : "direct command";
    }

    private static String osDistribution() {
        return Stream.of(Path.of("/etc/os-release"), Path.of("/usr/lib/os-release"))
                .map(SetupDiagnosticReporter::readOsRelease)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse("");
    }

    private static Optional<String> readOsRelease(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            return osReleaseValue(lines, "PRETTY_NAME").or(() -> osReleaseNameAndVersion(lines));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> osReleaseNameAndVersion(List<String> lines) {
        return osReleaseValue(lines, "NAME").map(name -> osReleaseValue(lines, "VERSION")
                .map(version -> name + " " + version)
                .orElse(name));
    }

    private static Optional<String> osReleaseValue(List<String> lines, String key) {
        String prefix = key + "=";
        return lines.stream()
                .filter(line -> line.startsWith(prefix))
                .map(line -> unquoteOsReleaseValue(line.substring(prefix.length())))
                .filter(value -> !value.isBlank())
                .findAny();
    }

    private static String unquoteOsReleaseValue(String value) {
        String stripped = value.strip();
        if (stripped.length() < 2) {
            return stripped;
        }
        char first = stripped.charAt(0);
        char last = stripped.charAt(stripped.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private void appendInstallerContext(StringBuilder body, LocalWorkerPaths paths, Path manifestPath) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("app_home", paths.appHome().toString());
        values.put("config_dir", paths.configDir().toString());
        values.put("workspace_root", paths.workspaceRoot().toString());
        values.put("state_home", paths.stateHome().toString());
        values.put("manifest", manifestPath.toString());
        values.put(
                "dotenv",
                environment.getOrDefault(
                        "SYMPHONY_TRELLO_DOTENV", paths.defaultEnvPath().toString()));
        values.put("caller_dir", environment.getOrDefault("SYMPHONY_TRELLO_CALLER_DIR", ""));
        values.put("repo_url", environment.getOrDefault("SYMPHONY_TRELLO_REPO_URL", ""));
        values.put("ref", environment.getOrDefault("SYMPHONY_TRELLO_REF", ""));
        values.put("command", environment.getOrDefault("SYMPHONY_TRELLO_COMMAND", ""));
        values.put("wrapper_shell", environment.getOrDefault("SYMPHONY_TRELLO_WRAPPER_SHELL", ""));
        values.forEach((key, value) -> line(body, key, sanitizeInstallerContextValue(key, value)));
        Path context = paths.stateHome().resolve("install-context.properties");
        readInstallContextContent(context).ifPresent(content -> {
            body.append("\n```text\n");
            body.append(sanitizeInstallerContextBlock(truncate(content, BODY_LIMIT)));
            body.append("\n```\n");
        });
    }

    private void appendLocalPathIdentifiers(StringBuilder body, LocalWorkerPaths paths, Path manifestPath) {
        MarkdownTable table = MarkdownTable.of(
                List.of("name", "token", "value"),
                List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT));
        table.row("app_home", pathToken(paths.appHome().toString()), paths.appHome());
        table.row("config_dir", pathToken(paths.configDir().toString()), paths.configDir());
        table.row("workspace_root", pathToken(paths.workspaceRoot().toString()), paths.workspaceRoot());
        table.row("state_home", pathToken(paths.stateHome().toString()), paths.stateHome());
        table.row("manifest", pathToken(manifestPath.toString()), manifestPath);
        Path dotenv = Path.of(environment.getOrDefault(
                "SYMPHONY_TRELLO_DOTENV", paths.defaultEnvPath().toString()));
        table.row("dotenv", pathToken(dotenv.toString()), dotenv);
        Optional.ofNullable(environment.get("SYMPHONY_TRELLO_CALLER_DIR"))
                .filter(value -> !value.isBlank())
                .ifPresent(value -> table.row("caller_dir", pathToken(value), value));
        Optional.ofNullable(environment.get("SYMPHONY_TRELLO_COMMAND"))
                .filter(value -> !value.isBlank())
                .ifPresent(value -> table.row("command", pathToken(value), value));
        table.appendTo(body);
    }

    private void appendToolStatus(StringBuilder body, List<String> tools) {
        MarkdownTable table = MarkdownTable.of(
                List.of("tool", "status", "detail"),
                List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT));
        for (String tool : tools) {
            ToolProbe probe = toolProbe(tool);
            table.row(tool, probe.available() ? "available" : "missing", sanitize(probe.detail()));
        }
        table.appendTo(body);
    }

    private ToolProbe toolProbe(String tool) {
        CommandResult location = commandRunner.run(commandLookup(tool));
        if (!location.success()) {
            return new ToolProbe(false, "");
        }
        String detail =
                switch (tool) {
                    case "java" -> commandRunner.run("java", "-version").output();
                    case "javac" -> commandRunner.run("javac", "-version").output();
                    case "codex" -> codexStatus(deepDiagnostics);
                    case "gh" -> githubStatus(deepDiagnostics);
                    default -> commandRunner.run(tool, "--version").output();
                };
        return new ToolProbe(true, firstLine(detail));
    }

    private static String[] commandLookup(String tool) {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return new String[] {"cmd", "/c", "where", tool};
        }
        return new String[] {"sh", "-c", "command -v -- \"$1\"", "sh", tool};
    }

    private String codexStatus(boolean deepDiagnostics) {
        CommandResult version = commandRunner.run("codex", "--version");
        if (!deepDiagnostics) {
            return firstLine(version.output()) + "; login=not-probed";
        }
        CommandResult auth = commandRunner.run("codex", "login", "status");
        return firstLine(version.output()) + "; login=" + (auth.success() ? "ok" : "not-ok");
    }

    private String githubStatus(boolean deepDiagnostics) {
        CommandResult version = commandRunner.run("gh", "--version");
        if (!deepDiagnostics) {
            return firstLine(version.output()) + "; auth=not-probed";
        }
        CommandResult auth = commandRunner.run("gh", "auth", "status");
        return firstLine(version.output()) + "; auth=" + (auth.success() ? "ok" : "not-ok");
    }

    private ManifestSnapshot manifest(Path manifestPath) {
        if (!Files.isRegularFile(manifestPath)) {
            return new ManifestSnapshot(new ConnectedBoardManifest(List.of()), ManifestStatus.MISSING);
        }
        try {
            return new ManifestSnapshot(new ConnectedBoardRepository(manifestPath, json).load(), ManifestStatus.LOADED);
        } catch (IOException | RuntimeException ignored) {
            return new ManifestSnapshot(new ConnectedBoardManifest(List.of()), ManifestStatus.UNREADABLE);
        }
    }

    private void appendManifest(
            StringBuilder body, ConnectedBoardManifest manifest, ManifestStatus status, Path defaultEnvPath) {
        line(body, "manifest_status", status.name().toLowerCase(Locale.ROOT));
        line(body, "board_count", manifest.boards().size());
        appendManifestStatusNote(body, status);
        body.append('\n');
        MarkdownTable table = MarkdownTable.of(
                List.of(
                        "board_hash",
                        "key_hash",
                        "github",
                        "port",
                        "roots",
                        "danger_full_access",
                        "workspace",
                        "workflow",
                        "env"),
                List.of(
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT));
        for (ConnectedBoard board : manifest.boards()) {
            table.row(
                    hash(board.boardId()),
                    hash(board.boardKey()),
                    board.githubEnabled(),
                    board.serverPort(),
                    board.additionalWritableRoots().size(),
                    board.dangerFullAccess(),
                    sanitize(board.workspaceRoot().toString()),
                    sanitize(board.workflowPath().toString()),
                    sanitize(effectiveEnvPath(board, defaultEnvPath).toString()));
        }
        table.appendTo(body);
    }

    private void appendLocalManifestIdentifiers(
            StringBuilder body, ConnectedBoardManifest manifest, ManifestStatus status, Path defaultEnvPath) {
        line(body, "manifest_status", status.name().toLowerCase(Locale.ROOT));
        line(body, "board_count", manifest.boards().size());
        appendManifestStatusNote(body, status);
        body.append('\n');
        MarkdownTable table = MarkdownTable.of(
                List.of(
                        "board_hash",
                        "key_hash",
                        "board_name",
                        "board_id",
                        "board_key",
                        "trello_url",
                        "port",
                        "workflow_token",
                        "workflow_path",
                        "env_token",
                        "env_path",
                        "workspace_token",
                        "workspace_root",
                        "github",
                        "danger_full_access",
                        "writable_roots"),
                List.of(
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.RIGHT));
        for (ConnectedBoard board : manifest.boards()) {
            table.row(
                    hash(board.boardId()),
                    hash(board.boardKey()),
                    board.boardName(),
                    board.boardId(),
                    board.boardKey(),
                    board.boardUrl(),
                    board.serverPort(),
                    pathToken(board.workflowPath().toString()),
                    board.workflowPath(),
                    pathToken(effectiveEnvPath(board, defaultEnvPath).toString()),
                    effectiveEnvPath(board, defaultEnvPath),
                    pathToken(board.workspaceRoot().toString()),
                    board.workspaceRoot(),
                    board.githubEnabled(),
                    board.dangerFullAccess(),
                    board.additionalWritableRoots().size());
        }
        table.appendTo(body);
    }

    private static Path effectiveEnvPath(ConnectedBoard board, Path defaultEnvPath) {
        return board.envPath() == null ? defaultEnvPath : board.envPath();
    }

    private static void appendManifestStatusNote(StringBuilder body, ManifestStatus status) {
        switch (status) {
            case LOADED -> {}
            case MISSING ->
                body.append(
                        "No connected-board manifest was found. Local workflow files may still be summarized below.\n");
            case UNREADABLE ->
                body.append(
                        "The connected-board manifest could not be read. Local workflow files may still be summarized below.\n");
        }
    }

    private DiagnosticsSelection selectDiagnostics(
            ConnectedBoardManifest manifest,
            Optional<String> selectedBoard,
            Optional<Path> selectedWorkflow,
            Path configDir) {
        CliInputValidation.rejectBlankBoardSelector(selectedBoard);
        CliInputValidation.rejectBlankWorkflowSelector(selectedWorkflow);
        if (selectedBoard.isPresent() && selectedWorkflow.isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--board and --workflow cannot be used together.");
        }
        return selectedBoard
                .map(boardSelector -> selectBoardDiagnostics(manifest, boardSelector))
                .orElseGet(() -> selectedWorkflow
                        .map(workflow -> selectWorkflowDiagnostics(manifest, workflow, configDir))
                        .orElseGet(() -> new DiagnosticsSelection(
                                DiagnosticsSelectorKind.NONE, manifest.boards(), Optional.empty())));
    }

    private static DiagnosticsSelection selectBoardDiagnostics(ConnectedBoardManifest manifest, String boardSelector) {
        List<ConnectedBoard> matches = manifest.findAllByBoard(boardSelector);
        if (matches.size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "Multiple connected boards match --board. Re-run with a board id or short link.");
        }
        return new DiagnosticsSelection(DiagnosticsSelectorKind.BOARD, matches, Optional.empty());
    }

    private DiagnosticsSelection selectWorkflowDiagnostics(
            ConnectedBoardManifest manifest, Path selectedWorkflow, Path configDir) {
        Path workflow = resolveWorkflowPathOption(selectedWorkflow, configDir, WorkflowPathResolution.CONFIG_DIR);
        rejectUnusableSelectedWorkflow(
                workflow, workflowEnvironmentResolver(manifest, workflow, configDir.resolve(".env")));
        List<ConnectedBoard> matches = manifest.findAllByWorkflow(workflow);
        if (matches.size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "Multiple connected-board rows reference --workflow. Repair connected-boards.json, then rerun the command.");
        }
        return new DiagnosticsSelection(DiagnosticsSelectorKind.WORKFLOW, matches, Optional.of(workflow));
    }

    private static void rejectUnusableSelectedWorkflow(
            Path workflow, Function<String, Optional<String>> environmentResolver) {
        WorkflowValidation validation = new WorkflowConfigEditor().diagnosticsValidation(workflow, environmentResolver);
        if (!validation.ok()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "--workflow must reference a readable workflow file with usable workflow front matter.");
        }
    }

    private static void appendSelectionMatch(StringBuilder body, DiagnosticsSelection selection) {
        switch (selection.kind()) {
            case BOARD ->
                line(body, "selected_board_matched", selection.boards().size() == 1);
            case WORKFLOW ->
                line(body, "selected_workflow_in_manifest", selection.boards().size() == 1);
            case NONE -> {}
        }
    }

    private void appendDiagnosticsTokenKeyStatus(StringBuilder body) {
        line(body, "diagnostics_token_key", tokenHasher.persisted() ? "local" : "temporary");
        if (!tokenHasher.persisted()) {
            body.append(
                    "Diagnostics tokens are stable only for this run because the local diagnostics key could not be read or written.\n");
        }
    }

    private void appendWorkflows(
            StringBuilder body,
            ConnectedBoardManifest manifest,
            LocalWorkerPaths paths,
            List<String> args,
            WorkflowPathResolution workflowPathResolution) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        SequencedSet<Path> workflowPaths =
                reportWorkflowPaths(manifest, paths, args, workflowPathResolution, !hasBoardOption(args));
        MarkdownTable table = workflowTable();
        for (Path workflow : workflowPaths) {
            appendWorkflowRow(
                    table, editor, workflow, workflowEnvironmentResolver(manifest, workflow, paths.defaultEnvPath()));
        }
        table.appendTo(body);
    }

    private void appendWorkflows(
            StringBuilder body,
            ConnectedBoardManifest manifest,
            SequencedSet<Path> workflowPaths,
            Path defaultEnvPath) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        MarkdownTable table = workflowTable();
        for (Path workflow : workflowPaths) {
            appendWorkflowRow(table, editor, workflow, workflowEnvironmentResolver(manifest, workflow, defaultEnvPath));
        }
        table.appendTo(body);
    }

    private static MarkdownTable workflowTable() {
        return MarkdownTable.of(
                List.of("workflow", "board_hash", "port", "max_agents", "active", "terminal", "in_progress", "blocked"),
                List.of(
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT));
    }

    private void appendWorkflowRow(
            MarkdownTable table,
            WorkflowConfigEditor editor,
            Path workflow,
            Function<String, Optional<String>> environmentResolver) {
        WorkflowListConfiguration lists = editor.listConfiguration(workflow);
        table.row(
                sanitize(workflow.toString()),
                editor.boardId(workflow, environmentResolver).map(this::hash).orElse(""),
                editor.serverPortSetting(workflow, environmentResolver).diagnosticsCell(),
                editor.maxAgentsSetting(workflow).diagnosticsCell(),
                lists.activeStatesDiagnosticsCell(),
                lists.terminalStatesDiagnosticsCell(),
                lists.inProgressState().isPresent(),
                lists.blockedState().isPresent());
    }

    private void appendInvalidWorkflowFiles(
            StringBuilder body,
            ConnectedBoardManifest manifest,
            SequencedSet<Path> workflowPaths,
            Path defaultEnvPath) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        List<InvalidWorkflowFile> invalidWorkflows = workflowPaths.stream()
                .map(workflow -> editor.diagnosticsWarning(
                                workflow, workflowEnvironmentResolver(manifest, workflow, defaultEnvPath))
                        .map(warning -> new InvalidWorkflowFile(workflow, warning)))
                .flatMap(Optional::stream)
                .toList();
        if (invalidWorkflows.isEmpty()) {
            return;
        }

        section(body, "Invalid Workflow Files");
        line(body, "invalid_workflow_count", invalidWorkflows.size());
        MarkdownTable table = MarkdownTable.of(
                List.of("workflow", "problem"), List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT));
        invalidWorkflows.forEach(workflow -> table.row(sanitize(workflow.path().toString()), workflow.warning()));
        table.appendTo(body);
    }

    private void appendInvalidConnectedBoardWorkflows(
            StringBuilder body, ConnectedBoardManifest manifest, Path defaultEnvPath) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        List<InvalidConnectedBoardWorkflow> invalidWorkflows = manifest.boards().stream()
                .map(board -> invalidConnectedBoardWorkflow(editor, board, defaultEnvPath))
                .flatMap(Optional::stream)
                .toList();
        if (invalidWorkflows.isEmpty()) {
            return;
        }

        section(body, "Invalid Connected Board Workflows");
        line(body, "invalid_connected_board_workflow_count", invalidWorkflows.size());
        MarkdownTable table = MarkdownTable.of(
                List.of("board_hash", "workflow", "problem"),
                List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT));
        invalidWorkflows.forEach(workflow -> table.row(
                hash(workflow.board().boardId()),
                sanitize(workflow.board().workflowPath().toString()),
                workflow.problem()));
        table.appendTo(body);
    }

    private Optional<InvalidConnectedBoardWorkflow> invalidConnectedBoardWorkflow(
            WorkflowConfigEditor editor, ConnectedBoard board, Path defaultEnvPath) {
        WorkflowValidation validation =
                editor.validate(board, workflowEnvironmentResolver(board.envPath(), defaultEnvPath));
        if (validation.ok()) {
            return Optional.empty();
        }
        String problem =
                Files.isRegularFile(board.workflowPath()) ? "unusable workflow configuration" : "missing workflow file";
        return Optional.of(new InvalidConnectedBoardWorkflow(board, problem));
    }

    private void appendLocalWorkflowIdentifiers(StringBuilder body, SequencedSet<Path> workflowPaths) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        MarkdownTable table = MarkdownTable.of(
                List.of(
                        "workflow_token",
                        "workflow_path",
                        "board_hash",
                        "board_id",
                        "port",
                        "max_agents",
                        "active",
                        "terminal",
                        "in_progress",
                        "blocked"),
                List.of(
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.RIGHT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT));
        for (Path workflow : workflowPaths) {
            WorkflowListConfiguration lists = editor.listConfiguration(workflow);
            String boardId = editor.boardId(workflow).orElse("");
            table.row(
                    pathToken(workflow.toString()),
                    workflow,
                    hash(boardId),
                    boardId,
                    editor.serverPortSetting(workflow).diagnosticsCell(),
                    editor.maxAgentsSetting(workflow).diagnosticsCell(),
                    lists.activeStatesDiagnosticsCell(),
                    lists.terminalStatesDiagnosticsCell(),
                    lists.inProgressState().isPresent(),
                    lists.blockedState().isPresent());
        }
        table.appendTo(body);
    }

    private Function<String, Optional<String>> workflowEnvironmentResolver(
            ConnectedBoardManifest manifest, Path workflowPath, Path defaultEnvPath) {
        return manifest.boards().stream()
                .filter(board -> PathsEqual.samePath(board.workflowPath(), workflowPath))
                .findAny()
                .map(board -> workflowEnvironmentResolver(board.envPath(), defaultEnvPath))
                .orElseGet(() -> WorkflowEnvironmentResolver.resolver(environment, defaultEnvPath));
    }

    private Function<String, Optional<String>> workflowEnvironmentResolver(Path envPath, Path defaultEnvPath) {
        Path resolvedEnvPath = envPath == null ? defaultEnvPath : envPath;
        return WorkflowEnvironmentResolver.resolver(environment, resolvedEnvPath);
    }

    private void appendHealthProbes(
            StringBuilder body,
            ConnectedBoardManifest manifest,
            LocalWorkerPaths paths,
            List<String> args,
            WorkflowPathResolution workflowPathResolution) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        SequencedSet<Integer> ports = probePorts(
                editor,
                manifest,
                reportWorkflowPaths(manifest, paths, args, workflowPathResolution, !hasBoardOption(args)));
        if (ports.isEmpty()) {
            body.append("No configured local ports found.\n");
            return;
        }
        for (int port : ports) {
            appendProbe(body, port, "/api/v1/local-status");
            appendProbe(body, port, "/api/v1/state");
        }
    }

    private void appendHealthProbes(
            StringBuilder body, ConnectedBoardManifest manifest, SequencedSet<Path> workflowPaths) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        SequencedSet<Integer> ports = probePorts(editor, manifest, workflowPaths);
        if (ports.isEmpty()) {
            body.append("No configured local ports found.\n");
            return;
        }
        for (int port : ports) {
            appendProbe(body, port, "/api/v1/local-status");
            appendProbe(body, port, "/api/v1/state");
        }
    }

    private static SequencedSet<Path> reportWorkflowPaths(
            ConnectedBoardManifest manifest,
            LocalWorkerPaths paths,
            List<String> args,
            WorkflowPathResolution workflowPathResolution,
            boolean scanConfigDir) {
        return reportWorkflowPaths(
                manifest,
                workflowPathOption(args, paths.configDir(), workflowPathResolution),
                paths.configDir(),
                scanConfigDir);
    }

    private static SequencedSet<Path> reportWorkflowPaths(
            ConnectedBoardManifest manifest, Optional<Path> selectedWorkflow, Path configDir, boolean scanConfigDir) {
        SequencedSet<Path> workflowPaths = new LinkedHashSet<>();
        manifest.boards().stream().map(ConnectedBoard::workflowPath).forEach(workflowPaths::add);
        selectedWorkflow.ifPresent(workflowPaths::add);
        if (scanConfigDir) {
            workflowPaths.addAll(workflowFilesIfReadable(configDir));
        }
        return workflowPaths;
    }

    private static boolean hasBoardOption(List<String> args) {
        return args.stream().anyMatch(arg -> arg.equals("--board") || arg.startsWith("--board="));
    }

    private static List<Path> workflowFilesIfReadable(Path configDir) {
        try (Stream<Path> files = Files.list(configDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(SetupDiagnosticReporter::hasWorkflowFileName)
                    .filter(Files::isRegularFile)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean hasWorkflowFileName(Path path) {
        return path.getFileName().toString().contains("WORKFLOW");
    }

    private static Optional<Path> workflowPathOption(
            List<String> args, Path configDir, WorkflowPathResolution workflowPathResolution) {
        return pathOption(args, "--workflow")
                .map(path -> resolveWorkflowPathOption(path, configDir, workflowPathResolution));
    }

    private static Optional<Path> pathOption(List<String> args, String option) {
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            if (option.equals(arg) && index + 1 < args.size()) {
                return Optional.of(Path.of(args.get(index + 1)));
            }
            if (arg.startsWith(option + "=")) {
                return Optional.of(Path.of(arg.substring(option.length() + 1)));
            }
        }
        return Optional.empty();
    }

    private static Path resolveWorkflowPathOption(
            Path path, Path configDir, WorkflowPathResolution workflowPathResolution) {
        return switch (workflowPathResolution) {
            case CONFIG_DIR -> resolveUserDataPath(path, configDir);
            case CALLER_DIRECTORY -> path.toAbsolutePath().normalize();
        };
    }

    /**
     * One effective port per board: the workflow file's server.port is current, and the manifest
     * port is only the fallback when the workflow does not declare a readable port. Unioning both
     * would probe stale historical ports after a workflow path was reused or repaired.
     */
    private static SequencedSet<Integer> probePorts(
            WorkflowConfigEditor editor, ConnectedBoardManifest manifest, SequencedSet<Path> workflowPaths) {
        SequencedSet<Integer> ports = new LinkedHashSet<>();
        for (ConnectedBoard board : manifest.boards()) {
            Optional<Integer> workflowPort =
                    board.workflowPath() == null ? Optional.empty() : editor.serverPort(board.workflowPath());
            ports.add(workflowPort.orElse(board.serverPort()));
        }
        for (Path workflow : workflowPaths) {
            boolean connected = manifest.boards().stream()
                    .anyMatch(board ->
                            board.workflowPath() != null && PathsEqual.samePath(board.workflowPath(), workflow));
            if (connected) {
                continue;
            }
            editor.serverPort(workflow).ifPresent(ports::add);
        }
        return ports;
    }

    private void appendProbe(StringBuilder body, int port, String path) {
        body.append("\n### `http://127.0.0.1:").append(port).append(path).append("`\n\n");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            line(body, "status", response.statusCode());
            body.append("\n```json\n");
            body.append(probeBody(path, response.body()));
            body.append("\n```\n");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            line(body, "status", "unavailable");
            line(body, "error", sanitize(exceptionSummary(e)));
        }
    }

    private static String exceptionSummary(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
    }

    private void appendRecentLogs(StringBuilder body, Path stateHome) {
        appendRecentLogs(body, stateHome, Optional.empty());
    }

    private void appendRecentLogs(StringBuilder body, Path stateHome, Optional<SequencedSet<Path>> selectedWorkflows) {
        if (!Files.isDirectory(stateHome)) {
            body.append("State directory is missing.\n");
            return;
        }
        Set<Path> selectedLogs = selectedWorkflows
                .map(workflows -> expectedLogFiles(stateHome, workflows))
                .orElseGet(Set::of);
        List<Path> logs;
        try (Stream<Path> files = recentLogLister.list(stateHome)) {
            logs = files.filter(SetupDiagnosticReporter::isRegularLogFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".log") || name.endsWith(".err");
                    })
                    .filter(SetupDiagnosticReporter::nonEmptyOrUnreadable)
                    .filter(path -> selectedWorkflows.isEmpty()
                            || selectedLogs.contains(path.toAbsolutePath().normalize()))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .limit(6)
                    .toList();
        } catch (IOException e) {
            body.append("Could not list recent worker logs.\n");
            return;
        }
        if (logs.isEmpty()) {
            body.append("No worker logs found.\n");
            return;
        }
        for (Path log : logs) {
            body.append("\n### `")
                    .append(escapeBackticks(sanitize(log.toString())))
                    .append("`\n\n");
            appendFencedCodeBlock(body, "text", sanitize(logTail(log, LOG_LINE_LIMIT)));
        }
    }

    private void appendLocalLogIdentifiers(
            StringBuilder body, Path stateHome, SequencedSet<Path> workflowPaths, boolean selected) {
        if (!Files.isDirectory(stateHome)) {
            body.append("State directory is missing.\n");
            return;
        }
        MarkdownTable table = MarkdownTable.of(
                List.of("path_token", "log_path", "workflow_token", "workflow_path", "stream", "exists", "has_content"),
                List.of(
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT,
                        MarkdownTable.Alignment.LEFT));
        Set<Path> rows = new LinkedHashSet<>();
        rows.addAll(expectedLogFiles(stateHome, workflowPaths));
        if (!selected) {
            rows.addAll(existingLogFiles(stateHome));
        }
        for (Path log : rows) {
            Optional<WorkflowLogMapping> mapping = workflowLogMapping(stateHome, workflowPaths, log);
            table.row(
                    pathToken(log.toString()),
                    log,
                    mapping.map(value -> pathToken(value.workflow().toString())).orElse(""),
                    mapping.map(WorkflowLogMapping::workflow)
                            .map(Path::toString)
                            .orElse(""),
                    logStream(log),
                    isRegularLogFile(log),
                    logHasContent(log));
        }
        table.appendTo(body);
    }

    private List<Path> existingLogFiles(Path stateHome) {
        try (Stream<Path> files = recentLogLister.list(stateHome)) {
            return files.filter(SetupDiagnosticReporter::isRegularLogFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".log") || name.endsWith(".err");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Optional<WorkflowLogMapping> workflowLogMapping(
            Path stateHome, SequencedSet<Path> workflowPaths, Path log) {
        return workflowPaths.stream()
                .map(workflow -> new WorkflowLogMapping(workflow, new ManagedProcessStore(stateHome).files(workflow)))
                .filter(mapping -> sameNormalizedPath(log, mapping.files().stdoutLog())
                        || sameNormalizedPath(log, mapping.files().stderrLog()))
                .findAny();
    }

    private static boolean sameNormalizedPath(Path first, Path second) {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    private static String logStream(Path log) {
        String name = PathNames.fileName(log);
        if (name.endsWith(".log")) {
            return "stdout";
        }
        if (name.endsWith(".err")) {
            return "stderr";
        }
        return "";
    }

    private static boolean logHasContent(Path log) {
        return isRegularLogFile(log) && nonEmptyOrUnreadable(log);
    }

    private static boolean nonEmptyOrUnreadable(Path path) {
        try (FileChannel channel = openLogFile(path)) {
            return channel.size() > 0;
        } catch (IOException ignored) {
            // Keep unreadable logs visible so logTail can report a sanitized read failure.
            return isRegularLogFile(path);
        }
    }

    private static boolean isRegularLogFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static Set<Path> expectedLogFiles(Path stateHome, SequencedSet<Path> workflowPaths) {
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        return workflowPaths.stream()
                .flatMap(workflow -> {
                    ManagedProcessStore.ManagedProcessFiles files = store.files(workflow);
                    return Stream.of(files.stdoutLog(), files.stderrLog());
                })
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private void offerGithubIssue(Path reportPath, Terminal terminal) {
        if (SystemConsole.current() == null) {
            return;
        }
        if (!commandRunner.run("gh", "auth", "status").success()) {
            return;
        }
        try {
            String answer =
                    terminal.readLine("GitHub CLI is authenticated. Open a GitHub issue with this report? [y/N] ");
            if (answer == null || !answer.toLowerCase(Locale.ROOT).startsWith("y")) {
                return;
            }
            String body = Files.readString(reportPath, StandardCharsets.UTF_8);
            PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
            out.println();
            out.println("Issue title:");
            out.println("Local setup failed");
            out.println();
            out.println("Issue body:");
            out.println(body);
            String confirm = terminal.readLine("Post this GitHub issue now? [y/N] ");
            if (confirm == null || !confirm.toLowerCase(Locale.ROOT).startsWith("y")) {
                return;
            }
            CommandResult result = commandRunner.run(
                    "gh",
                    "issue",
                    "create",
                    "--repo",
                    "martin-francois/symphony-trello",
                    "--title",
                    "Local setup failed",
                    "--body-file",
                    reportPath.toString());
            if (result.success()) {
                out.println(firstLine(result.output()));
            } else {
                terminal.err().println("GitHub issue creation failed: " + sanitize(firstLine(result.output())));
            }
        } catch (IOException e) {
            terminal.err().println("GitHub issue prompt failed: " + sanitize(e.getMessage()));
        }
    }

    private static List<String> requestArguments(LocalSetupRequest request) {
        List<String> args = new ArrayList<>();
        args.add("setup-local");
        switch (request.action()) {
            case CHECK -> args.add("check");
            case REPAIR_PORT -> args.add("repair-port");
            case CONFIGURE_GITHUB -> args.add("configure-github");
            case SETUP -> {}
        }
        addFlag(args, request.dryRun(), "--dry-run");
        addFlag(args, request.nonInteractive(), "--non-interactive");
        addFlag(args, request.force(), "--force");
        addFlag(args, request.forceNewSetup(), "--new-setup");
        request.githubMode().ifPresent(enabled -> args.add(enabled ? "--github" : "--no-github"));
        addOption(args, "--key", request.apiKey());
        addOption(args, "--token", request.apiToken());
        addOption(args, "--board-name", request.boardName());
        addOption(args, "--existing-board-id", request.existingBoardId());
        addOption(args, "--workspace-id", request.workspaceId());
        addOptions(args, "--active", request.activeStates());
        addOptions(args, "--terminal", request.terminalStates());
        addOption(args, "--in-progress", request.inProgressState());
        addOption(args, "--blocked", request.blockedState());
        request.workflowPath().ifPresent(path -> addOption(args, "--workflow", path.toString()));
        request.workspaceRoot().ifPresent(path -> addOption(args, "--workspace-root", path.toString()));
        request.configDir().ifPresent(path -> addOption(args, "--config-dir", path.toString()));
        request.manifestPath().ifPresent(path -> addOption(args, "--manifest", path.toString()));
        request.serverPort().ifPresent(port -> addOption(args, "--server-port", String.valueOf(port)));
        request.envPath().ifPresent(path -> addOption(args, "--env", path.toString()));
        request.additionalWritableRoots().forEach(path -> addOption(args, "--add-path", path.toString()));
        addFlag(args, request.allowAllPaths(), "--allow-all-paths");
        addFlag(args, request.dangerFullAccess(), "--danger-full-access");
        addFlag(args, request.noStart(), "--no-start");
        addOption(args, "--endpoint", request.endpoint().toString());
        return args;
    }

    private static List<String> diagnosticsArguments(DiagnosticsRequest request, boolean privateContext) {
        List<String> args = new ArrayList<>();
        args.add("diagnostics");
        addFlag(args, privateContext, "--show-private-context");
        addOption(args, "--board", request.board());
        request.output().ifPresent(path -> addOption(args, "--output", path.toString()));
        request.configDir().ifPresent(path -> addOption(args, "--config-dir", path.toString()));
        request.manifestPath().ifPresent(path -> addOption(args, "--manifest", path.toString()));
        request.workspaceRoot().ifPresent(path -> addOption(args, "--workspace-root", path.toString()));
        request.stateHome().ifPresent(path -> addOption(args, "--state-home", path.toString()));
        request.workflow().ifPresent(path -> addOption(args, "--workflow", path.toString()));
        addFlag(args, request.json(), "--json");
        addFlag(args, request.deep(), "--deep");
        return args;
    }

    private static void addFlag(List<String> args, boolean enabled, String flag) {
        if (enabled) {
            args.add(flag);
        }
    }

    private static void addOption(List<String> args, String option, Optional<String> value) {
        value.ifPresent(actual -> addOption(args, option, actual));
    }

    private static void addOption(List<String> args, String option, String value) {
        if (value != null && !value.isBlank()) {
            args.add(option);
            args.add(value);
        }
    }

    private static void addOptions(List<String> args, String option, List<String> values) {
        values.forEach(value -> addOption(args, option, value));
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String sanitized = redactSensitivePaths(value);
        for (String sensitiveValue : sensitiveValues) {
            sanitized = sanitized.replace(sensitiveValue, "<value:" + hash(sensitiveValue) + ">");
        }
        sanitized = AUTHORIZATION_HEADER.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = GITHUB_TOKEN.matcher(sanitized).replaceAll("<redacted>");
        sanitized = OPENAI_TOKEN.matcher(sanitized).replaceAll("<redacted>");
        sanitized = TRELLO_TOKEN.matcher(sanitized).replaceAll("<redacted>");
        sanitized = TRELLO_API_KEY_CONTEXT.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = YAML_PARSER_CONTINUATION_LINE.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = redactUrlUserInfo(sanitized);
        sanitized = redactGithubSshRemotes(sanitized);
        sanitized = GITHUB_HTTPS_URL.matcher(sanitized).replaceAll(match -> "<github-url:" + hash(match.group()) + ">");
        sanitized = TRELLO_URL.matcher(sanitized).replaceAll(match -> "<trello-url:" + hash(match.group()) + ">");
        sanitized = TRELLO_CARD_FIELD.matcher(sanitized).replaceAll(match -> match.group(1) + "<redacted>");
        sanitized = TRELLO_OBJECT_ID.matcher(sanitized).replaceAll(match -> "<id:" + hash(match.group()) + ">");
        sanitized = PATH_ASSIGNMENT.matcher(sanitized).replaceAll(match -> match.group(1) + pathToken(match.group(2)));
        sanitized = QUOTED_WINDOWS_PATH
                .matcher(sanitized)
                .replaceAll(match -> match.group(1) + pathToken(match.group(2)) + match.group(1));
        sanitized = QUOTED_POSIX_PATH
                .matcher(sanitized)
                .replaceAll(match -> match.group(1) + pathToken(match.group(2)) + match.group(1));
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll(match -> pathToken(match.group()));
        return CliInputValidation.safeDiagnosticsText(redactAbsolutePosixPaths(sanitized));
    }

    private String redactUrlUserInfo(String value) {
        StringBuilder redacted = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            int schemeStart = nextHttpScheme(value, cursor);
            if (schemeStart < 0) {
                redacted.append(value, cursor, value.length());
                break;
            }
            int authorityStart = schemeStart + httpSchemeLength(value, schemeStart);
            int authorityEnd = urlAuthorityEnd(value, authorityStart);
            int at = indexOf(value, '@', authorityStart, authorityEnd);
            if (at < 0) {
                redacted.append(value, cursor, authorityEnd);
            } else {
                redacted.append(value, cursor, authorityStart)
                        .append("<redacted>@")
                        .append(value, at + 1, authorityEnd);
            }
            cursor = authorityEnd;
        }
        return redacted.toString();
    }

    private int nextHttpScheme(String value, int start) {
        int maxStart = value.length() - HTTP_SCHEME.length();
        for (int index = Math.max(0, start); index <= maxStart; index++) {
            if (startsWithAsciiIgnoreCase(value, index, HTTP_SCHEME)
                    || startsWithAsciiIgnoreCase(value, index, HTTPS_SCHEME)) {
                return index;
            }
        }
        return -1;
    }

    private static int httpSchemeLength(String value, int schemeStart) {
        return startsWithAsciiIgnoreCase(value, schemeStart, HTTPS_SCHEME)
                ? HTTPS_SCHEME.length()
                : HTTP_SCHEME.length();
    }

    private static int urlAuthorityEnd(String value, int start) {
        int terminator = URL_AUTHORITY_TERMINATOR.indexIn(value, start);
        return terminator < 0 ? value.length() : terminator;
    }

    private String redactGithubSshRemotes(String value) {
        StringBuilder redacted = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            int git = indexOfIgnoreCase(value, GITHUB_SSH_HOST, cursor);
            if (git < 0) {
                redacted.append(value, cursor, value.length());
                break;
            }
            int remoteStart = githubSshRemoteStart(value, git);
            int separator = git + GITHUB_SSH_HOST.length();
            int remoteEnd = tokenEnd(value, separator + 1);
            if (!isGithubSshRemote(value, remoteStart, separator, remoteEnd)) {
                redacted.append(value, cursor, git + 1);
                cursor = git + 1;
                continue;
            }
            redacted.append(value, cursor, remoteStart)
                    .append("<github-remote:")
                    .append(hash(value.substring(remoteStart, remoteEnd)))
                    .append('>');
            cursor = remoteEnd;
        }
        return redacted.toString();
    }

    private static int githubSshRemoteStart(String value, int git) {
        int sshStart = git - SSH_SCHEME.length();
        return startsWithAsciiIgnoreCase(value, sshStart, SSH_SCHEME) && hasRegexWordBoundaryBefore(value, sshStart)
                ? sshStart
                : git;
    }

    private static boolean isGithubSshRemote(String value, int remoteStart, int separator, int remoteEnd) {
        return hasRegexWordBoundaryBefore(value, remoteStart)
                && separator < value.length()
                && (value.charAt(separator) == ':' || value.charAt(separator) == '/')
                && remoteEnd > separator + 1;
    }

    private static boolean hasRegexWordBoundaryBefore(String value, int index) {
        return index == 0 || !ASCII_WORD_CHARACTER.matches(value.charAt(index - 1));
    }

    private static int tokenEnd(String value, int start) {
        if (start >= value.length()) {
            return value.length();
        }
        int terminator = TOKEN_TERMINATOR.indexIn(value, start);
        return terminator < 0 ? value.length() : terminator;
    }

    private String redactAbsolutePosixPaths(String value) {
        StringBuilder redacted = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            int start = nextAbsolutePosixPathStart(value, cursor);
            if (start < 0) {
                redacted.append(value, cursor, value.length());
                break;
            }
            int end = absolutePosixPathEnd(value, start);
            redacted.append(value, cursor, start).append(pathToken(value.substring(start, end)));
            cursor = end;
        }
        return redacted.toString();
    }

    private static int nextAbsolutePosixPathStart(String value, int cursor) {
        int index = POSIX_PATH_START.indexIn(value, cursor);
        while (index >= 0) {
            if (isPosixPathStartBoundary(value, index)) {
                return index;
            }
            index = POSIX_PATH_START.indexIn(value, index + 1);
        }
        return -1;
    }

    private static boolean isPosixPathStartBoundary(String value, int index) {
        return index == 0 || !isPosixPathBoundaryBlocker(value.charAt(index - 1));
    }

    private static boolean isPosixPathBoundaryBlocker(char ch) {
        return PATH_LIKE_TOKEN_BOUNDARY_BLOCKER.matches(ch);
    }

    private static int absolutePosixPathEnd(String value, int start) {
        int terminator = POSIX_PATH_TERMINATOR.indexIn(value, start + 1);
        return terminator < 0 ? value.length() : terminator;
    }

    private static int indexOf(String value, char needle, int start, int end) {
        for (int index = start; index < end; index++) {
            if (value.charAt(index) == needle) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String value, String needle, int start) {
        int maxStart = value.length() - needle.length();
        for (int index = Math.max(0, start); index <= maxStart; index++) {
            if (startsWithAsciiIgnoreCase(value, index, needle)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean startsWithAsciiIgnoreCase(String value, int start, String prefix) {
        if (start < 0 || start + prefix.length() > value.length()) {
            return false;
        }
        for (int index = 0; index < prefix.length(); index++) {
            if (Ascii.toLowerCase(value.charAt(start + index)) != Ascii.toLowerCase(prefix.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private String sanitizeInstallerContextValue(String key, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "repo_url", "ref" -> "<value:" + hash(value) + ">";
            default ->
                normalizedKey.contains("token")
                                || normalizedKey.contains("secret")
                                || normalizedKey.contains("password")
                                || normalizedKey.contains("key")
                                || normalizedKey.contains("authorization")
                                || normalizedKey.contains("bearer")
                        ? "<redacted>"
                        : sanitize(value);
        };
    }

    private String sanitizeInstallerContextBlock(String context) {
        List<String> lines = context.lines()
                .map(line -> {
                    int separator = line.indexOf('=');
                    if (separator < 0) {
                        return sanitize(line);
                    }
                    String key = line.substring(0, separator);
                    String value = line.substring(separator + 1);
                    return key + "=" + sanitizeInstallerContextValue(key, value);
                })
                .toList();
        return String.join("\n", lines);
    }

    private List<String> sensitiveValues(ConnectedBoardManifest manifest, LocalWorkerPaths paths, List<String> args) {
        List<String> values = new ArrayList<>(manifest.boards().stream()
                .flatMap(board -> Stream.of(board.boardId(), board.boardKey(), board.boardName(), board.boardUrl()))
                .filter(value -> value != null && !value.isBlank())
                .toList());
        values.addAll(commandOptionValues(args));
        addValue(values, environment.get("SYMPHONY_TRELLO_REPO_URL"));
        addValue(values, environment.get("SYMPHONY_TRELLO_REF"));
        Path context = paths.stateHome().resolve("install-context.properties");
        readInstallContextContent(context).ifPresent(content -> content.lines().forEach(line -> {
            int separator = line.indexOf('=');
            if (separator < 0) {
                return;
            }
            String key = line.substring(0, separator).toLowerCase(Locale.ROOT);
            if ("repo_url".equals(key) || "ref".equals(key)) {
                addValue(values, line.substring(separator + 1));
            }
        }));
        return values.stream().distinct().toList();
    }

    private static Optional<String> readInstallContextContent(Path context) {
        if (!Files.isRegularFile(context, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (FileChannel channel = FileChannel.open(context, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(channel.size()));
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Read until the no-follow file handle is fully buffered.
            }
            buffer.flip();
            return Optional.of(StandardCharsets.UTF_8.decode(buffer).toString());
        } catch (IOException | ArithmeticException e) {
            return Optional.of("Could not read installer context file.");
        }
    }

    private static List<String> commandOptionValues(List<String> args) {
        List<String> values = new ArrayList<>();
        boolean captureNext = false;
        for (String arg : args) {
            if (captureNext) {
                addValue(values, arg);
                captureNext = false;
            } else {
                Optional<String> redactedOption = redactedCommandValueOption(arg);
                captureNext = redactedOption.map(arg::equals).orElse(false);
                redactedOption
                        .filter(option -> !arg.equals(option))
                        .map(option -> arg.substring(option.length() + 1))
                        .ifPresent(value -> addValue(values, value));
            }
        }
        return values;
    }

    private static void addValue(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String sanitizeCommand(List<String> args) {
        List<String> sanitizedArgs = new ArrayList<>();
        boolean redactNext = false;
        for (String arg : args) {
            if (redactNext) {
                sanitizedArgs.add("<redacted>");
                redactNext = false;
            } else {
                Optional<String> redactedOption = redactedCommandValueOption(arg);
                sanitizedArgs.add(redactedOption
                        .map(option -> arg.equals(option) ? arg : option + "=<redacted>")
                        .orElse(arg));
                redactNext = redactedOption.map(arg::equals).orElse(false);
            }
        }
        return sanitize(String.join(" ", sanitizedArgs));
    }

    private static Optional<String> redactedCommandValueOption(String arg) {
        return REDACTED_COMMAND_VALUE_OPTIONS.stream()
                .filter(option -> arg.equals(option) || arg.startsWith(option + "="))
                .findAny();
    }

    private String sanitizeExceptionMessage(Exception exception) {
        // Preserve the previous diagnostics text for null exception messages.
        @SuppressWarnings("IdentityConversion")
        String message = sanitize(String.valueOf(exception.getMessage()));
        message = message.replaceAll(
                "(?i)(Unknown [a-z-]+ list\\(s\\): ).*(\\. Open lists: ).*", "$1<redacted>$2<redacted>");
        message = message.replaceAll("(?i)(Available Workspaces: ).*", "$1<redacted>");
        return message.replaceAll("(?i)(Open lists: ).*", "$1<redacted>");
    }

    private static boolean isNonInteractive(List<String> args) {
        return args.contains("--non-interactive");
    }

    private static WorkflowPathResolution workflowPathResolution(List<String> args) {
        return args.stream().findFirst().filter("setup-local"::equals).isPresent()
                ? WorkflowPathResolution.CONFIG_DIR
                : WorkflowPathResolution.CALLER_DIRECTORY;
    }

    private String probeBody(String path, String body) {
        if ("/api/v1/local-status".equals(path)) {
            return localStatusBody(body);
        }
        if (!"/api/v1/state".equals(path)) {
            return sanitize(truncate(body, BODY_LIMIT));
        }
        try {
            Map<?, ?> state = json.readValue(body, Map.class);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("generatedAt", state.get("generatedAt"));
            if (state.get("counts") instanceof Map<?, ?> counts) {
                summary.put("counts", counts);
            }
            summary.put("running_count", listSize(state.get("running")));
            summary.put("retrying_count", listSize(state.get("retrying")));
            if (state.get("routing") instanceof Map<?, ?> routing) {
                summary.put(
                        "routing",
                        Map.of(
                                "activeLists", listSize(routing.get("activeLists")),
                                "terminalLists", listSize(routing.get("terminalLists")),
                                "handoffLists", listSize(routing.get("handoffLists"))));
            }
            return sanitize(truncate(json.writeValueAsString(summary), BODY_LIMIT));
        } catch (IOException | RuntimeException ignored) {
            return sanitize("{\"summary\":\"state response omitted because it was not parseable\"}");
        }
    }

    private String localStatusBody(String body) {
        try {
            Map<?, ?> status = json.readValue(body, Map.class);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("resolved_board_hash", hash(String.valueOf(status.get("boardId"))));
            summary.put("configured_board_input_hash", hash(String.valueOf(status.get("configuredBoardId"))));
            summary.put("workflow_path", sanitize(String.valueOf(status.get("workflowPath"))));
            return sanitize(truncate(json.writeValueAsString(summary), BODY_LIMIT));
        } catch (IOException | RuntimeException ignored) {
            return sanitize("{\"summary\":\"local-status response omitted because it was not parseable\"}");
        }
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private List<String> sensitivePaths() {
        List<String> paths = new ArrayList<>();
        addPath(paths, System.getProperty("user.home"));
        addPath(paths, environment.get("SYMPHONY_HOME"));
        addPath(paths, environment.get("SYMPHONY_TRELLO_APP_HOME"));
        addPath(paths, environment.get(CONFIG_DIR_ENV));
        addPath(paths, environment.get("SYMPHONY_TRELLO_WORKSPACE_ROOT"));
        addPath(paths, environment.get("SYMPHONY_TRELLO_STATE_HOME"));
        addPath(paths, environment.get("SYMPHONY_TRELLO_DOTENV"));
        return paths.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private String redactSensitivePaths(String value) {
        String redacted = value;
        for (String path : sensitivePaths()) {
            if (!path.isBlank()) {
                Pattern pathWithChildren = Pattern.compile(Pattern.quote(path) + "(?:[/\\\\][^\\r\\n\"'`<>|]*)?");
                redacted = pathWithChildren.matcher(redacted).replaceAll(match -> pathToken(match.group()));
            }
        }
        return redacted;
    }

    private static void addPath(List<String> paths, String value) {
        if (value != null && !value.isBlank()) {
            paths.add(Path.of(value).toAbsolutePath().normalize().toString());
        }
    }

    private String pathToken(String path) {
        return "<path:" + hash(path) + ">";
    }

    private static PrintStream borrowedOut(Terminal terminal) {
        // Terminal owns the stream. Diagnostics can write to it but must not close it.
        return terminal.out();
    }

    private String hash(String value) {
        return tokenHasher.token(value);
    }

    private static String logTail(Path path, int maxLines) {
        return removeQuarkusBannerLines(tail(path, maxLines));
    }

    private static String removeQuarkusBannerLines(String text) {
        List<String> lines = text.lines().toList();
        List<String> kept = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            if (isQuarkusBannerBlock(lines, index)) {
                index += 4;
            } else if (kept.isEmpty() && lines.get(index).isBlank()) {
                // Ignore leading blanks so they do not prevent startup banner stripping.
                index++;
            } else if (kept.isEmpty() && isQuarkusBannerLine(lines.get(index))) {
                // The bounded tail can start halfway through the Quarkus startup banner.
                index++;
            } else {
                kept.add(lines.get(index));
                index++;
            }
        }
        return String.join("\n", kept);
    }

    private static boolean isQuarkusBannerBlock(List<String> lines, int index) {
        return index + 3 < lines.size()
                && isQuarkusBannerLine1(lines.get(index))
                && isQuarkusBannerLine2(lines.get(index + 1))
                && isQuarkusBannerLine3(lines.get(index + 2))
                && isQuarkusBannerLine4(lines.get(index + 3));
    }

    private static boolean isQuarkusBannerLine(String line) {
        return isQuarkusBannerLine1(line)
                || isQuarkusBannerLine2(line)
                || isQuarkusBannerLine3(line)
                || isQuarkusBannerLine4(line);
    }

    private static boolean isQuarkusBannerLine1(String line) {
        return line.startsWith("__  ____");
    }

    private static boolean isQuarkusBannerLine2(String line) {
        return line.startsWith(" --/ __ \\");
    }

    private static boolean isQuarkusBannerLine3(String line) {
        return line.startsWith(" -/ /_/");
    }

    private static boolean isQuarkusBannerLine4(String line) {
        return line.startsWith("--\\___");
    }

    private static String tail(Path path, int maxLines) {
        try (FileChannel channel = openLogFile(path)) {
            long size = channel.size();
            int bytesToRead = (int) Math.min(LOG_BYTE_LIMIT, size);
            ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
            channel.position(Math.max(0, size - bytesToRead));
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Continue until the bounded tail buffer is filled or EOF is reached.
            }
            buffer.flip();
            String text = StandardCharsets.UTF_8.decode(buffer).toString();
            if (size > bytesToRead) {
                int firstLineBreak = text.indexOf('\n');
                if (firstLineBreak >= 0 && firstLineBreak + 1 < text.length()) {
                    text = text.substring(firstLineBreak + 1);
                }
            }
            List<String> lines = text.lines().toList();
            int from = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "Could not read log: " + e.getMessage();
        }
    }

    private static FileChannel openLogFile(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.lines().findFirst().orElse("").trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "\n... truncated ...";
    }

    private static String escapeBackticks(String value) {
        return value.replace("`", "'");
    }

    private static void appendFencedCodeBlock(StringBuilder body, String language, String content) {
        String fence = markdownFence(content);
        body.append(fence).append(language).append('\n');
        body.append(content);
        if (!content.endsWith("\n")) {
            body.append('\n');
        }
        body.append(fence).append('\n');
    }

    private static String markdownFence(String content) {
        int longestRun = 0;
        int currentRun = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '`') {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        return "`".repeat(Math.max(3, longestRun + 1));
    }

    private static Path resolveUserDataPath(Path path, Path configDir) {
        return path.isAbsolute() ? path.normalize() : configDir.resolve(path).normalize();
    }

    private static void section(StringBuilder body, String title) {
        body.append("\n## ").append(title).append("\n\n");
    }

    private static void line(StringBuilder body, String label, Object value) {
        body.append("- **").append(label).append(":** ").append(value).append('\n');
    }

    private Instant now() {
        return clock.instant();
    }

    private static String errorCode(Exception exception) {
        return exception instanceof TrelloBoardSetupException setupException
                ? setupException.code()
                : "setup_local_failed";
    }

    private record ToolProbe(boolean available, String detail) {}

    private record ManifestSnapshot(ConnectedBoardManifest manifest, ManifestStatus status) {}

    private record DiagnosticsSelection(
            DiagnosticsSelectorKind kind, List<ConnectedBoard> boards, Optional<Path> workflow) {}

    private record InvalidWorkflowFile(Path path, String warning) {}

    private record InvalidConnectedBoardWorkflow(ConnectedBoard board, String problem) {}

    private record DiagnosticsContext(
            LocalWorkerPaths paths,
            Path manifestPath,
            ManifestSnapshot manifestSnapshot,
            ConnectedBoardManifest manifest,
            DiagnosticsSelection selection,
            ConnectedBoardManifest selectedManifest,
            SequencedSet<Path> selectedWorkflowPaths) {
        private boolean selected() {
            return selection.kind() != DiagnosticsSelectorKind.NONE;
        }
    }

    private record WorkflowLogMapping(Path workflow, ManagedProcessStore.ManagedProcessFiles files) {}

    record DiagnosticsRequest(
            Optional<String> board,
            Optional<Path> output,
            boolean json,
            boolean deep,
            Optional<Path> appHome,
            Optional<Path> configDir,
            Optional<Path> workspaceRoot,
            Optional<Path> stateHome,
            Optional<Path> manifestPath,
            Optional<Path> workflow) {}
}
