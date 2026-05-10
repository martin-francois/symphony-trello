package ch.fmartin.symphony.trello.setup;

import java.util.regex.Pattern;

final class TrelloBoardIds {
    private static final Pattern TRELLO_URL = Pattern.compile("trello\\.com/(?:b|c)/(?<id>[A-Za-z0-9]+)");

    private TrelloBoardIds() {}

    static String parse(String value) {
        if (value == null) {
            return "";
        }
        var matcher = TRELLO_URL.matcher(value);
        return matcher.find() ? matcher.group("id") : value.strip();
    }
}
