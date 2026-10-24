package ch.fmartin.symphony.trello.config;

import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// Parses workflow environment-variable references. Supports the generated `$NAME` form and
/// the common shell-style `${NAME}` form. Values with other shapes, including shell default
/// expansions such as `${NAME:-fallback}`, are not references and stay literal text.
///
/// This classifies a whole value and never interpolates inside text. Used as they are, the
/// SmallRye Config and Commons Text `StringSubstitutor` interpolators change which values count as
/// references, and wrapping them to keep this contract needs more code than this class. See
/// docs/adr/0099-keep-the-environment-reference-classifier.md.
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

    /// Splits a path value at its first `/` and classifies the first segment with
    /// [#referenceName(String)], so path settings and credentials share one reference grammar.
    /// `$NAME`, `${NAME}`, `$NAME/suffix` and `${NAME}/suffix` are references; any other value is
    /// a literal path.
    public static PathValue pathValue(String value) {
        int separator = value.indexOf('/');
        String firstSegment = separator < 0 ? value : value.substring(0, separator);
        String suffix = separator < 0 ? "" : value.substring(separator);
        return referenceName(firstSegment)
                .<PathValue>map(name -> new PathReference(name, suffix))
                .orElseGet(() -> new LiteralPath(value));
    }

    /// A path setting value, either written as a literal path or starting with an environment
    /// reference.
    public sealed interface PathValue {
        /// Returns the path with a leading reference replaced through `lookup`, or empty when
        /// `lookup` has no value for the referenced variable.
        Optional<String> resolve(Function<String, Optional<String>> lookup);
    }

    /// A path value without a leading reference, which resolves to itself.
    public record LiteralPath(String value) implements PathValue {
        @Override
        public Optional<String> resolve(Function<String, Optional<String>> lookup) {
            return Optional.of(value);
        }
    }

    /// A path value made of the environment variable `name` followed by `suffix`, which is empty or
    /// starts with `/`.
    public record PathReference(String name, String suffix) implements PathValue {
        @Override
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
