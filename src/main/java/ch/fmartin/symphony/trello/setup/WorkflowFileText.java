package ch.fmartin.symphony.trello.setup;

import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// The raw text of a workflow file split at the closing front matter line, with every byte kept.
/// The split follows the runtime loader: the first line must be `---`, and the next line that is
/// `---` after trimming closes the metadata. `metadata` ends with that closing line and its line
/// break; `body` is everything after it.
@NullMarked
record WorkflowFileText(String content, String metadata, String body) {
    private static final String FRONT_MATTER_DELIMITER = "---";
    private static final char CARRIAGE_RETURN = '\r';
    private static final char LINE_FEED = '\n';
    private static final String CRLF = "\r\n";
    private static final String LF = "\n";

    /// Returns empty when the file has no complete front matter or uses line breaks that the
    /// runtime loader and this parser would split differently, such as a lone carriage return.
    static Optional<WorkflowFileText> parse(String content) {
        if (hasLoneCarriageReturn(content)) {
            return Optional.empty();
        }
        int lineStart = 0;
        boolean opened = false;
        while (lineStart < content.length()) {
            int lineFeed = content.indexOf(LINE_FEED, lineStart);
            int nextLineStart = lineFeed < 0 ? content.length() : lineFeed + 1;
            boolean delimiter = FRONT_MATTER_DELIMITER.equals(
                    content.substring(lineStart, nextLineStart).trim());
            if (!opened && !delimiter) {
                return Optional.empty();
            }
            if (opened && delimiter) {
                return Optional.of(new WorkflowFileText(
                        content, content.substring(0, nextLineStart), content.substring(nextLineStart)));
            }
            opened = true;
            lineStart = nextLineStart;
        }
        return Optional.empty();
    }

    /// Body with every CRLF line break turned into LF, so the line-ending style alone never counts
    /// as a customization.
    String normalizedBody() {
        return normalize(body);
    }

    static String normalize(String text) {
        return text.replace(CRLF, LF);
    }

    /// Maps an offset in [#normalizedBody()] back to the same position in [#body()].
    int rawBodyOffset(int normalizedOffset) {
        int raw = 0;
        for (int normalized = 0; normalized < normalizedOffset; normalized++) {
            raw += body.startsWith(CRLF, raw) ? CRLF.length() : 1;
        }
        return raw;
    }

    /// The same file with the metadata kept byte for byte, `prefix` and `suffix` kept as raw body
    /// text, and the LF `generatedBody` written in the body's existing line-ending style.
    String withBody(String prefix, String generatedBody, String suffix) {
        return metadata + prefix + lineEnding().apply(generatedBody) + suffix;
    }

    /// The body's line-ending style, or the whole file's when the body has no line break.
    LineEnding lineEnding() {
        return body.indexOf(LINE_FEED) >= 0 ? LineEnding.of(body) : LineEnding.of(content);
    }

    private static boolean hasLoneCarriageReturn(String content) {
        for (int index = content.indexOf(CARRIAGE_RETURN);
                index >= 0;
                index = content.indexOf(CARRIAGE_RETURN, index + 1)) {
            if (index + 1 >= content.length() || content.charAt(index + 1) != LINE_FEED) {
                return true;
            }
        }
        return false;
    }

    /// Line-ending style for a replacement body. Text that mixes both styles gets the generated LF
    /// text, because no single style would match it.
    enum LineEnding {
        LF_ONLY,
        CRLF_ONLY,
        MIXED;

        static LineEnding of(String text) {
            int lineFeeds = count(text, LF);
            int crlfLineBreaks = count(text, CRLF);
            if (crlfLineBreaks == 0) {
                return LF_ONLY;
            }
            return crlfLineBreaks == lineFeeds ? CRLF_ONLY : MIXED;
        }

        String apply(String lfText) {
            return this == CRLF_ONLY ? lfText.replace(LF, CRLF) : lfText;
        }

        private static int count(String text, String lineBreak) {
            int count = 0;
            for (int index = text.indexOf(lineBreak); index >= 0; index = text.indexOf(lineBreak, index + 1)) {
                count++;
            }
            return count;
        }
    }
}
