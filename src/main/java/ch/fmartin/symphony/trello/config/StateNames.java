package ch.fmartin.symphony.trello.config;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class StateNames {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private StateNames() {}

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = WHITESPACE.matcher(value.trim()).replaceAll(" ");
        return Normalizer.normalize(trimmed, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
