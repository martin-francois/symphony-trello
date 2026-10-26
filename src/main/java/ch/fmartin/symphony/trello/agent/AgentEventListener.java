package ch.fmartin.symphony.trello.agent;

@FunctionalInterface
public interface AgentEventListener {
    void onEvent(AgentEvent event);

    /// Publishes an event and reports whether the consumer accepted it as current. Consumers that
    /// guard worker identity override this hook so rejected telemetry cannot affect shared client
    /// state; ordinary listeners retain the one-way callback behavior.
    default boolean onEventAndReportAccepted(AgentEvent event) {
        onEvent(event);
        return true;
    }

    /// Registers an owned move before the remote write. A rejected owner must not perform the write.
    default boolean beforeHandoffMove(CardHandoffMove move) {
        return true;
    }

    default void afterHandoffMove(CardHandoffMove move, boolean succeeded) {}

    /// Called after a turn completes successfully, before any continuation decision.
    default void onSuccessfulTurn(String threadId, String turnId) {}
}
