package ch.fmartin.symphony.trello.agent;

import ch.fmartin.symphony.trello.config.FollowUpRelationship;
import ch.fmartin.symphony.trello.tracker.SymphonyCommentFooter;
import com.google.common.base.CharMatcher;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// Formats and reads the Symphony metadata footer at the end of a follow-up card description.
///
/// The footer is the accepted relationship convention for follow-up cards: the last paragraph of
/// the description is exactly
/// `_Written by Symphony · Follow-up of [the source card](https://trello.com/c/<shortLink>) · <relationship>_`.
/// The previous exact `Managed by Symphony` prefix is also supported. A description that does not
/// end with either exact footer carries no relationship meaning, so a
/// pasted card link, an attachment, or an edited footer stays a plain reference.
record FollowUpCardMetadata(String sourceShortLink, FollowUpRelationship relationship) {
    static final String FOOTER_PREFIX = "_" + SymphonyCommentFooter.ATTRIBUTION;
    // Trello short links are 8 characters today; 24 also admits a full card id used as a link.
    static final int MIN_SHORT_LINK_LENGTH = 8;
    static final int MAX_SHORT_LINK_LENGTH = 24;
    private static final CharMatcher SHORT_LINK_CHARACTER = CharMatcher.inRange('A', 'Z')
            .or(CharMatcher.inRange('a', 'z'))
            .or(CharMatcher.inRange('0', '9'))
            .precomputed();
    static final String SEPARATOR = SymphonyCommentFooter.DETAIL_SEPARATOR;
    private static final String CARD_URL_PREFIX = "https://trello.com/c/";
    private static final String SOURCE_LINK_PREFIX = "Follow-up of [the source card](";
    // The short link group is loose on purpose: validShortLink owns the allowed characters and length.
    private static final Pattern FOOTER = Pattern.compile("(?:" + Pattern.quote(FOOTER_PREFIX) + "|"
            + Pattern.quote("_" + SymphonyCommentFooter.PREVIOUS_ATTRIBUTION) + ")"
            + Pattern.quote(SEPARATOR + SOURCE_LINK_PREFIX + CARD_URL_PREFIX)
            + "([^)\\s]+)" + Pattern.quote(")" + SEPARATOR) + "([^_\\n]+)_");

    static String cardUrl(String shortLink) {
        return CARD_URL_PREFIX + shortLink;
    }

    static boolean validShortLink(@Nullable String shortLink) {
        return shortLink != null
                && shortLink.length() >= MIN_SHORT_LINK_LENGTH
                && shortLink.length() <= MAX_SHORT_LINK_LENGTH
                && SHORT_LINK_CHARACTER.matchesAllOf(shortLink);
    }

    String footer() {
        return FOOTER_PREFIX + SEPARATOR + SOURCE_LINK_PREFIX + cardUrl(sourceShortLink) + ")" + SEPARATOR
                + relationship.visibleDescription() + "_";
    }

    /// Appends the footer as its own Markdown paragraph.
    String appendTo(String body) {
        String content = body.stripTrailing();
        return content.isEmpty() ? footer() : content + "\n\n" + footer();
    }

    static Optional<FollowUpCardMetadata> parse(@Nullable String description) {
        if (description == null) {
            return Optional.empty();
        }
        String text = description.stripTrailing();
        int lineStart = text.lastIndexOf('\n') + 1;
        // Markdown renders the footer as its own paragraph only after a blank line.
        if (lineStart > 0 && !text.substring(0, lineStart).endsWith("\n\n")) {
            return Optional.empty();
        }
        Matcher matcher = FOOTER.matcher(text.substring(lineStart));
        if (!matcher.matches() || !validShortLink(matcher.group(1))) {
            return Optional.empty();
        }
        return FollowUpRelationship.fromVisibleDescription(matcher.group(2))
                .map(relationship -> new FollowUpCardMetadata(matcher.group(1), relationship));
    }
}
