package com.openai.symphony;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymphonyMainTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesServerPortFromWorkflowFrontMatterBeforeQuarkusStarts() throws Exception {
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

        assertThat(SymphonyMain.configuredServerPort(workflow)).contains("9090");
    }

    @Test
    void parsesServerPortWithYamlInlineCommentsBeforeQuarkusStarts() throws Exception {
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

        assertThat(SymphonyMain.configuredServerPort(workflow)).contains("9090");
    }

    @Test
    void cliPortOverridesWorkflowPortInParsedOptions() {
        var options = SymphonyMain.CliOptions.parse("WORKFLOW.md", "--port", "8081");

        assertThat(options.workflowPath()).contains("WORKFLOW.md");
        assertThat(options.port()).contains("8081");
    }

    @Test
    void externalQuarkusPortOverridesWorkflowPort() throws Exception {
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

            assertThat(SymphonyMain.configuredPort(SymphonyMain.CliOptions.parse("WORKFLOW.md"), workflow))
                    .isEmpty();
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

            assertThat(SymphonyMain.configuredPort(
                            SymphonyMain.CliOptions.parse("WORKFLOW.md", "--port=8282"), workflow))
                    .contains("8282");
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
        String oldValue = System.getProperty("symphony.workflow.path");
        try {
            System.setProperty("symphony.workflow.path", "custom/WORKFLOW.md");

            assertThat(SymphonyMain.configuredWorkflowPath()).isEqualTo("custom/WORKFLOW.md");
        } finally {
            if (oldValue == null) {
                System.clearProperty("symphony.workflow.path");
            } else {
                System.setProperty("symphony.workflow.path", oldValue);
            }
        }
    }
}
