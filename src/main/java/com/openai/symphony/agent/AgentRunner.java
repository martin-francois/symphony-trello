package com.openai.symphony.agent;

import com.openai.symphony.config.EffectiveConfig;
import com.openai.symphony.domain.Card;

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
