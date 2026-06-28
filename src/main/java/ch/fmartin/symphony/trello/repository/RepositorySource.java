package ch.fmartin.symphony.trello.repository;

import static com.google.common.base.Preconditions.checkArgument;

import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record RepositorySource(
        Kind kind, Origin origin, String value, @Nullable RepositoryIdentity identity, @Nullable Path path) {
    public RepositorySource {
        checkArgument(kind != null, "Repository source kind is required");
        checkArgument(origin != null, "Repository source origin is required");
        checkArgument(value != null && !value.isBlank(), "Repository source value is required");
        RepositorySourceText.requirePromptLine(value, "Repository source value");
        if (path != null) {
            RepositorySourceText.requirePromptLine(path.toString(), "Repository source path");
        }
        checkArgument(kind != Kind.REMOTE || identity != null, "Remote repository sources require an identity");
        checkArgument(kind != Kind.LOCAL_PATH || path != null, "Local repository sources require a path");
    }

    public enum Kind {
        REMOTE,
        LOCAL_PATH
    }

    public enum Origin {
        CARD,
        WORKFLOW_DEFAULT_URL,
        WORKFLOW_DEFAULT_PATH
    }

    public boolean explicitCardSource() {
        return origin == Origin.CARD;
    }
}
