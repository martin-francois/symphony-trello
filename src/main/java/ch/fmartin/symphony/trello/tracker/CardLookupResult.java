package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.domain.Card;

public sealed interface CardLookupResult
        permits CardLookupResult.Found, CardLookupResult.Missing, CardLookupResult.Failed {
    record Found(Card card) implements CardLookupResult {}

    record Missing(String cardId) implements CardLookupResult {}

    record Failed(String cardId, String code, String message) implements CardLookupResult {}
}
