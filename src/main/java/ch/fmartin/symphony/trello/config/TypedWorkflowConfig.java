package ch.fmartin.symphony.trello.config;

import java.util.List;
import java.util.Map;

public record TypedWorkflowConfig(
        WorkflowIntegerSetting trackerRequestTimeoutMs,
        WorkflowIntegerSetting trackerMaxApiRetries,
        WorkflowIntegerSetting trackerApiRetryBaseDelayMs,
        WorkflowIntegerSetting pollingIntervalMs,
        WorkflowIntegerSetting hooksTimeoutMs,
        WorkflowIntegerSetting agentMaxConcurrentAgents,
        WorkflowIntegerSetting agentMaxTurns,
        WorkflowIntegerSetting agentMaxRetryBackoffMs,
        WorkflowIntegerSetting codexTurnTimeoutMs,
        WorkflowIntegerSetting codexReadTimeoutMs,
        WorkflowIntegerSetting codexStallTimeoutMs,
        WorkflowIntegerSetting serverPort,
        Map<String, Integer> priorityLabels,
        Map<String, Integer> maxConcurrentAgentsByState,
        List<WorkflowConfigFinding> findings) {
    public TypedWorkflowConfig {
        priorityLabels = Map.copyOf(priorityLabels);
        maxConcurrentAgentsByState = Map.copyOf(maxConcurrentAgentsByState);
        findings = List.copyOf(findings);
    }

    public WorkflowIntegerSetting localServerPortSetting() {
        return serverPort
                .value()
                .map(port -> WorkflowConfigIngestion.localServerPortInRange(port)
                        ? serverPort
                        : WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                                "server.port",
                                port < 0
                                        ? WorkflowConfigFinding.Kind.NEGATIVE
                                        : WorkflowConfigFinding.Kind.OUT_OF_INT_RANGE,
                                String.valueOf(port),
                                "server.port must be an integer between 0 and "
                                        + WorkflowConfigIngestion.MAX_LOCAL_SERVER_PORT
                                        + ".")))
                .orElseGet(() -> serverPort.present() && serverPort.finding().isEmpty()
                        ? WorkflowIntegerSetting.invalid(WorkflowConfigFinding.strict(
                                "server.port",
                                WorkflowConfigFinding.Kind.NOT_A_NUMBER,
                                "",
                                "server.port must be a whole number"))
                        : serverPort);
    }

    public WorkflowServerPortClassification serverPortClassification() {
        return serverPort
                .value()
                .map(WorkflowServerPortClassification::numericPort)
                .orElseGet(() -> serverPort.present()
                        ? WorkflowServerPortClassification.invalidValue()
                        : WorkflowServerPortClassification.omitted());
    }
}
