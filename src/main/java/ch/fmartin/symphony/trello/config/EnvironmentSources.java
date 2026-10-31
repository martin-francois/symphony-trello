package ch.fmartin.symphony.trello.config;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/// The ordered sources a workflow's environment references resolve from: the first source that
/// sets a non-blank value wins. Local runs use the shell environment first and one env file second,
/// so a real environment variable takes precedence over the file. The result keeps the winning
/// source, because a Trello key and token belong to the endpoint that source names.
public final class EnvironmentSources implements Function<String, Optional<String>> {
    private final List<EnvironmentSource> sources;

    private EnvironmentSources(List<EnvironmentSource> sources) {
        this.sources = sources;
    }

    public static EnvironmentSources of(EnvironmentSource... sources) {
        return new EnvironmentSources(List.of(sources));
    }

    public Optional<EnvironmentValue> lookup(String name) {
        return sources.stream().flatMap(source -> source.lookup(name).stream()).findFirst();
    }

    @Override
    public Optional<String> apply(String name) {
        return lookup(name).map(EnvironmentValue::value);
    }
}
