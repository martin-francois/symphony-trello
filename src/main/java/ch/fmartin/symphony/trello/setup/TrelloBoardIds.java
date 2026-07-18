package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Strings.nullToEmpty;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class TrelloBoardIds {
    private static final Pattern OPAQUE_BOARD_SELECTOR = Pattern.compile("[A-Za-z0-9-]+");

    private TrelloBoardIds() {}

    static String parse(String value) {
        return parseConnectedBoardSelector(value);
    }

    static String parseConnectedBoardSelector(String value) {
        String selector = nullToEmpty(value).strip();
        if (selector.isEmpty()) {
            return selector;
        }
        return boardIdFromTrelloBoardUrl(selector, InvalidBoardSelector.CONNECTED_BOARD)
                .orElseGet(() -> normalizeBareSelector(selector));
    }

    static String parseStoredBoardUrl(String value) {
        String selector = nullToEmpty(value).strip();
        if (selector.isEmpty()) {
            return selector;
        }
        return boardIdFromTrelloBoardUrl(selector, InvalidBoardSelector.IGNORE).orElse(selector);
    }

    static String parseImportBoardSelector(String value) {
        String selector = nullToEmpty(value).strip();
        if (selector.isEmpty()) {
            return selector;
        }
        return boardIdFromTrelloBoardUrl(selector, InvalidBoardSelector.IMPORT_BOARD)
                .orElseGet(() -> parseOpaqueImportBoardSelector(selector));
    }

    private static String parseOpaqueImportBoardSelector(String rawSelector) {
        String selector = normalizeBareSelector(rawSelector);
        if (isUrlLike(selector) || selector.contains("/") || selector.contains("\\")) {
            throw invalidImportBoardSelector();
        }
        if (!OPAQUE_BOARD_SELECTOR.matcher(selector).matches()) {
            throw invalidImportBoardSelector();
        }
        return selector;
    }

    /// Copied or shell-completed bare short links often gain a harmless trailing slash, query
    /// string, or fragment, such as `SYNTH001/`, `SYNTH001?utm=test`, or
    /// `SYNTH001#fragment`. Strip those decorations only when the remainder is a plain
    /// board id or short link, so board names containing the same characters stay untouched.
    private static String normalizeBareSelector(String selector) {
        String candidate = withoutQueryAndFragment(selector);
        if (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (OPAQUE_BOARD_SELECTOR.matcher(candidate).matches()) {
            return candidate;
        }
        return selector;
    }

    /// URI parsing decides whether `?` or `#` starts a real query or fragment: board
    /// names that merely contain those characters, such as `What? Board`, are not parseable
    /// URIs and are returned unchanged.
    private static String withoutQueryAndFragment(String selector) {
        if (selector.indexOf('?') < 0 && selector.indexOf('#') < 0) {
            return selector;
        }
        try {
            String path = new URI(selector).getPath();
            return path == null ? selector : path;
        } catch (URISyntaxException e) {
            // Not URI-shaped, so the '?' or '#' belongs to a board name; keep the selector.
            return selector;
        }
    }

    private static Optional<String> boardIdFromTrelloBoardUrl(String selector, InvalidBoardSelector invalidSelector) {
        String candidate = withSchemeForTrelloHost(selector);
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            if (invalidSelector.rejectsUrlLikeSelector() && isUrlLike(selector)) {
                throw invalidSelector.exception(e);
            }
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null) {
            rejectIfNeeded(selector, invalidSelector);
            return Optional.empty();
        }
        if (!isTrelloHost(host)) {
            rejectIfNeeded(selector, invalidSelector);
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null) {
            rejectIfNeeded(selector, invalidSelector);
            return Optional.empty();
        }
        if (!path.startsWith("/b/")) {
            rejectIfNeeded(selector, invalidSelector);
            return Optional.empty();
        }
        String remainder = path.substring(3);
        int nextSlash = remainder.indexOf('/');
        String boardId = nextSlash < 0 ? remainder : remainder.substring(0, nextSlash);
        if (!OPAQUE_BOARD_SELECTOR.matcher(boardId).matches()) {
            rejectIfNeeded(selector, invalidSelector);
            return Optional.empty();
        }
        return Optional.of(boardId);
    }

    private static void rejectIfNeeded(String selector, InvalidBoardSelector invalidSelector) {
        if (invalidSelector.rejectsUrlLikeSelector() && isUrlLike(selector)) {
            throw invalidSelector.exception();
        }
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

    private static TrelloBoardSetupException invalidConnectedBoardSelector(Throwable cause) {
        return new TrelloBoardSetupException(
                "setup_invalid_arguments",
                "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.",
                cause);
    }

    private enum InvalidBoardSelector {
        CONNECTED_BOARD,
        IMPORT_BOARD,
        IGNORE;

        private boolean rejectsUrlLikeSelector() {
            return this != IGNORE;
        }

        private TrelloBoardSetupException exception() {
            return exception(null);
        }

        private TrelloBoardSetupException exception(Throwable cause) {
            return this == CONNECTED_BOARD ? invalidConnectedBoardSelector(cause) : invalidImportBoardSelector(cause);
        }
    }
}
