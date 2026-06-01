package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
        String oldValue = System.getProperty("quarkus.http.port");
        try {
            System.setProperty("quarkus.http.port", "8181");

            // when
            var port = SymphonyMain.configuredPort(SymphonyMain.CliOptions.parse("WORKFLOW.md"), workflow);

            // then
            assertThat(port).isEmpty();
        } finally {
            if (oldValue == null) {
                System.clearProperty("quarkus.http.port");
            } else {
                System.setProperty("quarkus.http.port", oldValue);
            }
        }
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
        String oldValue = System.getProperty("quarkus.http.port");
        try {
            System.setProperty("quarkus.http.port", "8181");

            // when
            var port =
                    SymphonyMain.configuredPort(SymphonyMain.CliOptions.parse("WORKFLOW.md", "--port=8282"), workflow);

            // then
            assertThat(port).contains("8282");
        } finally {
            if (oldValue == null) {
                System.clearProperty("quarkus.http.port");
            } else {
                System.setProperty("quarkus.http.port", oldValue);
            }
        }
    }

    @Test
    void prebootWorkflowPathHonorsSystemPropertyBeforeDefault() {
        // given
        String oldValue = System.getProperty("symphony.workflow.path");
        try {
            System.setProperty("symphony.workflow.path", "custom/WORKFLOW.md");

            // when
            String workflowPath = SymphonyMain.configuredWorkflowPath();

            // then
            assertThat(workflowPath).isEqualTo("custom/WORKFLOW.md");
        } finally {
            if (oldValue == null) {
                System.clearProperty("symphony.workflow.path");
            } else {
                System.setProperty("symphony.workflow.path", oldValue);
            }
        }
    }
}
