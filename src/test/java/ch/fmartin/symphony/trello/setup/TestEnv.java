package ch.fmartin.symphony.trello.setup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent builder for the {@code .env} content exercised by the setup and worker tests.
 *
 * <p>The recurring inlined shape is the two Trello credential lines ({@code TRELLO_API_KEY} and
 * {@code TRELLO_API_TOKEN}) optionally followed by extra {@code KEY=VALUE} lines that back a
 * workflow {@code $VAR} reference. This builder emits exactly those lines in insertion order, each
 * terminated by a newline, so the rendered content matches the inlined text blocks byte-for-byte.
 * Bespoke env content (non-credential leading keys, blank values asserted verbatim, ...) stays
 * inlined.
 */
final class TestEnv {
    private final Map<String, String> entries = new LinkedHashMap<>();

    private TestEnv() {}

    /** Seeds the canonical {@code TRELLO_API_KEY}/{@code TRELLO_API_TOKEN} credential pair. */
    static TestEnv credentials(String key, String token) {
        TestEnv env = new TestEnv();
        env.entries.put("TRELLO_API_KEY", key);
        env.entries.put("TRELLO_API_TOKEN", token);
        return env;
    }

    /** Appends an extra {@code name=value} line after the credentials, in call order. */
    TestEnv var(String name, String value) {
        entries.put(name, value);
        return this;
    }

    /** Appends an extra {@code name=value} line with an integer value, in call order. */
    TestEnv var(String name, int value) {
        return var(name, Integer.toString(value));
    }

    /** Renders the env content with one {@code KEY=VALUE} line per entry and a trailing newline. */
    String render() {
        StringBuilder builder = new StringBuilder();
        entries.forEach(
                (name, value) -> builder.append(name).append('=').append(value).append('\n'));
        return builder.toString();
    }
}
