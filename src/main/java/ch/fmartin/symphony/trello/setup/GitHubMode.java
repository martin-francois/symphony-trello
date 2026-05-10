package ch.fmartin.symphony.trello.setup;

import java.util.Optional;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Option;

final class GitHubMode {
    @ArgGroup(exclusive = true)
    private Choice choice;

    Optional<Boolean> selected() {
        if (choice == null) {
            return Optional.empty();
        }
        if (choice.github) {
            return Optional.of(true);
        }
        if (choice.noGithub) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private static final class Choice {
        @Option(names = "--github", description = "Enable GitHub pull-request workflow pieces.")
        boolean github;

        @Option(names = "--no-github", description = "Use a Trello-only workflow without pull requests.")
        boolean noGithub;
    }
}
