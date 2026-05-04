package com.openai.symphony.agent;

@FunctionalInterface
public interface AgentEventListener {
    void onEvent(AgentEvent event);
}
