package ch.fmartin.symphony.trello.agent;

import java.time.Instant;

public final class CodexUsageWorkpadSection {
    private static final int MAX_MESSAGE_CODE_POINTS = 500;
    static final String START_MARKER = "<!-- symphony-trello:codex-usage:start -->";
    static final String END_MARKER = "<!-- symphony-trello:codex-usage:end -->";

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
        return workpad != null && (workpad.contains(START_MARKER) || workpad.contains(END_MARKER));
    }

    static String upsert(String workpad, String section) {
        if (hasMalformedManagedSectionMarkers(workpad)) {
            return workpad;
        }
        String withoutManagedSection = remove(workpad).stripTrailing();
        return withoutManagedSection + "\n\n" + section;
    }

    static String remove(String workpad) {
        if (hasMalformedManagedSectionMarkers(workpad)) {
            return workpad;
        }
        String result = workpad;
        int start = result.indexOf(START_MARKER);
        while (start >= 0) {
            int end = result.indexOf(END_MARKER, start + START_MARKER.length());
            int after = end + END_MARKER.length();
            result = (result.substring(0, start).stripTrailing() + "\n\n"
                            + result.substring(after).stripLeading())
                    .stripTrailing();
            start = result.indexOf(START_MARKER);
        }
        return result;
    }

    static String managedSection(String workpad) {
        if (workpad == null || hasMalformedManagedSectionMarkers(workpad)) {
            return null;
        }
        int start = workpad.indexOf(START_MARKER);
        if (start < 0) {
            return null;
        }
        int end = workpad.indexOf(END_MARKER, start + START_MARKER.length());
        return workpad.substring(start, end + END_MARKER.length());
    }

    static boolean hasMalformedManagedSectionMarkers(String workpad) {
        if (workpad == null) {
            return false;
        }
        int cursor = 0;
        int sections = 0;
        while (cursor < workpad.length()) {
            int start = workpad.indexOf(START_MARKER, cursor);
            int unmatchedEnd = workpad.indexOf(END_MARKER, cursor);
            if (start < 0) {
                return unmatchedEnd >= 0;
            }
            if (unmatchedEnd >= 0 && unmatchedEnd < start) {
                return true;
            }
            int end = workpad.indexOf(END_MARKER, start + START_MARKER.length());
            if (end < 0) {
                return true;
            }
            int nestedStart = workpad.indexOf(START_MARKER, start + START_MARKER.length());
            if (nestedStart >= 0 && nestedStart < end) {
                return true;
            }
            sections++;
            if (sections > 1) {
                return true;
            }
            cursor = end + END_MARKER.length();
        }
        return false;
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
        StringBuilder normalized = new StringBuilder(value.length());
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
        StringBuilder escaped = new StringBuilder(Math.min(value.length(), MAX_MESSAGE_CODE_POINTS));
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
