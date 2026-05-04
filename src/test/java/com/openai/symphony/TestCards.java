package com.openai.symphony;

import com.openai.symphony.domain.Card;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TestCards {
    private TestCards() {}

    public static Card card(String id, String identifier, String state) {
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
                "https://trello.com/c/abc",
                null,
                "https://trello.com/c/abc",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                null,
                false,
                BigDecimal.ONE);
    }
}
