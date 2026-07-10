package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WindowsManagedProcessPlatformTest {
    @TempDir
    Path tempDir;

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
        WindowsManagedProcessPlatform platform = new WindowsManagedProcessPlatform();

        // when
        boolean appendsToExistingLogs = platform.appendsToExistingLogs();

        // then
        assertThat(appendsToExistingLogs).isFalse();
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
