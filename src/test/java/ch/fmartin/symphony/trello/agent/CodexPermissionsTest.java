package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.setup.SourceWorkspaceDefaults;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class CodexPermissionsTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExplicitUnrestrictedThreadOrTurnPolicy(boolean turnPolicy) throws Exception {
        // given
        var config = config(
                turnPolicy
                        ? Map.of("turn_sandbox_policy", Map.of("type", "dangerFullAccess"))
                        : Map.of("thread_sandbox", "danger-full-access"));
        var permissions = new CodexPermissions(json, config, temporary.resolve("workspace"));
        ObjectNode thread = json.createObjectNode();
        ObjectNode turn = json.createObjectNode();

        // when
        permissions.applyThread(thread);
        permissions.applyTurn(turn);

        // then
        assertThat(thread.path("permissions").asText()).isEqualTo(":danger-full-access");
        assertThat(turn.path("permissions")).isEqualTo(thread.path("permissions"));
        assertThat(thread.path("config").has("permissions"))
                .as("unrestricted mode has no deny profile")
                .isFalse();
    }

    @Test
    void preservesReadOnlyAndRejectsMissingProfileAcknowledgement() throws Exception {
        // given
        var permissions = new CodexPermissions(json, config(Map.of("thread_sandbox", "read-only")), temporary);
        ObjectNode thread = json.createObjectNode();

        // when
        permissions.applyThread(thread);

        // then
        JsonNode profile = thread.path("config")
                .path("permissions")
                .path(thread.path("permissions").asText());
        assertThat(profile.path("extends").asText()).isEqualTo(":read-only");
        assertThat(profile.path("workspace_roots")).isEmpty();
        assertThatThrownBy(() -> permissions.requireSelected(json.createObjectNode()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("acknowledges the requested permission profile");
    }

    @ParameterizedTest
    @ValueSource(strings = {"setup-local", "new-board", "start"})
    void sourceSetupDefaultsPermitWorkspacesOutsideProtectedCheckout(String command) throws Exception {
        // given
        Path checkout = Path.of(".").toAbsolutePath().normalize();
        Path workspace = SourceWorkspaceDefaults.workspaceRoot(command)
                .toAbsolutePath()
                .normalize()
                .resolve("TRELLO-test");
        EffectiveConfig base = config(Map.of());
        var config = base.withCredentialSources(base.credentialSources().withFiles(Set.of(checkout)))
                .withProtectedLaunchPaths(Set.of(checkout));
        ObjectNode thread = json.createObjectNode();

        // when
        new CodexPermissions(json, config, workspace).applyThread(thread);

        // then
        JsonNode profile = thread.path("config")
                .path("permissions")
                .path(thread.path("permissions").asText());
        assertThat(profile.path("workspace_roots").path(workspace.toString()).asBoolean())
                .as("the source setup default grants the per-card workspace")
                .isTrue();
        assertThat(profile.path("filesystem").path(checkout.toString()).asText())
                .isEqualTo("deny");
    }

    @CsvSource({
        "setup-local, checkout, checkout/config",
        "start, checkout, checkout/config",
        "setup-local, workspaces, symphony-workspaces",
        "start, workspaces, symphony-workspaces"
    })
    @ParameterizedTest
    void sourceConfigDefaultsPermitWorkspacesOutsideProtectedDirectories(
            String command, String appDirectory, String configDirectory) throws Exception {
        // given
        Path app = temporary.resolve(appDirectory);
        Path configDir = temporary.resolve(configDirectory);
        Path workspace = SourceWorkspaceDefaults.workspaceRoot(
                        command,
                        Map.of(
                                "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString(),
                                "SYMPHONY_TRELLO_APP_HOME", app.toString()))
                .resolve("TRELLO-test");
        EffectiveConfig base = config(Map.of());
        var config = base.withCredentialSources(base.credentialSources().withFiles(Set.of(configDir)))
                .withProtectedLaunchPaths(Set.of(app));
        ObjectNode thread = json.createObjectNode();

        // when
        new CodexPermissions(json, config, workspace).applyThread(thread);

        // then
        JsonNode profile = thread.path("config")
                .path("permissions")
                .path(thread.path("permissions").asText());
        assertThat(profile.path("workspace_roots").path(workspace.toString()).asBoolean())
                .as("source config defaults grant the per-card workspace")
                .isTrue();
        assertThat(profile.path("filesystem").path(configDir.toString()).asText())
                .isEqualTo("deny");
        assertThat(profile.path("filesystem").path(app.toString()).asText()).isEqualTo("read");
    }

    @Test
    void refusesWorkspaceInsideProtectedState() {
        // given
        Path state = temporary.resolve("state");
        EffectiveConfig base = config(Map.of());
        var config = base.withCredentialSources(base.credentialSources().withFiles(Set.of(state)));

        // when
        Throwable thrown = catchThrowable(() -> new CodexPermissions(json, config, state.resolve("workspace")));

        // then
        assertThat(thrown)
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("move the workspace outside protected");
    }

    @Test
    void freshSessionNamesPreventMergingExistingProfileGrants() throws Exception {
        // given
        EffectiveConfig config = config(Map.of());
        ObjectNode first = json.createObjectNode();
        ObjectNode second = json.createObjectNode();

        // when
        new CodexPermissions(json, config, temporary.resolve("workspace")).applyThread(first);
        new CodexPermissions(json, config, temporary.resolve("workspace")).applyThread(second);

        // then
        assertThat(first.path("permissions").asText())
                .startsWith("symphony-worker-")
                .isNotEqualTo(second.path("permissions").asText());
    }

    private EffectiveConfig config(Map<String, Object> codex) {
        return new ConfigResolver(name -> Optional.empty())
                .resolve(new WorkflowDefinition(temporary.resolve("WORKFLOW.md"), Map.of("codex", codex), ""));
    }

    @Test
    void enclosingDenyReplacesNestedDeniesThatPreventSandboxStartup() throws Exception {
        // given
        EffectiveConfig base = config(Map.of());
        var config = base.withCredentialSources(base.credentialSources().withFiles(Set.of(temporary)));
        var permissions = new CodexPermissions(json, config, temporary.resolveSibling("workspace"));
        ObjectNode thread = json.createObjectNode();

        // when
        permissions.applyThread(thread);

        // then
        JsonNode profile = thread.path("config")
                .path("permissions")
                .path(thread.path("permissions").asText());
        assertThat(profile.path("filesystem").properties())
                .containsExactly(
                        Map.entry(temporary.toString(), json.getNodeFactory().textNode("deny")));
    }
}
