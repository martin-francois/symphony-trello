package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class TrelloApiEndpointTest {
    @MethodSource("validEndpointValues")
    @ParameterizedTest(name = "{0}")
    void normalizesTrelloApiBaseEndpoints(String name, String endpoint, String expected) {
        // given
        URI configuredEndpoint = URI.create(endpoint);

        // when
        URI normalized = TrelloApiEndpoint.normalize(configuredEndpoint);

        // then
        assertThat(normalized).hasToString(expected);
    }

    @MethodSource("invalidEndpointValues")
    @ParameterizedTest(name = "{0}")
    void rejectsValuesThatAreNotTrelloApiBaseEndpoints(String name, String endpoint) {
        // given
        URI configuredEndpoint = URI.create(endpoint);

        // when
        Throwable thrown = catchThrowable(() -> TrelloApiEndpoint.normalize(configuredEndpoint));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage("--endpoint must point to the Trello REST API base, for example https://api.trello.com/1")
                .extracting("code")
                .isEqualTo("setup_invalid_arguments");
    }

    private static Stream<Arguments> validEndpointValues() {
        return Stream.of(
                Arguments.of("host-root", "https://api.trello.com", "https://api.trello.com/1"),
                Arguments.of("host-trailing-dot", "https://api.trello.com./1", "https://api.trello.com/1"),
                Arguments.of("rest-base", "https://api.trello.com/1/", "https://api.trello.com/1"),
                Arguments.of("local-root", "http://127.0.0.1:1234", "http://127.0.0.1:1234/1"),
                Arguments.of(
                        "reverse-proxy-prefix", "https://proxy.example/trello/1/", "https://proxy.example/trello/1"));
    }

    private static Stream<Arguments> invalidEndpointValues() {
        return Stream.of(
                Arguments.of("duplicated-rest-path", "https://api.trello.com/1/members/me"),
                Arguments.of("wrong-rest-version", "https://api.trello.com/2"),
                Arguments.of("insecure-production-endpoint", "http://api.trello.com/1"),
                Arguments.of("insecure-production-endpoint-trailing-dot", "http://api.trello.com./1"),
                Arguments.of("official-host-prefix", "https://api.trello.com/foo/1"),
                Arguments.of("query-string", "https://api.trello.com/1?x=y"),
                Arguments.of("fragment", "https://api.trello.com/1#frag"));
    }
}
