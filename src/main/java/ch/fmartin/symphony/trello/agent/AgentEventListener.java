package ch.fmartin.symphony.trello.agent;

@FunctionalInterface
public interface AgentEventListener {
    void onEvent(AgentEvent event);
}
