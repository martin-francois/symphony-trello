package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.CommaSeparatedValues.preservingEmptyFields;

import java.nio.file.Path;
import java.util.List;

final class CliValueNormalizer {
    private CliValueNormalizer() {}

    static List<String> nonBlankTrimmed(List<String> values) {
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    static List<String> nonBlank(List<String> values) {
        return values.stream().filter(value -> !value.isBlank()).toList();
    }

    static List<String> commaSeparatedValues(String value) {
        return preservingEmptyFields(value);
    }

    static List<Path> commaSeparatedPaths(String value) {
        return commaSeparatedValues(value).stream().map(Path::of).toList();
    }

    static List<Path> nonBlankTrimmedPaths(List<Path> values) {
        return values.stream()
                .map(Path::toString)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .toList();
    }

    static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
