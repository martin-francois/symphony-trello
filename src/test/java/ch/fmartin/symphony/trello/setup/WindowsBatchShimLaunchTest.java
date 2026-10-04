package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.NPM_PATHEXT;
import static java.nio.file.Files.readAllLines;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.process.ExecutableResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Starts a real batch shim shaped like the one npm writes for Codex. Only Windows has `cmd.exe`,
/// so the windows-powershell CI lane runs this class.
@EnabledOnOs(OS.WINDOWS)
final class WindowsBatchShimLaunchTest {
    private static final String SHIM_NAME = "fake-codex";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);

    @TempDir
    Path tempDir;

    @Test
    void bareNameMissesTheNpmShimThatTheResolverStarts() throws Exception {
        // given
        Path recordFile = tempDir.resolve("probe-arguments.txt");
        Path shimDirectory = npmStyleShim(recordFile);

        // when
        String bare = probe(shimDirectory, BareCommandProbe.BARE);
        String resolved = probe(shimDirectory, BareCommandProbe.RESOLVED);

        // then
        assertThat(bare).as("ProcessBuilder by bare name on Windows").isEqualTo(BareCommandProbe.LAUNCH_FAILED);
        assertThat(resolved).isEqualTo(BareCommandProbe.EXIT_PREFIX + ArgumentRecorder.EXIT_CODE);
        assertThat(readAllLines(recordFile)).containsExactly("login", "status");
    }

    @FieldSource("ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures#CMD_SAFE_ARGUMENTS")
    @ParameterizedTest(name = "[{index}] argument <{0}>")
    void runPassesArgumentAndExitStatusThroughTheShimUnchanged(String argument) throws Exception {
        // given
        Path recordFile = tempDir.resolve("run-arguments.txt");
        var runner = runnerFindingShimIn(npmStyleShim(recordFile));

        // when
        CommandResult result = runner.run(SHIM_NAME, argument);

        // then
        assertThat(result.exitCode())
                .as("exit status of the shim; its output was: %s", result.output())
                .isEqualTo(ArgumentRecorder.EXIT_CODE);
        assertThat(readAllLines(recordFile)).containsExactly(argument);
    }

    @ParameterizedTest(name = "[{index}] metacharacter probe <{0}>")
    @ValueSource(
            strings = {
                "a&b",
                "a|b",
                "a<b",
                "a>b",
                "a^b",
                "(a)",
                "a)b",
                "C:\\Program Files (x86)\\tool",
                "RUNNER~1",
                "a;b",
                "[x]{y}#$'",
                "a b&c",
                "x & y"
            })
    void metacharacterProbe(String argument) throws Exception {
        // given
        Path recordFile = tempDir.resolve("probe-arguments.txt");
        var runner = runnerFindingShimIn(npmStyleShim(recordFile));

        // when
        CommandResult result = runner.run(SHIM_NAME, argument);

        // then
        assertThat(result.exitCode())
                .as("exit status of the shim; its output was: %s", result.output())
                .isEqualTo(ArgumentRecorder.EXIT_CODE);
        assertThat(readAllLines(recordFile)).containsExactly(argument);
    }

    @Test
    void runInteractiveStartsTheShimWithTheSameArguments() throws Exception {
        // given
        Path recordFile = tempDir.resolve("interactive-arguments.txt");
        var runner = runnerFindingShimIn(npmStyleShim(recordFile));

        // when
        CommandResult result = runner.runInteractive(SHIM_NAME, "login", "--device-auth");

        // then
        assertThat(result.exitCode()).isEqualTo(ArgumentRecorder.EXIT_CODE);
        assertThat(readAllLines(recordFile)).containsExactly("login", "--device-auth");
    }

    /// Writes `fake-codex.cmd` with the same structure as npm's shim: start a program with a fixed
    /// script argument and forward `%*`.
    private Path npmStyleShim(Path recordFile) throws IOException {
        Path shimDirectory = Files.createDirectories(tempDir.resolve("npm-prefix"));
        Files.writeString(
                shimDirectory.resolve(SHIM_NAME + ".cmd"),
                "@ECHO off\r\n\"" + javaExecutable() + "\" -cp \"" + System.getProperty("java.class.path") + "\" "
                        + ArgumentRecorder.class.getName() + " \"" + recordFile + "\" %*\r\n");
        return shimDirectory;
    }

    private static ProcessCommandRunner runnerFindingShimIn(Path shimDirectory) {
        return new ProcessCommandRunner(
                PROCESS_TIMEOUT,
                new ExecutableResolver(
                        Map.of("PATH", shimDirectory.toString(), "PATHEXT", NPM_PATHEXT),
                        System.getProperty("os.name")));
    }

    /// Runs [BareCommandProbe] in a JVM whose `PATH` is only the shim directory. `CreateProcess`
    /// searches the parent's `PATH`, so the bare-name case needs its own process.
    private String probe(Path shimDirectory, String mode) throws IOException, InterruptedException {
        Path output = tempDir.resolve("probe-" + mode + ".txt");
        ProcessBuilder builder = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        BareCommandProbe.class.getName(),
                        mode,
                        SHIM_NAME,
                        "login",
                        "status")
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        // Windows names the variable Path; drop every spelling so the shim directory is the only entry.
        builder.environment().keySet().removeIf(name -> name.equalsIgnoreCase("PATH"));
        builder.environment().put("PATH", shimDirectory.toString());
        builder.environment().put("PATHEXT", NPM_PATHEXT);
        Process process = builder.start();
        boolean exited = process.waitFor(PROCESS_TIMEOUT);
        if (!exited) {
            process.destroyForcibly();
        }
        assertThat(exited).as("the probe JVM exits within %s", PROCESS_TIMEOUT).isTrue();
        return Files.readString(output).strip();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }
}
