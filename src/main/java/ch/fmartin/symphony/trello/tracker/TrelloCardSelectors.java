package ch.fmartin.symphony.trello.tracker;

import com.google.common.base.Ascii;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// Parses a card argument that a user or agent typed: a Trello card id, a short link, or a card URL.
@NullMarked
public final class TrelloCardSelectors {
    /// Trello card ids have 24 characters and short links 8; the bound only rejects absurd input.
    private static final int MAX_LOOKUP_ID_LENGTH = 64;

    private TrelloCardSelectors() {}

    /// Returns the id or short link that Trello's card endpoint accepts, or empty when the selector
    /// is neither a bare id or short link nor a `https://trello.com/c/` URL.
    public static Optional<String> lookupId(String selector) {
        String trimmed = selector.strip();
        String candidate = Ascii.toLowerCase(trimmed).startsWith(TrelloCardReferenceParser.CARD_URL_PREFIX)
                ? leadingCardIdCharacters(trimmed.substring(TrelloCardReferenceParser.CARD_URL_PREFIX.length()))
                : trimmed;
        boolean valid = !candidate.isEmpty()
                && candidate.length() <= MAX_LOOKUP_ID_LENGTH
                && TrelloCardReferenceParser.CARD_ID_CHARACTER.matchesAllOf(candidate);
        return valid ? Optional.of(candidate) : Optional.empty();
    }

    private static String leadingCardIdCharacters(String path) {
        int end = TrelloCardReferenceParser.CARD_ID_CHARACTER.negate().indexIn(path);
        return end < 0 ? path : path.substring(0, end);
    }
}
