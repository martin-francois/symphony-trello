package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessHandleManagedProcessPlatformTest {
    @TempDir
    Path tempDir;

    @Test
    void managedCommandMatchesExactInstallAndWorkflowArguments() {
        // given
        Path appHome = tempDir.resolve("symphony");
        Path workflow = tempDir.resolve("config/WORKFLOW.md");
        List<String> arguments = javaArguments(appHome, workflow);

        // when
        boolean workflowMatch =
                ProcessHandleManagedProcessPlatform.isManagedCommand(arguments, appHome, Optional.of(workflow));
        boolean installMatch =
                ProcessHandleManagedProcessPlatform.isManagedCommand(arguments, appHome, Optional.empty());

        // then
        assertThat(workflowMatch).isTrue();
        assertThat(installMatch).isTrue();
    }

    @Test
    void managedCommandRejectsInstallPathAndWorkflowPrefixes() {
        // given
        Path appHome = tempDir.resolve("symphony");
        Path workflow = tempDir.resolve("config/WORKFLOW.md");
        List<String> otherInstallArguments = javaArguments(tempDir.resolve("symphony-old"), workflow);
        List<String> otherWorkflowArguments = javaArguments(appHome, tempDir.resolve("config/WORKFLOW.md.bak"));

        // when
        boolean installPrefixMatch = ProcessHandleManagedProcessPlatform.isManagedCommand(
                otherInstallArguments, appHome, Optional.of(workflow));
        boolean workflowPrefixMatch = ProcessHandleManagedProcessPlatform.isManagedCommand(
                otherWorkflowArguments, appHome, Optional.of(workflow));

        // then
        assertThat(installPrefixMatch).isFalse();
        assertThat(workflowPrefixMatch).isFalse();
    }

    private static List<String> javaArguments(Path appHome, Path workflow) {
        Path normalizedAppHome = appHome.toAbsolutePath().normalize();
        return List.of(
                "-Dsymphony.trello.managed.app_home=" + normalizedAppHome,
                "-jar",
                normalizedAppHome.resolve("target/quarkus-app/quarkus-run.jar").toString(),
                workflow.toAbsolutePath().normalize().toString());
    }
}
