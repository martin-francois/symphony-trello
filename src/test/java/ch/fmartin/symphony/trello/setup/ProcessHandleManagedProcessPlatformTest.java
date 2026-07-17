package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProcessHandleManagedProcessPlatformTest {
    @TempDir
    Path tempDir;

    @Test
    void managedWorkerEnvironmentDropsInstallerCompletionMode() {
        // given
        Map<String, String> inheritedEnvironment = new LinkedHashMap<>(
                Map.of(LocalSetup.INSTALLER_COMPLETION_ENV, "defer", "KEEP_INHERITED", "inherited"));
        Map<String, String> configuredEnvironment =
                Map.of(LocalSetup.INSTALLER_COMPLETION_ENV, "print", "KEEP_CONFIGURED", "configured");

        // when
        ProcessHandleManagedProcessPlatform.configureWorkerEnvironment(inheritedEnvironment, configuredEnvironment);

        // then
        assertThat(inheritedEnvironment)
                .containsEntry("KEEP_INHERITED", "inherited")
                .containsEntry("KEEP_CONFIGURED", "configured")
                .doesNotContainKey(LocalSetup.INSTALLER_COMPLETION_ENV);
    }

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
        assertThat(workflowMatch)
                .as("the exact workflow-scoped command matches the managed process")
                .isTrue();
        assertThat(installMatch)
                .as("the exact install-scoped command matches the managed process")
                .isTrue();
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
        assertThat(installPrefixMatch)
                .as("an install-path prefix does not match the managed process")
                .isFalse();
        assertThat(workflowPrefixMatch)
                .as("a workflow-path prefix does not match the managed process")
                .isFalse();
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
