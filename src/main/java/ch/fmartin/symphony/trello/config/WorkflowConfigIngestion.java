package ch.fmartin.symphony.trello.config;

import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class WorkflowConfigIngestion {
    public static final int MIN_LOCAL_SERVER_PORT = 0;
    public static final int MAX_LOCAL_SERVER_PORT = 65_535;

    private static final TypeReference<LinkedHashMap<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, Integer> DEFAULT_PRIORITIES = Map.of(
            "p1", 1,
            "p2", 2,
            "p3", 3,
            "p4", 4,
            "priority: critical", 1,
            "priority: high", 2,
            "priority: medium", 3,
            "priority: low", 4);

    public enum UnresolvedEnvironmentPolicy {
        OMIT,
        THROW_SERVER_PORT
    }

    private WorkflowConfigIngestion() {}

    public static Optional<TypedWorkflowConfig> collectFrontMatter(
            String frontMatter,
            Function<String, Optional<String>> environmentResolver,
            UnresolvedEnvironmentPolicy unresolvedEnvironmentPolicy) {
        try {
            LinkedHashMap<String, Object> parsed = YAML.readValue(frontMatter, YAML_MAP_TYPE);
            return Optional.of(
                    collect(parsed == null ? Map.of() : parsed, environmentResolver, unresolvedEnvironmentPolicy));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public static TypedWorkflowConfig collect(
            WorkflowDefinition workflow, Function<String, Optional<String>> environmentResolver) {
        return collect(workflow.config(), environmentResolver, UnresolvedEnvironmentPolicy.OMIT);
    }

    public static TypedWorkflowConfig collect(
            Map<String, Object> root, Function<String, Optional<String>> environmentResolver) {
        return collect(root, environmentResolver, UnresolvedEnvironmentPolicy.OMIT);
    }

    public static TypedWorkflowConfig collect(
            Map<String, Object> root,
            Function<String, Optional<String>> environmentResolver,
            UnresolvedEnvironmentPolicy unresolvedEnvironmentPolicy) {
        List<WorkflowConfigFinding> findings = new ArrayList<>();
        Map<String, Object> tracker = object(root, "tracker");
        Map<String, Object> polling = object(root, "polling");
        Map<String, Object> hooks = object(root, "hooks");
        Map<String, Object> agent = object(root, "agent");
        Map<String, Object> codex = object(root, "codex");
        Map<String, Object> server = object(root, "server");

        WorkflowIntegerSetting serverPort = serverPort(server, environmentResolver, unresolvedEnvironmentPolicy);
        add(findings, serverPort);

        Map<String, Integer> priorityLabels = positiveNormalizedIntegerMap(
                object(tracker, "priority_labels"), DEFAULT_PRIORITIES, "tracker.priority_labels", findings);
        Map<String, Integer> maxConcurrentAgentsByState = positiveNormalizedIntegerMap(
                object(agent, "max_concurrent_agents_by_state"),
                Map.of(),
                "agent.max_concurrent_agents_by_state",
                findings);

        WorkflowIntegerSetting trackerRequestTimeoutMs =
                integerSetting(tracker, "request_timeout_ms", "request_timeout_ms");
        WorkflowIntegerSetting trackerMaxApiRetries = integerSetting(tracker, "max_api_retries", "max_api_retries");
        WorkflowIntegerSetting trackerApiRetryBaseDelayMs =
                integerSetting(tracker, "api_retry_base_delay_ms", "api_retry_base_delay_ms");
        WorkflowIntegerSetting pollingIntervalMs = integerSetting(polling, "interval_ms", "interval_ms");
        WorkflowIntegerSetting hooksTimeoutMs = integerSetting(hooks, "timeout_ms", "timeout_ms");
        WorkflowIntegerSetting agentMaxConcurrentAgents =
                positiveIntegerSetting(agent, "max_concurrent_agents", "max_concurrent_agents");
        WorkflowIntegerSetting agentMaxTurns = positiveIntegerSetting(agent, "max_turns", "max_turns");
        WorkflowIntegerSetting agentMaxRetryBackoffMs =
                integerSetting(agent, "max_retry_backoff_ms", "max_retry_backoff_ms");
        WorkflowIntegerSetting codexTurnTimeoutMs = integerSetting(codex, "turn_timeout_ms", "turn_timeout_ms");
        WorkflowIntegerSetting codexReadTimeoutMs = integerSetting(codex, "read_timeout_ms", "read_timeout_ms");
        WorkflowIntegerSetting codexStallTimeoutMs = integerSetting(codex, "stall_timeout_ms", "stall_timeout_ms");

        add(
                findings,
                trackerRequestTimeoutMs,
                trackerMaxApiRetries,
                trackerApiRetryBaseDelayMs,
                pollingIntervalMs,
                hooksTimeoutMs,
                agentMaxConcurrentAgents,
                agentMaxTurns,
                agentMaxRetryBackoffMs,
                codexTurnTimeoutMs,
                codexReadTimeoutMs,
                codexStallTimeoutMs);
        return new TypedWorkflowConfig(
                trackerRequestTimeoutMs,
                trackerMaxApiRetries,
                trackerApiRetryBaseDelayMs,
                pollingIntervalMs,
                hooksTimeoutMs,
                agentMaxConcurrentAgents,
                agentMaxTurns,
                agentMaxRetryBackoffMs,
                codexTurnTimeoutMs,
                codexReadTimeoutMs,
                codexStallTimeoutMs,
                serverPort,
                priorityLabels,
                maxConcurrentAgentsByState,
                findings);
    }

    public static boolean localServerPortInRange(int port) {
        return port >= MIN_LOCAL_SERVER_PORT && port <= MAX_LOCAL_SERVER_PORT;
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
        return Map.of();
    }

    private static WorkflowIntegerSetting serverPort(
            Map<String, Object> server,
            Function<String, Optional<String>> environmentResolver,
            UnresolvedEnvironmentPolicy unresolvedEnvironmentPolicy) {
        if (!server.containsKey("port")) {
            return WorkflowIntegerSetting.omitted();
        }
        Object configured = server.get("port");
        Optional<String> reference = environmentReferenceName(configured);
        return reference
                .map(environmentName -> environmentResolver
                        .apply(environmentName)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .map(resolved -> parseInteger("server.port", resolved))
                        .orElseGet(() -> unresolvedServerPortReference(unresolvedEnvironmentPolicy, environmentName)))
                .orElseGet(() -> literalServerPort(configured));
    }

    private static WorkflowIntegerSetting unresolvedServerPortReference(
            UnresolvedEnvironmentPolicy unresolvedEnvironmentPolicy, String environmentName) {
        return switch (unresolvedEnvironmentPolicy) {
            case OMIT -> WorkflowIntegerSetting.omitted();
            case THROW_SERVER_PORT ->
                throw new IllegalArgumentException(
                        "Workflow server.port references missing environment variable " + environmentName + ".");
        };
    }

    private static WorkflowIntegerSetting literalServerPort(Object configured) {
        if (configured instanceof String text && text.isBlank()) {
            return new WorkflowIntegerSetting(Optional.empty(), Optional.empty(), true);
        }
        return parseInteger("server.port", configured);
    }

    private static Optional<String> environmentReferenceName(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        return EnvironmentReferences.referenceName(text.trim());
    }

    private static WorkflowIntegerSetting integerSetting(Map<String, Object> root, String key, String path) {
        if (!root.containsKey(key)) {
            return WorkflowIntegerSetting.omitted();
        }
        return parseInteger(path, root.get(key));
    }

    private static WorkflowIntegerSetting positiveIntegerSetting(Map<String, Object> root, String key, String path) {
        WorkflowIntegerSetting parsed = integerSetting(root, key, path);
        return parsed.value()
                .filter(value -> value > 0)
                .map(WorkflowIntegerSetting::valid)
                .or(() -> parsed.value()
                        .map(value -> WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                                path,
                                WorkflowConfigFinding.Kind.NON_POSITIVE,
                                String.valueOf(value),
                                path + " must be positive"))))
                .orElse(parsed);
    }

    private static WorkflowIntegerSetting parseInteger(String path, Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            return WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                    path,
                    WorkflowConfigFinding.Kind.NOT_A_NUMBER,
                    String.valueOf(value),
                    path + " must be a whole number"));
        }
        String text = String.valueOf(value);
        WholeNumbers.Classified classified = WholeNumbers.classify(text);
        return switch (classified.kind()) {
            case WHOLE -> WorkflowIntegerSetting.valid(classified.value());
            case OUT_OF_INT_RANGE ->
                WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                        path,
                        WorkflowConfigFinding.Kind.OUT_OF_INT_RANGE,
                        text.trim(),
                        path + " is out of integer range"));
            case FRACTIONAL ->
                WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                        path, WorkflowConfigFinding.Kind.FRACTIONAL, text.trim(), path + " must be a whole number"));
            case NOT_A_NUMBER ->
                WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                        path, WorkflowConfigFinding.Kind.NOT_A_NUMBER, text.trim(), path + " must be a whole number"));
        };
    }

    private static Map<String, Integer> positiveNormalizedIntegerMap(
            Map<String, Object> configured,
            Map<String, Integer> defaultValues,
            String path,
            List<WorkflowConfigFinding> findings) {
        Map<String, Integer> values = new LinkedHashMap<>(defaultValues);
        configured.forEach((key, value) -> {
            WorkflowIntegerSetting parsed = parseInteger(path + "." + key, value);
            Optional<Integer> positive = parsed.value().filter(integer -> integer > 0);
            positive.ifPresentOrElse(
                    integer -> values.put(StateNames.normalize(key), integer),
                    () -> findings.add(WorkflowConfigFinding.ignored(path + "." + key, String.valueOf(value))));
        });
        return Map.copyOf(values);
    }

    private static void add(List<WorkflowConfigFinding> findings, WorkflowIntegerSetting... settings) {
        for (WorkflowIntegerSetting setting : settings) {
            setting.finding().ifPresent(findings::add);
        }
    }
}
