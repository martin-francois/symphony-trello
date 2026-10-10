package ch.fmartin.symphony.trello.tracker;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Formats and parses the readable attribution footer that ends every Trello comment Symphony writes.
///
/// The footer is the last Markdown paragraph of the comment: `_Managed by Symphony_`, or
/// `_Managed by Symphony · <detail>_` when a comment family needs extra visible identity such as the
/// stale-blocker link. It tells a board user who wrote the comment. It does not grant ownership:
/// anyone can type the same text, so every update or delete path must still prove that a comment
/// belongs to its own family before it changes it.
public final class SymphonyCommentFooter {
    public static final String ATTRIBUTION = "Managed by Symphony";
    private static final char MIDDLE_DOT = '\u00B7';
    private static final String EMPHASIS = "_";
    private static final String DETAIL_SEPARATOR = " " + MIDDLE_DOT + " ";
    private static final String PARAGRAPH_BREAK = "\n\n";
    private static final String PLAIN_LINE = EMPHASIS + ATTRIBUTION + EMPHASIS;
    private static final String DETAIL_LINE_PREFIX = EMPHASIS + ATTRIBUTION + DETAIL_SEPARATOR;

    private SymphonyCommentFooter() {}

    /// Returns `body` followed by the plain footer paragraph.
    public static String append(String body) {
        return withFooterLine(body, PLAIN_LINE);
    }

    /// Returns `body` followed by a footer paragraph that carries family-specific visible detail.
    public static String append(String body, String detail) {
        checkArgument(validDetail(detail), "Footer detail must be one non-blank line without emphasis edges");
        return withFooterLine(body, DETAIL_LINE_PREFIX + detail + EMPHASIS);
    }

    /// Parses the canonical footer at the very end of `text`. A footer that is not the last
    /// paragraph, shares a paragraph with other text, or differs in spelling is not a footer.
    public static Optional<Footer> parse(@Nullable String text) {
        if (text == null) {
            return Optional.empty();
        }
        int lineStart = text.lastIndexOf('\n') + 1;
        String line = text.substring(lineStart);
        String detail;
        if (line.equals(PLAIN_LINE)) {
            detail = null;
        } else if (line.startsWith(DETAIL_LINE_PREFIX)
                && line.endsWith(EMPHASIS)
                && line.length() > DETAIL_LINE_PREFIX.length() + EMPHASIS.length()) {
            detail = line.substring(DETAIL_LINE_PREFIX.length(), line.length() - EMPHASIS.length());
            if (!validDetail(detail)) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
        String before = text.substring(0, lineStart);
        if (!startsNewParagraph(before)) {
            return Optional.empty();
        }
        return Optional.of(new Footer(before.stripTrailing(), detail));
    }

    /// Removes every canonical footer from the end of `text`, for example when an agent echoes an
    /// earlier comment back into a tool call. Text without a trailing footer is returned unchanged.
    public static String withoutTrailingFooters(String text) {
        return parse(text).map(footer -> withoutTrailingFooters(footer.body())).orElse(text);
    }

    /// Compares two comment texts as their owning path sees them: footers at the end do not count,
    /// so a legacy comment without a footer equals the same content with one.
    public static boolean sameBody(String left, @Nullable String right) {
        return right != null && withoutTrailingFooters(left).equals(withoutTrailingFooters(right));
    }

    private static String withFooterLine(String body, String footerLine) {
        String content = body.stripTrailing();
        return content.isEmpty() ? footerLine : content + PARAGRAPH_BREAK + footerLine;
    }

    private static boolean validDetail(@Nullable String detail) {
        return detail != null
                && !detail.isBlank()
                && detail.equals(detail.strip())
                && !detail.startsWith(EMPHASIS)
                && !detail.endsWith(EMPHASIS)
                && detail.chars().noneMatch(c -> c == '\n' || c == '\r');
    }

    /// Markdown only renders the footer as its own paragraph when a blank line precedes it.
    private static boolean startsNewParagraph(String before) {
        if (before.isEmpty()) {
            return true;
        }
        String withoutLineBreak = before.substring(0, before.length() - 1);
        int previousLineStart = withoutLineBreak.lastIndexOf('\n') + 1;
        return withoutLineBreak.substring(previousLineStart).isBlank();
    }

    /// A parsed footer. `body` is the comment text before the footer paragraph, without trailing
    /// whitespace. `detail` is the family-specific text after the separator, or `null` for the plain
    /// footer.
    public record Footer(String body, @Nullable String detail) {}
}
