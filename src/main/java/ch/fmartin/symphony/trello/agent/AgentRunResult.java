package ch.fmartin.symphony.trello.agent;

public record AgentRunResult(boolean success, String reason) {
    public static AgentRunResult ok() {
        return new AgentRunResult(true, "normal");
    }

    public static AgentRunResult fail(String reason) {
        return new AgentRunResult(false, reason);
    }
}
