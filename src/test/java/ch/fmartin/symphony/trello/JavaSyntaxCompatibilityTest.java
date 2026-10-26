package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The source itself exercises the formatter and compiler gates for supported resource syntax.
final class JavaSyntaxCompatibilityTest {
    @Test
    void unnamedAndNamedResourcesCloseAfterTheirBodies() throws Exception {
        // given
        List<String> events = new ArrayList<>();

        // when
        try (var _ = recordClose(events, "unnamed closed")) {
            events.add("unnamed body");
        }
        var named = recordClose(events, "named closed");
        try (named) {
            events.add("named body");
        }

        // then
        assertThat(events).containsExactly("unnamed body", "unnamed closed", "named body", "named closed");
    }

    private static AutoCloseable recordClose(List<String> events, String event) {
        return () -> events.add(event);
    }
}
