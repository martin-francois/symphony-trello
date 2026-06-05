package ch.fmartin.symphony.trello.setup;

import com.google.common.base.CharMatcher;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class CliInputValidation {
    private static final CharMatcher CONTROL_CHARACTERS = CharMatcher.javaIsoControl().precomputed();

    private CliInputValidation() {}

    static void rejectControlCharacters(String optionName, Optional<Path> value) {
        value.ifPresent(path -> rejectControlCharacters(optionName, path));
    }

    static void rejectControlCharacters(String optionName, String value) {
        if (CONTROL_CHARACTERS.matchesAnyOf(value)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments", optionName + " must not contain control characters.");
        }
    }

    static void rejectControlCharacters(String optionName, Path value) {
        rejectControlCharacters(optionName, value.toString());
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
