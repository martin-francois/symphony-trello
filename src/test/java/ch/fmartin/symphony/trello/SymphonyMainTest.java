package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SymphonyMainTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesServerPortFromWorkflowFrontMatterBeforeQuarkusStarts() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                server:
                  port: "9090"
                ---
                Prompt
                """);

        // when
        var port = SymphonyMain.configuredServerPort(workflow);

        // then
        assertThat(port).hasValue("9090");
    }

    @Test
    void parsesServerPortWithYamlInlineCommentsBeforeQuarkusStarts() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                server:
                  port: 9090 # local Symphony status port
                ---
                Prompt
                """);

        // when
        var port = SymphonyMain.configuredServerPort(workflow);

        // then
        assertThat(port).hasValue("9090");
    }

    @Test
    void normalizesWholeValuedFloatServerPortBeforeQuarkusStarts() {
        // given
        String literalFloat = """
                server:
                  port: 18080.0
                """;
        String envBackedFloat =
                """
                server:
                  port: $SYMPHONY_FLOAT_PORT
                """;

        // when
        var literal = SymphonyMain.configuredServerPort(literalFloat, ignored -> Optional.empty());
        var envBacked = SymphonyMain.configuredServerPort(
                envBackedFloat, name -> "SYMPHONY_FLOAT_PORT".equals(name) ? Optional.of("18080.0") : Optional.empty());

        // then
        assertThat(literal)
                .as("Quarkus cannot parse 18080.0 as an integer port, so whole floats normalize")
                .hasValue("18080");
        assertThat(envBacked).hasValue("18080");
    }

    @Test
    void rejectsFractionalServerPortBeforeQuarkusStarts() {
        // given
        String literalFraction = """
                server:
                  port: 18080.5
                """;
        String envBackedFraction =
                """
                server:
                  port: $SYMPHONY_FRACTION_PORT
                """;

        // when
        Throwable literal =
                catchThrowable(() -> SymphonyMain.configuredServerPort(literalFraction, ignored -> Optional.empty()));
        Throwable envBacked = catchThrowable(() -> SymphonyMain.configuredServerPort(
                envBackedFraction,
                name -> "SYMPHONY_FRACTION_PORT".equals(name) ? Optional.of("18080.5") : Optional.empty()));

        // then
        assertThat(literal)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.port must be a whole number: 18080.5");
        assertThat(envBacked)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.port must be a whole number: 18080.5");
    }

    @Test
    void rejectsWholeButTooLargeServerPortBeforeQuarkusStarts() {
        // given
        String tooLarge =
                """
                server:
                  port: 99999999999999999999999
                """;

        // when
        Throwable thrown =
                catchThrowable(() -> SymphonyMain.configuredServerPort(tooLarge, ignored -> Optional.empty()));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server.port is out of integer range");
    }

    @Test
    void resolvesServerPortEnvironmentReferenceBeforeQuarkusStarts() {
        // given
        String frontMatter =
                """
                server:
                  port: $SYMPHONY_TEST_PORT
                """;

        // when
        var port = SymphonyMain.configuredServerPort(
                frontMatter, name -> "SYMPHONY_TEST_PORT".equals(name) ? Optional.of("19092") : Optional.empty());

        // then
        assertThat(port).hasValue("19092");
    }

    @Test
    void rejectsUnresolvedServerPortEnvironmentReferenceBeforeQuarkusStarts() {
        // given
        String frontMatter =
                """
                server:
                  port: $SYMPHONY_TEST_PORT
                """;

        // when
        var thrown = assertThatThrownBy(() -> SymphonyMain.configuredServerPort(frontMatter, name -> Optional.empty()));

        // then
        thrown.isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workflow server.port references missing environment variable SYMPHONY_TEST_PORT.");
    }

    @Test
    void cliPortOverridesWorkflowPortInParsedOptions() {
        // given
        String[] args = {"WORKFLOW.md", "--port", "8081"};

        // when
        var options = SymphonyMain.CliOptions.parse(args);

        // then
        assertThat(options.workflowPath()).hasValue("WORKFLOW.md");
        assertThat(options.port()).hasValue("8081");
    }

    @Test
    void externalQuarkusPortOverridesWorkflowPort() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                server:
                  port: 9090
                ---
                Prompt
                """);

        // when
        var port = SymphonyMain.configuredPort(
                SymphonyMain.CliOptions.parse("WORKFLOW.md"), workflow, "8181", Optional.empty());

        // then
        assertThat(port).isEmpty();
    }

    @Test
    void cliPortStillWinsOverExternalQuarkusPort() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                server:
                  port: 9090
                ---
                Prompt
                """);

        // when
        var port = SymphonyMain.configuredPort(
                SymphonyMain.CliOptions.parse("WORKFLOW.md", "--port=8282"), workflow, "8181", Optional.empty());

        // then
        assertThat(port).hasValue("8282");
    }

    @Test
    void prebootWorkflowPathHonorsSystemPropertyBeforeDefault() {
        // given

        // when
        String workflowPath = SymphonyMain.configuredWorkflowPath("custom/WORKFLOW.md", Optional.empty());

        // then
        assertThat(workflowPath).isEqualTo("custom/WORKFLOW.md");
    }
}
