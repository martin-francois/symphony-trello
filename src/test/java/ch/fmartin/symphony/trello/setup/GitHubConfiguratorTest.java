package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GitHubConfiguratorTest {
    @Test
    void alreadyAuthenticatedGitHubIsEnabledAutomatically() throws Exception {
        // given
        FakeCommandRunner commands =
                new FakeCommandRunner().returns(0, "alex", "gh", "api", "user", "--jq", ".login // \"\"");
        var terminal = new RecordingTerminal();

        // when
        GitHubIntegration integration = new GitHubConfigurator(commands)
                .resolve(
                        SetupOptionFactory.options(Path.of("target/github-auth")), prerequisites(true, true), terminal);

        // then
        assertThat(integration).isEqualTo(GitHubIntegration.ENABLED);
        assertThat(terminal.stdout()).contains("GitHub CLI authenticated", "GitHub integration configured");
    }

    @Test
    void installedButUnauthenticatedGithubRunsInlineLoginWhenAccepted() throws Exception {
        // given
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "gh version", "gh", "--version")
                .returns(0, "", "gh", "auth", "login")
                .returns(1, "not logged in", "gh", "auth", "status")
                .returns(0, "ok", "gh", "auth", "status")
                .returns(0, "alex", "gh", "api", "user", "--jq", ".login // \"\"");
        var terminal = new RecordingTerminal("y");

        // when
        GitHubIntegration integration = new GitHubConfigurator(commands)
                .resolve(
                        SetupOptionFactory.options(Path.of("target/github-login")),
                        prerequisites(true, false),
                        terminal);

        // then
        assertThat(integration).isEqualTo(GitHubIntegration.ENABLED);
        assertThat(commands.interactiveCommands()).containsExactly(List.of("gh", "auth", "login"));
    }

    @Test
    void missingGithubCliCanBeDeclined() {
        // given
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "apt", "apt-get", "--version")
                .returns(0, "0", "id", "-u");
        var terminal = new RecordingTerminal("y", "n");

        // when
        Throwable thrown = catchThrowable(() -> new GitHubConfigurator(commands)
                .resolve(
                        SetupOptionFactory.options(Path.of("target/github-decline")),
                        prerequisites(false, false),
                        terminal));

        // then
        assertThat(thrown).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("declined");
    }

    @Test
    void missingGithubCliUsesRootPackageManagerWithoutSudo() throws Exception {
        // given
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "apt", "apt-get", "--version")
                .returns(0, "0", "id", "-u")
                .returns(0, "installed gh", "bash", "-lc", "apt-get update && apt-get install -y gh")
                .returns(0, "gh version", "gh", "--version")
                .returns(0, "ok", "gh", "auth", "status")
                .returns(0, "alex", "gh", "api", "user", "--jq", ".login // \"\"");
        var terminal = new RecordingTerminal("y", "y");

        // when
        GitHubIntegration integration = new GitHubConfigurator(commands)
                .resolve(
                        SetupOptionFactory.options(Path.of("target/github-install-root")),
                        prerequisites(false, false),
                        terminal);

        // then
        assertThat(integration).isEqualTo(GitHubIntegration.ENABLED);
        assertThat(commands.interactiveCommands())
                .containsExactly(List.of("bash", "-lc", "apt-get update && apt-get install -y gh"));
        assertThat(terminal.stdout())
                .contains("Proposed install command:", "apt-get update && apt-get install -y gh")
                .doesNotContain("sudo apt-get");
    }

    @Test
    void nonGithubModeDoesNotRequireGh() throws Exception {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(
                Path.of("target/github-skip"), false, Optional.of(false), List.of(), false, false);
        var terminal = new RecordingTerminal();

        // when
        GitHubIntegration integration =
                new GitHubConfigurator(new FakeCommandRunner()).resolve(options, prerequisites(false, false), terminal);

        // then
        assertThat(integration).isEqualTo(GitHubIntegration.DISABLED);
        assertThat(terminal.stdout())
                .contains(
                        "GitHub integration skipped", "curl -fsSL https://symphony-trello.fmartin.ch/install.sh | bash")
                .doesNotContain("raw.githubusercontent.com");
    }

    @Test
    void nonInteractiveGithubModeFailsWhenAuthIsMissing() {
        // given
        LocalSetup.Options options = SetupOptionFactory.options(
                Path.of("target/github-required"), true, Optional.of(true), List.of(), false, false);

        // when
        Throwable thrown = catchThrowable(() -> new GitHubConfigurator(new FakeCommandRunner())
                .resolve(options, prerequisites(true, false), new RecordingTerminal()));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("GitHub auth is required");
    }

    private static Prerequisites prerequisites(boolean ghAvailable, boolean ghAuthenticated) {
        return new Prerequisites(
                ToolStatus.found(),
                ToolStatus.found(),
                ToolStatus.found(),
                ToolStatus.found(),
                ghAvailable ? ToolStatus.found() : ToolStatus.unavailable(),
                ghAuthenticated ? ToolStatus.found() : ToolStatus.unavailable());
    }
}
