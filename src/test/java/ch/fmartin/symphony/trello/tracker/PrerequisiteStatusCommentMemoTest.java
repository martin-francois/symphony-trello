package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.PrerequisiteStatusCommentMemo.CommentActivity;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class PrerequisiteStatusCommentMemoTest {
    private static final String CARD_ID = "card-1";
    private static final CommentActivity ACTIVITY = new CommentActivity(3, "2026-01-01T00:00:01Z");
    private static final Optional<Card.Comment> STATUS_COMMENT = Optional.of(
            new Card.Comment("comment-1", TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER, "someone", Instant.EPOCH));

    private final PrerequisiteStatusCommentMemo memo = new PrerequisiteStatusCommentMemo();
    private final AtomicInteger lookups = new AtomicInteger();
    private final Supplier<Optional<Card.Comment>> lookup = () -> {
        lookups.incrementAndGet();
        return STATUS_COMMENT;
    };

    @Test
    void reusesTheRememberedCommentWhileTheCommentActivityIsUnchanged() {
        // given
        memo.find(CARD_ID, ACTIVITY, lookup);

        // when
        Optional<Card.Comment> found = memo.find(CARD_ID, ACTIVITY, lookup);

        // then
        assertThat(found).isEqualTo(STATUS_COMMENT);
        assertThat(lookups).as("lookups for unchanged comment activity").hasValue(1);
    }

    @Test
    void looksUpAgainWhenTheCommentActivityChanges() {
        // given
        memo.find(CARD_ID, ACTIVITY, lookup);

        // when
        memo.find(CARD_ID, new CommentActivity(4, "2026-01-01T00:00:02Z"), lookup);

        // then
        assertThat(lookups).as("lookups after a comment was added").hasValue(2);
    }

    @Test
    void alwaysLooksUpWithoutCommentActivity() {
        // given
        memo.find(CARD_ID, null, lookup);

        // when
        memo.find(CARD_ID, null, lookup);

        // then
        assertThat(lookups).as("lookups without a complete comment count").hasValue(2);
    }

    @Test
    void looksUpAgainAfterForget() {
        // given
        memo.find(CARD_ID, ACTIVITY, lookup);
        memo.forget(CARD_ID);

        // when
        memo.find(CARD_ID, ACTIVITY, lookup);

        // then
        assertThat(lookups)
                .as("lookups after Symphony wrote the status comment")
                .hasValue(2);
    }

    @Test
    void doesNotRememberAnAnswerReadBeforeAConcurrentStatusWrite() {
        // given
        Supplier<Optional<Card.Comment>> lookupRacingWithWrite = () -> {
            lookups.incrementAndGet();
            // Another thread writes the status comment after this lookup read the old comments.
            memo.forget(CARD_ID);
            return Optional.empty();
        };
        memo.find(CARD_ID, ACTIVITY, lookupRacingWithWrite);

        // when
        Optional<Card.Comment> found = memo.find(CARD_ID, ACTIVITY, lookup);

        // then
        assertThat(found)
                .as("the answer read before the write must not hide the written comment")
                .isEqualTo(STATUS_COMMENT);
        assertThat(lookups).hasValue(2);
    }

    @Test
    void retainOnlyDropsCardsThatLeftTheCandidates() {
        // given
        memo.find(CARD_ID, ACTIVITY, lookup);
        memo.find("card-2", ACTIVITY, lookup);
        memo.retainOnly(Set.of("card-2"));

        // when
        memo.find(CARD_ID, ACTIVITY, lookup);
        memo.find("card-2", ACTIVITY, lookup);

        // then
        assertThat(lookups).as("only the dropped card is looked up again").hasValue(3);
    }
}
