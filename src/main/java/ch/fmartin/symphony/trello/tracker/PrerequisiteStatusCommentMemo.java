package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.domain.Card;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/// Remembers the managed prerequisite status comment that the last lookup found on each candidate
/// card, keyed by the card's comment activity from the candidate poll. Adding or deleting a comment
/// changes the comment count, and Symphony forgets the answer after its own status writes. While
/// Trello reports the same comment count and last-activity time, the poll reuses the remembered
/// answer instead of reading up to [TrelloClient#WORKPAD_COMMENT_ACTION_LIMIT] comment actions again
/// on every tick.
///
/// The memo lives only in memory. A restart starts empty and pays one lookup per card. See
/// `docs/adr/0117-in-memory-prerequisite-status-lookup-memo.md`.
final class PrerequisiteStatusCommentMemo {
    // Several threads may sync waiting feedback through the shared TrelloClient.
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /// Comment activity of one card as the candidate poll's card list reports it.
    record CommentActivity(int commentCount, @Nullable String lastActivity) {}

    /// Returns the card's managed status comment. Uses the remembered answer when `activity`
    /// matches the activity of the remembered lookup, otherwise runs `lookup` and remembers its
    /// answer. A `null` activity means the caller has no complete comment count, so the lookup
    /// always runs and nothing is remembered.
    Optional<Card.Comment> find(
            String cardId, @Nullable CommentActivity activity, Supplier<Optional<Card.Comment>> lookup) {
        if (activity == null) {
            return lookup.get();
        }
        Entry before = entries.computeIfAbsent(cardId, id -> Entry.unknown());
        if (activity.equals(before.activity)) {
            return before.comment;
        }
        Optional<Card.Comment> found = lookup.get();
        // Compare by identity: a forget() that ran during the lookup installed a new entry, and an
        // answer read before that write must not replace it.
        entries.replace(cardId, before, new Entry(activity, found));
        return found;
    }

    /// Drops the remembered answer after Symphony writes the card's status comment, so the next
    /// poll reads the comment again.
    void forget(String cardId) {
        entries.put(cardId, Entry.unknown());
    }

    /// Keeps entries only for the given cards, so the memo stays bounded by the active candidates.
    void retainOnly(Set<String> cardIds) {
        entries.keySet().retainAll(cardIds);
    }

    /// Uses identity equality on purpose, see [#find].
    private static final class Entry {
        private final @Nullable CommentActivity activity;
        private final Optional<Card.Comment> comment;

        private Entry(@Nullable CommentActivity activity, Optional<Card.Comment> comment) {
            this.activity = activity;
            this.comment = comment;
        }

        private static Entry unknown() {
            return new Entry(null, Optional.empty());
        }
    }
}
