package ch.fmartin.symphony.trello.agent;

final class TrelloMarkdown {
    private TrelloMarkdown() {}

    static String escapeLeadingHashtags(String markdown) {
        var escaped = new StringBuilder(markdown.length());
        var lineStart = 0;
        Fence fence = Fence.none();
        var listContentIndent = -1;
        while (lineStart < markdown.length()) {
            var contentEnd = lineEnd(markdown, lineStart);
            String line = markdown.substring(lineStart, contentEnd);
            Fence lineFence = fenceMarker(line, listContentIndent);
            if (fence.active() || lineFence.active()) {
                var insideFence = fence.active();
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
                var lineBreakEnd = lineBreakEnd(markdown, contentEnd);
                escaped.append(markdown, contentEnd, lineBreakEnd);
                lineStart = lineBreakEnd;
            } else {
                lineStart = contentEnd;
            }
        }
        return escaped.toString();
    }

    private static Fence fenceMarker(String line, int listContentIndent) {
        var firstVisible = 0;
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
        var marker = line.charAt(firstVisible);
        if (marker != '`' && marker != '~') {
            return Fence.none();
        }
        var markerEnd = firstVisible;
        while (markerEnd < line.length() && line.charAt(markerEnd) == marker) {
            markerEnd++;
        }
        var length = markerEnd - firstVisible;
        if (length < 3) {
            return Fence.none();
        }
        return new Fence(marker, length, line.substring(markerEnd).isBlank());
    }

    private static int lineEnd(String markdown, int lineStart) {
        var lineEnd = lineStart;
        while (lineEnd < markdown.length()) {
            var current = markdown.charAt(lineEnd);
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
        var firstVisible = skipWhitespace(line, 0);
        if (firstVisible >= line.length()) {
            return line;
        }
        var inListContext = listContentIndent >= 0;
        var indentedCodeBlock = startsIndentedCodeBlock(line, listContentIndent);
        var bulletTextStart = unorderedBulletTextStart(line, firstVisible);
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
        var firstVisible = skipWhitespace(line, 0);
        var inListContext = listContentIndent >= 0;
        var listItem = firstVisible < line.length()
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
        var spaces = 0;
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
        var marker = line.charAt(markerStart);
        if (marker != '-' && marker != '*' && marker != '+') {
            return -1;
        }
        var afterMarker = markerStart + 1;
        if (afterMarker >= line.length() || !Character.isWhitespace(line.charAt(afterMarker))) {
            return -1;
        }
        return skipWhitespace(line, afterMarker);
    }

    private static int skipWhitespace(String line, int start) {
        var index = start;
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
