package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Resolves a requested destination list against the open lists of the configured board and the
/// `trello_tools` move allowlist. Every Codex-facing move tool uses this policy so a list that one
/// tool refuses cannot be reached through another.
@NullMarked
public final class TrelloMoveTargets {
    private TrelloMoveTargets() {}

    public static boolean allowlistConfigured(EffectiveConfig config) {
        return !config.trelloTools().allowedMoveListIds().isEmpty()
                || !config.trelloTools().allowedMoveListNames().isEmpty();
    }

    /// Resolves `listId` when present, otherwise `listName`. Name matches fail closed when several
    /// open lists share the requested name.
    public static MoveTarget resolve(
            EffectiveConfig config,
            List<TrelloClient.BoardList> boardLists,
            @Nullable String listId,
            @Nullable String listName) {
        List<TrelloClient.BoardList> openLists =
                boardLists.stream().filter(list -> !list.closed()).toList();
        if (!blank(listId)) {
            return openLists.stream()
                    .filter(list -> list.id().equals(listId))
                    .findAny()
                    .map(list -> allowedTargetById(config, list, openLists))
                    .orElseGet(() -> MoveTarget.rejected("Destination list is not open on the configured board."));
        }

        String requestedName = StateNames.normalize(listName);
        List<TrelloClient.BoardList> nameMatches = openLists.stream()
                .filter(list -> StateNames.normalize(list.name()).equals(requestedName))
                .toList();
        if (nameMatches.size() > 1) {
            return MoveTarget.rejected(
                    "Destination list name matches multiple open Trello lists. Rename the duplicate lists or move by list_id.");
        }
        if (nameMatches.isEmpty()) {
            return MoveTarget.rejected("Destination list is not open on the configured board.");
        }
        return allowedTarget(config, nameMatches.getFirst());
    }

    private static MoveTarget allowedTargetById(
            EffectiveConfig config, TrelloClient.BoardList list, List<TrelloClient.BoardList> openLists) {
        if (allowedById(config, list)) {
            return MoveTarget.allowed(list);
        }
        if (allowedByName(config, list) && hasUniqueOpenListName(list, openLists)) {
            return MoveTarget.allowed(list);
        }
        if (allowedByName(config, list)) {
            return MoveTarget.rejected(
                    "Destination list name matches multiple open Trello lists. Allow the exact list_id before moving by list_id.");
        }
        return MoveTarget.rejected("Destination list is not included in the configured Trello move allowlist.");
    }

    private static MoveTarget allowedTarget(EffectiveConfig config, TrelloClient.BoardList list) {
        return allowedById(config, list) || allowedByName(config, list)
                ? MoveTarget.allowed(list)
                : MoveTarget.rejected("Destination list is not included in the configured Trello move allowlist.");
    }

    private static boolean hasUniqueOpenListName(TrelloClient.BoardList list, List<TrelloClient.BoardList> openLists) {
        String normalized = StateNames.normalize(list.name());
        return openLists.stream()
                        .filter(candidate -> normalized.equals(StateNames.normalize(candidate.name())))
                        .count()
                == 1;
    }

    private static boolean allowedById(EffectiveConfig config, TrelloClient.BoardList list) {
        return config.trelloTools().allowedMoveListIds().contains(list.id());
    }

    private static boolean allowedByName(EffectiveConfig config, TrelloClient.BoardList list) {
        return config.trelloTools().allowedMoveListNames().contains(StateNames.normalize(list.name()));
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /// Either an allowed destination list or the reason the move is refused.
    public record MoveTarget(TrelloClient.@Nullable BoardList list, @Nullable String error) {
        static MoveTarget allowed(TrelloClient.BoardList list) {
            return new MoveTarget(list, null);
        }

        static MoveTarget rejected(String error) {
            return new MoveTarget(null, error);
        }
    }
}
