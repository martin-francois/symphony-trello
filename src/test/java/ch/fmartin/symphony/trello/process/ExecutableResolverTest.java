package ch.fmartin.symphony.trello.process;

import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.CMD_SAFE_ARGUMENTS;
import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.NPM_PATHEXT;
import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.WINDOWS_OS_NAME;
import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.command;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ExecutableResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void windowsStartsNpmCodexShimFromPathThroughCmd() throws Exception {
        // given
        Path codexShim = tool("codex.CMD");
        var resolver = windowsResolver(NPM_PATHEXT);

        // when
        List<String> command = resolver.launchCommand(List.of("codex", "login", "--device-auth"));

        // then
        assertThat(command)
                .containsExactly("cmd.exe", "/d", "/s", "/c", "\"\"" + codexShim + "\" \"login\" \"--device-auth\"\"");
    }

    @Test
    void windowsStartsTheCommandInterpreterNamedByComSpec() throws Exception {
        // given
        Path codexShim = tool("codex.CMD");
        Path comSpec = tempDir.resolve("system32").resolve("cmd.exe");
        var resolver = new ExecutableResolver(
                Map.of("PATH", tempDir.toString(), "PATHEXT", NPM_PATHEXT, "ComSpec", comSpec.toString()),
                WINDOWS_OS_NAME);

        // when
        List<String> command = resolver.launchCommand(List.of("codex", "--version"));

        // then
        assertThat(command)
                .containsExactly(comSpec.toString(), "/d", "/s", "/c", "\"\"" + codexShim + "\" \"--version\"\"");
    }

    @Test
    void windowsQuotesEveryBatchArgumentSoCmdKeepsItUnchanged() throws Exception {
        // given
        Path codexShim = tool("codex.CMD");
        var resolver = windowsResolver(NPM_PATHEXT);

        // when
        List<String> command = resolver.launchCommand(command("codex", CMD_SAFE_ARGUMENTS));

        // then
        assertThat(command)
                .last()
                .isEqualTo("\"\"" + codexShim + "\" \"-c\""
                        + " \"developer_instructions=Selected board: Team board, short link abc123.\""
                        + " \"C:\\Program Files (x86)\\RUNNER~1\\tool\""
                        + " \"a;b [x]{y}#$'\""
                        + " \"C:\\work dir\\\\\""
                        + " \"\"\"");
    }

    @ParameterizedTest(name = "[{index}] argument <{0}> is refused")
    @ValueSource(
            strings = {
                "say \"hi\"",
                "%PATH%",
                "wow!",
                "a&b",
                "a|b",
                "a<b",
                "a>b",
                "a^b",
                "two\nlines",
                "carriage\rreturn"
            })
    void windowsRefusesBatchArgumentsThatCmdWouldChange(String argument) throws Exception {
        // given
        tool("codex.CMD");
        var resolver = windowsResolver(NPM_PATHEXT);

        // when
        Throwable thrown = catchThrowable(() -> resolver.launchCommand(List.of("codex", argument)));

        // then
        assertThat(thrown)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Windows batch file")
                .hasMessageNotContaining(argument);
    }

    @Test
    void windowsStartsBatchFileNamedByPathThroughCmd() throws Exception {
        // given
        Path script = tool("helper.bat");
        var resolver = windowsResolver(NPM_PATHEXT);

        // when
        List<String> command = resolver.launchCommand(List.of(script.toString(), "--version"));

        // then
        assertThat(command).containsExactly("cmd.exe", "/d", "/s", "/c", "\"\"" + script + "\" \"--version\"\"");
    }

    @Test
    void windowsLeavesCommandUnchangedWhenPathextFindsNativeExecutableFirst() throws Exception {
        // given
        tool("codex.EXE");
        tool("codex.CMD");
        var resolver = windowsResolver(".EXE;.CMD");

        // when
        List<String> command = resolver.launchCommand(List.of("codex", "login"));

        // then
        assertThat(command).containsExactly("codex", "login");
    }

    @Test
    void windowsLeavesUnknownCommandUnchangedSoTheLaunchReportsItMissing() throws Exception {
        // given
        var resolver = windowsResolver(NPM_PATHEXT);

        // when
        List<String> command = resolver.launchCommand(List.of("codex", "login"));

        // then
        assertThat(command).containsExactly("codex", "login");
    }

    @Test
    void windowsFindsToolThroughQuotedPathEntryAndCaseInsensitivePathName() throws Exception {
        // given
        Path codexShim = tool("codex.CMD");
        var resolver = new ExecutableResolver(
                Map.of("Path", '"' + tempDir.toString() + '"', "PATHEXT", ".CMD"), WINDOWS_OS_NAME);

        // when
        var found = resolver.find("codex");

        // then
        assertThat(found).hasValue(codexShim);
    }

    @Test
    void posixLeavesCommandUnchangedEvenWhenABatchShimExists() throws Exception {
        // given
        tool("codex.CMD");
        var resolver = new ExecutableResolver(Map.of("PATH", tempDir.toString(), "PATHEXT", NPM_PATHEXT), "Linux");

        // when
        List<String> command = resolver.launchCommand(List.of("codex", "login"));

        // then
        assertThat(command).containsExactly("codex", "login");
    }

    private ExecutableResolver windowsResolver(String pathext) {
        return new ExecutableResolver(Map.of("PATH", tempDir.toString(), "PATHEXT", pathext), WINDOWS_OS_NAME);
    }

    private Path tool(String fileName) throws IOException {
        return Files.writeString(tempDir.resolve(fileName), "");
    }
}
