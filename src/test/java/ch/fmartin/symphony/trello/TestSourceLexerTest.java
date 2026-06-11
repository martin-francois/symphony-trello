package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestSourceLexerTest {
    @TempDir
    Path tempDir;

    @Test
    void blanksUnbalancedBracesInsideStringLiterals() {
        // given
        String source = "String manifest = \"{not valid json\";";

        // when
        String code = TestSourceLexer.stripNonCode(source);

        // then
        assertThat(code).doesNotContain("{").contains("String manifest =");
    }

    @Test
    void blanksBracesInsideTextBlocks() {
        // given
        String source =
                """
                String json = \"""
                        {"boards":[{}]}
                        extra } closer
                        \""";
                """;

        // when
        String code = TestSourceLexer.stripNonCode(source);

        // then
        assertThat(code).doesNotContain("{").doesNotContain("}");
        assertThat(code.lines().count()).isEqualTo(source.lines().count());
    }

    @Test
    void escapedQuotesDoNotEndStringLiterals() {
        // given
        String source = "String value = \"a\\\"b{\"; int x = 1;";

        // when
        String code = TestSourceLexer.stripNonCode(source);

        // then
        assertThat(code).doesNotContain("{").contains("int x = 1;");
    }

    @Test
    void blanksBracesInsideLineAndBlockComments() {
        // given
        String source =
                """
                int a = 1; // dangling {
                /* { still
                   open { */
                int b = 2;
                """;

        // when
        String code = TestSourceLexer.stripNonCode(source);

        // then
        assertThat(code).doesNotContain("{").contains("int a = 1;").contains("int b = 2;");
    }

    @Test
    void blanksBraceCharLiterals() {
        // given
        String source = "char open = '{'; char escaped = '\\u007B';";

        // when
        String code = TestSourceLexer.stripNonCode(source);

        // then
        assertThat(code).doesNotContain("{").contains("char open =");
    }

    @Test
    void keepsCodeBracesAndLineStructure() {
        // given
        String source =
                """
                void run() {
                    list.forEach(item -> { use(item); }); // trailing note
                }
                """;

        // when
        String code = TestSourceLexer.stripNonCode(source);

        // then
        assertThat(code.lines().count()).isEqualTo(source.lines().count());
        assertThat(code).contains("void run() {").contains("-> { use(item); });");
        assertThat(code).doesNotContain("trailing note");
    }

    @Test
    void conventionalTestWithUnbalancedBraceFixtureParses() throws Exception {
        // given
        // The issue #376 reproduction: a conventional, compilable test whose fixture string
        // contains unbalanced braces previously corrupted the brace depth, so the scanner
        // reported "could not parse test method" for perfectly conventional methods.
        Path file = tempDir.resolve("UnbalancedFixtureTest.java");
        Files.writeString(
                file,
                """
                final class UnbalancedFixtureTest {
                    @Test
                    void reportsMalformedManifest() throws Exception {
                        // given
                        Files.writeString(manifest, "{not {valid json", StandardCharsets.UTF_8);

                        // when
                        Throwable thrown = catchThrowable(() -> fixture.status(request));

                        // then
                        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class);
                    }

                    @Test
                    void conventionalSibling() {
                        // given
                        String input = "value";

                        // when
                        String stripped = input.strip();

                        // then
                        assertThat(stripped).isEqualTo("value");
                    }
                }
                """);

        // when
        List<String> violations = TestConventionTest.testSectionViolations(file);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void unbalancedClosingBraceInStringDoesNotTruncateMethodBody() throws Exception {
        // given
        // A surplus closing brace inside a string previously ended the method body early, so the
        // conventional early method was falsely flagged as missing its sections; only the later
        // method's genuine violation may be reported.
        Path file = tempDir.resolve("FalseNegativeTest.java");
        Files.writeString(
                file,
                """
                final class FalseNegativeTest {
                    @Test
                    void earlyTest() {
                        // given
                        String tail = "early closer }";

                        // when
                        String value = tail.strip();

                        // then
                        assertThat(value).isNotEmpty();
                    }

                    @Test
                    void laterTestWithoutSections() {
                        assertThat(1).isEqualTo(1);
                    }
                }
                """);

        // when
        List<String> violations = TestConventionTest.testSectionViolations(file);

        // then
        assertThat(violations).singleElement().asString().contains("expected // given, // when, and // then in order");
    }
}
