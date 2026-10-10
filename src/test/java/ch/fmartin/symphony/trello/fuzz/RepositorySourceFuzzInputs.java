package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Builds the cards and workflow repository defaults that `RepositorySourceFuzzer` and
/// `RepositorySourceResolverFuzzTest` pass to `RepositorySourceResolver`, so both fuzz the same
/// input shapes. `oss-fuzz/build.sh` copies this class with the standalone fuzzers.
public final class RepositorySourceFuzzInputs {
    public static final int MAX_TEXT_LENGTH = 2_048;
    public static final int MAX_COMMENTS = 2;

    private static final Path WORKFLOW_DIRECTORY = Path.of("/srv/symphony/workflow");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private RepositorySourceFuzzInputs() {}

    public static Card card(String title, @Nullable String description, List<String> commentTexts) {
        List<Card.Comment> comments = new ArrayList<>(commentTexts.size());
        for (String commentText : commentTexts) {
            comments.add(new Card.Comment("comment-" + comments.size(), commentText, "author", NOW));
        }
        return new Card(
                "card-1",
                "TRELLO-1",
                title,
                description,
                null,
                "Ready for Codex",
                "list",
                "list-ready",
                "Ready for Codex",
                false,
                "board-1",
                false,
                false,
                1,
                "abc123",
                "https://trello.com/c/abc123",
                null,
                "https://trello.com/c/abc123/example",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                comments,
                NOW,
                NOW,
                null,
                false,
                null);
    }

    /// Which `repository` default the workflow configures.
    public enum WorkflowDefault {
        NONE,
        URL,
        PATH;

        public EffectiveConfig.RepositoryConfig config(String value) {
            return switch (this) {
                case NONE -> new EffectiveConfig.RepositoryConfig(null, null);
                case URL -> new EffectiveConfig.RepositoryConfig(value, null);
                case PATH -> new EffectiveConfig.RepositoryConfig(null, configuredPath(value));
            };
        }

        /// Resolves an already expanded `repository.default_path` against the workflow directory,
        /// as `ConfigResolver` does after `~` and `$VAR` expansion. `ConfigResolver` rejects a path
        /// the platform cannot represent or that is not a usable default, so such a path never
        /// reaches repository source selection and becomes no default here.
        private static @Nullable Path configuredPath(String value) {
            try {
                Path path = WORKFLOW_DIRECTORY.resolve(value).toAbsolutePath().normalize();
                return EffectiveConfig.RepositoryConfig.usableDefaultPath(path) ? path : null;
            } catch (InvalidPathException e) {
                return null;
            }
        }
    }
}
