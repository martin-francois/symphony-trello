package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;

public interface AgentRunner {
    AgentRunResult run(AgentRunRequest request);

    void cancel(String workerIdentity);

    record AgentRunRequest(
            Card card,
            Integer attempt,
            String prompt,
            EffectiveConfig config,
            String workerIdentity,
            AgentEventListener listener) {}
}
