package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.ProtectedPaths;
import ch.fmartin.symphony.trello.workflow.CodexSandboxPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/// Sends one immutable permission profile for the session. A fresh name prevents local profile
/// entries from merging additional grants into the worker's protected paths.
final class CodexPermissions {
    private final String id;
    private final ObjectNode overrides;

    CodexPermissions(ObjectMapper json, EffectiveConfig config, Path workspace) throws IOException {
        overrides = json.createObjectNode();
        if (config.codex().bypassesSandbox()) {
            id = ":danger-full-access";
            overrides.put("default_permissions", id);
            return;
        }
        id = "symphony-worker-" + UUID.randomUUID();
        overrides.put("default_permissions", id);
        ObjectNode profile = overrides.putObject("permissions").putObject(id);
        JsonNode policy = CodexSandboxPolicy.effectivePolicy(
                json, config.codex().turnSandboxPolicy(), config.codex().additionalWritableRoots(), false);
        boolean readOnly = readOnly(config, policy);
        profile.put("extends", readOnly ? ":read-only" : ":workspace");
        profile.putObject("network")
                .put("enabled", policy != null && policy.path("networkAccess").asBoolean(false));
        Set<Path> protectedPaths =
                ProtectedPaths.aliases(config.credentialSources().files());
        Set<Path> launchPaths = ProtectedPaths.aliases(config.protectedLaunchPaths());
        Set<Path> unwritablePaths = new HashSet<>(protectedPaths);
        unwritablePaths.addAll(launchPaths);
        Set<Path> writableRoots = new HashSet<>();
        if (!readOnly) {
            writableRoots.addAll(ProtectedPaths.aliases(Set.of(workspace)));
        }
        if (policy != null) {
            for (JsonNode root : policy.path("writableRoots")) {
                writableRoots.addAll(ProtectedPaths.aliases(Set.of(Path.of(root.asText()))));
            }
        }
        ObjectNode roots = profile.putObject("workspace_roots");
        for (Path root : writableRoots) {
            for (Path protectedPath : unwritablePaths) {
                if (root.startsWith(protectedPath)) {
                    throw invalid(
                            "cannot grant workspace access: move the workspace outside protected configuration and credential paths");
                }
            }
            roots.put(root.toString(), true);
        }
        ObjectNode filesystem = profile.putObject("filesystem");
        for (Path path : launchPaths) {
            if (protectedPaths.stream().noneMatch(path::startsWith)) {
                filesystem.put(path.toString(), "read");
            }
        }
        for (Path path : ProtectedPaths.outermost(protectedPaths)) {
            filesystem.put(path.toString(), "deny");
        }
        if (policy != null && policy.path("excludeTmpdirEnvVar").asBoolean(false)) {
            filesystem.put(":tmpdir", "read");
        }
        if (policy != null && policy.path("excludeSlashTmp").asBoolean(false)) {
            filesystem.put(":slash_tmp", "read");
        }
    }

    void applyThread(ObjectNode params) {
        params.put("permissions", id);
        params.set("config", overrides);
    }

    void applyTurn(ObjectNode params) {
        params.put("permissions", id);
    }

    void requireSelected(JsonNode response) {
        if (!id.equals(response.path("activePermissionProfile").path("id").asText())) {
            throw invalid(
                    "cannot start protected Codex session: install a Codex version that acknowledges the requested permission profile");
        }
    }

    private static boolean readOnly(EffectiveConfig config, JsonNode policy) {
        if (policy != null) {
            return CodexSandboxPolicy.READ_ONLY.equals(policy.path("type").asText());
        }
        Object sandbox = config.codex().threadSandbox();
        if (sandbox == null || "workspace-write".equals(sandbox)) {
            return false;
        }
        if ("read-only".equals(sandbox)) {
            return true;
        }
        throw invalid("cannot select Codex permissions: use read-only, workspace-write or explicit danger-full-access");
    }

    private static ConfigException invalid(String message) {
        return new ConfigException("codex_permissions_unavailable", message);
    }
}
