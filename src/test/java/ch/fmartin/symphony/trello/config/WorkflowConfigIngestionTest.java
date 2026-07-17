package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkflowConfigIngestionTest {
    @Test
    void appliesSharedWholeNumberPolicyToLiteralAndEnvironmentBackedServerPort() {
        // given
        Map<String, Object> literal = Map.of("server", Map.of("port", 18080.0));
        Map<String, Object> envBacked = Map.of("server", Map.of("port", "$STATUS_PORT"));

        // when
        TypedWorkflowConfig literalConfig = WorkflowConfigIngestion.collect(literal, ignored -> Optional.empty());
        TypedWorkflowConfig envConfig = WorkflowConfigIngestion.collect(
                envBacked, name -> "STATUS_PORT".equals(name) ? Optional.of("18080.0") : Optional.empty());

        // then
        assertThat(literalConfig.serverPort().value()).contains(18080);
        assertThat(envConfig.serverPort().value()).contains(18080);
    }

    @Test
    void distinguishesFractionalAndOutOfRangeIntegerSettings() {
        // given
        Map<String, Object> workflow = Map.of(
                "agent", Map.of("max_concurrent_agents", 1.5), "server", Map.of("port", "99999999999999999999999"));

        // when
        TypedWorkflowConfig config = WorkflowConfigIngestion.collect(workflow, ignored -> Optional.empty());

        // then
        assertThat(config.agentMaxConcurrentAgents().finding()).hasValueSatisfying(finding -> assertThat(finding.kind())
                .isEqualTo(WorkflowConfigFinding.Kind.FRACTIONAL));
        assertThat(config.serverPort().finding()).hasValueSatisfying(finding -> assertThat(finding.kind())
                .isEqualTo(WorkflowConfigFinding.Kind.OUT_OF_INT_RANGE));
    }

    @Test
    void classifiesLocalServerPortRangeWithoutChangingRuntimeTypedValue() {
        // given
        Map<String, Object> workflow = Map.of("server", Map.of("port", 70000));

        // when
        TypedWorkflowConfig config = WorkflowConfigIngestion.collect(workflow, ignored -> Optional.empty());

        // then
        assertThat(config.serverPort().value()).contains(70000);
        assertThat(config.localServerPortSetting().diagnosticsCell()).isEqualTo("invalid");
        assertThat(config.serverPortClassification().kind())
                .isEqualTo(WorkflowServerPortClassification.Kind.OUT_OF_RANGE);
    }

    @Test
    void classifiesBlankServerPortAsInvalidForDiagnostics() {
        // given
        Map<String, Object> workflow = Map.of("server", Map.of("port", ""));

        // when
        TypedWorkflowConfig config = WorkflowConfigIngestion.collect(workflow, ignored -> Optional.empty());

        // then
        assertThat(config.serverPort()).isEqualTo(new WorkflowIntegerSetting(Optional.empty(), Optional.empty(), true));
        assertThat(config.localServerPortSetting())
                .isEqualTo(WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                        "server.port",
                        WorkflowConfigFinding.Kind.NOT_A_NUMBER,
                        "",
                        "server.port must be a whole number")));
        assertThat(config.serverPortClassification()).isEqualTo(WorkflowServerPortClassification.invalidValue());
    }

    @Test
    void treatsUnresolvedEnvironmentServerPortAsOmittedOrStrictFailureByPolicy() {
        // given
        String frontMatter = """
                server:
                  port: $STATUS_PORT
                """;

        // when
        Optional<TypedWorkflowConfig> omitted = WorkflowConfigIngestion.collectFrontMatter(
                frontMatter, ignored -> Optional.empty(), WorkflowConfigIngestion.UnresolvedEnvironmentPolicy.OMIT);

        // then
        assertThat(omitted).hasValueSatisfying(config -> assertThat(config.serverPort())
                .isEqualTo(WorkflowIntegerSetting.omitted()));
        assertThatThrownBy(() -> WorkflowConfigIngestion.collectFrontMatter(
                        frontMatter,
                        ignored -> Optional.empty(),
                        WorkflowConfigIngestion.UnresolvedEnvironmentPolicy.THROW_SERVER_PORT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workflow server.port references missing environment variable STATUS_PORT.");
    }

    @Test
    void recordsIgnoredFindingsForQuietPositiveIntegerMaps() {
        // given
        Map<String, Object> workflow = Map.of(
                "tracker",
                Map.of("priority_labels", Map.of("P1", 1, "Rush", 2.5)),
                "agent",
                Map.of("max_concurrent_agents_by_state", Map.of("Ready for Codex", 2, "Blocked", 0)));

        // when
        TypedWorkflowConfig config = WorkflowConfigIngestion.collect(workflow, ignored -> Optional.empty());

        // then
        assertThat(config.priorityLabels()).containsEntry("p1", 1).doesNotContainKey("rush");
        assertThat(config.maxConcurrentAgentsByState())
                .containsEntry("ready for codex", 2)
                .doesNotContainKey("blocked");
        assertThat(config.findings())
                .extracting(WorkflowConfigFinding::path)
                .contains("tracker.priority_labels.Rush", "agent.max_concurrent_agents_by_state.Blocked");
    }
}
