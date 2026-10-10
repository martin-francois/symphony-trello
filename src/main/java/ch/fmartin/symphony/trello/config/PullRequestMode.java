package ch.fmartin.symphony.trello.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

/// How a GitHub-backed generated workflow hands off repository-changing work. The workflow value is
/// also the value exposed to prompt templates, so both sides use the same spelling.
@NullMarked
public enum PullRequestMode {
    /// Push the task branch and create or update a pull request before `Human Review`.
    CREATE("create"),
    /// Push the task branch and stop before creating a pull request.
    BRANCH_ONLY("branch_only");

    private final String workflowValue;

    PullRequestMode(String workflowValue) {
        this.workflowValue = workflowValue;
    }

    public String workflowValue() {
        return workflowValue;
    }

    public static Optional<PullRequestMode> fromWorkflowValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> mode.workflowValue.equals(normalized))
                .findAny();
    }

    /// The accepted workflow values joined for error messages, for example `create or branch_only`.
    public static String workflowValueChoices() {
        return Arrays.stream(values()).map(PullRequestMode::workflowValue).collect(Collectors.joining(" or "));
    }
}
