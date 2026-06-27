package ch.fmartin.symphony.trello.tracker;

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

final class TrelloCardReferenceParser {
    private static final String CARD_URL_PREFIX = "https://trello.com/c/";
    private static final CharMatcher CARD_ID_CHARACTER = CharMatcher.inRange('A', 'Z')
            .or(CharMatcher.inRange('a', 'z'))
            .or(CharMatcher.inRange('0', '9'))
            .precomputed();
    private static final CharMatcher URL_TERMINATOR =
            CharMatcher.whitespace().or(CharMatcher.anyOf(")]>")).precomputed();
    private static final Pattern EXACT_BARE_REFERENCE =
            Pattern.compile("^(?:[0-9a-fA-F]{24}|(?=[A-Za-z0-9]{8,24}$)(?=.*\\d)[A-Za-z0-9]+)$");
    private static final Pattern MARKDOWN_CARD_LINK =
            Pattern.compile("(?i)\\[[^\\]\\n]+\\]\\((https://trello\\.com/c/[^\\s)]+)\\)");

    private TrelloCardReferenceParser() {}

    static Optional<TrelloCardReference> exactReference(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return exactCardUrl(trimmed).or(() -> exactBareReference(trimmed));
    }

    static List<TrelloCardReference> referencesIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return cardUrlRanges(text).stream().map(ReferencedRange::reference).toList();
    }

    static boolean containsTrelloCardUrl(String text) {
        return text != null && !cardUrlRanges(text).isEmpty();
    }

    static boolean allTrelloCardUrlsAreMarkdownLinks(String text) {
        if (!containsTrelloCardUrl(text)) {
            return true;
        }
        List<Range> markdownRanges = MARKDOWN_CARD_LINK
                .matcher(text)
                .results()
                .map(TrelloCardReferenceParser::range)
                .toList();
        if (markdownRanges.isEmpty()) {
            return false;
        }
        return cardUrlRanges(text).stream().map(ReferencedRange::range).allMatch(url -> markdownRanges.stream()
                .anyMatch(range -> range.contains(url)));
    }

    private static Optional<TrelloCardReference> exactCardUrl(String text) {
        List<ReferencedRange> urls = cardUrlRanges(text);
        if (urls.size() != 1) {
            return Optional.empty();
        }
        ReferencedRange url = urls.getFirst();
        return url.range().start() == 0 && url.range().end() == text.length()
                ? Optional.of(url.reference())
                : Optional.empty();
    }

    private static Optional<TrelloCardReference> exactBareReference(String text) {
        return EXACT_BARE_REFERENCE.matcher(text).matches() ? Optional.of(reference(text)) : Optional.empty();
    }

    private static List<ReferencedRange> cardUrlRanges(String text) {
        String lower = Ascii.toLowerCase(text);
        List<ReferencedRange> references = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom < text.length()) {
            int start = lower.indexOf(CARD_URL_PREFIX, searchFrom);
            if (start < 0) {
                break;
            }
            int idStart = start + CARD_URL_PREFIX.length();
            int idEnd = idStart;
            while (idEnd < text.length() && CARD_ID_CHARACTER.matches(text.charAt(idEnd))) {
                idEnd++;
            }
            if (idEnd == idStart) {
                searchFrom = idStart;
                continue;
            }
            int end = idEnd;
            while (end < text.length() && !URL_TERMINATOR.matches(text.charAt(end))) {
                end++;
            }
            references.add(new ReferencedRange(reference(text.substring(idStart, idEnd)), new Range(start, end)));
            searchFrom = end;
        }
        return references;
    }

    private static TrelloCardReference reference(String lookupId) {
        return new TrelloCardReference(lookupId, "https://trello.com/c/" + lookupId);
    }

    private static Range range(MatchResult result) {
        return new Range(result.start(), result.end());
    }

    private record Range(int start, int end) {
        boolean contains(Range other) {
            return start <= other.start && end >= other.end;
        }
    }

    private record ReferencedRange(TrelloCardReference reference, Range range) {}
}
