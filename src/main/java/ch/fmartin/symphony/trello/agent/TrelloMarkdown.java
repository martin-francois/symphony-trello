package ch.fmartin.symphony.trello.agent;

final class TrelloMarkdown {
    private TrelloMarkdown() {}

    static String escapeLeadingHashtags(String markdown) {
        StringBuilder escaped = new StringBuilder(markdown.length());
        int lineStart = 0;
        Fence fence = Fence.none();
        int listContentIndent = -1;
        while (lineStart < markdown.length()) {
            int contentEnd = lineEnd(markdown, lineStart);
            String line = markdown.substring(lineStart, contentEnd);
            Fence lineFence = fenceMarker(line, listContentIndent);
            if (fence.active() || lineFence.active()) {
                boolean insideFence = fence.active();
                escaped.append(line);
                fence = fence.next(lineFence);
                if (!insideFence || lineFence.closingMarker()) {
                    listContentIndent = nextListContentIndent(line, listContentIndent);
                }
            } else {
                escaped.append(escapeLine(line, listContentIndent));
                listContentIndent = nextListContentIndent(line, listContentIndent);
            }
            if (contentEnd < markdown.length()) {
                int lineBreakEnd = lineBreakEnd(markdown, contentEnd);
                escaped.append(markdown, contentEnd, lineBreakEnd);
                lineStart = lineBreakEnd;
            } else {
                lineStart = contentEnd;
            }
        }
        return escaped.toString();
    }

    private static Fence fenceMarker(String line, int listContentIndent) {
        int firstVisible = 0;
        while (firstVisible < line.length() && line.charAt(firstVisible) == ' ') {
            firstVisible++;
        }
        int maximumFenceIndent = listContentIndent >= 0 ? listContentIndent + 3 : 3;
        if (firstVisible > maximumFenceIndent) {
            return Fence.none();
        }
        if (firstVisible >= line.length()) {
            return Fence.none();
        }
        char marker = line.charAt(firstVisible);
        if (marker != '`' && marker != '~') {
            return Fence.none();
        }
        int markerEnd = firstVisible;
        while (markerEnd < line.length() && line.charAt(markerEnd) == marker) {
            markerEnd++;
        }
        int length = markerEnd - firstVisible;
        if (length < 3) {
            return Fence.none();
        }
        return new Fence(marker, length, line.substring(markerEnd).isBlank());
    }

    private static int lineEnd(String markdown, int lineStart) {
        int lineEnd = lineStart;
        while (lineEnd < markdown.length()) {
            char current = markdown.charAt(lineEnd);
            if (current == '\n' || current == '\r') {
                return lineEnd;
            }
            lineEnd++;
        }
        return lineEnd;
    }

    private static int lineBreakEnd(String markdown, int lineEnd) {
        if (markdown.charAt(lineEnd) == '\r'
                && lineEnd + 1 < markdown.length()
                && markdown.charAt(lineEnd + 1) == '\n') {
            return lineEnd + 2;
        }
        return lineEnd + 1;
    }

    private static String escapeLine(String line, int listContentIndent) {
        if (line.startsWith("\t")) {
            return line;
        }
        int firstVisible = skipWhitespace(line, 0);
        if (firstVisible >= line.length()) {
            return line;
        }
        boolean inListContext = listContentIndent >= 0;
        boolean indentedCodeBlock = startsIndentedCodeBlock(line, listContentIndent);
        int bulletTextStart = unorderedBulletTextStart(line, firstVisible);
        if (bulletTextStart >= 0
                && (!indentedCodeBlock || inListContext)
                && startsIssueReference(line, bulletTextStart)) {
            return line.substring(0, bulletTextStart) + '\\' + line.substring(bulletTextStart);
        }
        if (inListContext
                && firstVisible >= listContentIndent
                && firstVisible < listContentIndent + 4
                && startsIssueReference(line, firstVisible)) {
            return line.substring(0, firstVisible) + '\\' + line.substring(firstVisible);
        }
        if (indentedCodeBlock) {
            return line;
        }
        if (startsIssueReference(line, firstVisible)) {
            return line.substring(0, firstVisible) + '\\' + line.substring(firstVisible);
        }
        return line;
    }

    private static int nextListContentIndent(String line, int listContentIndent) {
        if (line.isBlank()) {
            return listContentIndent;
        }
        if (line.startsWith("\t")) {
            return -1;
        }
        int firstVisible = skipWhitespace(line, 0);
        boolean inListContext = listContentIndent >= 0;
        boolean listItem = firstVisible < line.length()
                && unorderedBulletTextStart(line, firstVisible) >= 0
                && (!startsIndentedCodeBlock(line, listContentIndent) || inListContext);
        if (listItem) {
            return unorderedBulletTextStart(line, firstVisible);
        }
        if (inListContext && firstVisible >= listContentIndent) {
            return listContentIndent;
        }
        return -1;
    }

    private static boolean startsIndentedCodeBlock(String line, int listContentIndent) {
        if (line.startsWith("\t")) {
            return true;
        }
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        int codeIndent = listContentIndent >= 0 ? listContentIndent + 4 : 4;
        return spaces >= codeIndent;
    }

    private static boolean startsIssueReference(String line, int index) {
        return index + 1 < line.length() && line.charAt(index) == '#' && Character.isDigit(line.charAt(index + 1));
    }

    private static int unorderedBulletTextStart(String line, int markerStart) {
        char marker = line.charAt(markerStart);
        if (marker != '-' && marker != '*' && marker != '+') {
            return -1;
        }
        int afterMarker = markerStart + 1;
        if (afterMarker >= line.length() || !Character.isWhitespace(line.charAt(afterMarker))) {
            return -1;
        }
        return skipWhitespace(line, afterMarker);
    }

    private static int skipWhitespace(String line, int start) {
        int index = start;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return index;
    }

    private record Fence(char marker, int length, boolean closingMarker) {
        private static Fence none() {
            return new Fence('\0', 0, false);
        }

        private boolean active() {
            return length > 0;
        }

        private Fence next(Fence lineFence) {
            if (!active()) {
                return lineFence;
            }
            if (lineFence.marker == marker && lineFence.length >= length && lineFence.closingMarker) {
                return none();
            }
            return this;
        }
    }
}
