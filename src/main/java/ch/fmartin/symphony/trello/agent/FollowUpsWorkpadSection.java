package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.config.FollowUpRelationship;
import ch.fmartin.symphony.trello.tracker.SymphonyCommentFooter;
import java.util.regex.Pattern;

/// Symphony records completed follow-up writes here, independently of the model's next response.
final class FollowUpsWorkpadSection {
    static final String START_MARKER = "<!-- symphony-trello:follow-ups:start -->";
    static final String END_MARKER = "<!-- symphony-trello:follow-ups:end -->";
    static final ManagedWorkpadSection SECTION = new ManagedWorkpadSection(START_MARKER, END_MARKER);

    private static final String CREATED_PREFIX = "- Created: ";

    private FollowUpsWorkpadSection() {}

    static boolean hasConflictingEntry(String workpad, String url, FollowUpRelationship relationship) {
        String previous = SECTION.managedSection(workpad);
        if (previous == null) {
            return false;
        }
        // The URL identifies the entry even after its link label or Markdown delimiters are edited.
        // A title is free text, and a longer card ID is a different URL.
        Pattern cardUrl = Pattern.compile(Pattern.quote(url) + "(?![A-Za-z0-9])");
        String expectedFooter = footer(url, relationship);
        return previous.lines()
                .filter(line -> !line.startsWith(CREATED_PREFIX))
                .filter(cardUrl.asPredicate())
                .anyMatch(line -> !line.equals(expectedFooter));
    }

    static String add(String workpad, String url, String title, FollowUpRelationship relationship) {
        String previous = SECTION.managedSection(workpad);
        String footer = footer(url, relationship);
        if (previous != null && previous.lines().anyMatch(footer::equals)) {
            return workpad;
        }
        String content = previous == null
                ? START_MARKER + "\n**Follow-ups**\n"
                : previous.substring(0, previous.length() - END_MARKER.length()).stripTrailing();
        return SECTION.upsert(
                workpad, content + "\n\n" + CREATED_PREFIX + escapedTitle(title) + "\n\n" + footer + "\n" + END_MARKER);
    }

    private static String link(String url) {
        return "[the follow-up card](" + url + ")";
    }

    private static String footer(String url, FollowUpRelationship relationship) {
        return SymphonyCommentFooter.append(
                "", "Follow-up " + link(url) + FollowUpCardMetadata.SEPARATOR + relationship.sourceCardNote());
    }

    private static String escapedTitle(String title) {
        var escaped = new StringBuilder();
        for (int index = 0; index < title.length(); index++) {
            char character = title.charAt(index);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '\\', '*', '_', '[', ']', '(', ')', '`', '#' ->
                    escaped.append('\\').append(character);
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
