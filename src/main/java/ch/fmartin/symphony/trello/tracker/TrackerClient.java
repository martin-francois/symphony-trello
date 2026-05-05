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
}
