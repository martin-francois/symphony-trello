package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(port).contains("9090");
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
        assertThat(port).contains("9090");
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
        assertThat(port).contains("19092");
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
        assertThat(options.workflowPath()).contains("WORKFLOW.md");
        assertThat(options.port()).contains("8081");
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
        assertThat(port).contains("8282");
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
