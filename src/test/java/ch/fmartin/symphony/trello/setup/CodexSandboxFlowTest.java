package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodexSandboxFlowTest {
    @Test
    void defaultNoKeepsSandboxEnabled() throws Exception {
        // given
        RecordingTerminal terminal = new RecordingTerminal("n");

        // when
        boolean dangerFullAccess =
                new CodexSandboxFlow().resolve(SetupOptionFactory.options(Path.of("target/sandbox-no")), terminal);

        // then
        assertThat(dangerFullAccess).isFalse();
        assertThat(terminal.stdout())
                .contains("Codex execution")
                .contains("Allow Codex to run without its command/filesystem sandbox")
                .doesNotContain("danger-full-access disables Codex's command/filesystem sandbox");
    }

    @Test
    void acceptedPromptEnablesDangerFullAccess() throws Exception {
        // given
        RecordingTerminal terminal = new RecordingTerminal("y");

        // when
        boolean dangerFullAccess =
                new CodexSandboxFlow().resolve(SetupOptionFactory.options(Path.of("target/sandbox-yes")), terminal);

        // then
        assertThat(dangerFullAccess).isTrue();
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "Allow Codex to run without its command/filesystem sandbox",
                        "danger-full-access disables Codex's command/filesystem sandbox");
    }

    @Test
    void explicitCliFlagEnablesDangerFullAccessWithoutSecondPrompt() throws Exception {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(
                Path.of("target/sandbox-flag"), false, Optional.empty(), List.of(), false, true);
        RecordingTerminal terminal = new RecordingTerminal();

        // when
        boolean dangerFullAccess = new CodexSandboxFlow().resolve(options, terminal);

        // then
        assertThat(dangerFullAccess).isTrue();
        assertThat(terminal.stdout())
                .contains("Codex execution", "danger-full-access disables Codex's command/filesystem sandbox")
                .doesNotContain("[y/N]");
    }

    @Test
    void nonInteractiveUsesExplicitFlagValue() throws Exception {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(
                Path.of("target/sandbox-non-interactive"), true, Optional.empty(), List.of(), false, true);
        RecordingTerminal terminal = new RecordingTerminal();

        // when
        boolean dangerFullAccess = new CodexSandboxFlow().resolve(options, terminal);

        // then
        assertThat(dangerFullAccess).isTrue();
        assertThat(terminal.stdout()).contains("danger-full-access disables Codex's command/filesystem sandbox");
    }
}
