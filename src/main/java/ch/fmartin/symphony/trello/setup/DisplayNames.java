package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.SingleLineText;
import com.google.common.base.Strings;
import java.util.List;
import java.util.stream.Collectors;

/// Display-quoting for external Trello names in CLI output, generated prose, and diagnostics.
/// Trello allows quotes, backslashes, and control characters such as newlines in board, list, and
/// card names, so rendered names escape them: one logical message must stay on one physical line
/// with unambiguous quoted boundaries.
final class DisplayNames {
    private DisplayNames() {}

    static String quotedName(String name) {
        return "\"" + escape(Strings.nullToEmpty(name)) + "\"";
    }

    static String quotedList(List<String> names) {
        return names.stream().map(DisplayNames::quotedName).collect(Collectors.joining(", "));
    }

    /// Escapes one line of external text for terminal output, like [#quotedName] but without the
    /// quotes.
    static String escapedText(String text) {
        return escape(Strings.nullToEmpty(text));
    }

    private static String escape(String name) {
        return SingleLineText.escaped(name);
    }
}
