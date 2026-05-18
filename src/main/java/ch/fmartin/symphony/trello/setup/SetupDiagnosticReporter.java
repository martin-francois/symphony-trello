package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class SetupDiagnosticReporter {
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final int BODY_LIMIT = 4_000;
    private static final int LOG_LINE_LIMIT = 80;
    private static final int LOG_BYTE_LIMIT = 128 * 1024;
    private static final List<String> TOOL_COMMANDS = List.of(
            "git", "java", "javac", "mvn", "npm", "node", "codex", "gh", "apt-get", "sudo", "doas", "brew", "winget",
            "dnf", "yum", "pacman", "zypper", "docker");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:key|token|secret|password|api[_-]?key|api[_-]?token|oauth_consumer_key|oauth_token|authorization|bearer)[\"']?\\s*[:=]\\s*[\"']?)([^\\s,\"'}]+)");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern URL_USER_INFO = Pattern.compile("(?i)(https?://)[^\\s/@]+(?::[^\\s/@]*)?@");
    private static final Pattern GITHUB_HTTPS_URL = Pattern.compile("(?i)\\bhttps?://github\\.com/[^\\s)>'\"`]+");
    private static final Pattern GITHUB_SSH_REMOTE =
            Pattern.compile("(?i)\\b(?:ssh://)?git@github\\.com[:/][^\\s)>'\"`]+");
    private static final Pattern TRELLO_URL = Pattern.compile("https://trello\\.com/\\S+");
    private static final Pattern PATH_ASSIGNMENT =
            Pattern.compile("(?i)(\\b(?:path|file|directory|dir|workspace|config|state|home)=)([^\\r\\n]+)");
    private static final Pattern QUOTED_POSIX_PATH = Pattern.compile("([\"'`])(/[^\"'`\\r\\n]+)\\1");
    private static final Pattern QUOTED_WINDOWS_PATH = Pattern.compile("(?i)([\"'`])([A-Z]:\\\\[^\"'`\\r\\n]+)\\1");
    private static final Pattern ABSOLUTE_POSIX_PATH =
            Pattern.compile("(?<![A-Za-z0-9_.:/-])/(?:[^\\r\\n:'\"<>|]+/)*[^\\r\\n:'\"<>|]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)\\b[A-Z]:\\\\[^\\r\\n:'\"<>|]+");
    private static final Pattern TRELLO_OBJECT_ID = Pattern.compile("\\b[0-9a-f]{24}\\b", Pattern.CASE_INSENSITIVE);
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
            "--manifest",
            "--env",
            "--add-path",
            "--endpoint");

    private enum WorkflowPathResolution {
        CONFIG_DIR,
        CALLER_DIRECTORY
    }

    private final Map<String, String> environment;
    private final CommandRunner commandRunner;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private List<String> sensitiveValues = List.of();

    SetupDiagnosticReporter(Map<String, String> environment, CommandRunner commandRunner) {
        this.environment = Map.copyOf(environment);
        this.commandRunner = commandRunner;
        this.httpClient = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
        this.json = ConnectedBoardRepository.jsonMapper();
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
        return switch (setupException.code()) {
            case "setup_invalid_arguments",
                    "setup_invalid_max_agents",
                    "setup_invalid_port",
                    "setup_invalid_server_port",
                    "setup_allow_all_paths_without_root" -> false;
            default -> true;
        };
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
                    .orElse(paths.manifestPath());
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
                    .orElse(paths.manifestPath());
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
            Path report = reportDir.resolve("setup-failure-" + FILE_TIMESTAMP.format(Instant.now()) + ".md");
            Files.writeString(
                    report,
                    render(exception, args, paths, manifestPath, workflowPathResolution),
                    StandardCharsets.UTF_8);
            return Optional.of(report);
        } catch (RuntimeException | IOException ignored) {
            return Optional.empty();
        }
    }

    private String render(
            Exception exception,
            List<String> args,
            LocalWorkerPaths paths,
            Path manifestPath,
            WorkflowPathResolution workflowPathResolution)
            throws IOException {
        ConnectedBoardManifest manifest = manifest(manifestPath);
        sensitiveValues = sensitiveValues(manifest, paths, args);

        StringBuilder body = new StringBuilder();
        body.append("# Symphony for Trello Setup Failure\n\n");
        section(body, "Failure");
        line(body, "time_utc", Instant.now().toString());
        line(body, "command", sanitizeCommand(args));
        line(body, "error_code", errorCode(exception));
        line(body, "message", sanitizeExceptionMessage(exception));

        section(body, "System");
        line(body, "os_name", System.getProperty("os.name"));
        line(body, "os_version", System.getProperty("os.version"));
        line(body, "os_arch", System.getProperty("os.arch"));
        line(body, "java_version", System.getProperty("java.version"));
        line(body, "java_vendor", System.getProperty("java.vendor"));
        line(body, "container", Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv")));
        line(body, "shell", sanitize(environment.getOrDefault("SHELL", environment.getOrDefault("ComSpec", ""))));

        section(body, "Installer Context");
        appendInstallerContext(body, paths, manifestPath);

        section(body, "Tool Availability");
        appendToolStatus(body);

        section(body, "Connected Boards");
        appendManifest(body, manifest);

        section(body, "Workflow Summary");
        appendWorkflows(body, manifest, paths, args, workflowPathResolution);

        section(body, "Local Health Probes");
        appendHealthProbes(body, manifest, paths, args, workflowPathResolution);

        section(body, "Recent Logs");
        appendRecentLogs(body, paths.stateHome());

        return body.toString();
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
        if (Files.isRegularFile(context)) {
            body.append("\n```text\n");
            body.append(sanitizeInstallerContextBlock(truncate(readLenient(context), BODY_LIMIT)));
            body.append("\n```\n");
        }
    }

    private void appendToolStatus(StringBuilder body) {
        body.append("| tool | status | detail |\n");
        body.append("| --- | --- | --- |\n");
        for (String tool : TOOL_COMMANDS) {
            ToolProbe probe = toolProbe(tool);
            body.append("| ")
                    .append(tool)
                    .append(" | ")
                    .append(probe.available() ? "available" : "missing")
                    .append(" | ")
                    .append(escapeTable(sanitize(probe.detail())))
                    .append(" |\n");
        }
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
                    case "codex" -> codexStatus();
                    case "gh" -> githubStatus();
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

    private String codexStatus() {
        CommandResult version = commandRunner.run("codex", "--version");
        CommandResult auth = commandRunner.run("codex", "login", "status");
        return firstLine(version.output()) + "; login=" + (auth.success() ? "ok" : "not-ok");
    }

    private String githubStatus() {
        CommandResult version = commandRunner.run("gh", "--version");
        CommandResult auth = commandRunner.run("gh", "auth", "status");
        return firstLine(version.output()) + "; auth=" + (auth.success() ? "ok" : "not-ok");
    }

    private ConnectedBoardManifest manifest(Path manifestPath) {
        try {
            return new ConnectedBoardRepository(manifestPath, json).load();
        } catch (IOException | RuntimeException ignored) {
            return new ConnectedBoardManifest(List.of());
        }
    }

    private void appendManifest(StringBuilder body, ConnectedBoardManifest manifest) {
        line(body, "board_count", manifest.boards().size());
        body.append("\n| board_hash | key_hash | github | port | roots | danger_full_access | workflow | env |\n");
        body.append("| --- | --- | --- | ---: | ---: | --- | --- | --- |\n");
        for (ConnectedBoard board : manifest.boards()) {
            body.append("| ")
                    .append(hash(board.boardId()))
                    .append(" | ")
                    .append(hash(board.boardKey()))
                    .append(" | ")
                    .append(board.githubEnabled())
                    .append(" | ")
                    .append(board.serverPort())
                    .append(" | ")
                    .append(board.additionalWritableRoots().size())
                    .append(" | ")
                    .append(board.dangerFullAccess())
                    .append(" | ")
                    .append(escapeTable(sanitize(board.workflowPath().toString())))
                    .append(" | ")
                    .append(escapeTable(sanitize(board.envPath().toString())))
                    .append(" |\n");
        }
    }

    private void appendWorkflows(
            StringBuilder body,
            ConnectedBoardManifest manifest,
            LocalWorkerPaths paths,
            List<String> args,
            WorkflowPathResolution workflowPathResolution) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        SequencedSet<Path> workflowPaths = reportWorkflowPaths(manifest, paths, args, workflowPathResolution);
        body.append("| workflow | board_hash | port | max_agents | active | terminal | in_progress | blocked |\n");
        body.append("| --- | --- | ---: | ---: | ---: | ---: | --- | --- |\n");
        for (Path workflow : workflowPaths) {
            WorkflowListConfiguration lists = editor.listConfiguration(workflow);
            body.append("| ")
                    .append(escapeTable(sanitize(workflow.toString())))
                    .append(" | ")
                    .append(editor.boardId(workflow)
                            .map(SetupDiagnosticReporter::hash)
                            .orElse(""))
                    .append(" | ")
                    .append(editor.serverPort(workflow).map(String::valueOf).orElse(""))
                    .append(" | ")
                    .append(editor.maxAgents(workflow).map(String::valueOf).orElse(""))
                    .append(" | ")
                    .append(lists.activeStates().size())
                    .append(" | ")
                    .append(lists.terminalStates().size())
                    .append(" | ")
                    .append(lists.inProgressState().isPresent())
                    .append(" | ")
                    .append(lists.blockedState().isPresent())
                    .append(" |\n");
        }
    }

    private void appendHealthProbes(
            StringBuilder body,
            ConnectedBoardManifest manifest,
            LocalWorkerPaths paths,
            List<String> args,
            WorkflowPathResolution workflowPathResolution) {
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        SequencedSet<Integer> ports = new LinkedHashSet<>();
        manifest.boards().stream().map(ConnectedBoard::serverPort).forEach(ports::add);
        reportWorkflowPaths(manifest, paths, args, workflowPathResolution).stream()
                .map(editor::serverPort)
                .flatMap(Optional::stream)
                .forEach(ports::add);
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
            WorkflowPathResolution workflowPathResolution) {
        SequencedSet<Path> workflowPaths = new LinkedHashSet<>();
        manifest.boards().stream().map(ConnectedBoard::workflowPath).forEach(workflowPaths::add);
        workflowPathOption(args, paths.configDir(), workflowPathResolution).ifPresent(workflowPaths::add);
        workflowFilesIfReadable(paths.configDir()).forEach(workflowPaths::add);
        return workflowPaths;
    }

    private static List<Path> workflowFilesIfReadable(Path configDir) {
        try (Stream<Path> files = Files.list(configDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(SetupDiagnosticReporter::hasWorkflowFileName)
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
            line(body, "error", sanitize(e.getMessage()));
        }
    }

    private void appendRecentLogs(StringBuilder body, Path stateHome) throws IOException {
        if (!Files.isDirectory(stateHome)) {
            body.append("State directory is missing.\n");
            return;
        }
        List<Path> logs;
        try (Stream<Path> files = Files.list(stateHome)) {
            logs = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".log") || name.endsWith(".err");
                    })
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .limit(6)
                    .toList();
        }
        if (logs.isEmpty()) {
            body.append("No worker logs found.\n");
            return;
        }
        for (Path log : logs) {
            body.append("\n### `")
                    .append(escapeBackticks(sanitize(log.toString())))
                    .append("`\n\n");
            body.append("```text\n");
            body.append(sanitize(tail(log, LOG_LINE_LIMIT)));
            body.append("\n```\n");
        }
    }

    private Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private void offerGithubIssue(Path reportPath, Terminal terminal) {
        if (System.console() == null) {
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
            PrintStream out = terminal.out();
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
                    "martinfrancois/symphony-trello",
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
        sanitized = URL_USER_INFO.matcher(sanitized).replaceAll("$1<redacted>@");
        sanitized =
                GITHUB_SSH_REMOTE.matcher(sanitized).replaceAll(match -> "<github-remote:" + hash(match.group()) + ">");
        sanitized = GITHUB_HTTPS_URL.matcher(sanitized).replaceAll(match -> "<github-url:" + hash(match.group()) + ">");
        sanitized = TRELLO_URL.matcher(sanitized).replaceAll(match -> "<trello-url:" + hash(match.group()) + ">");
        sanitized = TRELLO_OBJECT_ID.matcher(sanitized).replaceAll(match -> "<id:" + hash(match.group()) + ">");
        sanitized = PATH_ASSIGNMENT.matcher(sanitized).replaceAll(match -> match.group(1) + pathToken(match.group(2)));
        sanitized = QUOTED_WINDOWS_PATH
                .matcher(sanitized)
                .replaceAll(match -> match.group(1) + pathToken(match.group(2)) + match.group(1));
        sanitized = QUOTED_POSIX_PATH
                .matcher(sanitized)
                .replaceAll(match -> match.group(1) + pathToken(match.group(2)) + match.group(1));
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll(match -> pathToken(match.group()));
        sanitized = ABSOLUTE_POSIX_PATH.matcher(sanitized).replaceAll(match -> pathToken(match.group()));
        return sanitized;
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
        if (Files.isRegularFile(context)) {
            readLenient(context).lines().forEach(line -> {
                int separator = line.indexOf('=');
                if (separator < 0) {
                    return;
                }
                String key = line.substring(0, separator).toLowerCase(Locale.ROOT);
                if ("repo_url".equals(key) || "ref".equals(key)) {
                    addValue(values, line.substring(separator + 1));
                }
            });
        }
        return values.stream().distinct().toList();
    }

    private static List<String> commandOptionValues(List<String> args) {
        List<String> values = new ArrayList<>();
        boolean captureNext = false;
        for (String arg : args) {
            if (captureNext) {
                addValue(values, arg);
                captureNext = false;
                continue;
            }
            Optional<String> option = REDACTED_COMMAND_VALUE_OPTIONS.stream()
                    .filter(redactedOption -> arg.equals(redactedOption) || arg.startsWith(redactedOption + "="))
                    .findFirst();
            if (option.isEmpty()) {
                continue;
            }
            String redactedOption = option.orElseThrow();
            if (arg.equals(redactedOption)) {
                captureNext = true;
            } else {
                addValue(values, arg.substring(redactedOption.length() + 1));
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
                continue;
            }
            Optional<String> secretOption = REDACTED_COMMAND_VALUE_OPTIONS.stream()
                    .filter(option -> arg.equals(option) || arg.startsWith(option + "="))
                    .findFirst();
            if (secretOption.isPresent()) {
                String option = secretOption.orElseThrow();
                if (arg.equals(option)) {
                    sanitizedArgs.add(arg);
                    redactNext = true;
                } else {
                    sanitizedArgs.add(option + "=<redacted>");
                }
                continue;
            }
            sanitizedArgs.add(arg);
        }
        return sanitize(String.join(" ", sanitizedArgs));
    }

    private String sanitizeExceptionMessage(Exception exception) {
        String message = sanitize(String.valueOf(exception.getMessage()));
        message = message.replaceAll(
                "(?i)(Unknown [a-z-]+ list\\(s\\): ).*(\\. Open lists: ).*", "$1<redacted>$2<redacted>");
        message = message.replaceAll("(?i)(Available Workspaces: ).*", "$1<redacted>");
        return message.replaceAll("(?i)(Open lists: ).*", "$1<redacted>");
    }

    private static boolean isNonInteractive(List<String> args) {
        return args.stream().anyMatch("--non-interactive"::equals);
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
            summary.put("board_hash", hash(String.valueOf(status.get("boardId"))));
            summary.put("configured_board_hash", hash(String.valueOf(status.get("configuredBoardId"))));
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
        addPath(paths, environment.get("SYMPHONY_TRELLO_CONFIG_DIR"));
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

    private static String pathToken(String path) {
        return "<path:" + hash(path) + ">";
    }

    private static String hash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String tail(Path path, int maxLines) {
        try {
            long size = Files.size(path);
            int bytesToRead = (int) Math.min(LOG_BYTE_LIMIT, size);
            ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.position(Math.max(0, size - bytesToRead));
                while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                    // Continue until the bounded tail buffer is filled or EOF is reached.
                }
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

    private static String readLenient(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Could not read file: " + e.getMessage();
        }
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

    private static String escapeTable(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private static String escapeBackticks(String value) {
        return value.replace("`", "'");
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

    private static String errorCode(Exception exception) {
        return exception instanceof TrelloBoardSetupException setupException
                ? setupException.code()
                : "setup_local_failed";
    }

    private record ToolProbe(boolean available, String detail) {}
}
