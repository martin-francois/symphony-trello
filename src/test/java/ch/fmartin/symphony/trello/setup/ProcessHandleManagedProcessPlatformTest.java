package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ProcessHandleManagedProcessPlatformTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableIdentityRefusesLiveWorkers(boolean workflowScoped) {
        // given
        var platform = mock(ProcessHandleManagedProcessPlatform.class, CALLS_REAL_METHODS);
        doReturn(true).when(platform).isAlive(42);
        doReturn(Optional.empty()).when(platform).commandArguments(42);

        // when
        Throwable thrown = catchThrowable(() -> inspectWorker(platform, workflowScoped));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("cannot inspect worker");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableIdentityRecognizesExitedProcesses(boolean workflowScoped) {
        // given
        var platform = mock(ProcessHandleManagedProcessPlatform.class, CALLS_REAL_METHODS);
        doReturn(false).when(platform).isAlive(42);
        doReturn(Optional.empty()).when(platform).commandArguments(42);

        // when
        boolean managed = inspectWorker(platform, workflowScoped);

        // then
        assertThat(managed).as("an exited process is not a managed worker").isFalse();
    }

    private boolean inspectWorker(ManagedProcessPlatform platform, boolean workflowScoped) {
        return workflowScoped
                ? platform.isManaged(42, tempDir, tempDir.resolve("WORKFLOW.md"))
                : platform.isManaged(42, tempDir);
    }

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
