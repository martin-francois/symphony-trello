package ch.fmartin.symphony.trello.setup;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

final class TrelloApiEndpoint {
    private static final String API_TRELLO_HOST = "api.trello.com";
    private static final String REST_API_PATH = "/1";

    private TrelloApiEndpoint() {}

    static URI normalize(URI endpoint) {
        URI value = Objects.requireNonNull(endpoint, "endpoint");
        String scheme = value.getScheme();
        String host = value.getHost();
        if (scheme == null || host == null || value.getUserInfo() != null) {
            throw invalidEndpoint();
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = canonicalHost(host);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            throw invalidEndpoint();
        }
        if (API_TRELLO_HOST.equals(normalizedHost) && !"https".equals(normalizedScheme)) {
            throw invalidEndpoint();
        }
        if (value.getQuery() != null || value.getFragment() != null) {
            throw invalidEndpoint();
        }
        String normalizedPath = normalizePath(value.getPath(), API_TRELLO_HOST.equals(normalizedHost));
        try {
            return new URI(normalizedScheme, null, normalizedHost, value.getPort(), normalizedPath, null, null);
        } catch (URISyntaxException exception) {
            throw invalidEndpoint(exception);
        }
    }

    private static String canonicalHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizePath(String path, boolean officialTrelloHost) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return REST_API_PATH;
        }
        String withoutTrailingSlashes = stripTrailingSlashes(path);
        if (officialTrelloHost && !REST_API_PATH.equals(withoutTrailingSlashes)) {
            throw invalidEndpoint();
        }
        if (withoutTrailingSlashes.endsWith(REST_API_PATH)) {
            return withoutTrailingSlashes;
        }
        throw invalidEndpoint();
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static TrelloBoardSetupException invalidEndpoint() {
        return invalidEndpoint(null);
    }

    private static TrelloBoardSetupException invalidEndpoint(Throwable cause) {
        return new TrelloBoardSetupException(
                "setup_invalid_arguments",
                "--endpoint must point to the Trello REST API base, for example https://api.trello.com/1",
                cause);
    }
}
