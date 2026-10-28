package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.domain.Card;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/// Remembers the managed prerequisite status comment that the last lookup found on each candidate
/// card within its tracker endpoint and board namespace, keyed by the card's comment activity.
/// Adding or deleting a comment changes the count. Symphony forgets the answer before its own
/// status writes. While Trello reports the same complete comment count and last-activity time,
/// the poll reuses the remembered answer instead of reading up to
/// [TrelloClient#WORKPAD_COMMENT_ACTION_LIMIT] comment actions again on every tick.
///
/// The memo lives only in memory. A restart starts empty and pays one lookup per card. See
/// `docs/adr/0117-in-memory-prerequisite-status-lookup-memo.md`.
final class PrerequisiteStatusCommentMemo {
    // Several threads may sync waiting feedback through the shared TrelloClient.
    private final ConcurrentMap<CardKey, Entry> entries = new ConcurrentHashMap<>();

    /// A card belongs to one canonical tracker endpoint and resolved board namespace.
    record CardKey(TrackerTarget target, String cardId) {}

    /// Comment activity of one card as the candidate poll's card list reports it.
    record CommentActivity(int commentCount, @Nullable String lastActivity) {
        boolean isComplete() {
            return lastActivity != null && !lastActivity.isBlank();
        }
    }

    /// Returns the card's managed status comment. Uses the remembered answer when `activity`
    /// matches the activity of the remembered lookup, otherwise runs `lookup` and remembers its
    /// answer. Missing activity or a null/blank timestamp invalidates the previous answer and
    /// always runs the lookup without remembering its result.
    Optional<Card.Comment> find(
            CardKey card, @Nullable CommentActivity activity, Supplier<Optional<Card.Comment>> lookup) {
        if (activity == null || !activity.isComplete()) {
            // An incomplete observation also invalidates any lookup already in flight.
            entries.remove(card);
            return lookup.get();
        }
        Entry before = entries.computeIfAbsent(card, id -> Entry.unknown());
        // An unknown entry has a null activity, which never equals a real one, so it always looks up.
        if (activity.equals(before.activity)) {
            return before.comment;
        }
        Optional<Card.Comment> found = lookup.get();
        // Compare by identity: a forget() that ran during the lookup installed a new entry, and an
        // answer read before that write must not replace it.
        entries.replace(card, before, new Entry(activity, found));
        return found;
    }

    /// Drops the remembered answer before Symphony attempts a status write, so the next poll
    /// reads the comment again even if the write fails.
    void forget(CardKey card) {
        entries.put(card, Entry.unknown());
    }

    /// Keeps entries only for the given cards, so the memo stays bounded by the active candidates.
    void retainOnly(Set<CardKey> cards) {
        entries.keySet().retainAll(cards);
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
