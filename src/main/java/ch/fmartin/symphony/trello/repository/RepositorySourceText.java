package ch.fmartin.symphony.trello.repository;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNSAFE_SINGLE_LINE_CHARACTERS;
import static com.google.common.base.Preconditions.checkArgument;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class RepositorySourceText {
    private RepositorySourceText() {}

    static void requirePromptLine(String value, String name) {
        checkArgument(safePromptLine(value), "%s must fit on one prompt line", name);
    }

    static boolean safePromptLine(@Nullable String value) {
        return value != null && UNSAFE_SINGLE_LINE_CHARACTERS.matchesNoneOf(value);
    }

    static boolean unsafePromptLine(@Nullable String value) {
        return value != null && UNSAFE_SINGLE_LINE_CHARACTERS.matchesAnyOf(value);
    }
}
