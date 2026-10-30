package ch.fmartin.symphony.trello.tracker;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/// Text rules for cards that Symphony creates. Follow-up cards from the worker and cards from
/// `symphony-trello card create` pass the same checks, so neither path can write a title or
/// description that the other refuses. A failed check throws [IllegalArgumentException] with a
/// message that names the field.
@NullMarked
public final class TrelloCardText {
    public static final int MAX_TITLE_CODE_POINTS = 200;
    public static final int MAX_DESCRIPTION_CODE_POINTS = 4000;

    private TrelloCardText() {}

    /// Returns `value` stripped, after checking that it is one non-blank line of at most
    /// `maxCodePoints` code points.
    public static String singleLine(String value, String name, int maxCodePoints) {
        String stripped = Objects.requireNonNull(value).strip();
        checkArgument(!stripped.isEmpty(), "%s must not be blank", name);
        checkArgument(stripped.codePoints().noneMatch(TrelloCardText::lineBreakOrControl), "%s must be one line", name);
        checkArgument(
                stripped.codePointCount(0, stripped.length()) <= maxCodePoints,
                "%s must be at most %s characters",
                name,
                maxCodePoints);
        return stripped;
    }

    /// Returns the description with CRLF line breaks turned into LF. A description must not carry
    /// the Symphony attribution footer, because Symphony reads that footer as card metadata.
    public static String description(String value) {
        String description = value.replace("\r\n", "\n");
        checkArgument(
                description.codePointCount(0, description.length()) <= MAX_DESCRIPTION_CODE_POINTS,
                "description must be at most %s characters",
                MAX_DESCRIPTION_CODE_POINTS);
        checkArgument(
                description.codePoints().noneMatch(TrelloCardText::controlOtherThanNewlineOrTab),
                "description must not contain control characters");
        checkArgument(
                description.lines().map(String::strip).noneMatch(SymphonyCommentFooter::hasAttributionPrefix),
                "cannot accept description: omit the attribution footer; Symphony writes it");
        return description;
    }

    private static boolean controlOtherThanNewlineOrTab(int codePoint) {
        return codePoint != '\n' && codePoint != '\t' && lineBreakOrControl(codePoint);
    }

    public static boolean lineBreakOrControl(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
