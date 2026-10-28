package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.PrerequisiteStatusCommentMemo.CardKey;
import ch.fmartin.symphony.trello.tracker.PrerequisiteStatusCommentMemo.CommentActivity;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class PrerequisiteStatusCommentMemoTest {
    private static final String ENDPOINT = "https://api.example.test/1";
    private static final TrackerTarget TARGET = TrackerTarget.from("trello", ENDPOINT, "board-1");
    private static final CardKey CARD = new CardKey(TARGET, "card-1");
    private static final CardKey OTHER_CARD = new CardKey(TARGET, "card-2");
    private static final CommentActivity ACTIVITY = new CommentActivity(3, "2026-01-01T00:00:01Z");
    private static final Optional<Card.Comment> STATUS_COMMENT = Optional.of(
            new Card.Comment("comment-1", TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER, "someone", Instant.EPOCH));

    private final PrerequisiteStatusCommentMemo memo = new PrerequisiteStatusCommentMemo();
    private final AtomicInteger lookups = new AtomicInteger();

    @Test
    void reusesTheRememberedCommentWhileTheCommentActivityIsUnchanged() {
        // given
        memo.find(CARD, ACTIVITY, this::countedLookup);

        // when
        Optional<Card.Comment> found = memo.find(CARD, ACTIVITY, this::countedLookup);

        // then
        assertThat(found).isEqualTo(STATUS_COMMENT);
        assertThat(lookups).as("lookups for unchanged comment activity").hasValue(1);
    }

    @Test
    void looksUpAgainWhenTheCommentActivityChanges() {
        // given
        memo.find(CARD, ACTIVITY, this::countedLookup);

        // when
        memo.find(CARD, new CommentActivity(4, "2026-01-01T00:00:02Z"), this::countedLookup);

        // then
        assertThat(lookups).as("lookups after a comment was added").hasValue(2);
    }

    @MethodSource("incompleteActivities")
    @ParameterizedTest
    void alwaysLooksUpWithoutCompleteCommentActivity(@Nullable CommentActivity activity) {
        // given
        memo.find(CARD, ACTIVITY, this::countedLookup);
        memo.find(CARD, activity, this::countedLookup);

        // when
        memo.find(CARD, activity, this::countedLookup);

        // then
        assertThat(lookups)
                .as("priming lookup and both incomplete-activity lookups")
                .hasValue(3);
    }

    @MethodSource("incompleteActivities")
    @ParameterizedTest
    void incompleteActivityInvalidatesTheRememberedAnswer(@Nullable CommentActivity activity) {
        // given
        memo.find(CARD, ACTIVITY, Optional::empty);
        memo.find(CARD, activity, this::countedLookup);

        // when
        Optional<Card.Comment> found = memo.find(CARD, ACTIVITY, this::countedLookup);

        // then
        assertThat(found)
                .as("old activity must not revive the stale empty answer")
                .isEqualTo(STATUS_COMMENT);
        assertThat(lookups)
                .as("incomplete activity and the next complete activity both refresh")
                .hasValue(2);
    }

    @MethodSource("incompleteActivities")
    @ParameterizedTest
    void incompleteActivityInvalidatesAnInFlightLookup(@Nullable CommentActivity activity) {
        // given
        memo.find(CARD, ACTIVITY, () -> lookupOverlappingIncompleteActivity(activity));

        // when
        Optional<Card.Comment> found = memo.find(CARD, ACTIVITY, this::countedLookup);

        // then
        assertThat(found)
                .as("the in-flight empty answer must not survive incomplete activity")
                .isEqualTo(STATUS_COMMENT);
        assertThat(lookups).hasValue(2);
    }

    static @Nullable CommentActivity[] incompleteActivities() {
        return new CommentActivity[] {
            null,
            new CommentActivity(ACTIVITY.commentCount(), null),
            new CommentActivity(ACTIVITY.commentCount(), ""),
            new CommentActivity(ACTIVITY.commentCount(), " "),
            new CommentActivity(ACTIVITY.commentCount(), "\t")
        };
    }

    private Optional<Card.Comment> lookupOverlappingIncompleteActivity(@Nullable CommentActivity activity) {
        // The incomplete-activity lookup observes a new comment before the older lookup returns.
        memo.find(CARD, activity, this::countedLookup);
        return Optional.empty();
    }

    @Test
    void looksUpAgainAfterForget() {
        // given
        memo.find(CARD, ACTIVITY, this::countedLookup);
        memo.forget(CARD);

        // when
        memo.find(CARD, ACTIVITY, this::countedLookup);

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
            memo.forget(CARD);
            return Optional.empty();
        };
        memo.find(CARD, ACTIVITY, lookupRacingWithWrite);

        // when
        Optional<Card.Comment> found = memo.find(CARD, ACTIVITY, this::countedLookup);

        // then
        assertThat(found)
                .as("the answer read before the write must not hide the written comment")
                .isEqualTo(STATUS_COMMENT);
        assertThat(lookups).hasValue(2);
    }

    @Test
    void retainOnlyDropsCardsThatLeftTheCandidates() {
        // given
        memo.find(CARD, ACTIVITY, this::countedLookup);
        memo.find(OTHER_CARD, ACTIVITY, this::countedLookup);
        memo.retainOnly(Set.of(OTHER_CARD));

        // when
        memo.find(CARD, ACTIVITY, this::countedLookup);
        memo.find(OTHER_CARD, ACTIVITY, this::countedLookup);

        // then
        assertThat(lookups).as("only the dropped card is looked up again").hasValue(3);
    }

    @Test
    void boardNamespacesKeepSameCardIdsSeparateWhenForgettingAndRetaining() {
        // given
        TrackerTarget otherBoard = TrackerTarget.from("trello", ENDPOINT, "board-2");
        var sameIdOnOtherBoard = new CardKey(otherBoard, CARD.cardId());
        memo.find(CARD, ACTIVITY, this::countedLookup);
        memo.find(sameIdOnOtherBoard, ACTIVITY, this::countedLookup);
        memo.forget(sameIdOnOtherBoard);

        // when
        memo.find(CARD, ACTIVITY, this::countedLookup);
        memo.find(sameIdOnOtherBoard, ACTIVITY, this::countedLookup);
        memo.retainOnly(Set.of(CARD));
        memo.find(CARD, ACTIVITY, this::countedLookup);
        memo.find(sameIdOnOtherBoard, ACTIVITY, this::countedLookup);

        // then
        assertThat(lookups)
                .as("two initial reads, one scoped invalidation and one scoped eviction")
                .hasValue(4);
    }

    private Optional<Card.Comment> countedLookup() {
        lookups.incrementAndGet();
        return STATUS_COMMENT;
    }
}
