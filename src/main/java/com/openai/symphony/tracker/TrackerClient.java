package com.openai.symphony.tracker;

import com.openai.symphony.config.EffectiveConfig;
import com.openai.symphony.domain.Card;
import java.util.List;
import java.util.Map;

public interface TrackerClient {
    String resolveBoardId(EffectiveConfig config);

    List<Card> fetchCandidateCards(EffectiveConfig config);

    List<Card> fetchTerminalCards(EffectiveConfig config);

    Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds);
}
