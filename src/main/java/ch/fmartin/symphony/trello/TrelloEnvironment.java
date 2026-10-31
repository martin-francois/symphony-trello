package ch.fmartin.symphony.trello;

public final class TrelloEnvironment {
    public static final String API_KEY = "TRELLO_API_KEY";
    public static final String API_TOKEN = "TRELLO_API_TOKEN";
    /// Names the endpoint that the key and token of the same source belong to; see
    /// [ch.fmartin.symphony.trello.config.TrelloCredentialEndpoint].
    public static final String API_ENDPOINT = "TRELLO_API_ENDPOINT";

    private TrelloEnvironment() {}
}
