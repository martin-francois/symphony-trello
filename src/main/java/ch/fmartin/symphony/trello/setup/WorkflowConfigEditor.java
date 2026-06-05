package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.regex.Pattern;

final class WorkflowConfigEditor {
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build());
    private static final TypeReference<SequencedMap<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};
    private static final Pattern FRONT_MATTER =
            Pattern.compile("\\A---\\R(?<yaml>.*?)\\R---\\R(?<body>.*)\\z", Pattern.DOTALL);

    WorkflowValidation validate(ConnectedBoard board) {
        if (!Files.isRegularFile(board.workflowPath())) {
            return WorkflowValidation.warn(
                    "Workflow file is missing for \"" + board.boardName() + "\": " + board.workflowPath());
        }
        try {
            FrontMatter frontMatter = read(board.workflowPath());
            SequencedMap<String, Object> yaml = parseYaml(frontMatter);
            WorkflowValidation boardIdValidation = boardId(yaml)
                    .map(configuredBoardId -> validateBoardId(board, configuredBoardId))
                    .orElseGet(() -> WorkflowValidation.warn("Workflow file is missing tracker.board_id for \""
                            + board.boardName() + "\": " + board.workflowPath()));
            if (!boardIdValidation.ok()) {
                return boardIdValidation;
            }
            return serverPort(yaml)
                    .map(configuredServerPort -> validateServerPort(board, configuredServerPort))
                    .orElseGet(() -> WorkflowValidation.warn("Workflow file is missing server.port for \""
                            + board.boardName() + "\": " + board.workflowPath()));
        } catch (IOException | TrelloBoardSetupException e) {
            return WorkflowValidation.warn("Workflow file is not readable or has invalid YAML for \""
                    + board.boardName() + "\": " + board.workflowPath() + " (" + e.getMessage() + ")");
        }
    }

    private static WorkflowValidation validateBoardId(ConnectedBoard board, String configuredBoardId) {
        if (!configuredBoardId.equals(board.boardId()) && !configuredBoardId.equals(board.boardKey())) {
            return WorkflowValidation.warn("Workflow tracker.board_id does not match the connected board for \""
                    + board.boardName() + "\": expected " + board.boardId() + " or " + board.boardKey()
                    + " but found " + configuredBoardId);
        }
        return WorkflowValidation.valid();
    }

    private static WorkflowValidation validateServerPort(ConnectedBoard board, int configuredServerPort) {
        if (configuredServerPort != board.serverPort()) {
            return WorkflowValidation.warn("Workflow server.port does not match the connected board for \""
                    + board.boardName() + "\": expected " + board.serverPort() + " but found " + configuredServerPort);
        }
        return WorkflowValidation.valid();
    }

    Optional<Integer> serverPort(Path workflowPath) {
        return serverPortSetting(workflowPath).value();
    }

    WorkflowIntegerSetting serverPortSetting(Path workflowPath) {
        try {
            return serverPortSetting(parseYaml(read(workflowPath)));
        } catch (IOException | RuntimeException ignored) {
            return WorkflowIntegerSetting.omitted();
        }
    }

    Optional<String> boardId(Path workflowPath) {
        try {
            return boardId(parseYaml(read(workflowPath)));
        } catch (IOException | TrelloBoardSetupException ignored) {
            return Optional.empty();
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
        WorkflowValidation validation = diagnosticsValidation(workflowPath);
        return validation.ok() ? Optional.empty() : Optional.of(validation.message());
    }

    WorkflowValidation diagnosticsValidation(Path workflowPath) {
        if (!Files.isRegularFile(workflowPath)) {
            return WorkflowValidation.warn("missing workflow file");
        }
        try {
            SequencedMap<String, Object> yaml = parseYaml(read(workflowPath));
            if (invalidServerPortSetting(yaml)) {
                return WorkflowValidation.warn("invalid server.port");
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
        return FrontMatter.parse(Files.readString(workflowPath, StandardCharsets.UTF_8));
    }

    private static SequencedMap<String, Object> parseYaml(FrontMatter frontMatter) throws IOException {
        return YAML.readValue(frontMatter.yaml(), YAML_MAP_TYPE);
    }

    private static void write(Path workflowPath, SequencedMap<String, Object> yaml, String body) throws IOException {
        Files.writeString(
                workflowPath, "---\n" + YAML.writeValueAsString(yaml) + "---\n" + body, StandardCharsets.UTF_8);
    }

    private static Optional<Integer> serverPort(Map<String, Object> yaml) {
        return serverPortSetting(yaml).value();
    }

    private static WorkflowIntegerSetting serverPortSetting(Map<String, Object> yaml) {
        Object serverValue = yaml.get("server");
        if (!(serverValue instanceof Map<?, ?> server)) {
            return WorkflowIntegerSetting.omitted();
        }
        if (!server.containsKey("port")) {
            return WorkflowIntegerSetting.omitted();
        }
        Object port = server.get("port");
        return workflowServerPortSetting(port);
    }

    private static Optional<String> boardId(Map<String, Object> yaml) {
        Object trackerValue = yaml.get("tracker");
        if (!(trackerValue instanceof Map<?, ?> tracker)) {
            return Optional.empty();
        }
        return optionalString(tracker.get("board_id"));
    }

    private static boolean invalidServerPortSetting(Map<String, Object> yaml) {
        Object serverValue = yaml.get("server");
        if (!(serverValue instanceof Map<?, ?> server) || !server.containsKey("port")) {
            return false;
        }
        return serverPortSetting(yaml).invalid();
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
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
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
        return switch (number) {
            case Byte value -> Optional.of(value.intValue());
            case Short value -> Optional.of(value.intValue());
            case Integer value -> Optional.of(value);
            case Long value -> exactInt(BigInteger.valueOf(value));
            case BigInteger value -> exactInt(value);
            case BigDecimal value -> exactIntegralInt(value);
            case Float value -> finiteIntegralInt(value.doubleValue());
            case Double value -> finiteIntegralInt(value);
            default -> exactNumberTextInt(number.toString());
        };
    }

    private static Optional<Integer> finiteIntegralInt(double value) {
        if (!Double.isFinite(value)) {
            return Optional.empty();
        }
        return exactIntegralInt(BigDecimal.valueOf(value));
    }

    private static Optional<Integer> exactNumberTextInt(String value) {
        try {
            return exactIntegralInt(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> exactIntegralInt(BigDecimal value) {
        try {
            return exactInt(value.toBigIntegerExact());
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> exactInt(BigInteger value) {
        try {
            return Optional.of(value.intValueExact());
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
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
