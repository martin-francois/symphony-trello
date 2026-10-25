package ch.fmartin.symphony.trello.agent;

import java.time.Instant;

public final class CodexUsageWorkpadSection {
    private static final int MAX_MESSAGE_CODE_POINTS = 500;
    static final String START_MARKER = "<!-- symphony-trello:codex-usage:start -->";
    static final String END_MARKER = "<!-- symphony-trello:codex-usage:end -->";

    private static final ManagedWorkpadSection SECTION = new ManagedWorkpadSection(START_MARKER, END_MARKER);

    private CodexUsageWorkpadSection() {}

    public static String paused(String message, Instant nextAttempt) {
        return START_MARKER
                + "\n**Codex usage availability**\n\n"
                + "- State: Paused after Codex reported a usage limit.\n"
                + "- Message: "
                + sanitizeMessage(message)
                + "\n- Next attempt: `"
                + nextAttempt
                + "`\n"
                + "- Note: If that time has passed, check Symphony status; this process-local notice may be stale after a restart.\n"
                + END_MARKER;
    }

    public static String rechecking(String message) {
        return START_MARKER
                + "\n**Codex usage availability**\n\n"
                + "- State: Rechecking Codex usage availability.\n"
                + "- Message: "
                + sanitizeMessage(message)
                + "\n"
                + END_MARKER;
    }

    public static boolean containsManagedSection(String workpad) {
        return containsManagedSectionMarker(workpad);
    }

    static boolean containsManagedSectionMarker(String workpad) {
        return SECTION.containsManagedSectionMarker(workpad);
    }

    static String upsert(String workpad, String section) {
        return SECTION.upsert(workpad, section);
    }

    static String remove(String workpad) {
        return SECTION.remove(workpad);
    }

    static String managedSection(String workpad) {
        return SECTION.managedSection(workpad);
    }

    static boolean hasMalformedManagedSectionMarkers(String workpad) {
        return SECTION.hasMalformedManagedSectionMarkers(workpad);
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Codex usage is temporarily unavailable.";
        }
        String oneLine = normalizeSingleLine(message);
        if (oneLine.isEmpty()) {
            return "Codex usage is temporarily unavailable.";
        }
        return escapeMarkdownWithinLimit(oneLine);
    }

    private static String normalizeSingleLine(String value) {
        var normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        var codePoints = value.codePoints().iterator();
        while (codePoints.hasNext()) {
            int codePoint = codePoints.nextInt();
            if (isUnsafeOrWhitespace(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private static boolean isUnsafeOrWhitespace(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }

    private static String escapeMarkdownWithinLimit(String value) {
        long escapedLength = value.codePoints()
                .mapToLong(CodexUsageWorkpadSection::escapedLength)
                .sum();
        boolean truncated = escapedLength > MAX_MESSAGE_CODE_POINTS;
        int budget = truncated ? MAX_MESSAGE_CODE_POINTS - 3 : MAX_MESSAGE_CODE_POINTS;
        int used = 0;
        var escaped = new StringBuilder(Math.min(value.length(), MAX_MESSAGE_CODE_POINTS));
        var codePoints = value.codePoints().iterator();
        while (codePoints.hasNext()) {
            int codePoint = codePoints.nextInt();
            int length = escapedLength(codePoint);
            if (used + length > budget) {
                break;
            }
            if (needsMarkdownEscape(codePoint)) {
                escaped.append('\\');
            }
            escaped.appendCodePoint(codePoint);
            used += length;
        }
        if (truncated) {
            escaped.append("...");
        }
        return escaped.toString();
    }

    private static int escapedLength(int codePoint) {
        return needsMarkdownEscape(codePoint) ? 2 : 1;
    }

    private static boolean needsMarkdownEscape(int codePoint) {
        return switch (codePoint) {
            case '\\', '*', '_', '[', ']', '(', ')', '<', '>', '`', '&' -> true;
            default -> false;
        };
    }
}
