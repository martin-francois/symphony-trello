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
}
