package ch.fmartin.symphony.trello.repository;

import static com.google.common.base.Preconditions.checkArgument;

public record RepositorySourceSelection(Status status, RepositorySource source, RepositorySourceProblem problem) {
    public RepositorySourceSelection {
        checkArgument(status != null, "Repository source selection status is required");
        checkArgument(status != Status.SELECTED || source != null, "Selected repository source is required");
        checkArgument(
                status != Status.INVALID_SELECTED || problem != null, "Invalid repository source problem is required");
    }

    public static RepositorySourceSelection selected(RepositorySource source) {
        return new RepositorySourceSelection(Status.SELECTED, source, null);
    }

    public static RepositorySourceSelection invalid(RepositorySourceProblem problem) {
        return new RepositorySourceSelection(Status.INVALID_SELECTED, null, problem);
    }

    public static RepositorySourceSelection none() {
        return new RepositorySourceSelection(Status.NONE, null, null);
    }

    public enum Status {
        SELECTED,
        INVALID_SELECTED,
        NONE
    }
}
