package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.EnvironmentReferences;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.config.TrelloListRoleValidator;
import ch.fmartin.symphony.trello.config.WholeNumbers;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.regex.Pattern;

final class WorkflowConfigEditor {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build());
    private static final TypeReference<SequencedMap<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};
    private static final Pattern FRONT_MATTER =
            Pattern.compile("\\A---\\R(?<yaml>.*?)\\R---\\R(?<body>.*)\\z", Pattern.DOTALL);

    private final WorkflowLoader workflowLoader = new WorkflowLoader();

    WorkflowValidation validate(ConnectedBoard board) {
        return validate(board, LocalEnvironment::get);
    }

    WorkflowValidation validate(ConnectedBoard board, Function<String, Optional<String>> environmentResolver) {
        if (board.workflowPath() == null) {
            return WorkflowValidation.warn("Workflow path for " + DisplayNames.quotedName(board.boardName())
                    + " is missing from connected-boards.json.");
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
        Object serverValue = yaml.get("server");
        if (!(serverValue instanceof Map<?, ?> server) || !server.containsKey("port")) {
            return WorkflowServerPortClassification.omitted();
        }
        Object port = server.get("port");
        return environmentReferenceName(port)
                .map(name -> environmentResolver
                        .apply(name)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .map(WorkflowConfigEditor::classifyServerPortValue)
                        .orElseGet(WorkflowServerPortClassification::omitted))
                .orElseGet(() -> classifyServerPortValue(port));
    }

    private static WorkflowServerPortClassification classifyServerPortValue(Object port) {
        Optional<Integer> numeric =
                switch (port) {
                    case Number number -> integralInteger(number);
                    case String text when !text.isBlank() -> parseInteger(text.trim());
                    default -> Optional.empty();
                };
        return numeric.map(WorkflowServerPortClassification::numericPort)
                .orElseGet(WorkflowServerPortClassification::invalidValue);
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
        validateResolvedTextReference(yaml, "tracker", "board_id", "tracker.board_id", environmentResolver);
        if (validateServerPort) {
            validateServerPortReference(yaml, environmentResolver);
            validateLaunchServerPort(workflowPath, yaml, environmentResolver);
        }
    }

    /**
     * Rejects literal server.port values outside the local port range before a worker launch.
     * Environment-reference problems are reported by validateServerPortReference first, so this
     * check only flags literal values such as 70000, -1, or fractional YAML numbers.
     */
    private void validateLaunchServerPort(
            Path workflowPath,
            SequencedMap<String, Object> yaml,
            Function<String, Optional<String>> environmentResolver) {
        if (invalidServerPortSetting(yaml, environmentResolver)) {
            throw invalidWorkflowForLaunch(
                    workflowPath,
                    new ConfigException(
                            "invalid_server_port",
                            "server.port must be an integer between 0 and " + LocalPort.MAX + "."));
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

    WorkflowIntegerSetting maxAgentsSetting(Path workflowPath) {
        try {
            SequencedMap<String, Object> yaml = parseYaml(read(workflowPath));
            if (!(yaml.get("agent") instanceof Map<?, ?> agent)) {
                return WorkflowIntegerSetting.omitted();
            }
            if (!agent.containsKey("max_concurrent_agents")) {
                return WorkflowIntegerSetting.omitted();
            }
            return positiveIntegerSetting(agent.get("max_concurrent_agents"));
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
        requireLaunchableWorkflowFile(workflowPath);
        try {
            WorkflowDefinition workflow = workflowLoader.load(workflowPath);
            return new ConfigResolver(environmentResolver).resolve(workflow);
        } catch (WorkflowException | ConfigException | IllegalArgumentException | ClassCastException e) {
            throw invalidWorkflowForLaunch(workflowPath, e);
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

    void validateLaunchDispatch(Path workflowPath, EffectiveConfig launchConfig) {
        try {
            new ConfigResolver().validateForDispatch(launchConfig);
        } catch (ConfigException e) {
            throw invalidWorkflowForLaunch(workflowPath, e);
        }
    }

    private static TrelloBoardSetupException invalidWorkflowForLaunch(Path workflowPath, RuntimeException cause) {
        return new TrelloBoardSetupException(
                "setup_workflow_invalid",
                "Invalid workflow configuration: " + cause.getMessage() + "\nWorkflow file:\n  "
                        + workflowPath.toAbsolutePath().normalize()
                        + "\nFix the workflow front matter, then rerun symphony-trello start.",
                cause);
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

    void updateServerPort(Path workflowPath, int port) throws IOException {
        FrontMatter frontMatter = read(workflowPath);
        SequencedMap<String, Object> yaml = parseYaml(frontMatter);
        Object serverValue = yaml.get("server");
        SequencedMap<String, Object> server = new LinkedHashMap<>();
        if (serverValue instanceof Map<?, ?> existingServer) {
            existingServer.forEach((key, value) -> server.put(String.valueOf(key), value));
        }
        server.put("port", port);
        yaml.put("server", server);
        write(workflowPath, yaml, frontMatter.body());
    }

    void applyCodexAccess(Path workflowPath, List<Path> additionalWritableRoots, boolean dangerFullAccess)
            throws IOException {
        if (additionalWritableRoots.isEmpty() && !dangerFullAccess) {
            return;
        }
        FrontMatter frontMatter = read(workflowPath);
        SequencedMap<String, Object> yaml = parseYaml(frontMatter);
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
        write(workflowPath, yaml, frontMatter.body());
    }

    private static FrontMatter read(Path workflowPath) throws IOException {
        if (!Files.isRegularFile(workflowPath)) {
            throw new IOException("Workflow path is not a regular file.");
        }
        return FrontMatter.parse(Files.readString(workflowPath, StandardCharsets.UTF_8));
    }

    private static SequencedMap<String, Object> parseYaml(FrontMatter frontMatter) throws IOException {
        return YAML.readValue(frontMatter.yaml(), YAML_MAP_TYPE);
    }

    private static void write(Path workflowPath, SequencedMap<String, Object> yaml, String body) throws IOException {
        Files.writeString(
                workflowPath, "---\n" + YAML.writeValueAsString(yaml) + "---\n" + body, StandardCharsets.UTF_8);
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
        Object serverValue = yaml.get("server");
        if (!(serverValue instanceof Map<?, ?> server)) {
            return WorkflowIntegerSetting.omitted();
        }
        if (!server.containsKey("port")) {
            return WorkflowIntegerSetting.omitted();
        }
        Object port = server.get("port");
        return environmentReferenceName(port)
                .flatMap(environmentResolver)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(WorkflowConfigEditor::workflowServerPortSetting)
                .orElseGet(() -> workflowServerPortSetting(port));
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
        if (workflowServerPortSetting(value).invalid()) {
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

    private static WorkflowIntegerSetting workflowServerPortSetting(Object value) {
        Optional<Integer> port =
                switch (value) {
                    case Number number -> integralInteger(number);
                    case String text when !text.isBlank() -> parseInteger(text.trim());
                    default -> Optional.empty();
                };
        return port.filter(valuePort -> valuePort >= 0 && valuePort <= LocalPort.MAX)
                .map(WorkflowIntegerSetting::valid)
                .orElseGet(WorkflowIntegerSetting::invalidSetting);
    }

    private static Optional<Integer> parseInteger(String value) {
        // Environment-backed text follows the same whole-number classification as literal YAML
        // numbers, so "18080.0" normalizes identically in both spellings.
        return WholeNumbers.wholeInt(value).stream().boxed().findAny();
    }

    private static WorkflowIntegerSetting positiveIntegerSetting(Object value) {
        if (value instanceof Number number) {
            return integralInteger(number)
                    .filter(integer -> integer > 0)
                    .map(WorkflowIntegerSetting::valid)
                    .orElseGet(WorkflowIntegerSetting::invalidSetting);
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                int integer = Integer.parseInt(text.trim());
                return integer > 0 ? WorkflowIntegerSetting.valid(integer) : WorkflowIntegerSetting.invalidSetting();
            } catch (NumberFormatException ignored) {
                return WorkflowIntegerSetting.invalidSetting();
            }
        }
        return WorkflowIntegerSetting.invalidSetting();
    }

    private static Optional<Integer> integralInteger(Number number) {
        OptionalInt whole = WholeNumbers.wholeInt(number.toString());
        return whole.stream().boxed().findAny();
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

    record TrackerCredentialReferences(Optional<String> apiKey, Optional<String> apiToken) {}

    record WorkflowServerPortClassification(Kind kind, Optional<Integer> port) {
        enum Kind {
            VALID,
            OMITTED,
            OUT_OF_RANGE,
            INVALID_VALUE,
            UNREADABLE
        }

        static WorkflowServerPortClassification numericPort(int port) {
            Kind kind = port >= 0 && port <= LocalPort.MAX ? Kind.VALID : Kind.OUT_OF_RANGE;
            return new WorkflowServerPortClassification(kind, Optional.of(port));
        }

        static WorkflowServerPortClassification omitted() {
            return new WorkflowServerPortClassification(Kind.OMITTED, Optional.empty());
        }

        static WorkflowServerPortClassification invalidValue() {
            return new WorkflowServerPortClassification(Kind.INVALID_VALUE, Optional.empty());
        }

        static WorkflowServerPortClassification unreadable() {
            return new WorkflowServerPortClassification(Kind.UNREADABLE, Optional.empty());
        }

        /**
         * Ports the diagnostics report should list: valid ports get probed, out-of-range ports
         * render the safe skip line. Omitted, unreadable, and non-numeric settings list nothing.
         */
        Optional<Integer> probeOrSkipPort() {
            return kind == Kind.VALID || kind == Kind.OUT_OF_RANGE ? port : Optional.empty();
        }
    }

    record WorkflowIntegerSetting(Optional<Integer> value, boolean invalid) {
        static WorkflowIntegerSetting valid(int value) {
            return new WorkflowIntegerSetting(Optional.of(value), false);
        }

        static WorkflowIntegerSetting omitted() {
            return new WorkflowIntegerSetting(Optional.empty(), false);
        }

        static WorkflowIntegerSetting invalidSetting() {
            return new WorkflowIntegerSetting(Optional.empty(), true);
        }

        String diagnosticsCell() {
            return value.map(String::valueOf).orElse(invalid ? "invalid" : "");
        }
    }
}
