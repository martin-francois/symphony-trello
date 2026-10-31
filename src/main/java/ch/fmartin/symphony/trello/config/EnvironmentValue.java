package ch.fmartin.symphony.trello.config;

/// One non-blank value read from the local environment, together with the source that supplied it.
/// The source matters for a Trello key or token: the value belongs to the endpoint that the same
/// source names in `TRELLO_API_ENDPOINT`; see [TrelloCredentialEndpoint].
public record EnvironmentValue(String name, String value, EnvironmentSource source) {}
