package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LocalEnvironmentTest {
    @TempDir
    Path tempDir;

    @Test
    void readsProjectDotenvWhenEnvironmentVariableIsMissing() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(
                dotenv,
                """
                # Trello credentials for local runs
                TRELLO_API_KEY=key-from-dotenv
                export TRELLO_API_TOKEN='token-from-dotenv'
                """);

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());
        var apiToken = LocalEnvironment.get("TRELLO_API_TOKEN", dotenv, Map.of());

        // then
        assertThat(apiKey).contains("key-from-dotenv");
        assertThat(apiToken).contains("token-from-dotenv");
    }

    @Test
    void realEnvironmentVariableWinsOverDotenv() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "TRELLO_API_KEY=key-from-dotenv");

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of("TRELLO_API_KEY", "key-from-env"));

        // then
        assertThat(apiKey).contains("key-from-env");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ignoredDotenvLines")
    void ignoresInvalidDotenvLines(String ignoredLine) throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(
                dotenv, """
                %s
                VALID=value
                """.formatted(ignoredLine));

        // when
        var values = LocalEnvironment.load(dotenv);

        // then
        assertThat(values).containsExactly(Map.entry("VALID", "value"));
    }

    private static Stream<Arguments> ignoredDotenvLines() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of("# comment"),
                Arguments.of("MISSING_SEPARATOR"),
                Arguments.of("1INVALID=value"),
                Arguments.of("INVALID-NAME=value"));
    }
}
