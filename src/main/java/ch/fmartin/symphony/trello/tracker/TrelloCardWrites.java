package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.tracker.TrelloWritePermission.COMMENTS;
import static ch.fmartin.symphony.trello.tracker.TrelloWritePermission.TOOLS;
import static ch.fmartin.symphony.trello.tracker.TrelloWritePermission.WRITES;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Card writes that the worker's Trello tools and the `symphony-trello card` commands share. Both
/// add comments, check move destinations against the `trello_tools` policy, and match open lists by
/// name through this class, so a write that one path refuses cannot succeed through the other.
/// Creating and archiving a chosen card are only reachable from the card commands; the worker
/// creates follow-up cards through its own tool, which adds relationship metadata.
///
/// A refused write throws [TrelloException] with the same code and message on both paths.
@NullMarked
public final class TrelloCardWrites {
    private static final String LIST_NOT_OPEN = "Destination list is not open on the configured board.";
    private static final String LIST_NOT_ALLOWED =
            "Destination list is not included in the configured Trello move allowlist.";

    private final TrelloClient trello;

    public TrelloCardWrites(TrelloClient trello) {
        this.trello = trello;
    }

    /// Adds `text` as a comment. Symphony escapes leading hashtags and appends its attribution
    /// footer, replacing any footer at the end of `text`.
    public void addComment(EffectiveConfig config, String cardId, String text) {
        TrelloWritePermission.requireGranted(config, TOOLS, WRITES, COMMENTS);
        if (SymphonyCommentFooter.isFooterOnly(text)) {
            throw new TrelloException(
                    "invalid_comment_text", "Comment text must contain more than the Symphony footer.");
        }
        String body = SymphonyCommentFooter.withoutTrailingFooters(text.stripTrailing());
        trello.addComment(config, cardId, SymphonyCommentFooter.append(TrelloMarkdown.escapeLeadingHashtags(body)));
    }

    /// Creates a card at the bottom of `list`. A card may start in a list that the move allowlist
    /// names, or in a list that is neither active nor terminal, so creating a card cannot start or
    /// finish work that a move would not be allowed to.
    public TrelloClient.CreatedCard createCard(
            EffectiveConfig config, TrelloClient.BoardList list, String title, String description) {
        TrelloWritePermission.requireGranted(config, TOOLS, WRITES);
        if (!startListAllowed(config, list)) {
            throw new TrelloException(
                    "trello_create_list_not_allowed",
                    "New cards can start only in a list that trello_tools allows moves to, or in a list that is"
                            + " neither active nor terminal.");
        }
        String name =
                checkedText(() -> TrelloCardText.singleLine(title, "title", TrelloCardText.MAX_TITLE_CODE_POINTS));
        String body = checkedText(() -> TrelloCardText.description(description));
        return trello.createCard(config, list.id(), name, TrelloMarkdown.escapeLeadingHashtags(body), List.of());
    }

    private static boolean startListAllowed(EffectiveConfig config, TrelloClient.BoardList list) {
        return allowedById(config, list)
                || allowedByName(config, list)
                || !(TrelloClient.isActiveList(config, list) || TrelloClient.isTerminalList(config, list));
    }

    /// Moves a card to `list` after the same allowlist check as the worker's move tool, by list id.
    public void moveCard(EffectiveConfig config, String cardId, TrelloClient.BoardList list) {
        TrelloClient.BoardList target = allowedMoveTarget(config, list.id(), null);
        trello.moveCardToList(config, cardId, target.id());
    }

    /// Archives a card. Trello keeps archived cards and can restore them, so archiving needs the
    /// write permission but not `allow_destructive_operations`, which covers deletes.
    public void archiveCard(EffectiveConfig config, String cardId) {
        TrelloWritePermission.requireGranted(config, TOOLS, WRITES);
        trello.archiveCard(config, cardId);
    }

    public static boolean moveAllowlistConfigured(EffectiveConfig config) {
        return !config.trelloTools().allowedMoveListIds().isEmpty()
                || !config.trelloTools().allowedMoveListNames().isEmpty();
    }

    /// The open list that a card may move to, chosen by `listId` when present, otherwise by
    /// `listName`. Throws the refusal when the workflow does not allow the move.
    public TrelloClient.BoardList allowedMoveTarget(
            EffectiveConfig config, @Nullable String listId, @Nullable String listName) {
        TrelloWritePermission.requireGranted(config, TOOLS, WRITES);
        if (!moveAllowlistConfigured(config)) {
            throw new TrelloException(
                    "trello_move_allowlist_required",
                    "Trello card moves require trello_tools.allowed_move_list_ids or allowed_move_list_names.");
        }
        if (blank(listId) && blank(listName)) {
            throw new TrelloException(
                    "missing_destination_list", "Provide list_name or list_id for the destination list.");
        }
        return switch (resolveMoveTarget(config, listId, listName)) {
            case MoveTarget.Allowed allowed -> allowed.list();
            case MoveTarget.Refused refused -> throw new TrelloException("trello_move_not_allowed", refused.reason());
        };
    }

    /// Checks a destination against the open lists of the configured board and the move
    /// allowlist. Name matches fail closed when several open lists share the requested name.
    public MoveTarget resolveMoveTarget(EffectiveConfig config, @Nullable String listId, @Nullable String listName) {
        return moveTarget(config, openLists(trello.fetchBoardLists(config)), listId, listName);
    }

    /// Whether a card may move to `list`, one of `openLists`, under every rule that [#moveCard]
    /// checks.
    public static boolean movableTo(
            EffectiveConfig config, List<TrelloClient.BoardList> openLists, TrelloClient.BoardList list) {
        return TrelloWritePermission.allGranted(config, TOOLS, WRITES)
                && moveAllowlistConfigured(config)
                && moveTarget(config, openLists, list.id(), null) instanceof MoveTarget.Allowed;
    }

    /// Whether a new card may start in `list` under every rule that [#createCard] checks.
    public static boolean creatableIn(EffectiveConfig config, TrelloClient.BoardList list) {
        return TrelloWritePermission.allGranted(config, TOOLS, WRITES) && startListAllowed(config, list);
    }

    private static MoveTarget moveTarget(
            EffectiveConfig config,
            List<TrelloClient.BoardList> openLists,
            @Nullable String listId,
            @Nullable String listName) {
        if (!blank(listId)) {
            return openLists.stream()
                    .filter(list -> list.id().equals(listId))
                    .findAny()
                    .map(list -> allowedTargetById(config, list, openLists))
                    .orElseGet(() -> new MoveTarget.Refused(LIST_NOT_OPEN));
        }
        return switch (matchOpenList(openLists, null, listName)) {
            case OpenListMatch.Found found -> allowedTarget(config, found.list());
            case OpenListMatch.Missing _ -> new MoveTarget.Refused(LIST_NOT_OPEN);
            case OpenListMatch.Ambiguous _ ->
                new MoveTarget.Refused(
                        "Destination list name matches multiple open Trello lists. Rename the duplicate lists or move by list_id.");
        };
    }

    private static MoveTarget allowedTargetById(
            EffectiveConfig config, TrelloClient.BoardList list, List<TrelloClient.BoardList> openLists) {
        if (allowedById(config, list)) {
            return new MoveTarget.Allowed(list);
        }
        boolean uniqueName = matchOpenList(openLists, null, list.name()) instanceof OpenListMatch.Found;
        if (allowedByName(config, list) && uniqueName) {
            return new MoveTarget.Allowed(list);
        }
        if (allowedByName(config, list)) {
            return new MoveTarget.Refused(
                    "Destination list name matches multiple open Trello lists. Allow the exact list_id before moving by list_id.");
        }
        return new MoveTarget.Refused(LIST_NOT_ALLOWED);
    }

    private static MoveTarget allowedTarget(EffectiveConfig config, TrelloClient.BoardList list) {
        return allowedById(config, list) || allowedByName(config, list)
                ? new MoveTarget.Allowed(list)
                : new MoveTarget.Refused(LIST_NOT_ALLOWED);
    }

    private static boolean allowedById(EffectiveConfig config, TrelloClient.BoardList list) {
        return config.trelloTools().allowedMoveListIds().contains(list.id());
    }

    private static boolean allowedByName(EffectiveConfig config, TrelloClient.BoardList list) {
        return config.trelloTools().allowedMoveListNames().contains(StateNames.normalize(list.name()));
    }

    public static List<TrelloClient.BoardList> openLists(List<TrelloClient.BoardList> lists) {
        return lists.stream().filter(list -> !list.closed()).toList();
    }

    /// Finds the open list with `listId` when it is present, otherwise the open list whose
    /// normalized name equals `listName`.
    public static OpenListMatch matchOpenList(
            List<TrelloClient.BoardList> openLists, @Nullable String listId, @Nullable String listName) {
        Predicate<TrelloClient.BoardList> selected =
                listId == null ? namedList(listName) : list -> list.id().equals(listId);
        List<TrelloClient.BoardList> matches =
                openLists.stream().filter(selected).limit(2).toList();
        return switch (matches.size()) {
            case 0 -> new OpenListMatch.Missing();
            case 1 -> new OpenListMatch.Found(matches.getFirst());
            default -> new OpenListMatch.Ambiguous();
        };
    }

    private static Predicate<TrelloClient.BoardList> namedList(@Nullable String listName) {
        String normalizedName = StateNames.normalize(listName);
        return list -> StateNames.normalize(list.name()).equals(normalizedName);
    }

    private static String checkedText(Supplier<String> check) {
        try {
            return check.get();
        } catch (IllegalArgumentException e) {
            throw new TrelloException("invalid_card_text", e.getMessage(), e);
        }
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /// A move destination the workflow allows, or the reason it refuses one.
    public sealed interface MoveTarget {
        default Optional<TrelloClient.BoardList> allowedList() {
            return this instanceof Allowed allowed ? Optional.of(allowed.list()) : Optional.empty();
        }

        record Allowed(TrelloClient.BoardList list) implements MoveTarget {}

        record Refused(String reason) implements MoveTarget {}
    }

    /// The result of looking up one open list: exactly one match, none, or several.
    public sealed interface OpenListMatch {
        record Found(TrelloClient.BoardList list) implements OpenListMatch {}

        record Missing() implements OpenListMatch {}

        record Ambiguous() implements OpenListMatch {}
    }
}
