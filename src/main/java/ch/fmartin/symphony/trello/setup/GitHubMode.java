package ch.fmartin.symphony.trello.setup;

import java.util.Optional;
import picocli.CommandLine.Option;

final class GitHubMode {
    @Option(names = "--github", description = "Enable GitHub pull-request workflow pieces.")
    private boolean github;

    @Option(names = "--no-github", description = "Use a Trello-only workflow without pull requests.")
    private boolean noGithub;

    Optional<Boolean> selected() {
        validate();
        if (github) {
            return Optional.of(true);
        }
        if (noGithub) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    void validate() {
        if (github && noGithub) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--github and --no-github cannot be used together.");
        }
    }
}
