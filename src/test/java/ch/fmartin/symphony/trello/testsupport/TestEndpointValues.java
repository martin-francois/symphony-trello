package ch.fmartin.symphony.trello.testsupport;

import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

public final class TestEndpointValues {
    private TestEndpointValues() {}

    public static Stream<Arguments> invalidTrelloApiBaseEndpoints() {
        return invalidTrelloApiBaseEndpointValues()
                .map(endpoint -> Arguments.of(endpointScenarioName(endpoint), endpoint));
    }

    public static Stream<String> invalidTrelloApiBaseEndpointValues() {
        return Stream.of(
                "https://api.trello.com/1/members/me",
                "https://api.trello.com/2",
                "http://api.trello.com/1",
                "http://api.trello.com./1",
                "https://api.trello.com/foo/1",
                "https://api.trello.com/1?x=y",
                "https://api.trello.com/1#frag");
    }

    private static String endpointScenarioName(String endpoint) {
        return switch (endpoint) {
            case "https://api.trello.com/1/members/me" -> "duplicated-rest-path";
            case "https://api.trello.com/2" -> "wrong-rest-version";
            case "http://api.trello.com/1" -> "insecure-production-endpoint";
            case "http://api.trello.com./1" -> "insecure-production-endpoint-trailing-dot";
            case "https://api.trello.com/foo/1" -> "official-host-prefix";
            case "https://api.trello.com/1?x=y" -> "query-string";
            case "https://api.trello.com/1#frag" -> "fragment";
            default -> throw new IllegalArgumentException("Unknown endpoint scenario: " + endpoint);
        };
    }
}
