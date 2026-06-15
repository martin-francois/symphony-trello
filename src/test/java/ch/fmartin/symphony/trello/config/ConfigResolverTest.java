package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: $SYMPHONY_BOARD_ID
                  resolved_board_id: $SYMPHONY_RESOLVED_BOARD_ID
                server:
                  port: $SYMPHONY_TEST_PORT
                ---
                Prompt
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

    @MethodSource("malformedNumericValues")
    @ParameterizedTest(name = "{0}")
    void rejectsMalformedNumericConfigValues(String name, String section, String expectedMessage) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW." + name + ".md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                %s
                ---
                Prompt
                """
                        .formatted(section));
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("config_value_error");
        assertThat(error).hasMessage(expectedMessage);
    }

    private static Stream<Arguments> malformedNumericValues() {
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
                Arguments.of("overflowing-float-port", "server:\n  port: 1e400", "server.port must be a whole number"),
                Arguments.of(
                        "out-of-range-whole-port",
                        "server:\n  port: 99999999999999999999999",
                        "server.port is out of integer range"));
    }

    @Test
    void emptyPriorityLabelValuesFallBackToDefaultsInsteadOfCrashing() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.empty-priority.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                  priority_labels:
                    rush:
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().priorityLabels()).doesNotContainKey("rush");
    }

    @MethodSource("malformedEnvironmentBackedServerPorts")
    @ParameterizedTest(name = "{0}")
    void rejectsMalformedEnvironmentBackedServerPort(String name, String envValue, String expectedMessage)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW." + name + ".md");
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
                  port: $SYMPHONY_TEST_PORT
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(
                lookup -> "SYMPHONY_TEST_PORT".equals(lookup) ? Optional.of(envValue) : Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("config_value_error");
        assertThat(error).hasMessage(expectedMessage);
    }

    private static Stream<Arguments> malformedEnvironmentBackedServerPorts() {
        return Stream.of(
                Arguments.of("fractional-env-port", "18080.5", "server.port must be a whole number"),
                Arguments.of("huge-env-port", "9999999999", "server.port is out of integer range"));
    }

    @Test
    void acceptsWholeValuedFloatingPointServerPort() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.whole-float-port.md");
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
                  port: 18080.0
                ---
                Prompt
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
        Path workflow = tempDir.resolve("WORKFLOW.out-of-range-port.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                codex:
                  command: fake
                server:
                  port: 70000
                ---
                Prompt
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
        Path workflow = tempDir.resolve("WORKFLOW.ephemeral-port.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                codex:
                  command: fake
                server:
                  port: 0
                ---
                Prompt
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
        Path workflow = tempDir.resolve("WORKFLOW.fractional-priority.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                  priority_labels:
                    rush: 2.5
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().priorityLabels()).doesNotContainKey("rush");
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

    @MethodSource("overlappingListRoleScenarios")
    @ParameterizedTest(name = "{0}")
    void overlappingWorkflowListRolesFailBeforeDispatch(String name, String listRoleSection, String expectedMessage)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW." + name + ".md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                %s
                codex:
                  command: fake
                ---
                Prompt
                """
                        .formatted(listRoleSection));
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("overlapping_tracker_list_roles");
        assertThat(error).hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> overlappingListRoleScenarios() {
        return Stream.of(
                Arguments.of(
                        "active-terminal-name-overlap",
                        "  active_states:\n"
                                + "    - \"Ready for Codex\"\n"
                                + "  terminal_states:\n"
                                + "    - \"Ready  for Codex\"",
                        "active and terminal both use Ready for Codex"),
                Arguments.of(
                        "active-blocked-name-overlap",
                        "  active_states:\n"
                                + "    - \"Ready for Codex\"\n"
                                + "  terminal_states:\n"
                                + "    - \"Done\"\n"
                                + "  blocked_state: \"Ready  for Codex\"",
                        "active and blocked both use Ready for Codex"),
                Arguments.of(
                        "active-terminal-list-id-overlap",
                        "  active_states:\n"
                                + "    - \"Ready for Codex\"\n"
                                + "  active_list_ids:\n"
                                + "    - \"shared-list-id\"\n"
                                + "  terminal_states:\n"
                                + "    - \"Done\"\n"
                                + "  terminal_list_ids:\n"
                                + "    - \"shared-list-id\"",
                        "active and terminal both use shared-list-id"));
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
}
