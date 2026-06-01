package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class PrerequisiteCheckerTest {
    @MethodSource("javaVersions")
    @ParameterizedTest(name = "{0}")
    void javaStatusRequiresJava25Jdk(String name, String javaOutput, String javacOutput, boolean expectedAvailable) {
        // given
        FakeCommandRunner commands = baseCommands()
                .returns(0, javaOutput, "java", "-version")
                .returns(
                        javacOutput == null ? 127 : 0,
                        javacOutput == null ? "missing" : javacOutput,
                        "javac",
                        "-version");

        // when
        Prerequisites prerequisites = new PrerequisiteChecker(commands).check();

        // then
        assertThat(prerequisites.java().available()).as(name).isEqualTo(expectedAvailable);
    }

    @Test
    void codexAuthIsMissingWhenCodexIsMissing() {
        // given
        FakeCommandRunner commands = baseCommands();

        // when
        Prerequisites prerequisites = new PrerequisiteChecker(commands).check();

        // then
        assertThat(prerequisites)
                .extracting(value -> value.codex().available(), value -> value.codexAuth()
                        .available())
                .containsExactly(false, false);
    }

    @Test
    void githubAuthIsOptionalUnlessGithubModeRequiresIt() {
        // given
        FakeCommandRunner commands = baseCommands()
                .returns(0, "java 25", "java", "-version")
                .returns(0, "javac 25", "javac", "-version")
                .returns(0, "git", "git", "--version")
                .returns(0, "codex", "codex", "--version")
                .returns(0, "auth", "codex", "login", "status");

        // when
        Prerequisites prerequisites = new PrerequisiteChecker(commands).check();

        // then
        assertThat(prerequisites.readyFor(SetupOptionFactory.options(java.nio.file.Path.of("target"))))
                .isTrue();
    }

    private static Stream<Arguments> javaVersions() {
        return Stream.of(
                Arguments.of("runtime without javac", "openjdk version \"25.0.1\"", null, false),
                Arguments.of("java 24 rejected", "openjdk version \"24.0.2\"", "javac 24.0.2", false),
                Arguments.of("java 25 accepted", "openjdk version \"25.0.1\"", "javac 25.0.1", true),
                Arguments.of(
                        "leading Java option diagnostics ignored",
                        """
                        Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8
                        openjdk version "25.0.1"
                        """,
                        """
                        NOTE: Picked up JDK_JAVA_OPTIONS: -Xmx2g
                        javac 25.0.1
                        """,
                        true),
                Arguments.of("newer java accepted", "openjdk version \"26.0.1\"", "javac 26.0.1", true));
    }

    private static FakeCommandRunner baseCommands() {
        return new FakeCommandRunner();
    }
}
