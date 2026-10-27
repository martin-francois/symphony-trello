package ch.fmartin.symphony.trello.tracker;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Formats and parses the readable attribution footer that ends every Trello comment Symphony writes.
///
/// The footer is the last Markdown paragraph of the comment: `_Written by Symphony_`, or
/// the attribution followed by a middle dot and `<detail>` inside the emphasis when a comment family
/// needs extra visible identity such as the stale-blocker link. It tells a board user who wrote the
/// comment. It does not grant ownership:
/// anyone can type the same text, so every update or delete path must still prove that a comment
/// belongs to its own family before it changes it.
public final class SymphonyCommentFooter {
    /// Attribution emitted by new writes.
    public static final String ATTRIBUTION = "Written by Symphony";
    /// Exact attribution accepted when reading existing content.
    public static final String PREVIOUS_ATTRIBUTION = "Managed by Symphony";
    private static final char MIDDLE_DOT = '\u00B7';
    private static final String EMPHASIS = "_";
    /// Delimiter between attribution and its visible detail.
    public static final String DETAIL_SEPARATOR = " " + MIDDLE_DOT + " ";
    private static final String PARAGRAPH_BREAK = "\n\n";
    private static final String PLAIN_LINE = EMPHASIS + ATTRIBUTION + EMPHASIS;
    private static final String DETAIL_LINE_PREFIX = EMPHASIS + ATTRIBUTION + DETAIL_SEPARATOR;
    private static final String PREVIOUS_LINE_PREFIX = EMPHASIS + PREVIOUS_ATTRIBUTION;

    private SymphonyCommentFooter() {}

    /// Returns `body` followed by the plain footer paragraph.
    ///
    /// @param body comment content before attribution
    /// @return content with one appended plain footer paragraph
    public static String append(String body) {
        return withFooterLine(body, PLAIN_LINE);
    }

    /// Returns `body` followed by a footer paragraph that carries family-specific visible detail.
    ///
    /// @param body comment content before attribution
    /// @param detail single-line identity required by the comment family
    /// @return content with one appended detailed footer paragraph
    public static String append(String body, String detail) {
        checkArgument(validDetail(detail), "Footer detail must be one non-blank line without emphasis edges");
        return withFooterLine(body, DETAIL_LINE_PREFIX + detail + EMPHASIS);
    }

    /// Parses the current or previous footer at the very end of `text`. A footer that is not the last
    /// paragraph, shares a paragraph with other text, or differs in spelling is not a footer.
    ///
    /// @param text complete comment text, or `null` when absent
    /// @return the parsed body and detail, or empty when no supported trailing footer exists
    public static Optional<Footer> parse(@Nullable String text) {
        if (text == null) {
            return Optional.empty();
        }
        int lineStart = text.lastIndexOf('\n') + 1;
        String line = text.substring(lineStart);
        if (line.startsWith(PREVIOUS_LINE_PREFIX)) {
            line = EMPHASIS + ATTRIBUTION + line.substring(PREVIOUS_LINE_PREFIX.length());
        }
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

    /// Recognizes reserved attribution prefixes before accepting caller-supplied description text.
    ///
    /// @param text description line to inspect
    /// @return whether the line starts with a reserved attribution prefix
    public static boolean hasAttributionPrefix(String text) {
        return text.startsWith(EMPHASIS + ATTRIBUTION) || text.startsWith(PREVIOUS_LINE_PREFIX);
    }

    /// Removes every canonical footer from the end of `text`, for example when an agent echoes an
    /// earlier comment back into a tool call. Text without a trailing footer is returned unchanged.
    ///
    /// @param text comment text that may end with repeated footer paragraphs
    /// @return text with its supported trailing footer paragraphs removed
    public static String withoutTrailingFooters(String text) {
        String body = text;
        // Tool arguments can echo many footers; iteration keeps their count off the call stack.
        while (true) {
            String stripped = parse(body).map(Footer::body).orElse(body);
            if (Objects.equals(stripped, body)) {
                return body;
            }
            body = stripped;
        }
    }

    /// Tells whether `text` holds nothing but whitespace and canonical footers, which no tool may
    /// post because the comment would say nothing besides who wrote it.
    ///
    /// @param text caller-supplied comment or workpad text
    /// @return whether removing surrounding whitespace and trailing footers leaves no body
    public static boolean isFooterOnly(String text) {
        return withoutTrailingFooters(text.strip()).isBlank();
    }

    /// Compares two comment texts as their owning path sees them: footers at the end do not count,
    /// so a legacy comment without a footer equals the same content with one.
    ///
    /// @param left desired comment text
    /// @param right existing comment text, or `null` when absent
    /// @return whether both bodies match after removing supported trailing footers
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

    /// A parsed attribution footer.
    ///
    /// @param body comment text before the footer paragraph, without trailing whitespace
    /// @param detail family-specific text after the separator, or `null` for the plain footer
    public record Footer(String body, @Nullable String detail) {}
}
