package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.Sha3;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Stable, non-secret identity for one tracker board namespace. */
record TrackerTarget(String kind, String endpointFingerprint, String resolvedBoardId) {
    static TrackerTarget from(EffectiveConfig config) {
        EffectiveConfig.TrackerConfig tracker = config.tracker();
        return from(tracker.kind(), tracker.endpoint(), tracker.resolvedBoardId());
    }

    static TrackerTarget from(String kind, String endpoint, String resolvedBoardId) {
        return new TrackerTarget(
                kind.toLowerCase(Locale.ROOT),
                Sha3.sha3_256(canonicalEndpoint(endpoint)),
                Objects.requireNonNull(resolvedBoardId, "resolvedBoardId"));
    }

    private static String canonicalEndpoint(String endpoint) {
        URI uri = URI.create(endpoint.replaceAll("/+$", ""));
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        while (host.endsWith(".") && host.length() > 1) {
            host = host.substring(0, host.length() - 1);
        }
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

    private static String normalizeOptionalPercentEscapes(String value) {
        return value == null ? "0" : "1" + normalizePercentEscapes(value);
    }

    private static String normalizePercentEscapes(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
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
