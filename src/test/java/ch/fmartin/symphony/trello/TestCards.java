package ch.fmartin.symphony.trello;

import ch.fmartin.symphony.trello.domain.Card;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TestCards {
    private TestCards() {}

    private static final String SYNTHETIC_SHORT_URL = "https://trello.com/c/SYNTH101";

    public static Card card(String id, String identifier, String state) {
        return cardWithComments(id, identifier, state, List.of());
    }

    public static Card cardWithComments(String id, String identifier, String state, List<Card.Comment> comments) {
        return cardWithUrls(id, identifier, state, SYNTHETIC_SHORT_URL, SYNTHETIC_SHORT_URL, comments);
    }

    public static Card cardWithUrls(String id, String identifier, String state, String shortUrl, String url) {
        return cardWithUrls(id, identifier, state, shortUrl, url, List.of());
    }

    public static Card cardWithUrls(
            String id, String identifier, String state, String shortUrl, String url, List<Card.Comment> comments) {
        return new Card(
                id,
                identifier,
                "Implement feature",
                "Description",
                null,
                state,
                "list",
                "list-todo",
                state,
                false,
                "board-1",
                false,
                false,
                1,
                "abc",
                shortUrl,
                null,
                url,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                comments,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                null,
                false,
                BigDecimal.ONE);
    }
}
