package ch.fmartin.symphony.trello.testsupport;

public final class TestWorkflows {
    private TestWorkflows() {}

    public static String workflowWithBoardAndPort(String boardId, int port) {
        return """
                ---
                tracker:
                  kind: trello
                  board_id: "%s"
                server:
                  port: %d
                ---
                Body
                """
                .formatted(boardId, port);
    }

    public static String diagnosticsWorkflowWithPort(int port) {
        return """
                ---
                tracker:
                  board_id: "custom-board-id"
                server:
                  port: %d
                ---
                Body
                """
                .formatted(port);
    }

    public static String diagnosticsWorkflowWithEnvironmentBackedPort() {
        return """
                ---
                tracker:
                  board_id: "$BOARD_ID_REF"
                server:
                  port: $BOARD_STATUS_PORT
                ---
                Body
                """;
    }

    public static String workflowWithRepositoryDefaults(
            String boardId, int port, String repositoryDefaultUrl, String repositoryDefaultPath) {
        return """
                ---
                tracker:
                  kind: trello
                  board_id: "%s"
                repository:
                  default_url: %s
                  default_path: %s
                server:
                  port: %d
                ---
                Existing body
                """
                .formatted(boardId, repositoryDefaultUrl, repositoryDefaultPath, port);
    }

    public static String diagnosticsWorkflowWithMaxAgents(String maxAgentsValue) {
        return """
                ---
                tracker:
                  board_id: "custom-board-id"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                agent:
                  max_concurrent_agents: %s
                server:
                  port: 20731
                ---
                Body
                """
                .formatted(maxAgentsValue);
    }

    public static String diagnosticsWorkflowWithRoutingLists(String routingListValue) {
        return """
                ---
                tracker:
                  board_id: "custom-board-id"
                  active_states: %s
                  terminal_states: %s
                server:
                  port: 20722
                ---
                Body
                """
                .formatted(routingListValue, routingListValue);
    }
}
