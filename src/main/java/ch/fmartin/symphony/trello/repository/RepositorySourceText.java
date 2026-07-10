package ch.fmartin.symphony.trello.repository;

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
        return value != null && value.codePoints().noneMatch(RepositorySourceText::unsafePromptLineCharacter);
    }

    static boolean unsafePromptLineCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
