package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Arrays;
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
        return Arrays.asList(value.split(",", -1));
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
}
