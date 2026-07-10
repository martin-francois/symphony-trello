package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.EnvironmentReferences;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.config.TrelloListRoleValidator;
import ch.fmartin.symphony.trello.config.TypedWorkflowConfig;
import ch.fmartin.symphony.trello.config.WorkflowConfigIngestion;
import ch.fmartin.symphony.trello.config.WorkflowIntegerSetting;
import ch.fmartin.symphony.trello.config.WorkflowServerPortClassification;
import ch.fmartin.symphony.trello.workflow.CodexSandboxPolicy;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

final class WorkflowConfigEditor {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build());
    private static final TypeReference<SequencedMap<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};
    private static final Pattern FRONT_MATTER =
            Pattern.compile("\\A---\\R(?<yaml>.*?)\\R---\\R(?<body>.*)\\z", Pattern.DOTALL);
    private static final Set<PosixFilePermission> POSIX_WRITE_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

    WorkflowValidation validate(ConnectedBoard board) {
        return validate(board, LocalEnvironment::get);
    }

    WorkflowValidation validate(ConnectedBoard board, Function<String, Optional<String>> environmentResolver) {
        if (board.workflowPath() == null) {
            return WorkflowValidation.warn("Workflow path for " + DisplayNames.quotedName(board.boardName())
                    + " is missing from "
                    + ConnectedBoardManifest.FILE_NAME
                    + ".");
        }
        if (!Files.isRegularFile(board.workflowPath())) {
            return WorkflowValidation.warn("Workflow file is missing for " + DisplayNames.quotedName(board.boardName())
                    + ": " + board.workflowPath());
        }
        try {
            FrontMatter frontMatter = read(board.workflowPath());
            SequencedMap<String, Object> yaml = parseYaml(frontMatter);
            WorkflowValidation boardIdValidation = boardId(yaml, environmentResolver)
                    .map(configuredBoardId -> validateBoardId(board, configuredBoardId))
                    .orElseGet(() -> WorkflowValidation.warn("Workflow file is missing tracker.board_id for "
                            + DisplayNames.quotedName(board.boardName()) + ": " + board.workflowPath()));
            if (!boardIdValidation.ok()) {
                return boardIdValidation;
            }
            WorkflowValidation serverPortValidation = serverPort(yaml, environmentResolver)
                    .map(configuredServerPort -> validateServerPort(board, configuredServerPort))
                    .orElseGet(() -> WorkflowValidation.warn("Workflow file is missing server.port for "
                            + DisplayNames.quotedName(board.boardName()) + ": " + board.workflowPath()));
            if (!serverPortValidation.ok()) {
                return serverPortValidation;
            }
            if (invalidListRoleOverlap(yaml, frontMatter.body())) {
                return WorkflowValidation.warn("Workflow tracker list roles overlap for "
                        + DisplayNames.quotedName(board.boardName()) + ": overlapping tracker list roles");
            }
            return WorkflowValidation.valid();
        } catch (IOException | TrelloBoardSetupException e) {
            return WorkflowValidation.warn("Workflow file is not readable or has invalid YAML for "
                    + DisplayNames.quotedName(board.boardName()) + ": " + board.workflowPath() + " (" + e.getMessage()
                    + ")");
        }
    }

    private static WorkflowValidation validateBoardId(ConnectedBoard board, String configuredBoardId) {
        if (!configuredBoardId.equals(board.boardId()) && !configuredBoardId.equals(board.boardKey())) {
            return WorkflowValidation.warn("Workflow tracker.board_id does not match the connected board for "
                    + DisplayNames.quotedName(board.boardName()) + ": expected " + board.boardId() + " or "
                    + board.boardKey() + " but found " + configuredBoardId);
        }
        return WorkflowValidation.valid();
    }

    private static WorkflowValidation validateServerPort(ConnectedBoard board, int configuredServerPort) {
        if (configuredServerPort != board.serverPort()) {
            return WorkflowValidation.warn("Workflow server.port does not match the connected board for "
                    + DisplayNames.quotedName(board.boardName()) + ": expected " + board.serverPort() + " but found "
                    + configuredServerPort);
        }
        return WorkflowValidation.valid();
    }

    Optional<Integer> serverPort(Path workflowPath) {
        return serverPortSetting(workflowPath).value();
    }

    Optional<Integer> serverPort(Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        return serverPortSetting(workflowPath, environmentResolver).value();
    }

    WorkflowIntegerSetting serverPortSetting(Path workflowPath) {
        try {
            return serverPortSetting(parseYaml(read(workflowPath)));
        } catch (IOException | RuntimeException ignored) {
            return WorkflowIntegerSetting.omitted();
        }
    }

    WorkflowIntegerSetting serverPortSetting(
            Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        try {
            return serverPortSetting(parseYaml(read(workflowPath)), environmentResolver);
        } catch (IOException | RuntimeException ignored) {
            return WorkflowIntegerSetting.omitted();
        }
    }

    WorkflowServerPortClassification classifyServerPortForDiagnostics(Path workflowPath) {
        return classifyServerPortForDiagnostics(workflowPath, ignored -> Optional.empty());
    }

    /**
     * Classifies the effective workflow server.port for diagnostics health probing, resolving
     * environment references through the given resolver. A reference the resolver cannot satisfy
     * classifies as omitted: diagnostics without the board environment cannot know the real port,
     * so the manifest fallback covers it like a missing setting.
     */
    WorkflowServerPortClassification classifyServerPortForDiagnostics(
            Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        SequencedMap<String, Object> yaml;
        try {
            yaml = parseYaml(read(workflowPath));
        } catch (IOException | TrelloBoardSetupException ignored) {
            // An unreadable file or invalid front matter is already reported by the workflow
            // summary; probing falls back to the manifest port like a missing setting.
            return WorkflowServerPortClassification.unreadable();
        }
        return typedConfig(yaml, environmentResolver).serverPortClassification();
    }

    Optional<String> boardId(Path workflowPath) {
        return boardId(workflowPath, ignored -> Optional.empty());
    }

    Optional<String> boardId(Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        try {
            return boardId(parseYaml(read(workflowPath)), environmentResolver);
        } catch (IOException | TrelloBoardSetupException ignored) {
            return Optional.empty();
        }
    }

    void validateStartEnvironmentReferences(Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        validateStartEnvironmentReferences(workflowPath, environmentResolver, true);
    }

    void validateStartEnvironmentReferences(
            Path workflowPath, Function<String, Optional<String>> environmentResolver, boolean validateServerPort) {
        SequencedMap<String, Object> yaml;
        try {
            yaml = parseYaml(read(workflowPath));
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_read_failed", "Workflow file cannot be read before worker start.", e);
        } catch (TrelloBoardSetupException e) {
            // This method only runs on the start launch path, which reports every
            // workflow-content failure, including missing front matter, through the
            // setup_workflow_invalid classification with the underlying cause.
            throw invalidWorkflowForLaunch(workflowPath, e);
        }
        try {
            validateStartEnvironmentReferences(yaml, environmentResolver, validateServerPort);
        } catch (ConfigException | TrelloBoardSetupException e) {
            throw invalidWorkflowForLaunch(workflowPath, e);
        }
    }

    private void validateStartEnvironmentReferences(
            SequencedMap<String, Object> yaml,
            Function<String, Optional<String>> environmentResolver,
            boolean validateServerPort) {
        validateResolvedTextReference(yaml, "tracker", "board_id", "tracker.board_id", environmentResolver);
        if (validateServerPort) {
            validateServerPortReference(yaml, environmentResolver);
            validateLaunchServerPort(yaml, environmentResolver);
        }
    }

    /**
     * Rejects literal server.port values outside the local port range before a worker launch.
     * Environment-reference problems are reported by validateServerPortReference first, so this
     * check only flags literal values such as 70000, -1, or fractional YAML numbers.
     */
    private void validateLaunchServerPort(
            SequencedMap<String, Object> yaml, Function<String, Optional<String>> environmentResolver) {
        if (invalidServerPortSetting(yaml, environmentResolver)) {
            throw new ConfigException(
                    "invalid_server_port", "server.port must be an integer between 0 and " + LocalPort.MAX + ".");
        }
    }

    Optional<TrackerCredentialReferences> trackerCredentialReferences(Path workflowPath) {
        try {
            SequencedMap<String, Object> yaml = parseYaml(read(workflowPath));
            if (!(yaml.get("tracker") instanceof Map<?, ?> tracker)) {
                return Optional.empty();
            }
            if (!isTrelloTracker(tracker)) {
                return Optional.empty();
            }
            return Optional.of(new TrackerCredentialReferences(
                    optionalString(tracker.get("api_key")), optionalString(tracker.get("api_token"))));
        } catch (IOException | TrelloBoardSetupException ignored) {
            return Optional.empty();
        }
    }

    Optional<Integer> maxAgents(Path workflowPath) {
        return maxAgentsSetting(workflowPath).value();
    }

    TrelloBoardSetup.RepositoryDefaults repositoryDefaults(Path workflowPath) {
        try {
            SequencedMap<String, Object> yaml = parseYaml(read(workflowPath));
            if (!(yaml.get("repository") instanceof Map<?, ?> repository)) {
                return TrelloBoardSetup.RepositoryDefaults.empty();
            }
            return TrelloBoardSetup.RepositoryDefaults.preserved(
                    optionalString(repository.get("default_url")).orElse(null),
                    optionalString(repository.get("default_path")).orElse(null));
        } catch (IOException | RuntimeException ignored) {
            return TrelloBoardSetup.RepositoryDefaults.empty();
        }
    }

    WorkflowIntegerSetting maxAgentsSetting(Path workflowPath) {
        try {
            SequencedMap<String, Object> yaml = parseYaml(read(workflowPath));
            if (!(yaml.get("agent") instanceof Map<?, ?> agent)) {
                return WorkflowIntegerSetting.omitted();
            }
            if (!agent.containsKey("max_concurrent_agents")) {
                return WorkflowIntegerSetting.omitted();
            }
            return typedConfig(yaml).agentMaxConcurrentAgents();
        } catch (IOException | RuntimeException ignored) {
            return WorkflowIntegerSetting.omitted();
        }
    }

    WorkflowListConfiguration listConfiguration(Path workflowPath) {
        try {
            FrontMatter frontMatter = read(workflowPath);
            SequencedMap<String, Object> yaml = parseYaml(frontMatter);
            if (!(yaml.get("tracker") instanceof Map<?, ?> tracker)) {
                return WorkflowListConfiguration.empty();
            }
            return new WorkflowListConfiguration(
                    stringList(tracker.get("active_states")),
                    stringList(tracker.get("terminal_states")),
                    optionalString(tracker.get("in_progress_state")),
                    optionalString(tracker.get("blocked_state"))
                            .or(() -> blockedStateFromWorkflowBody(frontMatter.body())),
                    invalidListSetting(tracker, "active_states"),
                    invalidListSetting(tracker, "terminal_states"));
        } catch (IOException | RuntimeException ignored) {
            return WorkflowListConfiguration.empty();
        }
    }

    Optional<String> diagnosticsWarning(Path workflowPath) {
        return diagnosticsWarning(workflowPath, LocalEnvironment::get);
    }

    Optional<String> diagnosticsWarning(Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        WorkflowValidation validation = diagnosticsValidation(workflowPath, environmentResolver);
        return validation.ok() ? Optional.empty() : Optional.of(validation.message());
    }

    WorkflowValidation diagnosticsValidation(Path workflowPath) {
        return diagnosticsValidation(workflowPath, LocalEnvironment::get);
    }

    WorkflowValidation diagnosticsValidation(
            Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        return diagnosticsValidation(workflowPath, environmentResolver, true);
    }

    EffectiveConfig resolveLaunchConfig(Path workflowPath, Function<String, Optional<String>> environmentResolver) {
        return prepareLaunchWorkflow(workflowPath, environmentResolver, true);
    }

    EffectiveConfig prepareLaunchWorkflow(
            Path workflowPath, Function<String, Optional<String>> environmentResolver, boolean validateServerPort) {
        return prepareLaunchWorkflow(workflowPath, environmentResolver, validateServerPort, Optional.empty());
    }

    EffectiveConfig prepareLaunchWorkflow(
            Path workflowPath,
            Function<String, Optional<String>> environmentResolver,
            boolean validateServerPort,
            Path diagnosticsConfigDir) {
        return prepareLaunchWorkflow(
                workflowPath,
                environmentResolver,
                validateServerPort,
                Optional.of(diagnosticsConfigDir.toAbsolutePath().normalize()));
    }

    private EffectiveConfig prepareLaunchWorkflow(
            Path workflowPath,
            Function<String, Optional<String>> environmentResolver,
            boolean validateServerPort,
            Optional<Path> diagnosticsConfigDir) {
        requireLaunchableWorkflowFile(workflowPath);
        ReadWorkflow readWorkflow = readWorkflowForLaunch(workflowPath);
        try {
            SequencedMap<String, Object> yaml = parseYaml(readWorkflow.frontMatter());
            WorkflowDefinition workflow = new WorkflowDefinition(
                    workflowPath.toAbsolutePath().normalize(),
                    yaml,
                    readWorkflow.frontMatter().body().trim());
            EffectiveConfig config = new ConfigResolver(environmentResolver).resolve(workflow);
            validateStartEnvironmentReferences(yaml, environmentResolver, validateServerPort);
            return config;
        } catch (IOException e) {
            throw invalidWorkflowForLaunch(
                    workflowPath,
                    new TrelloBoardSetupException(
                            "setup_workflow_yaml_invalid", "Workflow front matter is invalid YAML.", e),
                    diagnosticsConfigDir);
        } catch (WorkflowException
                | ConfigException
                | TrelloBoardSetupException
                | IllegalArgumentException
                | ClassCastException e) {
            throw invalidWorkflowForLaunch(workflowPath, e, diagnosticsConfigDir);
        }
    }

    /**
     * Rejects workflow paths that cannot possibly launch, with the same expected
     * setup_workflow_invalid classification as unusable workflow content.
     */
    void requireLaunchableWorkflowFile(Path workflowPath) {
        if (Files.isRegularFile(workflowPath)) {
            return;
        }
        String problem =
                Files.exists(workflowPath) ? "workflow path is not a regular workflow file" : "missing workflow file";
        throw invalidWorkflowForLaunch(workflowPath, new ConfigException("workflow_file_unusable", problem));
    }

    private ReadWorkflow readWorkflowForLaunch(Path workflowPath) {
        try {
            return readWorkflow(workflowPath);
        } catch (IOException e) {
            throw invalidWorkflowForLaunch(
                    workflowPath,
                    new TrelloBoardSetupException(
                            "setup_workflow_read_failed", "Workflow file cannot be read before worker start.", e));
        } catch (TrelloBoardSetupException e) {
            throw invalidWorkflowForLaunch(workflowPath, e);
        }
    }

    void validateLaunchDispatch(Path workflowPath, EffectiveConfig launchConfig) {
        try {
            new ConfigResolver().validateForDispatch(launchConfig);
        } catch (ConfigException e) {
            throw invalidWorkflowForLaunch(workflowPath, e);
        }
    }

    private static TrelloBoardSetupException invalidWorkflowForLaunch(Path workflowPath, RuntimeException cause) {
        return invalidWorkflowForLaunch(workflowPath, cause, Optional.empty());
    }

    private static TrelloBoardSetupException invalidWorkflowForLaunch(
            Path workflowPath, RuntimeException cause, Optional<Path> diagnosticsConfigDir) {
        return new TrelloBoardSetupException(
                "setup_workflow_invalid",
                "Invalid workflow configuration: "
                        + publicWorkflowLaunchProblem(workflowPath, cause, diagnosticsConfigDir)
                        + "\nWorkflow file:\n  selected workflow file"
                        + "\n" + publicWorkflowLaunchNextStep(cause),
                cause);
    }

    private static String publicWorkflowLaunchProblem(
            Path workflowPath, RuntimeException cause, Optional<Path> diagnosticsConfigDir) {
        if (cause instanceof ConfigException config) {
            return publicConfigProblem(workflowPath, config, diagnosticsConfigDir);
        }
        if (cause instanceof WorkflowException workflow) {
            return publicWorkflowProblem(workflow);
        }
        if (cause instanceof TrelloBoardSetupException setup) {
            return publicSetupProblem(setup);
        }
        if (cause instanceof IllegalArgumentException || cause instanceof ClassCastException) {
            return "Workflow front matter contains invalid values.";
        }
        return "Workflow front matter is invalid.";
    }

    private static String publicConfigProblem(
            Path workflowPath, ConfigException failure, Optional<Path> diagnosticsConfigDir) {
        return switch (failure.code()) {
            case "invalid_server_port" -> "server.port must be an integer between 0 and " + LocalPort.MAX + ".";
            case "config_value_error" -> publicConfigValueProblem(failure);
            case "config_type_error" -> publicConfigTypeProblem(failure);
            case "unsupported_tracker_kind" -> "tracker.kind must be trello.";
            case "invalid_tracker_endpoint" -> "tracker.endpoint must be an absolute http(s) URL with a host.";
            case "missing_tracker_api_key" -> "tracker.api_key is required.";
            case "missing_tracker_api_token" -> "tracker.api_token is required.";
            case "missing_tracker_board_id" -> "tracker.board_id is required.";
            case "missing_codex_command" -> "codex.command is required.";
            case "overlapping_tracker_list_roles" -> "tracker list roles must use distinct Trello lists.";
            case "invalid_in_progress_state" ->
                "tracker.in_progress_state must also be listed in tracker.active_states.";
            case "secret_file_too_large" ->
                publicSecretFileProblem(diagnosticsConfigDir, failure, "secret file is too large.");
            case "secret_file_read_error" ->
                publicSecretFileProblem(diagnosticsConfigDir, failure, "secret file cannot be read.");
            case "codex_sandbox_policy_invalid" -> publicCodexSandboxPolicyProblem(failure);
            case "workflow_file_unusable" -> publicWorkflowFileProblem(failure);
            case "workflow_yaml_invalid" -> "Workflow front matter is invalid YAML.";
            default -> "Workflow configuration is invalid.";
        };
    }

    private static String publicConfigValueProblem(ConfigException failure) {
        String message = failure.getMessage();
        if (message == null) {
            return "A numeric workflow setting is invalid.";
        }
        if (message.startsWith("server.port ")
                || message.startsWith("max_concurrent_agents ")
                || message.startsWith("agent.max_concurrent_agents ")
                || message.startsWith("interval_ms ")
                || message.startsWith("request_timeout_ms ")
                || message.startsWith("api_retry_base_delay_ms ")
                || message.startsWith("max_retry_backoff_ms ")
                || message.startsWith("turn_timeout_ms ")
                || message.startsWith("read_timeout_ms ")
                || message.startsWith("stall_timeout_ms ")
                || message.startsWith("timeout_ms ")
                || message.startsWith("max_turns ")) {
            return message;
        }
        return "A numeric workflow setting is invalid.";
    }

    private static String publicConfigTypeProblem(ConfigException failure) {
        String message = failure.getMessage();
        if (message != null
                && (message.endsWith(" must be an object")
                        || message.endsWith(" must be a list")
                        || message.endsWith(" must be a string"))) {
            return message;
        }
        return "Workflow front matter has an invalid value type.";
    }

    private static String publicSecretFileProblem(
            Optional<Path> diagnosticsConfigDir, ConfigException failure, String suffix) {
        String message = failure.getMessage();
        String tokenHint = secretFileTokenHint(diagnosticsConfigDir, failure)
                .map(" "::concat)
                .orElse("");
        if (message != null && message.startsWith("tracker.api_key ")) {
            return "tracker.api_key " + suffix + tokenHint;
        }
        if (message != null && message.startsWith("tracker.api_token ")) {
            return "tracker.api_token " + suffix + tokenHint;
        }
        return "A configured secret file " + suffix + tokenHint;
    }

    private static Optional<String> secretFileTokenHint(Optional<Path> diagnosticsConfigDir, ConfigException failure) {
        return diagnosticsConfigDir.flatMap(
                configDir -> secretPathFromConfigFailure(failure).map(path -> {
                    String token = PrivateContextTokens.pathToken(configDir, path);
                    return "secret_file_token=" + token + PrivateContextTokens.lookupHint(token);
                }));
    }

    private static Optional<Path> secretPathFromConfigFailure(ConfigException failure) {
        String message = failure.getMessage();
        if (message == null) {
            return Optional.empty();
        }
        int separator = message.lastIndexOf(": ");
        if (separator < 0 || separator + 2 >= message.length()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(message.substring(separator + 2)));
        } catch (InvalidPathException ignored) {
            // If the underlying private path cannot be parsed on this host, keep the public
            // message sanitized and omit only the optional lookup token.
            return Optional.empty();
        }
    }

    private static String publicCodexSandboxPolicyProblem(ConfigException failure) {
        String message = failure.getMessage();
        if (message != null
                && (message.startsWith("codex.turn_sandbox_policy")
                        || message.startsWith("codex.additional_writable_roots"))) {
            return message;
        }
        return "codex.turn_sandbox_policy is invalid.";
    }

    private static String publicWorkflowFileProblem(ConfigException failure) {
        String message = failure.getMessage();
        if ("missing workflow file".equals(message) || "workflow path is not a regular workflow file".equals(message)) {
            return message;
        }
        return "Workflow file cannot be read.";
    }

    private static String publicWorkflowProblem(WorkflowException failure) {
        return switch (failure.code()) {
            case "missing_workflow_file" -> "Workflow file cannot be read.";
            case "workflow_front_matter_not_a_map" -> "Workflow front matter must be a YAML map.";
            case "workflow_parse_error" -> "Workflow front matter is invalid YAML.";
            default -> "Workflow front matter is invalid.";
        };
    }

    private static String publicSetupProblem(TrelloBoardSetupException failure) {
        return switch (failure.code()) {
            case "setup_workflow_frontmatter_missing" -> "Workflow has no YAML front matter.";
            case "setup_workflow_frontmatter_not_map" -> "Workflow front matter must be a YAML map.";
            case "setup_workflow_read_failed" -> "Workflow file cannot be read.";
            case "setup_workflow_yaml_invalid" -> "Workflow front matter is invalid YAML.";
            case "setup_workflow_unresolved_environment" -> publicUnresolvedEnvironmentProblem(failure);
            case "setup_invalid_server_port" ->
                "server.port must resolve to an integer between 0 and " + LocalPort.MAX + ".";
            default -> "Workflow setup state is invalid.";
        };
    }

    private static String publicUnresolvedEnvironmentProblem(TrelloBoardSetupException failure) {
        String message = failure.getMessage();
        if (message != null
                && message.startsWith("Workflow tracker.board_id references missing environment variable")) {
            return "tracker.board_id references a missing environment variable.";
        }
        if (message != null && message.startsWith("Workflow server.port references missing environment variable")) {
            return "server.port references a missing environment variable.";
        }
        return "Workflow front matter references a missing environment variable.";
    }

    private static String publicWorkflowLaunchNextStep(RuntimeException cause) {
        if (cause instanceof TrelloBoardSetupException setup && "setup_workflow_read_failed".equals(setup.code())) {
            return "Make sure the selected workflow file exists and can be read, then rerun symphony-trello start.";
        }
        return "Fix the workflow front matter, then rerun symphony-trello start.";
    }

    WorkflowValidation diagnosticsValidation(
            Path workflowPath, Function<String, Optional<String>> environmentResolver, boolean validateServerPort) {
        if (!Files.isRegularFile(workflowPath)) {
            return WorkflowValidation.warn("missing workflow file");
        }
        try {
            FrontMatter frontMatter = read(workflowPath);
            SequencedMap<String, Object> yaml = parseYaml(frontMatter);
            if (validateServerPort && invalidServerPortSetting(yaml, environmentResolver)) {
                return WorkflowValidation.warn("invalid server.port");
            }
            if (invalidListRoleOverlap(yaml, frontMatter.body())) {
                return WorkflowValidation.warn("overlapping tracker list roles");
            }
            return WorkflowValidation.valid();
        } catch (IOException | RuntimeException e) {
            return WorkflowValidation.warn("unreadable or invalid workflow configuration");
        }
    }

    WorkflowValidation diagnosticsConnectedBoardValidation(
            ConnectedBoard board, Function<String, Optional<String>> environmentResolver) {
        if (board.workflowPath() == null || !Files.isRegularFile(board.workflowPath())) {
            return WorkflowValidation.warn("missing workflow file");
        }
        try {
            FrontMatter frontMatter = read(board.workflowPath());
            SequencedMap<String, Object> yaml = parseYaml(frontMatter);
            WorkflowValidation boardIdValidation = boardId(yaml, environmentResolver)
                    .map(configuredBoardId -> validateBoardId(board, configuredBoardId))
                    .orElseGet(() -> WorkflowValidation.warn("missing tracker.board_id"));
            if (!boardIdValidation.ok()) {
                return boardIdValidation;
            }
            if (invalidServerPortSetting(yaml, environmentResolver)) {
                return WorkflowValidation.warn("invalid server.port");
            }
            if (invalidListRoleOverlap(yaml, frontMatter.body())) {
                return WorkflowValidation.warn("overlapping tracker list roles");
            }
            return WorkflowValidation.valid();
        } catch (IOException | RuntimeException e) {
            return WorkflowValidation.warn("unreadable or invalid workflow configuration");
        }
    }

    void updateServerPort(Path workflowPath, int port) throws IOException {
        ReadWorkflow readWorkflow = readWorkflow(workflowPath);
        SequencedMap<String, Object> yaml = parseYaml(readWorkflow.frontMatter());
        Object serverValue = yaml.get("server");
        SequencedMap<String, Object> server = new LinkedHashMap<>();
        if (serverValue instanceof Map<?, ?> existingServer) {
            existingServer.forEach((key, value) -> server.put(String.valueOf(key), value));
        }
        server.put("port", port);
        yaml.put("server", server);
        write(
                workflowPath,
                readWorkflow.content(),
                yaml,
                readWorkflow.frontMatter().body());
    }

    void applyCodexAccess(Path workflowPath, List<Path> additionalWritableRoots, boolean dangerFullAccess)
            throws IOException {
        if (additionalWritableRoots.isEmpty() && !dangerFullAccess) {
            return;
        }
        ReadWorkflow readWorkflow = readWorkflow(workflowPath);
        SequencedMap<String, Object> yaml = parseYaml(readWorkflow.frontMatter());
        Object codexValue = yaml.get("codex");
        if (!(codexValue instanceof Map<?, ?> existingCodex)) {
            throw new TrelloBoardSetupException("setup_workflow_codex_missing", "Workflow has no codex section.");
        }
        SequencedMap<String, Object> codex = new LinkedHashMap<>();
        existingCodex.forEach((key, value) -> codex.put(String.valueOf(key), value));
        if (!additionalWritableRoots.isEmpty()) {
            codex.put(
                    "additional_writable_roots",
                    additionalWritableRoots.stream().map(Path::toString).toList());
        }
        if (dangerFullAccess) {
            codex.put("turn_sandbox_policy", Map.of("type", "dangerFullAccess"));
        }
        yaml.put("codex", codex);
        try {
            CodexSandboxPolicy.validateCodexSection(codex);
            CodexSandboxPolicy.validateResolvedPolicy(
                    codex.get(CodexSandboxPolicy.TURN_SANDBOX_POLICY), additionalWritableRoots, dangerFullAccess);
        } catch (CodexSandboxPolicy.InvalidPolicyException e) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_invalid", "Cannot update Codex access because " + e.getMessage(), e);
        }
        write(
                workflowPath,
                readWorkflow.content(),
                yaml,
                readWorkflow.frontMatter().body());
    }

    private static FrontMatter read(Path workflowPath) throws IOException {
        return readWorkflow(workflowPath).frontMatter();
    }

    private static ReadWorkflow readWorkflow(Path workflowPath) throws IOException {
        if (!Files.isRegularFile(workflowPath)) {
            throw new IOException("Workflow path is not a regular file.");
        }
        String content = Files.readString(workflowPath, StandardCharsets.UTF_8);
        return new ReadWorkflow(content, FrontMatter.parse(content));
    }

    private static SequencedMap<String, Object> parseYaml(FrontMatter frontMatter) throws IOException {
        SequencedMap<String, Object> yaml = YAML.readValue(frontMatter.yaml(), YAML_MAP_TYPE);
        if (yaml == null) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_frontmatter_not_map", "Workflow front matter must be a YAML map.");
        }
        return yaml;
    }

    private static void write(Path workflowPath, String expectedContent, SequencedMap<String, Object> yaml, String body)
            throws IOException {
        writeAtomically(workflowPath, expectedContent, "---\n" + YAML.writeValueAsString(yaml) + "---\n" + body);
    }

    private static void writeAtomically(Path workflowPath, String expectedContent, String content) throws IOException {
        Path target = Files.isSymbolicLink(workflowPath)
                ? workflowPath.toRealPath()
                : workflowPath.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Workflow path has no parent directory.");
        }
        Optional<Set<PosixFilePermission>> permissions = readPosixPermissions(target);
        requireWritableTarget(target, permissions);
        requireUnchangedTarget(target, expectedContent);
        Path temporary = Files.createTempFile(parent, temporaryPrefix(target), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            if (permissions.isPresent()) {
                Files.setPosixFilePermissions(temporary, permissions.get());
            }
            requireUnchangedTarget(target, expectedContent);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Atomic workflow replacement is not supported.", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String temporaryPrefix(Path target) {
        String prefix = "." + target.getFileName();
        return prefix.length() >= 3 ? prefix : prefix + ".".repeat(3 - prefix.length());
    }

    private static void requireUnchangedTarget(Path target, String expectedContent) throws IOException {
        if (!Files.readString(target, StandardCharsets.UTF_8).equals(expectedContent)) {
            throw new IOException("Workflow file changed while preparing the update.");
        }
    }

    private static void requireWritableTarget(Path target, Optional<Set<PosixFilePermission>> permissions)
            throws IOException {
        if (!Files.isWritable(target)
                || permissions.map(WorkflowConfigEditor::lacksPosixWriteBit).orElse(false)) {
            throw new IOException("Workflow file cannot be updated.");
        }
    }

    private static boolean lacksPosixWriteBit(Set<PosixFilePermission> permissions) {
        return POSIX_WRITE_PERMISSIONS.stream().noneMatch(permissions::contains);
    }

    private static Optional<Set<PosixFilePermission>> readPosixPermissions(Path target) {
        try {
            return Optional.of(Files.getPosixFilePermissions(target));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Preserving POSIX permissions is best effort; the write path itself remains strict.
            return Optional.empty();
        }
    }

    private static Optional<Integer> serverPort(
            Map<String, Object> yaml, Function<String, Optional<String>> environmentResolver) {
        return serverPortSetting(yaml, environmentResolver).value();
    }

    private static WorkflowIntegerSetting serverPortSetting(Map<String, Object> yaml) {
        return serverPortSetting(yaml, ignored -> Optional.empty());
    }

    private static WorkflowIntegerSetting serverPortSetting(
            Map<String, Object> yaml, Function<String, Optional<String>> environmentResolver) {
        return typedConfig(yaml, environmentResolver).localServerPortSetting();
    }

    private static Optional<String> boardId(
            Map<String, Object> yaml, Function<String, Optional<String>> environmentResolver) {
        Object trackerValue = yaml.get("tracker");
        if (!(trackerValue instanceof Map<?, ?> tracker)) {
            return Optional.empty();
        }
        Object boardId = tracker.get("board_id");
        return optionalString(boardId)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .flatMap(value -> environmentReferenceName(value)
                        .flatMap(environmentResolver)
                        .map(String::trim)
                        .filter(resolved -> !resolved.isBlank())
                        .or(() -> Optional.of(value)));
    }

    private static boolean invalidServerPortSetting(
            Map<String, Object> yaml, Function<String, Optional<String>> environmentResolver) {
        Object serverValue = yaml.get("server");
        if (!(serverValue instanceof Map<?, ?> server) || !server.containsKey("port")) {
            return false;
        }
        return serverPortSetting(yaml, environmentResolver).invalid();
    }

    private static boolean invalidListRoleOverlap(Map<String, Object> yaml, String body) {
        Object trackerValue = yaml.get("tracker");
        if (!(trackerValue instanceof Map<?, ?> tracker)) {
            return false;
        }
        List<String> activeListIds = stringList(tracker.get("active_list_ids"));
        List<String> terminalListIds = stringList(tracker.get("terminal_list_ids"));
        return TrelloListRoleValidator.firstOverlap(activeListIds, terminalListIds, null, null)
                .or(() -> TrelloListRoleValidator.firstOverlap(
                        activeListIds.isEmpty() ? stringList(tracker.get("active_states")) : List.of(),
                        terminalListIds.isEmpty() ? stringList(tracker.get("terminal_states")) : List.of(),
                        optionalString(tracker.get("in_progress_state")).orElse(null),
                        optionalString(tracker.get("blocked_state"))
                                .or(() -> blockedStateFromWorkflowBody(body))
                                .orElse(null)))
                .isPresent();
    }

    private static void validateResolvedTextReference(
            Map<String, Object> yaml,
            String sectionName,
            String key,
            String displayName,
            Function<String, Optional<String>> environmentResolver) {
        Object sectionValue = yaml.get(sectionName);
        if (!(sectionValue instanceof Map<?, ?> section)) {
            return;
        }
        environmentReferenceName(section.get(key))
                .filter(name -> environmentResolver
                        .apply(name)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .isEmpty())
                .ifPresent(name -> {
                    throw unresolvedEnvironmentReference(displayName, name);
                });
    }

    private static void validateServerPortReference(
            Map<String, Object> yaml, Function<String, Optional<String>> environmentResolver) {
        Object serverValue = yaml.get("server");
        if (!(serverValue instanceof Map<?, ?> server)) {
            return;
        }
        environmentReferenceName(server.get("port")).ifPresent(name -> {
            environmentResolver
                    .apply(name)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .ifPresentOrElse(value -> validateResolvedServerPort(value, name), () -> {
                        throw unresolvedEnvironmentReference("server.port", name);
                    });
        });
    }

    private static void validateResolvedServerPort(String value, String environmentName) {
        if (typedConfig(Map.of("server", Map.of("port", value)))
                .localServerPortSetting()
                .invalid()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_server_port",
                    "Workflow server.port environment variable " + environmentName
                            + " must resolve to an integer between 0 and " + LocalPort.MAX + ".");
        }
    }

    private static Optional<String> environmentReferenceName(Object value) {
        return optionalString(value).flatMap(EnvironmentReferences::referenceName);
    }

    private static TrelloBoardSetupException unresolvedEnvironmentReference(String displayName, String name) {
        return new TrelloBoardSetupException(
                "setup_workflow_unresolved_environment",
                "Workflow " + displayName + " references missing environment variable " + name + ".");
    }

    private static TypedWorkflowConfig typedConfig(Map<String, Object> yaml) {
        return typedConfig(yaml, ignored -> Optional.empty());
    }

    private static TypedWorkflowConfig typedConfig(
            Map<String, Object> yaml, Function<String, Optional<String>> environmentResolver) {
        return WorkflowConfigIngestion.collect(yaml == null ? Map.of() : yaml, environmentResolver);
    }

    private static Optional<String> blockedStateFromWorkflowBody(String body) {
        var matcher =
                Pattern.compile("(?m)^- \"(?<name>[^\"]+)\": blocked work\\.").matcher(body);
        return matcher.find() ? Optional.of(matcher.group("name")) : Optional.empty();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .filter(valueText -> !valueText.isBlank())
                .toList();
    }

    private static boolean invalidListSetting(Map<?, ?> root, String key) {
        if (!root.containsKey(key)) {
            return false;
        }
        if (!(root.get(key) instanceof List<?> list)) {
            return true;
        }
        return list.isEmpty() || list.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank());
    }

    private static Optional<String> optionalString(Object value) {
        String text = value == null ? null : String.valueOf(value);
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static boolean isTrelloTracker(Map<?, ?> tracker) {
        return optionalString(tracker.get("kind")).map("trello"::equals).orElse(false);
    }

    record FrontMatter(String yaml, String body) {
        static FrontMatter parse(String workflow) {
            var matcher = FRONT_MATTER.matcher(workflow);
            if (!matcher.matches()) {
                throw new TrelloBoardSetupException(
                        "setup_workflow_frontmatter_missing", "Workflow has no YAML front matter.");
            }
            return new FrontMatter(matcher.group("yaml"), matcher.group("body"));
        }
    }

    record ReadWorkflow(String content, FrontMatter frontMatter) {}

    record TrackerCredentialReferences(Optional<String> apiKey, Optional<String> apiToken) {}
}
