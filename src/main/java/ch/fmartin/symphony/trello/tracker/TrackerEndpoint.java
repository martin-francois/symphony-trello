package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.DOTS;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.SLASHES;

import ch.fmartin.symphony.trello.Sha3;
import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Stable non-secret identity using the tracker's established endpoint-equivalence rules.
@NullMarked
public final class TrackerEndpoint {
    private TrackerEndpoint() {}

    public static String fingerprint(String endpoint) {
        return Sha3.sha3_256(canonicalEndpoint(endpoint));
    }

    private static String canonicalEndpoint(String endpoint) {
        URI uri = URI.create(SLASHES.trimTrailingFrom(endpoint));
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String hostWithoutTrailingDots = DOTS.trimTrailingFrom(uri.getHost().toLowerCase(Locale.ROOT));
        String host = hostWithoutTrailingDots.isEmpty() ? "." : hostWithoutTrailingDots;
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        String path = normalizePercentEscapes(uri.getRawPath());
        return String.join(
                "\n",
                scheme,
                host,
                Integer.toString(port),
                normalizeOptionalPercentEscapes(uri.getRawUserInfo()),
                path,
                normalizeOptionalPercentEscapes(uri.getRawQuery()),
                normalizeOptionalPercentEscapes(uri.getRawFragment()));
    }

    private static String normalizeOptionalPercentEscapes(@Nullable String value) {
        return value == null ? "0" : "1" + normalizePercentEscapes(value);
    }

    private static String normalizePercentEscapes(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        var normalized = new StringBuilder(value.length());
        int percentEscapeCharactersRemaining = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (percentEscapeCharactersRemaining > 0) {
                normalized.append(Character.toUpperCase(current));
                percentEscapeCharactersRemaining--;
            } else {
                normalized.append(current);
                if (current == '%' && index + 2 < value.length()) {
                    percentEscapeCharactersRemaining = 2;
                }
            }
        }
        return normalized.toString();
    }
}
