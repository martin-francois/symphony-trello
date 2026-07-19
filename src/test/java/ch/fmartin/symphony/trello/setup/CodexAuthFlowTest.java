package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CodexAuthFlowTest {
    @Test
    void browserLoginRunsCodexLoginAndVerifiesStatus() throws Exception {
        // given
        FakeCommandRunner commands = unauthenticatedCodex()
                .returns(0, "", "codex", "login")
                .returns(0, "authenticated", "codex", "login", "status");
        var terminal = new RecordingTerminal("");

        // when
        new CodexAuthFlow(commands)
                .ensureAuthenticated(
                        prerequisites(false), SetupOptionFactory.options(Path.of("target/codex-browser")), terminal);

        // then
        assertThat(commands.interactiveCommands()).containsExactly(List.of("codex", "login"));
        assertThat(terminal.stdout()).contains("Can this machine open a browser", "OK  Codex CLI authenticated");
    }

    @Test
    void deviceAuthLoginRunsDeviceCommand() throws Exception {
        // given
        FakeCommandRunner commands = unauthenticatedCodex()
                .returns(0, "", "codex", "login", "--device-auth")
                .returns(0, "authenticated", "codex", "login", "status");
        var terminal = new RecordingTerminal("n");

        // when
        new CodexAuthFlow(commands)
                .ensureAuthenticated(
                        prerequisites(false), SetupOptionFactory.options(Path.of("target/codex-device")), terminal);

        // then
        assertThat(commands.interactiveCommands()).containsExactly(List.of("codex", "login", "--device-auth"));
        assertThat(terminal.stdout())
                .contains("Can this machine open a browser", "OK  Codex CLI authenticated")
                .doesNotContain("Device auth");
    }

    @Test
    void nonInteractiveMissingAuthFailsBeforePrompting() {
        // given
        FakeCommandRunner commands = unauthenticatedCodex();
        var terminal = new RecordingTerminal();
        LocalSetup.Options options = SetupOptionFactory.options(
                Path.of("target/codex-non-interactive"), true, Optional.empty(), List.of(), false, false);

        // when
        Throwable thrown = catchThrowable(
                () -> new CodexAuthFlow(commands).ensureAuthenticated(prerequisites(false), options, terminal));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("codex login");
    }

    @Test
    void loginCompletionWithFailingStatusFails() {
        // given
        FakeCommandRunner commands = unauthenticatedCodex()
                .returns(0, "", "codex", "login")
                .returns(1, "missing", "codex", "login", "status");
        var terminal = new RecordingTerminal("");

        // when
        Throwable thrown = catchThrowable(() -> new CodexAuthFlow(commands)
                .ensureAuthenticated(
                        prerequisites(false), SetupOptionFactory.options(Path.of("target/codex-status")), terminal));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("Run `codex login`, then rerun setup-local.");
    }

    @Test
    void deviceAuthCompletionWithFailingStatusNamesDeviceCommand() {
        // given
        FakeCommandRunner commands = unauthenticatedCodex()
                .returns(0, "", "codex", "login", "--device-auth")
                .returns(1, "missing", "codex", "login", "status");
        var terminal = new RecordingTerminal("n");

        // when
        Throwable thrown = catchThrowable(() -> new CodexAuthFlow(commands)
                .ensureAuthenticated(
                        prerequisites(false), SetupOptionFactory.options(Path.of("target/codex-status")), terminal));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("Run `codex login --device-auth`, then rerun setup-local.");
    }

    private static FakeCommandRunner unauthenticatedCodex() {
        return new FakeCommandRunner();
    }

    private static Prerequisites prerequisites(boolean authenticated) {
        return new Prerequisites(
                ToolStatus.found(),
                ToolStatus.found(),
                ToolStatus.found(),
                authenticated ? ToolStatus.found() : ToolStatus.unavailable(),
                ToolStatus.unavailable(),
                ToolStatus.unavailable());
    }
}
