package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class WindowsManagedProcessPlatformTest {
    @TempDir
    Path tempDir;

    @EnabledOnOs(OS.WINDOWS)
    @Test
    void nativeExitWaitTimesOutWithoutSignallingALiveProcess() {
        // given
        var process = ProcessHandle.current();
        var platform = new WindowsManagedProcessPlatform();

        // when
        boolean exited = platform.awaitExited(List.of(process.pid()), Duration.ZERO);

        // then
        assertThat(exited).as("a live process has not signalled its exit").isFalse();
        assertThat(process.isAlive()).as("waiting never signals the process").isTrue();
    }

    @Test
    void longClasspathRemainsInTheLauncherEnvironment() {
        // given
        String classpath = "C:\\Users\\owner\\Symphony install\\lib\\dependency.jar;".repeat(400);

        // when
        ProcessBuilder launcher = WindowsManagedProcessPlatform.launcher(
                List.of("java.exe", "HelperMain"),
                tempDir,
                Map.of("CLASSPATH", classpath),
                tempDir.resolve("stdout.log"),
                tempDir.resolve("stderr.log"));

        // then
        assertThat(launcher.environment().get("CLASSPATH")).isEqualTo(classpath);
        assertThat(launcher.command().getLast()).hasSizeLessThan(32767);
        assertThat(decode(launcher.command().getLast())).doesNotContain(classpath);
    }

    @Test
    void readsProcessArgumentsThroughTheNativeQuery() {
        // given
        var runner = mock(CommandRunner.class);
        when(runner.run(any(String[].class)))
                .thenReturn(new CommandResult(0, "[\"java.exe\",\"-Dmarker=path with spaces\",\"HelperMain\"]"));
        var platform = new WindowsManagedProcessPlatform(runner);

        // when
        var arguments = platform.commandArguments(12345);

        // then
        assertThat(arguments).hasValue(List.of("java.exe", "-Dmarker=path with spaces", "HelperMain"));
    }

    @Test
    void launcherUsesDetachedStartProcessAndReturnsWorkerPid() {
        // given
        Path workingDirectory = tempDir.resolve("app with spaces");
        Path stdout = tempDir.resolve("state/worker out.log");
        Path stderr = tempDir.resolve("state/worker err.log");

        // when
        String script = decode(WindowsManagedProcessPlatform.encodedStartProcessScript(
                List.of(
                        "C:/Program Files/Java/bin/java.exe",
                        "-Dsymphony.trello.managed.app_home=C:/Program Files/Symphony/app",
                        "-jar",
                        "target/quarkus app/quarkus-run.jar",
                        "C:/Users/Fran/workflows/WORKFLOW.md"),
                workingDirectory,
                Map.of(
                        "SYMPHONY_TRELLO_DOTENV",
                        "C:/Users/Fran/config/.env",
                        LocalSetup.INSTALLER_COMPLETION_ENV,
                        "defer"),
                stdout,
                stderr));

        // then
        assertThat(script)
                .contains(
                        "$ErrorActionPreference = 'Stop'",
                        "$ProgressPreference = 'SilentlyContinue'",
                        "[System.Environment]::SetEnvironmentVariable('SYMPHONY_TRELLO_INSTALLER_COMPLETION', $null, 'Process')",
                        "[System.Environment]::SetEnvironmentVariable('SYMPHONY_TRELLO_DOTENV', 'C:/Users/Fran/config/.env', 'Process')",
                        "$process = Start-Process -FilePath 'C:/Program Files/Java/bin/java.exe'",
                        "-WorkingDirectory '"
                                + workingDirectory.toAbsolutePath().normalize() + "'",
                        "-RedirectStandardOutput '" + stdout.toAbsolutePath().normalize() + "'",
                        "-RedirectStandardError '" + stderr.toAbsolutePath().normalize() + "'",
                        "-WindowStyle Hidden -PassThru",
                        "[Console]::Out.WriteLine($process.Id)")
                .contains(
                        "-ArgumentList '\"-Dsymphony.trello.managed.app_home=C:/Program Files/Symphony/app\" -jar \"target/quarkus app/quarkus-run.jar\" C:/Users/Fran/workflows/WORKFLOW.md'")
                .doesNotContain("'SYMPHONY_TRELLO_INSTALLER_COMPLETION', 'defer'");
    }

    @Test
    void redirectedWorkerLogsAreRewrittenByStartProcess() {
        // given
        var platform = new WindowsManagedProcessPlatform();

        // when
        boolean appendsToExistingLogs = platform.appendsToExistingLogs();

        // then
        assertThat(appendsToExistingLogs)
                .as("PowerShell Start-Process rewrites redirected worker logs")
                .isFalse();
    }

    @Test
    void windowsCommandLineQuotesEmptyWhitespaceAndEmbeddedQuotes() {
        // given
        List<String> arguments = List.of("", "plain", "has space", "quote\"inside", "C:\\path with spaces\\");

        // when
        String commandLine = WindowsManagedProcessPlatform.windowsCommandLine(arguments);

        // then
        assertThat(commandLine).isEqualTo("\"\" plain \"has space\" \"quote\\\"inside\" \"C:\\path with spaces\\\\\"");
    }

    private static String decode(String encodedCommand) {
        return new String(Base64.getDecoder().decode(encodedCommand), StandardCharsets.UTF_16LE);
    }
}
