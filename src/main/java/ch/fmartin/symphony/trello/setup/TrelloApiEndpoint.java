package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.TrelloCredentialEndpoint;
import java.net.URI;
import java.util.Objects;

/// Validates the `--endpoint` option: an absolute http(s) URL of a Trello REST API base. The
/// spelling comes from [TrelloCredentialEndpoint#canonical], the one normalizer every endpoint
/// comparison uses, so a value setup accepts compares equal to the same value elsewhere.
final class TrelloApiEndpoint {
    private static final String API_TRELLO_HOST = "api.trello.com";
    private static final String REST_API_PATH = TrelloCredentialEndpoint.REST_API_PATH;

    private TrelloApiEndpoint() {}

    static URI normalize(URI endpoint) {
        URI value = Objects.requireNonNull(endpoint, "endpoint");
        if (value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw invalidEndpoint();
        }
        URI canonical =
                TrelloCredentialEndpoint.canonical(value.toString()).orElseThrow(TrelloApiEndpoint::invalidEndpoint);
        String scheme = canonical.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw invalidEndpoint();
        }
        boolean officialTrelloHost = API_TRELLO_HOST.equals(canonical.getHost());
        if (officialTrelloHost && !"https".equals(scheme)) {
            throw invalidEndpoint();
        }
        String path = canonical.getPath();
        if (officialTrelloHost ? !REST_API_PATH.equals(path) : !path.endsWith(REST_API_PATH)) {
            throw invalidEndpoint();
        }
        return canonical;
    }

    private static TrelloBoardSetupException invalidEndpoint() {
        return new TrelloBoardSetupException(
                "setup_invalid_arguments",
                "--endpoint must point to the Trello REST API base, for example https://api.trello.com/1");
    }
}
