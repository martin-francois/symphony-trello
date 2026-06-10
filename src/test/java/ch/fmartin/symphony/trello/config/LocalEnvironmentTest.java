package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LocalEnvironmentTest {
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
    void ignoresOneLeadingUtf8ByteOrderMarkBeforeTheFirstKey() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "\uFEFFTRELLO_API_KEY=key-behind-bom\n", StandardCharsets.UTF_8);

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());

        // then
        assertThat(apiKey).contains("key-behind-bom");
    }

    @Test
    void parsesQuotedValueWithTrailingCommentBehindAByteOrderMark() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "\uFEFFTRELLO_API_KEY=\"quoted-key\" # personal key\n", StandardCharsets.UTF_8);

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());

        // then
        assertThat(apiKey).contains("quoted-key");
    }

    @Test
    void parsesExportPrefixBehindAByteOrderMark() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "\uFEFFexport TRELLO_API_KEY=exported-key\n", StandardCharsets.UTF_8);

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());

        // then
        assertThat(apiKey).contains("exported-key");
    }

    @Test
    void treatsADoubledByteOrderMarkAsAnInvalidLineLikeBefore() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "\uFEFF\uFEFFTRELLO_API_KEY=value\nTRELLO_API_TOKEN=token\n", StandardCharsets.UTF_8);

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());
        var apiToken = LocalEnvironment.get("TRELLO_API_TOKEN", dotenv, Map.of());

        // then
        assertThat(apiKey).isEmpty();
        assertThat(apiToken).contains("token");
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

    @Test
    void realEnvironmentAliasesWinOverDotenvAliases() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "SYMPHONY_HTTP_PORT=18080");

        // when
        var port = LocalEnvironment.firstPresent(
                dotenv, Map.of("QUARKUS_HTTP_PORT", "19080"), "SYMPHONY_HTTP_PORT", "QUARKUS_HTTP_PORT");

        // then
        assertThat(port).contains("19080");
    }

    @Test
    void readsEscapedDoubleQuotedDotenvValues() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "TRELLO_API_TOKEN=\"token\\\"quoted\\\\path\\tvalue\"");

        // when
        var apiToken = LocalEnvironment.get("TRELLO_API_TOKEN", dotenv, Map.of());

        // then
        assertThat(apiToken).contains("token\"quoted\\path\tvalue");
    }

    @Test
    void preservesUnknownBackslashEscapesInDoubleQuotedDotenvValues() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "SYMPHONY_WORKFLOW_PATH=\"C:\\Users\\Jane Doe\\WORKFLOW.md\"");

        // when
        var workflowPath = LocalEnvironment.get("SYMPHONY_WORKFLOW_PATH", dotenv, Map.of());

        // then
        assertThat(workflowPath).contains("C:\\Users\\Jane Doe\\WORKFLOW.md");
    }

    @Test
    void configuredDotenvPathCanOverrideDefaultDotenvLocation() {
        // given
        Path dotenv = tempDir.resolve("runtime.env");

        // when
        Path resolved = LocalEnvironment.defaultDotenv(Map.of("SYMPHONY_TRELLO_DOTENV", dotenv.toString()));

        // then
        assertThat(resolved).isEqualTo(dotenv);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"", "# comment", "MISSING_SEPARATOR", "1INVALID=value", "INVALID-NAME=value"})
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

    @Test
    void loadStripsTrailingCommentsAfterQuotedAndUnquotedValues(@TempDir Path tempDir) throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(
                dotenv,
                """
                # credentials with comments
                TRELLO_API_KEY="synthetic-key" # key comment
                TRELLO_API_TOKEN='synthetic-token' # token comment
                PLAIN=plain-value # plain comment
                HASH_IN_VALUE=abc#def
                HASH_IN_QUOTES="value # not a comment"
                """,
                StandardCharsets.UTF_8);

        // when
        Map<String, String> values = LocalEnvironment.load(dotenv);

        // then
        assertThat(values)
                .containsEntry("TRELLO_API_KEY", "synthetic-key")
                .containsEntry("TRELLO_API_TOKEN", "synthetic-token")
                .containsEntry("PLAIN", "plain-value")
                .containsEntry("HASH_IN_VALUE", "abc#def")
                .containsEntry("HASH_IN_QUOTES", "value # not a comment");
    }
}
