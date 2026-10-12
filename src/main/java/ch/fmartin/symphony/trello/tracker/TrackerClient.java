package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import java.util.List;
import java.util.Map;

public interface TrackerClient {
    String resolveBoardId(EffectiveConfig config);

    List<Card> fetchCandidateCards(EffectiveConfig config);

    List<Card> fetchTerminalCards(EffectiveConfig config);

    Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds);

    default Map<String, CardLookupResult> fetchCardStatesForPromptByIds(EffectiveConfig config, List<String> cardIds) {
        return fetchCardStatesByIds(config, cardIds);
    }

    default Card prepareForDispatch(EffectiveConfig config, Card card) {
        return card;
    }

    default void releaseFromDispatch(EffectiveConfig config, Card card) {}

    default void releaseFromDispatch(EffectiveConfig config, Card card, Card dispatchSource) {
        releaseFromDispatch(config, card);
    }

    /// Returns the Trello `429` responses received since the previous call and starts a new batch.
    /// The orchestrator calls this once per poll tick to adapt its poll interval.
    default RateLimitPressure drainRateLimitPressure() {
        return RateLimitPressure.NONE;
    }
}
