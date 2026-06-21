package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class ConfigResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesWorkflowEnvironmentReferencesForBoardScopeAndServerPort() throws Exception {
        // given
        Path workflow = writeTrelloWorkflow(
                "WORKFLOW.md",
                "$SYMPHONY_BOARD_ID",
                """
                  resolved_board_id: $SYMPHONY_RESOLVED_BOARD_ID
                server:
                  port: $SYMPHONY_TEST_PORT
                """);
        ConfigResolver resolver = new ConfigResolver(name -> switch (name) {
            case "SYMPHONY_BOARD_ID" -> Optional.of("board-shortlink");
            case "SYMPHONY_RESOLVED_BOARD_ID" -> Optional.of("resolved-board-id");
            case "SYMPHONY_TEST_PORT" -> Optional.of("19093");
            default -> Optional.empty();
        });

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().boardId()).isEqualTo("board-shortlink");
        assertThat(config.tracker().resolvedBoardId()).isEqualTo("resolved-board-id");
        assertThat(config.server().port()).hasValue(19093);
    }

    @MethodSource("fractionalNumericValues")
    @ParameterizedTest
    void rejectsFractionalNumericValuesInsteadOfTruncatingThem(String name, String section, String expectedMessage)
            throws Exception {
        // given
        Path workflow = writeDefaultWorkflow("WORKFLOW." + name + ".md", section);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("config_value_error");
        assertThat(error).hasMessage(expectedMessage);
    }

    private static Stream<Arguments> fractionalNumericValues() {
        return Stream.of(
                Arguments.of("fractional-port", "server:\n  port: 18080.5", "server.port must be a whole number"),
                Arguments.of(
                        "fractional-agents",
                        "agent:\n  max_concurrent_agents: 1.5",
                        "max_concurrent_agents must be a whole number"),
                Arguments.of(
                        "non-numeric-retries",
                        "tracker:\n  max_api_retries: not-a-number",
                        "max_api_retries must be a whole number"),
                Arguments.of("overflowing-float-port", "server:\n  port: 1e400", "server.port must be a whole number"));
    }

    @MethodSource("malformedObjectSections")
    @ParameterizedTest
    void rejectsMalformedObjectSectionsInsteadOfIgnoringThem(String name, String section, String expectedMessage)
            throws Exception {
        // given
        Path workflow = writeDefaultWorkflow("WORKFLOW." + name + ".md", section);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("config_type_error");
        assertThat(error).hasMessage(expectedMessage);
    }

    private static Stream<Arguments> malformedObjectSections() {
        return Stream.of(
                Arguments.of("scalar-server", "server: 18080", "server must be an object"),
                Arguments.of("scalar-polling", "polling: disabled", "polling must be an object"));
    }

    @Test
    void emptyPriorityLabelValuesFallBackToDefaultsInsteadOfCrashing() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.empty-priority.md",
                """
                  priority_labels:
                    rush:
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().priorityLabels()).doesNotContainKey("rush");
    }

    @Test
    void rejectsFractionalEnvironmentBackedServerPortInsteadOfCrashingRaw() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.fractional-env-port.md",
                """
                server:
                  port: $SYMPHONY_FRACTIONAL_PORT
                """);
        ConfigResolver resolver = new ConfigResolver(
                name -> "SYMPHONY_FRACTIONAL_PORT".equals(name) ? Optional.of("18080.5") : Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("config_value_error");
        assertThat(error).hasMessage("server.port must be a whole number");
    }

    @Test
    void reportsWholeButTooLargeServerPortAsOutOfRangeNotAsFractional() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.huge-port.md",
                """
                server:
                  port: 99999999999999999999999
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("config_value_error");
        assertThat(error).hasMessage("server.port is out of integer range");
    }

    @Test
    void reportsWholeButTooLargeEnvironmentBackedServerPortAsOutOfRange() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.huge-env-port.md",
                """
                server:
                  port: $SYMPHONY_HUGE_PORT
                """);
        ConfigResolver resolver = new ConfigResolver(
                name -> "SYMPHONY_HUGE_PORT".equals(name) ? Optional.of("9999999999") : Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("config_value_error");
        assertThat(error).hasMessage("server.port is out of integer range");
    }

    @Test
    void acceptsWholeValuedFloatingPointServerPort() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.whole-float-port.md",
                """
                server:
                  port: 18080.0
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.server().port()).hasValue(18080);
    }

    @Test
    void keepsOutOfRangePortsResolvableBecauseRangePolicyLivesAtTheLaunchLayer() throws Exception {
        // given
        // The resolved server.port has no runtime consumer (the worker reads its HTTP port from
        // the raw workflow or the SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT overrides), so rejecting
        // ranges here would only break deployed workers whose unused workflow port is stale.
        // Range validation stays at the launch layer (WorkflowConfigEditor/LocalPort).
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.out-of-range-port.md",
                """
                codex:
                  command: fake
                server:
                  port: 70000
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        Throwable dispatchFailure = catchThrowable(() -> resolver.validateForDispatch(config));

        // then
        assertThat(config.server().port()).hasValue(70000);
        assertThat(dispatchFailure).isNull();
    }

    @Test
    void keepsEphemeralServerPortZeroDispatchable() throws Exception {
        // given
        // SPEC.md allows server.port 0 for temporary local runs on an ephemeral port.
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.ephemeral-port.md",
                """
                codex:
                  command: fake
                server:
                  port: 0
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        Throwable dispatchFailure = catchThrowable(() -> resolver.validateForDispatch(config));

        // then
        assertThat(config.server().port()).hasValue(0);
        assertThat(dispatchFailure).isNull();
    }

    @Test
    void fractionalPriorityLabelValuesFallBackToDefaultsInsteadOfTruncating() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.fractional-priority.md",
                """
                  priority_labels:
                    rush: 2.5
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().priorityLabels()).doesNotContainKey("rush");
    }

    @Test
    void requiredLabelsDefaultEmptyAndConfiguredLabelsAreNormalizedWithoutDroppingBlanks() throws Exception {
        // given
        Path configuredWorkflow = tempDir.resolve("WORKFLOW.required-labels.md");
        Files.writeString(
                configuredWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                  required_labels:
                    - " Ready For Codex "
                    - ""
                ---
                Prompt
                """);
        Path defaultWorkflow = tempDir.resolve("WORKFLOW.default-required-labels.md");
        Files.writeString(
                defaultWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig configured = resolver.resolve(new WorkflowLoader().load(configuredWorkflow));
        EffectiveConfig defaults = resolver.resolve(new WorkflowLoader().load(defaultWorkflow));

        // then
        assertThat(configured.tracker().requiredLabels()).containsExactly("ready for codex", "");
        assertThat(defaults.tracker().requiredLabels()).isEmpty();
    }

    @Test
    void unresolvedWorkflowBoardIdEnvironmentReferenceFailsBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: $SYMPHONY_MISSING_BOARD_ID
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(config.tracker().boardId()).isNull();
        assertThat(error.code()).isEqualTo("missing_tracker_board_id");
        assertThat(error).hasMessage("tracker.board_id is required");
    }

    @Test
    void overlappingWorkflowListRolesFailBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Ready  for Codex"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("overlapping_tracker_list_roles");
        assertThat(error).hasMessageContaining("active and terminal both use Ready for Codex");
    }

    @Test
    void overlappingBlockedWorkflowListRoleFailsBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.blocked-overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                  blocked_state: "Ready  for Codex"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("overlapping_tracker_list_roles");
        assertThat(error).hasMessageContaining("active and blocked both use Ready for Codex");
    }

    @Test
    void overlappingListNamesAreAllowedWhenListIdsAreAuthoritative() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.list-ids.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Done"
                  active_list_ids:
                    - "list-active-done"
                  terminal_states:
                    - "Done"
                  terminal_list_ids:
                    - "list-terminal-done"
                  blocked_state: "Done"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).isNull();
    }

    @Test
    void overlappingListIdsFailBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.list-id-overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                  active_list_ids:
                    - "shared-list-id"
                  terminal_states:
                    - "Done"
                  terminal_list_ids:
                    - "shared-list-id"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("overlapping_tracker_list_roles");
        assertThat(error).hasMessageContaining("active and terminal both use shared-list-id");
    }

    @Test
    void duplicateValuesWithinOneRoleAreAllowedBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.duplicate-role-values.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                    - "Ready  for Codex"
                  terminal_states:
                    - "Done"
                    - "Done "
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).isNull();
    }

    @Test
    void rejectsNonPositivePollingInterval() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                polling:
                  interval_ms: 0
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error).hasMessageContaining("interval_ms must be positive");
    }

    @Test
    void rejectsInvalidTrackerEndpointBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                  endpoint: "not-a-url"
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).hasMessageContaining("tracker.endpoint must be an absolute http(s) URL");
    }

    @Test
    void acceptsLoopbackHttpTrackerEndpointForDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                  endpoint: "http://127.0.0.1:18099/1"
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        Throwable error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).isNull();
    }

    @Test
    void acceptsUppercaseSchemeInTrackerEndpointLikeSetupNormalization() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  endpoint: "HTTPS://api.trello.com/1"
                  api_key: key
                  api_token: token
                  board_id: board-1
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        resolver.validateForDispatch(config);

        // then
        assertThat(config.tracker().endpoint()).isEqualTo("HTTPS://api.trello.com/1");
    }

    @Test
    void resolvesBraceStyleEnvironmentReferencesForCredentials() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: "${SYMPHONY_TEST_KEY}"
                  api_token: "${SYMPHONY_TEST_TOKEN}"
                  board_id: board-1
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> switch (name) {
            case "SYMPHONY_TEST_KEY" -> Optional.of("resolved-key");
            case "SYMPHONY_TEST_TOKEN" -> Optional.of("resolved-token");
            default -> Optional.empty();
        });

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().apiKey()).isEqualTo("resolved-key");
        assertThat(config.tracker().apiToken()).isEqualTo("resolved-token");
    }

    @Test
    void keepsShellDefaultExpansionSyntaxAsLiteralText() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: "${SYMPHONY_TEST_KEY:-fallback}"
                  api_token: literal-token
                  board_id: board-1
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> Optional.of("should-not-resolve"));

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().apiKey()).isEqualTo("${SYMPHONY_TEST_KEY:-fallback}");
    }

    @Test
    void preservesReadOnlyCodexSandboxPolicyWithoutWritableRootMerge() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.read-only-sandbox.md",
                """
                codex:
                  command: codex app-server
                  turn_sandbox_policy:
                    type: readOnly
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.codex().turnSandboxPolicy()).isEqualTo(Map.of("type", "readOnly"));
    }

    @Test
    void rejectsReadOnlySandboxPolicyWithAdditionalWritableRoots() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.read-only-sandbox-with-roots.md",
                """
                codex:
                  command: codex app-server
                  turn_sandbox_policy:
                    type: readOnly
                  additional_writable_roots:
                    - /allowed/project
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("codex_sandbox_policy_invalid");
        assertThat(error)
                .hasMessage(
                        "codex.additional_writable_roots require a workspaceWrite or dangerFullAccess sandbox policy.");
    }

    @Test
    void rejectsReadOnlySandboxPolicyWithEnvironmentAdditionalWritableRoots() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.read-only-sandbox-with-env-roots.md",
                """
                codex:
                  command: codex app-server
                  turn_sandbox_policy:
                    type: readOnly
                """);
        ConfigResolver resolver = new ConfigResolver(name -> "SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS".equals(name)
                ? Optional.of("/allowed/env")
                : Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("codex_sandbox_policy_invalid");
        assertThat(error)
                .hasMessage(
                        "codex.additional_writable_roots require a workspaceWrite or dangerFullAccess sandbox policy.");
    }

    @Test
    void forceDangerFullAccessIgnoresReadOnlyAdditionalWritableRootConflict() throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.read-only-sandbox-force-danger.md",
                """
                codex:
                  command: codex app-server
                  turn_sandbox_policy:
                    type: readOnly
                  additional_writable_roots:
                    - /allowed/project
                """);
        ConfigResolver resolver = new ConfigResolver(
                name -> "SYMPHONY_CODEX_DANGER_FULL_ACCESS".equals(name) ? Optional.of("true") : Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.codex().forceDangerFullAccess()).isTrue();
        assertThat(config.codex().turnSandboxPolicy()).isEqualTo(Map.of("type", "readOnly"));
        assertThat(config.codex().additionalWritableRoots()).isEmpty();
    }

    @MethodSource("invalidSandboxFallbacksIgnoredWhenDangerFullAccessIsForced")
    @ParameterizedTest
    @SuppressWarnings("JUnitValueSource")
    void forceDangerFullAccessIgnoresInvalidConfiguredSandboxFallback(String codexYaml) throws Exception {
        // given
        Path workflow = writeDefaultWorkflow(
                "WORKFLOW.force-danger-invalid-sandbox.md",
                """
                codex:
                  command: codex app-server
                %s
                """
                        .formatted(codexYaml.indent(2)));
        ConfigResolver resolver = new ConfigResolver(
                name -> "SYMPHONY_CODEX_DANGER_FULL_ACCESS".equals(name) ? Optional.of("true") : Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.codex().forceDangerFullAccess()).isTrue();
        assertThat(config.codex().additionalWritableRoots()).isEmpty();
    }

    @Test
    void forceDangerFullAccessIgnoresInvalidCurrentSandboxPolicy() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.force-danger-invalid-sandbox.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                server:
                  port: 18080
                codex:
                  command: codex app-server
                  turn_sandbox_policy: []
                ---
                ## Operating Posture

                This is an unattended orchestration run.

                ## Pull Request Publication

                Create a pull request when needed.

                ## Trello List Routing

                Card URL: {{ card.url }}
                """,
                StandardCharsets.UTF_8);
        ConfigResolver resolver = new ConfigResolver(
                name -> "SYMPHONY_CODEX_DANGER_FULL_ACCESS".equals(name) ? Optional.of("true") : Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.codex().forceDangerFullAccess()).isTrue();
        assertThat(config.codex().turnSandboxPolicy()).isEqualTo(List.of());
    }

    private static Stream<String> invalidSandboxFallbacksIgnoredWhenDangerFullAccessIsForced() {
        return Stream.of(
                "turn_sandbox_policy: workspaceWrite",
                "turn_sandbox_policy: []",
                "turn_sandbox_policy: {}",
                "turn_sandbox_policy:\n  type: futurePolicy\n",
                "turn_sandbox_policy:\n  type: workspaceWrite\n  networkAccess: \"true\"\n",
                "additional_writable_roots: /ignored-when-danger-is-forced");
    }

    private Path writeDefaultWorkflow(String fileName, String extraFrontMatter) throws Exception {
        return writeTrelloWorkflow(fileName, "board-1", extraFrontMatter);
    }

    private Path writeTrelloWorkflow(String fileName, String boardId, String extraFrontMatter) throws Exception {
        return writeWorkflow(
                fileName,
                """
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: %s
                %s
                """
                        .formatted(boardId, extraFrontMatter));
    }

    private Path writeWorkflow(String fileName, String frontMatter) throws Exception {
        Path workflow = tempDir.resolve(fileName);
        Files.writeString(
                workflow,
                """
                ---
                %s
                ---
                Prompt
                """
                        .formatted(frontMatter));
        return workflow;
    }
}
