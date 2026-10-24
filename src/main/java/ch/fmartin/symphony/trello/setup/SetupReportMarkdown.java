package ch.fmartin.symphony.trello.setup;

/// Markdown building blocks shared by the troubleshooting report and the setup-failure issue draft,
/// so both render fields and fences the same way.
final class SetupReportMarkdown {
    private SetupReportMarkdown() {}

    static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.lines().findFirst().orElse("").trim();
    }

    /// Returns a backtick fence longer than any backtick run in `content`, so the content cannot
    /// close the fence early.
    static String markdownFence(String content) {
        int longestRun = 0;
        int currentRun = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '`') {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        return "`".repeat(Math.max(3, longestRun + 1));
    }

    static void line(StringBuilder body, String label, Object value) {
        body.append("- **").append(label).append(":** ").append(value).append('\n');
    }
}
