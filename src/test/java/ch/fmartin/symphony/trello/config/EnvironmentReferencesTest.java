package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// Executable contract for whole-value environment references. A replacement parser, including
/// a library-backed one, must keep these cases unchanged; see
/// docs/adr/0099-keep-the-environment-reference-classifier.md.
final class EnvironmentReferencesTest {
    private static final String NAME = "TRELLO_API_KEY";

    @MethodSource("references")
    @ParameterizedTest(name = "{0}")
    void classifiesTheWholeValueAsAReference(String scenario, String value, String expectedName) {
        // given
        // The scenario supplies the raw workflow or credential-file value.

        // when
        Optional<String> name = EnvironmentReferences.referenceName(value);

        // then
        assertThat(name).as(scenario).hasValue(expectedName);
    }

    @MethodSource("literals")
    @ParameterizedTest(name = "{0}")
    void keepsOtherValueShapesAsLiteralText(String scenario, String value) {
        // given
        // The scenario supplies the raw workflow or credential-file value.

        // when
        Optional<String> name = EnvironmentReferences.referenceName(value);

        // then
        assertThat(name).as(scenario).isEmpty();
    }

    private static Stream<Arguments> references() {
        return Stream.of(
                Arguments.of("generated dollar form", "$" + NAME, NAME),
                Arguments.of("shell brace form", "${" + NAME + "}", NAME),
                Arguments.of("surrounding whitespace is trimmed", " \t$" + NAME + "\n", NAME),
                Arguments.of("leading underscore", "$_PRIVATE", "_PRIVATE"),
                Arguments.of("lowercase letters and digits", "${trello_key_2}", "trello_key_2"),
                Arguments.of("non-ASCII letters as Character.isLetter defines them", "$ÄPFEL", "ÄPFEL"),
                Arguments.of("non-ASCII digits as Character.isDigit defines them", "$A\u0661", "A\u0661"));
    }

    private static Stream<Arguments> literals() {
        return Stream.of(
                Arguments.of("missing value", null),
                Arguments.of("empty value", ""),
                Arguments.of("blank value", "   "),
                Arguments.of("plain token", "plain-token"),
                Arguments.of("file secret reference", "file:/run/secrets/trello-key"),
                Arguments.of("lone dollar sign", "$"),
                Arguments.of("empty braces", "${}"),
                Arguments.of("unclosed brace", "${" + NAME),
                Arguments.of("closing brace without opening brace", "$" + NAME + "}"),
                Arguments.of("shell default expansion", "${" + NAME + ":-fallback}"),
                Arguments.of("SmallRye Config default expansion", "${" + NAME + ":fallback}"),
                Arguments.of("shell unset-only default expansion", "${" + NAME + "-fallback}"),
                Arguments.of("shell required-variable expansion", "${" + NAME + ":?missing}"),
                Arguments.of("escaped dollar form", "$$" + NAME),
                Arguments.of("escaped brace form", "$${" + NAME + "}"),
                Arguments.of("text before a reference", "prefix${" + NAME + "}"),
                Arguments.of("text after a reference", "${" + NAME + "}suffix"),
                Arguments.of("two adjacent references", "${A}${B}"),
                Arguments.of(
                        "path suffix after a reference, which path settings expand separately", "$HOME/workspaces"),
                Arguments.of("leading digit", "$1" + NAME),
                Arguments.of("nested braces", "${{" + NAME + "}}"),
                Arguments.of("spaces inside braces", "${ " + NAME + " }"),
                Arguments.of("dotted config property name", "${trello.api.key}"),
                Arguments.of("hyphen in name", "$TRELLO-API-KEY"),
                Arguments.of("supplementary-plane letter, which the char-based name check rejects", "$\uD835\uDC00"));
    }
}
