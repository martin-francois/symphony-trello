package ch.fmartin.symphony.trello.repository;

import static com.google.common.base.Preconditions.checkArgument;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record RepositorySourceProblem(String code, String guidance) {
    public RepositorySourceProblem {
        checkArgument(code != null && !code.isBlank(), "Repository source problem code is required");
        checkArgument(guidance != null && !guidance.isBlank(), "Repository source problem guidance is required");
    }
}
