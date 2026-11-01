package ch.fmartin.symphony.trello.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/// Locations and variable names to exclude from agent access. This record contains no secret values.
@NullMarked
public record CredentialSources(Set<Path> files, Set<String> environmentVariables) {
    public CredentialSources {
        files = Set.copyOf(files);
        environmentVariables = Set.copyOf(environmentVariables);
    }

    public CredentialSources withFiles(Set<Path> additionalFiles) {
        Set<Path> combined = new HashSet<>(files);
        combined.addAll(additionalFiles);
        return new CredentialSources(combined, environmentVariables);
    }
}
