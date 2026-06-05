package ch.fmartin.symphony.trello.setup;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class TrelloBoardIds {
    private static final Pattern TRELLO_URL = Pattern.compile("trello\\.com/(?:b|c)/(?<id>[A-Za-z0-9]+)");
    private static final Pattern OPAQUE_BOARD_SELECTOR = Pattern.compile("[A-Za-z0-9-]+");

    private TrelloBoardIds() {}

    static String parse(String value) {
        if (value == null) {
            return "";
        }
        var matcher = TRELLO_URL.matcher(value);
        return matcher.find() ? matcher.group("id") : value.strip();
    }

    static String parseImportBoardSelector(String value) {
        String selector = value == null ? "" : value.strip();
        if (selector.isEmpty()) {
            return selector;
        }
        return boardIdFromTrelloBoardUrl(selector).orElseGet(() -> parseOpaqueImportBoardSelector(selector));
    }

    private static String parseOpaqueImportBoardSelector(String selector) {
        if (isUrlLike(selector) || selector.contains("/") || selector.contains("\\")) {
            throw invalidImportBoardSelector();
        }
        if (!OPAQUE_BOARD_SELECTOR.matcher(selector).matches()) {
            throw invalidImportBoardSelector();
        }
        return selector;
    }

    private static Optional<String> boardIdFromTrelloBoardUrl(String selector) {
        String candidate = withSchemeForTrelloHost(selector);
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            if (isUrlLike(selector)) {
                throw invalidImportBoardSelector(e);
            }
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null) {
            return Optional.empty();
        }
        if (!isTrelloHost(host)) {
            throw invalidImportBoardSelector();
        }
        String path = uri.getPath();
        if (path == null) {
            throw invalidImportBoardSelector();
        }
        if (!path.startsWith("/b/")) {
            throw invalidImportBoardSelector();
        }
        String remainder = path.substring(3);
        int nextSlash = remainder.indexOf('/');
        String boardId = nextSlash < 0 ? remainder : remainder.substring(0, nextSlash);
        if (!OPAQUE_BOARD_SELECTOR.matcher(boardId).matches()) {
            throw invalidImportBoardSelector();
        }
        return Optional.of(boardId);
    }

    private static String withSchemeForTrelloHost(String selector) {
        if (startsWithIgnoreCase(selector, "trello.com/") || startsWithIgnoreCase(selector, "www.trello.com/")) {
            return "https://" + selector;
        }
        return selector;
    }

    private static boolean isUrlLike(String selector) {
        return selector.contains("://")
                || startsWithIgnoreCase(selector, "trello.com/")
                || startsWithIgnoreCase(selector, "www.trello.com/");
    }

    private static boolean isTrelloHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "trello.com".equals(normalized) || "www.trello.com".equals(normalized);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static TrelloBoardSetupException invalidImportBoardSelector() {
        return invalidImportBoardSelector(null);
    }

    private static TrelloBoardSetupException invalidImportBoardSelector(Throwable cause) {
        return new TrelloBoardSetupException(
                "setup_invalid_arguments",
                "Invalid --board value. Use a Trello board URL, short link, or board id.",
                cause);
    }
}
