package ch.fmartin.symphony.trello.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class CodexSandboxPolicy {

    public static final String TYPE = "type";
    public static final String READ_ONLY = "readOnly";
    public static final String WORKSPACE_WRITE = "workspaceWrite";
    public static final String DANGER_FULL_ACCESS = "dangerFullAccess";
    public static final String NETWORK_ACCESS = "networkAccess";
    public static final String WRITABLE_ROOTS = "writableRoots";
    public static final String TURN_SANDBOX_POLICY = "turn_sandbox_policy";
    public static final String ADDITIONAL_WRITABLE_ROOTS = "additional_writable_roots";

    private CodexSandboxPolicy() {}

    public static void validateCodexSection(Object codexValue) {
        validateCodexSection(codexValue, false);
    }

    public static void validateCodexSection(Object codexValue, boolean forceDangerFullAccess) {
        if (codexValue == null) {
            return;
        }
        if (!(codexValue instanceof Map<?, ?> codex)) {
            throw invalid("codex must be an object.");
        }
        if (!forceDangerFullAccess && codex.containsKey(TURN_SANDBOX_POLICY)) {
            validateExplicitPolicy(codex.get(TURN_SANDBOX_POLICY));
        }
        if (!forceDangerFullAccess && codex.containsKey(ADDITIONAL_WRITABLE_ROOTS)) {
            validateAdditionalWritableRoots(codex.get(ADDITIONAL_WRITABLE_ROOTS));
        }
    }

    public static boolean hasExplicitPolicy(Object codexValue) {
        return codexValue instanceof Map<?, ?> codex && codex.containsKey(TURN_SANDBOX_POLICY);
    }

    public static void validateExplicitPolicy(Object value) {
        if (!(value instanceof Map<?, ?> policy) || policy.isEmpty()) {
            throw invalid("codex.turn_sandbox_policy must be an object with a supported type.");
        }
        Object type = policy.get(TYPE);
        if (!(type instanceof String typeName) || typeName.isBlank()) {
            throw invalid("codex.turn_sandbox_policy.type must be readOnly, workspaceWrite, or dangerFullAccess.");
        }
        validateSharedPolicyFields(policy);
        switch (typeName) {
            case READ_ONLY, WORKSPACE_WRITE, DANGER_FULL_ACCESS -> {
                // Supported as an explicit operator-selected policy.
            }
            default ->
                throw invalid("codex.turn_sandbox_policy.type must be readOnly, workspaceWrite, or dangerFullAccess.");
        }
    }

    public static JsonNode effectivePolicy(
            ObjectMapper json,
            Object configuredPolicy,
            List<Path> additionalWritableRoots,
            boolean forceDangerFullAccess) {
        validateResolvedPolicy(configuredPolicy, additionalWritableRoots, forceDangerFullAccess);
        if (forceDangerFullAccess) {
            ObjectNode danger = json.createObjectNode();
            danger.put(TYPE, DANGER_FULL_ACCESS);
            return danger;
        }

        if (additionalWritableRoots.isEmpty()) {
            return configuredPolicy == null ? null : json.valueToTree(configuredPolicy);
        }
        if (configuredPolicy == null) {
            ObjectNode policy = json.createObjectNode();
            policy.put(TYPE, WORKSPACE_WRITE);
            appendWritableRoots(policy, additionalWritableRoots);
            return policy;
        }
        ObjectNode policy = json.valueToTree(configuredPolicy);
        String type = policy.path(TYPE).asText();
        if (DANGER_FULL_ACCESS.equals(type)) {
            return policy;
        }
        if (!WORKSPACE_WRITE.equals(type)) {
            throw invalid("additional writable roots require a workspaceWrite sandbox policy.");
        }
        appendWritableRoots(policy, additionalWritableRoots);
        return policy;
    }

    public static void validateResolvedPolicy(
            Object configuredPolicy, List<Path> additionalWritableRoots, boolean forceDangerFullAccess) {
        if (forceDangerFullAccess) {
            return;
        }
        if (configuredPolicy != null) {
            validateExplicitPolicy(configuredPolicy);
        }
        if (additionalWritableRoots.isEmpty()) {
            return;
        }
        validateAdditionalWritableRootsPolicy(configuredPolicy);
    }

    private static void validateSharedPolicyFields(Map<?, ?> policy) {
        Object networkAccess = policy.get(NETWORK_ACCESS);
        if (networkAccess != null && !(networkAccess instanceof Boolean)) {
            throw invalid("codex.turn_sandbox_policy.networkAccess must be true or false.");
        }
        if (policy.containsKey(WRITABLE_ROOTS)) {
            validateStringList("codex.turn_sandbox_policy.writableRoots", policy.get(WRITABLE_ROOTS));
        }
    }

    private static void validateAdditionalWritableRoots(Object value) {
        validateStringList("codex.additional_writable_roots", value);
    }

    private static void validateAdditionalWritableRootsPolicy(Object policyValue) {
        if (policyValue == null) {
            return;
        }
        if (!(policyValue instanceof Map<?, ?> policy)) {
            return;
        }
        Object type = policy.get(TYPE);
        if (READ_ONLY.equals(type)) {
            throw invalid(
                    "codex.additional_writable_roots require a workspaceWrite or dangerFullAccess sandbox policy.");
        }
    }

    private static void validateStringList(String field, Object value) {
        if (!(value instanceof List<?> items)) {
            throw invalid(field + " must be a list of paths.");
        }
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalid(field + " must contain only non-blank path strings.");
            }
        }
    }

    private static void appendWritableRoots(ObjectNode policy, List<Path> roots) {
        ArrayNode writableRoots = policy.withArray(WRITABLE_ROOTS);
        for (Path root : roots) {
            String text = root.toString();
            if (!containsText(writableRoots, text)) {
                writableRoots.add(text);
            }
        }
    }

    private static boolean containsText(ArrayNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static InvalidPolicyException invalid(String message) {
        return new InvalidPolicyException(message);
    }

    public static final class InvalidPolicyException extends RuntimeException {
        private InvalidPolicyException(String message) {
            super(message);
        }
    }
}
