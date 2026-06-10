package ch.fmartin.symphony.trello.config;

import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.Pattern;

@ApplicationScoped
public class ConfigResolver {
    private static final String FILE_SECRET_PREFIX = "file:";
    private static final int MAX_SECRET_BYTES = 64 * 1024;

    private static final Map<String, Integer> DEFAULT_PRIORITIES = Map.of(
            "p1", 1,
            "p2", 2,
            "p3", 3,
            "p4", 4,
            "priority: critical", 1,
            "priority: high", 2,
            "priority: medium", 3,
            "priority: low", 4);

    private final Function<String, Optional<String>> environmentResolver;

    public ConfigResolver() {
        this(LocalEnvironment::get);
    }

    public ConfigResolver(Function<String, Optional<String>> environmentResolver) {
        this.environmentResolver = environmentResolver;
    }

    public EffectiveConfig resolve(WorkflowDefinition workflow) {
        Map<String, Object> root = workflow.config();
        Map<String, Object> tracker = object(root, "tracker");
        Map<String, Object> polling = object(root, "polling");
        Map<String, Object> workspace = object(root, "workspace");
        Map<String, Object> hooks = object(root, "hooks");
        Map<String, Object> agent = object(root, "agent");
        Map<String, Object> codex = object(root, "codex");
        Map<String, Object> trelloTools = object(root, "trello_tools");
        Map<String, Object> server = object(root, "server");

        String trackerKind = string(tracker, "kind", null);
        String endpoint = string(tracker, "endpoint", "https://api.trello.com/1");
        String apiKey = secret(workflow.path().getParent(), tracker, "api_key", "tracker.api_key", "TRELLO_API_KEY");
        String apiToken =
                secret(workflow.path().getParent(), tracker, "api_token", "tracker.api_token", "TRELLO_API_TOKEN");
        String boardId = environmentString(tracker, "board_id", null);
        String resolvedBoardId = environmentString(tracker, "resolved_board_id", boardId);

        boolean writes = bool(trelloTools, "allow_writes", false);
        return new EffectiveConfig(
                workflow.path(),
                new EffectiveConfig.TrackerConfig(
                        trackerKind,
                        endpoint,
                        apiKey,
                        apiToken,
                        boardId,
                        resolvedBoardId,
                        list(tracker, "active_states", List.of("Todo", "In Progress")),
                        list(tracker, "active_list_ids", List.of()),
                        string(tracker, "in_progress_state", null),
                        string(tracker, "blocked_state", null),
                        normalizedList(tracker, "blocker_enforced_states", List.of("Todo", "Ready for Codex")),
                        normalizedList(
                                tracker,
                                "terminal_states",
                                List.of("Done", "Archived", "ArchivedList", "ArchivedBoard", "Deleted")),
                        list(tracker, "terminal_list_ids", List.of()),
                        priorityLabels(object(tracker, "priority_labels")),
                        string(tracker, "card_identifier_prefix", "TRELLO"),
                        millis(tracker, "request_timeout_ms", ConfigDefaults.DEFAULT_TRACKER_REQUEST_TIMEOUT_MS),
                        integer(tracker, "max_api_retries", ConfigDefaults.DEFAULT_TRACKER_MAX_API_RETRIES),
                        millis(
                                tracker,
                                "api_retry_base_delay_ms",
                                ConfigDefaults.DEFAULT_TRACKER_API_RETRY_BASE_DELAY_MS)),
                new EffectiveConfig.PollingConfig(
                        positiveMillis(polling, "interval_ms", ConfigDefaults.DEFAULT_POLLING_INTERVAL_MS)),
                new EffectiveConfig.WorkspaceConfig(
                        path(workflow.path().getParent(), string(workspace, "root", systemTempRoot()))),
                new EffectiveConfig.HooksConfig(
                        string(hooks, "after_create", null),
                        string(hooks, "before_run", null),
                        string(hooks, "after_run", null),
                        string(hooks, "before_remove", null),
                        millis(hooks, "timeout_ms", 60_000)),
                new EffectiveConfig.AgentConfig(
                        positive(agent, "max_concurrent_agents", ConfigDefaults.DEFAULT_RUNTIME_MAX_CONCURRENT_AGENTS),
                        positive(agent, "max_turns", 20),
                        millis(agent, "max_retry_backoff_ms", ConfigDefaults.DEFAULT_AGENT_MAX_RETRY_BACKOFF_MS),
                        positiveStateMap(object(agent, "max_concurrent_agents_by_state"))),
                new EffectiveConfig.CodexConfig(
                        string(codex, "command", ConfigDefaults.DEFAULT_CODEX_COMMAND),
                        optionalString(codex, "model"),
                        optionalString(codex, "reasoning_effort"),
                        codex.get("approval_policy"),
                        codex.get("thread_sandbox"),
                        codex.get("turn_sandbox_policy"),
                        additionalWritableRoots(workflow.path().getParent(), codex),
                        LocalEnvironment.get("SYMPHONY_CODEX_DANGER_FULL_ACCESS")
                                .map(Boolean::parseBoolean)
                                .orElse(false),
                        millis(codex, "turn_timeout_ms", ConfigDefaults.DEFAULT_CODEX_TURN_TIMEOUT_MS),
                        millis(codex, "read_timeout_ms", ConfigDefaults.DEFAULT_CODEX_READ_TIMEOUT_MS),
                        millis(codex, "stall_timeout_ms", ConfigDefaults.DEFAULT_CODEX_STALL_TIMEOUT_MS)),
                new EffectiveConfig.TrelloToolsConfig(
                        bool(trelloTools, "enabled", false),
                        writes,
                        list(trelloTools, "allowed_move_list_ids", List.of()),
                        normalizedList(trelloTools, "allowed_move_list_names", List.of()),
                        bool(trelloTools, "allow_comments", writes),
                        bool(trelloTools, "allow_checklists", writes),
                        bool(trelloTools, "allow_url_attachments", writes),
                        bool(trelloTools, "allow_destructive_operations", false),
                        bool(trelloTools, "assume_write_scope", false)),
                new EffectiveConfig.ServerConfig(optionalEnvironmentInt(server, "port")));
    }

    public void validateForDispatch(EffectiveConfig config) {
        if (!"trello".equals(config.tracker().kind())) {
            throw new ConfigException("unsupported_tracker_kind", "tracker.kind must be trello");
        }
        validateEndpoint(config.tracker().endpoint());
        if (blank(config.tracker().apiKey())) {
            throw new ConfigException("missing_tracker_api_key", "tracker.api_key is required");
        }
        if (blank(config.tracker().apiToken())) {
            throw new ConfigException("missing_tracker_api_token", "tracker.api_token is required");
        }
        if (blank(config.tracker().boardId())) {
            throw new ConfigException("missing_tracker_board_id", "tracker.board_id is required");
        }
        if (blank(config.codex().command())) {
            throw new ConfigException("missing_codex_command", "codex.command is required");
        }
        firstAuthoritativeListRoleOverlap(config.tracker()).ifPresent(overlap -> {
            throw new ConfigException(
                    "overlapping_tracker_list_roles",
                    "tracker list roles must use distinct Trello lists: " + overlap.description());
        });
        if (!blank(config.tracker().inProgressState())
                && config.tracker().activeListIds().isEmpty()
                && config.tracker().activeStates().stream()
                        .map(StateNames::normalize)
                        .noneMatch(StateNames.normalize(config.tracker().inProgressState())::equals)) {
            throw new ConfigException(
                    "invalid_in_progress_state",
                    "tracker.in_progress_state must also be listed in tracker.active_states");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new ConfigException("config_type_error", key + " must be an object");
    }

    private static String string(Map<String, Object> root, String key, String defaultValue) {
        Object value = root.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    private static Optional<TrelloListRoleValidator.Overlap> firstAuthoritativeListRoleOverlap(
            EffectiveConfig.TrackerConfig tracker) {
        return TrelloListRoleValidator.firstOverlap(tracker.activeListIds(), tracker.terminalListIds(), null, null)
                .or(() -> TrelloListRoleValidator.firstOverlap(
                        tracker.activeListIds().isEmpty() ? tracker.activeStates() : List.of(),
                        tracker.terminalListIds().isEmpty() ? tracker.terminalStates() : List.of(),
                        tracker.inProgressState(),
                        tracker.blockedState()));
    }

    private static String optionalString(Map<String, Object> root, String key) {
        String value = string(root, key, null);
        return blank(value) ? null : value;
    }

    private String environmentString(Map<String, Object> root, String key, String defaultValue) {
        String configured = string(root, key, defaultValue);
        Optional<String> reference = EnvironmentReferences.referenceName(configured);
        // Plain branching because an unresolved reference must yield the nullable default, which
        // Optional.map would silently turn back into the raw reference text.
        if (reference.isPresent()) {
            return environmentValueOrDefault(reference.get(), defaultValue);
        }
        return configured;
    }

    private String secret(
            Path workflowDirectory, Map<String, Object> root, String key, String displayName, String defaultEnv) {
        String configured = string(root, key, "$" + defaultEnv);
        Optional<String> reference = EnvironmentReferences.referenceName(configured);
        if (reference.isPresent()) {
            return environmentValueOrDefault(reference.get(), null);
        }
        if (configured != null && configured.startsWith(FILE_SECRET_PREFIX)) {
            return fileSecret(workflowDirectory, displayName, configured.substring(FILE_SECRET_PREFIX.length()));
        }
        return configured;
    }

    private String environmentValueOrDefault(String environmentName, String defaultValue) {
        return environmentResolver.apply(environmentName).orElse(defaultValue);
    }

    private static String fileSecret(Path workflowDirectory, String displayName, String configuredPath) {
        Path secretPath = path(workflowDirectory, configuredPath);
        try {
            long size = Files.size(secretPath);
            if (size > MAX_SECRET_BYTES) {
                throw new ConfigException(
                        "secret_file_too_large", displayName + " secret file is too large: " + secretPath);
            }
            return stripTrailingLineBreaks(Files.readString(secretPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException(
                    "secret_file_read_error", displayName + " secret file cannot be read: " + secretPath, e);
        }
    }

    private static String stripTrailingLineBreaks(String value) {
        String stripped = value;
        while (stripped.endsWith("\n") || stripped.endsWith("\r")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static void validateEndpoint(String endpoint) {
        if (blank(endpoint)) {
            throw new ConfigException("invalid_tracker_endpoint", "tracker.endpoint must not be blank");
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            throw new ConfigException("invalid_tracker_endpoint", "tracker.endpoint must be a valid http(s) URL", e);
        }
        // Scheme comparison is case-insensitive like TrelloApiEndpoint.normalize on the setup
        // side, so a workflow endpoint accepted during setup cannot fail here over letter case.
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean httpScheme = "http".equals(scheme) || "https".equals(scheme);
        boolean hasHost = uri.getHost() != null;
        if (!httpScheme || !hasHost) {
            throw new ConfigException(
                    "invalid_tracker_endpoint", "tracker.endpoint must be an absolute http(s) URL with a host");
        }
    }

    private static Duration positiveMillis(Map<String, Object> root, String key, long defaultValue) {
        Duration value = millis(root, key, defaultValue);
        if (value.isZero()) {
            throw new ConfigException("config_value_error", key + " must be positive");
        }
        return value;
    }

    private static Duration millis(Map<String, Object> root, String key, long defaultValue) {
        int value = integer(root, key, (int) defaultValue);
        if (value < 0) {
            throw new ConfigException("config_value_error", key + " must be non-negative");
        }
        return Duration.ofMillis(value);
    }

    private static int positive(Map<String, Object> root, String key, int defaultValue) {
        int value = integer(root, key, defaultValue);
        if (value <= 0) {
            throw new ConfigException("config_value_error", key + " must be positive");
        }
        return value;
    }

    private static int integer(Map<String, Object> root, String key, int defaultValue) {
        Object value = root.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private OptionalInt optionalEnvironmentInt(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            return OptionalInt.empty();
        }
        if (value instanceof Number number) {
            return OptionalInt.of(number.intValue());
        }
        String text = environmentString(root, key, null);
        return blank(text) ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(text));
    }

    private static boolean bool(Map<String, Object> root, String key, boolean defaultValue) {
        Object value = root.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> root, String key, List<String> defaultValue) {
        Object value = root.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(value.toString());
    }

    private static List<String> normalizedList(Map<String, Object> root, String key, List<String> defaultValue) {
        return list(root, key, defaultValue).stream().map(StateNames::normalize).toList();
    }

    private static List<Path> additionalWritableRoots(Path workflowDirectory, Map<String, Object> codex) {
        List<Path> roots = new ArrayList<>();
        list(codex, "additional_writable_roots", List.of()).stream()
                .map(value -> path(workflowDirectory, value))
                .forEach(roots::add);
        LocalEnvironment.get("SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS").stream()
                .flatMap(value -> Arrays.stream(value.split(Pattern.quote(File.pathSeparator))))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> path(workflowDirectory, value))
                .forEach(roots::add);
        return roots.stream().distinct().toList();
    }

    private static Map<String, Integer> priorityLabels(Map<String, Object> configured) {
        return positiveNormalizedIntegerMap(configured, DEFAULT_PRIORITIES);
    }

    private static Map<String, Integer> positiveStateMap(Map<String, Object> configured) {
        return positiveNormalizedIntegerMap(configured, Map.of());
    }

    private static Map<String, Integer> positiveNormalizedIntegerMap(
            Map<String, Object> configured, Map<String, Integer> defaultValues) {
        Map<String, Integer> values = new LinkedHashMap<>(defaultValues);
        configured.forEach((key, value) ->
                positiveInteger(value).ifPresent(parsed -> values.put(StateNames.normalize(key), parsed)));
        return Map.copyOf(values);
    }

    private static OptionalInt positiveInteger(Object value) {
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
            return parsed > 0 ? OptionalInt.of(parsed) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static Path path(Path workflowDirectory, String value) {
        String expanded = expandPath(value);
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            path = workflowDirectory.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String expandPath(String value) {
        String expanded = value;
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        if (expanded.startsWith("$") && expanded.indexOf('/') < 0) {
            expanded = LocalEnvironment.get(expanded.substring(1)).orElse(expanded);
        } else if (expanded.startsWith("$")) {
            int separator = expanded.indexOf('/');
            String name = expanded.substring(1, separator);
            String suffix = expanded.substring(separator);
            expanded = LocalEnvironment.get(name)
                    .map(envValue -> envValue + suffix)
                    .orElse(expanded);
        }
        return expanded;
    }

    private static String systemTempRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "symphony_workspaces")
                .toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
