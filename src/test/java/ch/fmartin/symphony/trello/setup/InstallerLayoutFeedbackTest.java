package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.CONFIG_DIR_ENV;
import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.STATE_HOME_ENV;
import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.SYMPHONY_HOME_ENV;
import static ch.fmartin.symphony.trello.setup.SetupEnvironmentVariables.WORKSPACE_ROOT_ENV;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InstallerLayoutFeedbackTest {
    private static final String EXAMPLE_HOME = "/home/example";

    private final Map<String, String> properties =
            Map.of("os.name", "macOS", "os.version", "15.6", "os.arch", "aarch64", "symphony.trello.shell", "posix");

    @Test
    void authenticatedGithubCreatesReviewedSanitizedFeatureRequest() {
        // given
        Map<String, String> environment =
                feedbackEnvironment("/Users/example", CONFIG_DIR_ENV + "," + STATE_HOME_ENV + ",UNTRUSTED_NAME");
        var commands = new RecordingCommands(true);
        LocalWorkerPaths paths = paths("/Users/example/.config/symphony-trello", "/var/lib/symphony/state");

        // when
        RecordingTerminal terminal = offerFeedback(environment, commands, paths, "y", "y");

        // then
        assertThat(terminal.stdout())
                .contains(
                        "Proposed GitHub feature request:",
                        "Config: $HOME/.config/symphony-trello",
                        "State/logs: <outside-home>",
                        "Feature request created: https://github.com/martin-francois/symphony-trello/issues/900")
                .doesNotContain("/Users/example", "/var/lib/symphony/state", "UNTRUSTED_NAME");
        assertThat(commands.commands)
                .satisfiesExactly(
                        auth -> assertThat(auth).containsExactly("gh", "auth", "status"), create -> assertThat(create)
                                .contains(
                                        "gh",
                                        "issue",
                                        "create",
                                        "--repo",
                                        "martin-francois/symphony-trello",
                                        "--label",
                                        "enhancement")
                                .doesNotContain("/Users/example", "/var/lib/symphony/state", "UNTRUSTED_NAME"));
    }

    @Test
    void missingGithubAuthPrintsPrefilledBrowserLink() {
        // given
        Map<String, String> environment = feedbackEnvironment(EXAMPLE_HOME, SYMPHONY_HOME_ENV);
        var commands = new RecordingCommands(false);
        LocalWorkerPaths paths =
                paths(EXAMPLE_HOME + "/.config/symphony-trello", EXAMPLE_HOME + "/.local/state/symphony-trello");

        // when
        RecordingTerminal terminal = offerFeedback(environment, commands, paths, "y");

        // then
        assertThat(terminal.stdout())
                .contains(
                        "Open this prefilled feature request when convenient:",
                        "https://github.com/martin-francois/symphony-trello/issues/new?template=feature_request.yml",
                        "compatibility-impact=No%20visible%20impact")
                .doesNotContain(EXAMPLE_HOME);
        assertThat(commands.commands).containsExactly(List.of("gh", "auth", "status"));
    }

    @Test
    void workspaceOnlyLayoutIncludesSanitizedWorkspaceContext() {
        // given
        Map<String, String> environment = feedbackEnvironment(EXAMPLE_HOME, WORKSPACE_ROOT_ENV);
        var commands = new RecordingCommands(false);
        LocalWorkerPaths paths = new LocalWorkerPaths(
                Path.of("/app"),
                Path.of(EXAMPLE_HOME + "/config"),
                Path.of(EXAMPLE_HOME + "/shared/workspaces"),
                Path.of(EXAMPLE_HOME + "/state"));

        // when
        RecordingTerminal terminal = offerFeedback(environment, commands, paths, "y");

        // then
        assertThat(terminal.stdout())
                .contains("Explicit variables: " + WORKSPACE_ROOT_ENV, "Workspaces: $HOME/<redacted>/workspaces")
                .doesNotContain(EXAMPLE_HOME);
    }

    @Test
    void identifyingChildPathComponentsAreRedacted() {
        // given
        Map<String, String> environment = feedbackEnvironment(EXAMPLE_HOME, WORKSPACE_ROOT_ENV);
        var commands = new RecordingCommands(false);
        LocalWorkerPaths paths = new LocalWorkerPaths(
                Path.of("/app"),
                Path.of(EXAMPLE_HOME + "/.config/symphony-trello"),
                Path.of(EXAMPLE_HOME + "/workspaces/customer-host"),
                Path.of(EXAMPLE_HOME + "/.local/state/symphony-trello"));

        // when
        RecordingTerminal terminal = offerFeedback(environment, commands, paths, "y");

        // then
        assertThat(terminal.stdout())
                .contains("Workspaces: $HOME/workspaces/<redacted>")
                .doesNotContain("customer-host", EXAMPLE_HOME);
    }

    @Test
    void personalLayoutDeclineSkipsGithubProbe() {
        // given
        Map<String, String> environment = feedbackEnvironment(EXAMPLE_HOME, SYMPHONY_HOME_ENV);
        var commands = new RecordingCommands(true);
        LocalWorkerPaths paths = paths(EXAMPLE_HOME + "/config", EXAMPLE_HOME + "/state");

        // when
        RecordingTerminal terminal = offerFeedback(environment, commands, paths, "n");

        // then
        assertThat(terminal.stdout()).contains("Layout feedback skipped");
        assertThat(commands.commands).isEmpty();
    }

    @Test
    void standardLayoutDoesNotOfferFeedback() {
        // given
        var commands = new RecordingCommands(true);
        Map<String, String> environment = Map.of("HOME", EXAMPLE_HOME);
        LocalWorkerPaths paths = paths(EXAMPLE_HOME + "/config", EXAMPLE_HOME + "/state");

        // when
        RecordingTerminal terminal = offerFeedback(environment, commands, paths);

        // then
        assertThat(terminal.stdout()).isEmpty();
        assertThat(commands.commands).isEmpty();
    }

    @Test
    void githubFailureDoesNotEscapeFeedbackFlow() {
        // given
        Map<String, String> environment = feedbackEnvironment(EXAMPLE_HOME, SYMPHONY_HOME_ENV);
        CommandRunner commands = ignored -> {
            throw new IllegalStateException("private failure details");
        };
        LocalWorkerPaths paths = paths(EXAMPLE_HOME + "/config", EXAMPLE_HOME + "/state");

        // when
        RecordingTerminal terminal = offerFeedback(environment, commands, paths, "y");

        // then
        assertThat(terminal.stdout())
                .contains("GitHub CLI did not create the feature request", "Open this prefilled feature request")
                .doesNotContain("private failure details");
    }

    private RecordingTerminal offerFeedback(
            Map<String, String> environment, CommandRunner commands, LocalWorkerPaths paths, String... answers) {
        var terminal = new RecordingTerminal(answers);
        new InstallerLayoutFeedback(environment, commands, properties::get).offer(paths, terminal);
        return terminal;
    }

    private static Map<String, String> feedbackEnvironment(String home, String configuredNames) {
        return Map.of("HOME", home, InstallerLayoutFeedback.ENVIRONMENT_NAME, configuredNames);
    }

    private static LocalWorkerPaths paths(String configDir, String stateHome) {
        return new LocalWorkerPaths(Path.of("/app"), Path.of(configDir), Path.of("/workspaces"), Path.of(stateHome));
    }

    private static final class RecordingCommands implements CommandRunner {
        private final boolean authenticated;
        private final List<List<String>> commands = new ArrayList<>();

        private RecordingCommands(boolean authenticated) {
            this.authenticated = authenticated;
        }

        @Override
        public CommandResult run(String... command) {
            commands.add(List.of(command));
            if (List.of(command).equals(List.of("gh", "auth", "status"))) {
                return new CommandResult(authenticated ? 0 : 1, "");
            }
            return new CommandResult(0, "https://github.com/martin-francois/symphony-trello/issues/900");
        }
    }
}
