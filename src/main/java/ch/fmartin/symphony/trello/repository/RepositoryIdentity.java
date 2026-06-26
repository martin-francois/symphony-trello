package ch.fmartin.symphony.trello.repository;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Locale;

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
        String stripped = value.strip();
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static String stripGitSuffix(String value) {
        return value.endsWith(".git") ? value.substring(0, value.length() - ".git".length()) : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
