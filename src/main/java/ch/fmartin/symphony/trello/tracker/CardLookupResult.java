package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.domain.Card;
import org.jspecify.annotations.NullMarked;

@NullMarked
public sealed interface CardLookupResult
        permits CardLookupResult.Failed, CardLookupResult.Found, CardLookupResult.Missing {
    record Found(Card card) implements CardLookupResult {}

    record Missing(String cardId) implements CardLookupResult {}

    record Failed(String cardId, String code, String message) implements CardLookupResult {}
}
