package ch.fmartin.symphony.trello.config;

import java.util.Optional;

/**
 * Parses workflow environment-variable references. Supports the generated {@code $NAME} form and
 * the common shell-style {@code ${NAME}} form. Values with other shapes, including shell default
 * expansions such as {@code ${NAME:-fallback}}, are not references and stay literal text.
 */
public final class EnvironmentReferences {
    private EnvironmentReferences() {}

    public static Optional<String> referenceName(String value) {
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
