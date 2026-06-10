package ch.fmartin.symphony.trello.setup;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Display-quoting for external Trello names in CLI output, generated prose, and diagnostics.
 * Trello allows quotes, backslashes, and control characters such as newlines in board, list, and
 * card names, so rendered names escape them: one logical message must stay on one physical line
 * with unambiguous quoted boundaries.
 */
final class DisplayNames {
    private DisplayNames() {}

    static String quotedName(String name) {
        return "\"" + escape(name == null ? "" : name) + "\"";
    }

    static String quotedList(List<String> names) {
        return names.stream().map(DisplayNames::quotedName).collect(Collectors.joining(", "));
    }

    private static String escape(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            switch (current) {
                case '\\' -> safe.append("\\\\");
                case '"' -> safe.append("\\\"");
                case '\n' -> safe.append("\\n");
                case '\r' -> safe.append("\\r");
                case '\t' -> safe.append("\\t");
                default -> {
                    if (Character.isISOControl(current)) {
                        safe.append("\\u%04X".formatted((int) current));
                    } else {
                        safe.append(current);
                    }
                }
            }
        }
        return safe.toString();
    }
}
