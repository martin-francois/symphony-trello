package ch.fmartin.symphony.trello.setup;

import com.google.common.base.CharMatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class CliInputValidation {
    private static final CharMatcher CONTROL_CHARACTERS =
            CharMatcher.javaIsoControl().precomputed();

    private CliInputValidation() {}

    static void rejectControlCharacters(String optionName, Optional<Path> value) {
        value.ifPresent(path -> rejectControlCharacters(optionName, path));
    }

    static void rejectBlankPath(String optionName, Optional<Path> value) {
        value.ifPresent(path -> rejectBlankPath(optionName, path));
    }

    static void rejectBlankPath(String optionName, Optional<Path> value, String message) {
        value.ifPresent(path -> rejectBlankPath(optionName, path, message));
    }

    static void rejectControlCharactersInText(String optionName, Optional<String> value) {
        value.ifPresent(text -> rejectControlCharacters(optionName, text));
    }

    static void rejectBlankText(String optionName, Optional<String> value) {
        rejectBlankText(optionName, value, optionName + " must not be blank.");
    }

    static void rejectBlankText(String optionName, Optional<String> value, String message) {
        value.map(String::strip).filter(String::isBlank).ifPresent(ignored -> {
            throw new TrelloBoardSetupException("setup_invalid_arguments", message);
        });
    }

    static void rejectControlCharacters(String optionName, String value) {
        if (value == null) {
            return;
        }
        if (CONTROL_CHARACTERS.matchesAnyOf(value)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", optionName + " must not contain control characters.");
        }
    }

    static void rejectControlCharacters(String optionName, Path value) {
        rejectControlCharacters(optionName, value.toString());
    }

    static void rejectBlankPath(String optionName, Path value) {
        rejectBlankPath(optionName, value, optionName + " must be a file path.");
    }

    static void rejectBlankPath(String optionName, Path value, String message) {
        if (value.toString().isBlank()) {
            throw new TrelloBoardSetupException("setup_invalid_arguments", message);
        }
    }

    static void rejectControlCharactersInPaths(String optionName, List<Path> values) {
        values.forEach(path -> rejectControlCharacters(optionName, path));
    }

    static void rejectDashOutputFile(Optional<Path> value) {
        if (value.map(Path::toString).filter("-"::equals).isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", "--output - is not supported. Omit --output to print to stdout.");
        }
    }
}
