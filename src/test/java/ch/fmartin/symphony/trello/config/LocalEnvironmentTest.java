package ch.fmartin.symphony.trello.config;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_BYTE_ORDER_MARK;
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
import org.junit.jupiter.params.provider.ValueSource;

/// Executable contract for the dotenv format that existing user files rely on. A replacement
/// parser, including a library-backed one, must keep these cases unchanged; see
/// docs/adr/0100-keep-the-hand-rolled-dotenv-parser.md.
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
        assertThat(apiKey).hasValue("key-from-dotenv");
        assertThat(apiToken).hasValue("token-from-dotenv");
    }

    @Test
    void ignoresOneLeadingUtf8ByteOrderMarkBeforeTheFirstKey() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, UNICODE_BYTE_ORDER_MARK + "TRELLO_API_KEY=key-behind-bom\n");

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());

        // then
        assertThat(apiKey).hasValue("key-behind-bom");
    }

    @Test
    void parsesQuotedValueWithTrailingCommentBehindAByteOrderMark() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, UNICODE_BYTE_ORDER_MARK + "TRELLO_API_KEY=\"quoted-key\" # personal key\n");

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());

        // then
        assertThat(apiKey).hasValue("quoted-key");
    }

    @Test
    void parsesExportPrefixBehindAByteOrderMark() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, UNICODE_BYTE_ORDER_MARK + "export TRELLO_API_KEY=exported-key\n");

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());

        // then
        assertThat(apiKey).hasValue("exported-key");
    }

    @Test
    void treatsADoubledByteOrderMarkAsAnInvalidLineLikeBefore() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(
                dotenv,
                UNICODE_BYTE_ORDER_MARK + UNICODE_BYTE_ORDER_MARK + "TRELLO_API_KEY=value\nTRELLO_API_TOKEN=token\n");

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of());
        var apiToken = LocalEnvironment.get("TRELLO_API_TOKEN", dotenv, Map.of());

        // then
        assertThat(apiKey).isEmpty();
        assertThat(apiToken).hasValue("token");
    }

    @Test
    void realEnvironmentVariableWinsOverDotenv() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "TRELLO_API_KEY=key-from-dotenv");

        // when
        var apiKey = LocalEnvironment.get("TRELLO_API_KEY", dotenv, Map.of("TRELLO_API_KEY", "key-from-env"));

        // then
        assertThat(apiKey).hasValue("key-from-env");
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
        assertThat(port).hasValue("19080");
    }

    @Test
    void firstPresentUsesConfiguredNameOrderWithinTheSameSource() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "");

        // when
        var port = LocalEnvironment.firstPresent(
                dotenv,
                Map.of("SYMPHONY_HTTP_PORT", "18080", "QUARKUS_HTTP_PORT", "19080"),
                "SYMPHONY_HTTP_PORT",
                "QUARKUS_HTTP_PORT");

        // then
        assertThat(port).hasValue("18080");
    }

    @Test
    void readsEscapedDoubleQuotedDotenvValues() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "TRELLO_API_TOKEN=\"token\\\"quoted\\\\path\\tvalue\"");

        // when
        var apiToken = LocalEnvironment.get("TRELLO_API_TOKEN", dotenv, Map.of());

        // then
        assertThat(apiToken).hasValue("token\"quoted\\path\tvalue");
    }

    @Test
    void preservesUnknownBackslashEscapesInDoubleQuotedDotenvValues() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "SYMPHONY_WORKFLOW_PATH=\"C:\\Users\\Jane Doe\\WORKFLOW.md\"");

        // when
        var workflowPath = LocalEnvironment.get("SYMPHONY_WORKFLOW_PATH", dotenv, Map.of());

        // then
        assertThat(workflowPath).hasValue("C:\\Users\\Jane Doe\\WORKFLOW.md");
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
    @ValueSource(
            strings = {
                "",
                "# comment",
                "MISSING_SEPARATOR",
                "1INVALID=value",
                "INVALID-NAME=value",
                "dotted.name=value",
                "PROPERTIES_STYLE:value"
            })
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
                """);

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

    @MethodSource("lineShapes")
    @ParameterizedTest(name = "{0}")
    void loadKeepsTheDotenvLineContract(String scenario, String content, Map<String, String> expected)
            throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, content);

        // when
        Map<String, String> values = LocalEnvironment.load(dotenv);

        // then
        assertThat(values).as(scenario).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void loadReturnsNoValuesWhenTheFileIsNotValidUtf8() throws Exception {
        // given
        Path dotenv = tempDir.resolve(".env");
        Files.write(dotenv, new byte[] {'K', '=', (byte) 0xC3, (byte) 0x28, '\n'});

        // when
        Map<String, String> values = LocalEnvironment.load(dotenv);

        // then
        assertThat(values).isEmpty();
    }

    private static Stream<Arguments> lineShapes() {
        return Stream.of(
                Arguments.of(
                        "newline and carriage return escapes in double quotes",
                        "K=\"a\\nb\\rc\"",
                        Map.of("K", "a\nb\rc")),
                Arguments.of(
                        "backspace and form feed escapes in double quotes", "K=\"a\\bb\\fc\"", Map.of("K", "a\bb\fc")),
                Arguments.of("single quotes keep backslashes as written", "K='a\\nb\\\"c'", Map.of("K", "a\\nb\\\"c")),
                Arguments.of("escaped quote before a hash in double quotes", "K=\"a\\\" # b\"", Map.of("K", "a\" # b")),
                Arguments.of("empty double-quoted value", "K=\"\"", Map.of("K", "")),
                Arguments.of("text after the closing quote keeps the whole value", "K=\"a\"b", Map.of("K", "\"a\"b")),
                Arguments.of(
                        "matching outer quotes are removed from an ambiguous value", "K='it''s'", Map.of("K", "it''s")),
                Arguments.of(
                        "an unterminated quote does not continue on the next line",
                        "K=\"open\nNEXT=next\n",
                        Map.of("K", "\"open", "NEXT", "next")),
                Arguments.of(
                        "a trailing backslash does not continue on the next line",
                        "K=a\\\nNEXT=next\n",
                        Map.of("K", "a\\", "NEXT", "next")),
                Arguments.of("Windows line endings", "A=a\r\nB=\"b\"\r\n", Map.of("A", "a", "B", "b")),
                Arguments.of("spaces around the separator and value", "K = spaced \n", Map.of("K", "spaced")),
                Arguments.of("export prefix followed by several spaces", "export   K=v\n", Map.of("K", "v")),
                Arguments.of("dollar braces stay literal text", "K=${HOME}/x\n", Map.of("K", "${HOME}/x")),
                Arguments.of("separator inside the value", "K=a=b\n", Map.of("K", "a=b")),
                Arguments.of("the last definition of a key wins", "K=first\nK=second\n", Map.of("K", "second")));
    }
}
