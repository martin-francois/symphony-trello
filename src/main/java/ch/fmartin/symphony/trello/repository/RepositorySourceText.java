package ch.fmartin.symphony.trello.repository;

import static com.google.common.base.Preconditions.checkArgument;

final class RepositorySourceText {
    private RepositorySourceText() {}

    static void requirePromptLine(String value, String name) {
        checkArgument(safePromptLine(value), "%s must fit on one prompt line", name);
    }

    static boolean safePromptLine(String value) {
        return value != null && value.chars().noneMatch(RepositorySourceText::unsafePromptLineCharacter);
    }

    static boolean unsafePromptLineCharacter(int character) {
        return character <= 0x1F || character == 0x7F;
    }
}
