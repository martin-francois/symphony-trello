package ch.fmartin.symphony.trello.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;

import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.TypedWorkflowConfig;
import ch.fmartin.symphony.trello.config.WorkflowConfigIngestion;
import ch.fmartin.symphony.trello.config.WorkflowIntegerSetting;
import ch.fmartin.symphony.trello.config.WorkflowServerPortClassification;
import ch.fmartin.symphony.trello.workflow.CodexSandboxPolicy;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/// Resolves a loaded workflow into typed configuration the way the service does before dispatch, and
/// checks properties of the result. Shared by the standalone `WorkflowLoaderFuzzer` and the JUnit
/// `WorkflowLoaderFuzzTest`. It throws [AssertionError] instead of using AssertJ because the OSS-Fuzz
/// runtime classpath has no test libraries.
///
/// Resolution reads only the fixed environments and in-memory secret files below, never the process
/// environment, a `.env` file, or a host file. A [ConfigException] is the expected rejection of bad
/// configuration. Any other exception escapes to the fuzzer as a finding.
public final class WorkflowConfigInvariants {
    // The seed corpus refers to these names, so mutations start from references that resolve.
    private static final Map<String, String> BASE_ENVIRONMENT = Map.of(
            "TRELLO_API_KEY", "env-api-key",
            "TRELLO_API_TOKEN", "env-api-token",
            "SYMPHONY_FUZZ_BOARD", "env-board",
            "SYMPHONY_FUZZ_BLANK", " ",
            "SYMPHONY_FUZZ_PORT", "18080",
            "SYMPHONY_FUZZ_HIGH_PORT", "65536",
            "SYMPHONY_FUZZ_FRACTIONAL_PORT", "8080.5",
            "SYMPHONY_FUZZ_ROOT", "/srv/symphony",
            "SYMPHONY_FUZZ_RELATIVE_ROOT", "relative/root");
    // The operator overrides change which sandbox rules apply, so each input is resolved once
    // without them and once with each of them.
    private static final List<Map<String, String>> ENVIRONMENTS = List.of(
            BASE_ENVIRONMENT,
            withVariable(
                    ConfigResolver.ADDITIONAL_WRITABLE_ROOTS_ENVIRONMENT,
                    String.join(File.pathSeparator, "extra-root", "/srv/extra-root", " ")),
            withVariable(ConfigResolver.DANGER_FULL_ACCESS_ENVIRONMENT, "true"));
    // Keyed by file name, so relative and absolute `file:` references reach the same secret.
    private static final Map<String, String> SECRET_FILES = Map.of(
            "trello-api-key", "file-api-key\n",
            "trello-api-token", "file-api-token\r\n",
            "blank-secret", "\n");
    private static final String OVERSIZED_SECRET_FILE = "oversized-secret";
    private static final ConfigResolver.SecretFiles IN_MEMORY_SECRET_FILES = new ConfigResolver.SecretFiles() {
        @Override
        public long size(Path path) throws IOException {
            return OVERSIZED_SECRET_FILE.equals(fileName(path))
                    ? Long.MAX_VALUE
                    : secret(path).getBytes(UTF_8).length;
        }

        @Override
        public String readString(Path path) throws IOException {
            check(!OVERSIZED_SECRET_FILE.equals(fileName(path)), "a secret file over the size limit is never read");
            return secret(path);
        }
    };
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z]+(_[a-z]+)*");
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowConfigInvariants() {}

    public static void assertResolutionProperties(WorkflowDefinition workflow) {
        for (Map<String, String> environment : ENVIRONMENTS) {
            assertResolutionProperties(workflow, environment);
        }
    }

    private static void assertResolutionProperties(WorkflowDefinition workflow, Map<String, String> environment) {
        Function<String, Optional<String>> lookup = name -> Optional.ofNullable(environment.get(name));
        var resolver = new ConfigResolver(lookup, IN_MEMORY_SECRET_FILES);
        EffectiveConfig config;
        try {
            config = resolver.resolve(workflow);
        } catch (ConfigException rejected) {
            assertExplicitRejection(rejected);
            return;
        }
        assertResolvedValues(workflow, config, environment.containsKey(ConfigResolver.DANGER_FULL_ACCESS_ENVIRONMENT));
        assertServerPortViewsAgree(WorkflowConfigIngestion.collect(workflow, lookup), config);
        assertSandboxPolicyBuilds(config.codex());
        try {
            resolver.validateForDispatch(config);
        } catch (ConfigException rejected) {
            assertExplicitRejection(rejected);
        }
    }

    private static void assertExplicitRejection(ConfigException rejected) {
        check(
                rejected.code() != null && ERROR_CODE.matcher(rejected.code()).matches(),
                "rejection has a snake_case error code");
        check(rejected.getMessage() != null && !rejected.getMessage().isBlank(), "rejection explains the problem");
    }

    private static void assertResolvedValues(WorkflowDefinition workflow, EffectiveConfig config, boolean danger) {
        check(workflow.path().equals(config.workflowPath()), "config keeps the workflow path");
        List<Path> paths = new ArrayList<>(config.codex().additionalWritableRoots());
        paths.add(config.workspace().root());
        if (config.repository().defaultPath() != null) {
            paths.add(config.repository().defaultPath());
        }
        for (Path path : paths) {
            check(path.isAbsolute() && path.equals(path.normalize()), "resolved paths are absolute and normal");
        }
        List<Duration> durations = List.of(
                config.tracker().requestTimeout(),
                config.tracker().apiRetryBaseDelay(),
                config.hooks().timeout(),
                config.agent().maxRetryBackoff(),
                config.codex().turnTimeout(),
                config.codex().readTimeout(),
                config.codex().stallTimeout());
        for (Duration duration : durations) {
            check(!duration.isNegative(), "timeouts and delays are not negative");
        }
        check(config.polling().interval().compareTo(Duration.ZERO) > 0, "the polling interval is positive");
        check(config.agent().maxConcurrentAgents() > 0 && config.agent().maxTurns() > 0, "agent limits are positive");
        List<Integer> limits = new ArrayList<>(config.tracker().priorityLabels().values());
        limits.addAll(config.agent().maxConcurrentAgentsByState().values());
        for (int limit : limits) {
            check(limit > 0, "priorities and per-state limits are positive");
        }
        check(config.codex().forceDangerFullAccess() == danger, "only the operator override forces full access");
        check(
                !danger || config.codex().additionalWritableRoots().isEmpty(),
                "forced full access has no additional writable roots");
    }

    // Setup diagnostics read the port through the typed view; the service binds the resolved one.
    private static void assertServerPortViewsAgree(TypedWorkflowConfig typed, EffectiveConfig config) {
        OptionalInt servicePort = config.server().port();
        Optional<Integer> resolvedPort =
                servicePort.isPresent() ? Optional.of(servicePort.getAsInt()) : Optional.empty();
        WorkflowServerPortClassification classification = typed.serverPortClassification();
        check(classification.port().equals(resolvedPort), "diagnostics classify the port the service resolved");
        check(
                classification.probeOrSkipPort().equals(resolvedPort),
                "diagnostics probe or skip exactly the resolved port");
        WorkflowIntegerSetting localPort = typed.localServerPortSetting();
        check(
                localPort.value().isPresent() == (classification.kind() == WorkflowServerPortClassification.Kind.VALID),
                "setup accepts a local port exactly when diagnostics classify it as valid");
        localPort
                .value()
                .ifPresent(port ->
                        check(WorkflowConfigIngestion.localServerPortInRange(port), "an accepted port is in range"));
    }

    // The Codex client builds this policy for every turn of a dispatched card.
    private static void assertSandboxPolicyBuilds(EffectiveConfig.CodexConfig codex) {
        JsonNode policy = CodexSandboxPolicy.effectivePolicy(
                JSON, codex.turnSandboxPolicy(), codex.additionalWritableRoots(), codex.forceDangerFullAccess());
        boolean fullAccess = CodexSandboxPolicy.DANGER_FULL_ACCESS.equals(
                policy == null ? null : policy.path(CodexSandboxPolicy.TYPE).asText());
        if (codex.forceDangerFullAccess()) {
            check(fullAccess, "forced full access sends a dangerFullAccess policy");
            return;
        }
        if (codex.additionalWritableRoots().isEmpty() || fullAccess) {
            return;
        }
        Set<String> writableRoots = new HashSet<>();
        policy.path(CodexSandboxPolicy.WRITABLE_ROOTS).forEach(root -> writableRoots.add(root.asText()));
        for (Path root : codex.additionalWritableRoots()) {
            check(writableRoots.contains(root.toString()), "the sandbox policy grants every additional root");
        }
    }

    private static Map<String, String> withVariable(String name, String value) {
        Map<String, String> environment = new HashMap<>(BASE_ENVIRONMENT);
        environment.put(name, value);
        return Map.copyOf(environment);
    }

    private static String secret(Path path) throws NoSuchFileException {
        String content = SECRET_FILES.get(fileName(path));
        if (content == null) {
            throw new NoSuchFileException(path.toString());
        }
        return content;
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static void check(boolean property, String description) {
        if (!property) {
            throw new AssertionError("workflow config property violated: " + description);
        }
    }
}
