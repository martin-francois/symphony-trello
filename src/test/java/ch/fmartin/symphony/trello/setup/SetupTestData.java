package ch.fmartin.symphony.trello.setup;

import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Shared parameterized-test data for setup CLI endpoint validation.
 *
 * <p>The invalid Trello REST API base endpoints rejected by {@code --endpoint} validation are the
 * same across every setup command, so the canonical {@code (name, endpoint)} rows live here and are
 * referenced from each test via a fully-qualified {@link org.junit.jupiter.params.provider.MethodSource}.
 */
final class SetupTestData {
    private SetupTestData() {}

    /**
     * Invalid {@code --endpoint} values paired with the display name used as the parameterized-test
     * label. Each row is one rejected Trello REST API base endpoint.
     */
    static Stream<Arguments> invalidEndpointValues() {
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
