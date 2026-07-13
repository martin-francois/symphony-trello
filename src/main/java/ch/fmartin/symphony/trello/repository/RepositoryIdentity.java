package ch.fmartin.symphony.trello.repository;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.SLASHES;
import static com.google.common.base.Preconditions.checkArgument;

import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record RepositoryIdentity(String host, String repositoryPath) {

    public RepositoryIdentity {
        checkArgument(!blank(host), "Repository host is required");
        checkArgument(!blank(repositoryPath), "Repository path is required");
        host = host.toLowerCase(Locale.ROOT);
        repositoryPath = stripGitSuffix(stripSlashes(repositoryPath));
        RepositorySourceText.requirePromptLine(host, "Repository host");
        RepositorySourceText.requirePromptLine(repositoryPath, "Repository path");
        checkArgument(
                !blank(repositoryPath) && !repositoryPath.contains("\\") && !repositoryPath.contains("//"),
                "Repository path is invalid");
    }

    public String key() {
        return host + "/" + repositoryPath;
    }

    private static String stripSlashes(String value) {
        return SLASHES.trimFrom(value.strip());
    }

    private static String stripGitSuffix(String value) {
        return value.endsWith(".git") ? value.substring(0, value.length() - ".git".length()) : value;
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
