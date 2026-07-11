package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.repository.RepositorySourceResolver;
import ch.fmartin.symphony.trello.repository.RepositorySourceSelection;
import java.util.Optional;

final class RepositoryUrlInput {
    static final String PROMPT =
            "If this board is for one Git repository, enter its clone URL; press Enter for a general-purpose board:";
    private static final String GUIDANCE =
            "--repository-url must be a credential-free HTTP(S), username-only SSH, SCP-style SSH, or file URL without a query or fragment.";
    private static final RepositorySourceResolver RESOLVER = new RepositorySourceResolver();

    private RepositoryUrlInput() {}

    static Optional<String> validateExplicit(Optional<String> value) {
        return value.map(RepositoryUrlInput::validate);
    }

    static Optional<String> fromPrompt(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(validate(value));
    }

    private static String validate(String rawValue) {
        RepositorySourceSelection selection = RESOLVER.selectWorkflowDefaultUrl(rawValue);
        if (selection.status() != RepositorySourceSelection.Status.SELECTED) {
            throw new TrelloBoardSetupException("setup_invalid_repository_url", GUIDANCE);
        }
        return rawValue.strip();
    }
}
