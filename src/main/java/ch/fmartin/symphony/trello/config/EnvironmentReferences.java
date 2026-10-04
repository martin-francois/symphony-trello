package ch.fmartin.symphony.trello.config;

import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// Parses workflow environment-variable references. Supports the generated `$NAME` form and
/// the common shell-style `${NAME}` form. Values with other shapes, including shell default
/// expansions such as `${NAME:-fallback}`, are not references and stay literal text.
public final class EnvironmentReferences {
    private EnvironmentReferences() {}

    public static Optional<String> referenceName(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("$") || trimmed.length() <= 1) {
            return Optional.empty();
        }
        String name = trimmed.substring(1);
        if (name.startsWith("{")) {
            if (!name.endsWith("}") || name.length() <= 2) {
                return Optional.empty();
            }
            name = name.substring(1, name.length() - 1);
        }
        return isValidName(name) ? Optional.of(name) : Optional.empty();
    }

    /// Classifies a path value whose first segment is a reference: `$NAME`, `${NAME}`,
    /// `$NAME/suffix` or `${NAME}/suffix`. The first segment ends at the first `/`, so the
    /// suffix is empty or starts with `/`. Path settings use the same reference grammar as
    /// [#referenceName(String)].
    public static Optional<PathReference> pathReference(String value) {
        int separator = value.indexOf('/');
        String firstSegment = separator < 0 ? value : value.substring(0, separator);
        String suffix = separator < 0 ? "" : value.substring(separator);
        return referenceName(firstSegment).map(name -> new PathReference(name, suffix));
    }

    /// A path value made of the environment variable `name` followed by `suffix`.
    public record PathReference(String name, String suffix) {
        /// Returns the variable's value from `lookup` with the suffix appended, or empty when
        /// `lookup` has no value for the variable.
        public Optional<String> resolve(Function<String, Optional<String>> lookup) {
            return lookup.apply(name).map(value -> value + suffix);
        }
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty() || (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_')) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            char current = name.charAt(index);
            if (!Character.isLetterOrDigit(current) && current != '_') {
                return false;
            }
        }
        return true;
    }
}
