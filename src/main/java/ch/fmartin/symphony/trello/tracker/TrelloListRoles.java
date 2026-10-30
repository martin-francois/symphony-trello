package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import com.google.common.base.CharMatcher;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The list roles one workflow gives the lists of its board.
///
/// `active` holds the active lists where new work waits for pickup: the workflow's active lists
/// other than the in-progress and merging lists. Active and terminal lists follow
/// [TrelloClient#isActiveList] and [TrelloClient#isTerminalList], so a workflow with
/// `active_list_ids` or `terminal_list_ids` gives those roles by list id, as dispatch does. The other
/// roles name one list each. The human-review and merging lists are not workflow settings; setup
/// records them when it generates the workflow, so a hand-written workflow may have neither.
@NullMarked
public final class TrelloListRoles {
    /// Every [Role] key in [Role] order, for help text that needs a constant.
    /// `TrelloListRolesTest` checks it against the enum.
    public static final String ROLE_NAMES = "active, in-progress, human-review, blocked, merging, terminal";

    private static final CharMatcher ROLE_SEPARATORS = CharMatcher.anyOf(" _").precomputed();

    private final EffectiveConfig config;
    private final @Nullable String inProgress;
    private final @Nullable String humanReview;
    private final @Nullable String blocked;
    private final @Nullable String merging;

    private TrelloListRoles(
            EffectiveConfig config,
            @Nullable String inProgress,
            @Nullable String humanReview,
            @Nullable String blocked,
            @Nullable String merging) {
        this.config = config;
        this.inProgress = inProgress;
        this.humanReview = humanReview;
        this.blocked = blocked;
        this.merging = merging;
    }

    public static TrelloListRoles of(EffectiveConfig config, @Nullable String humanReview, @Nullable String merging) {
        EffectiveConfig.TrackerConfig tracker = config.tracker();
        return new TrelloListRoles(
                config,
                blankToNull(tracker.inProgressState()),
                blankToNull(humanReview),
                blankToNull(tracker.blockedState()),
                blankToNull(merging));
    }

    /// Every role that `list` has, in [Role] order.
    public List<Role> rolesOf(TrelloClient.BoardList list) {
        return Arrays.stream(Role.values()).filter(role -> holds(role, list)).toList();
    }

    private boolean holds(Role role, TrelloClient.BoardList list) {
        return switch (role) {
            case ACTIVE -> waitsForPickup(list);
            case IN_PROGRESS -> sameList(list.name(), inProgress);
            case HUMAN_REVIEW -> sameList(list.name(), humanReview);
            case BLOCKED -> sameList(list.name(), blocked);
            case MERGING -> sameList(list.name(), merging);
            case TERMINAL -> TrelloClient.isTerminalList(config, list);
        };
    }

    private boolean waitsForPickup(TrelloClient.BoardList list) {
        return TrelloClient.isActiveList(config, list)
                && !sameList(list.name(), inProgress)
                && !sameList(list.name(), merging);
    }

    /// Resolves what a user typed as a destination: an open list name, or a role name that names
    /// exactly one open list. A list name wins over a role name, so a list called "Blocked" is
    /// always that list.
    public Destination resolve(List<TrelloClient.BoardList> openLists, String listOrRole) {
        return switch (TrelloCardWrites.matchOpenList(openLists, null, listOrRole)) {
            case TrelloCardWrites.OpenListMatch.Found found -> new Destination.Found(found.list());
            case TrelloCardWrites.OpenListMatch.Ambiguous _ -> new Destination.AmbiguousListName();
            case TrelloCardWrites.OpenListMatch.Missing _ ->
                Role.parse(listOrRole)
                        .map(role -> resolveRole(openLists, role))
                        .orElseGet(Destination.UnknownList::new);
        };
    }

    private Destination resolveRole(List<TrelloClient.BoardList> openLists, Role role) {
        List<TrelloClient.BoardList> lists =
                openLists.stream().filter(list -> holds(role, list)).toList();
        return switch (lists.size()) {
            case 0 -> new Destination.RoleWithoutList(role);
            case 1 -> new Destination.Found(lists.getFirst());
            default -> new Destination.RoleWithSeveralLists(role, lists);
        };
    }

    private static boolean sameList(String list, @Nullable String other) {
        return other != null && StateNames.normalize(list).equals(StateNames.normalize(other));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /// A list role that a user may type instead of a list name.
    public enum Role {
        ACTIVE("active"),
        IN_PROGRESS("in-progress"),
        HUMAN_REVIEW("human-review"),
        BLOCKED("blocked"),
        MERGING("merging"),
        TERMINAL("terminal");

        private final String key;

        Role(String key) {
            this.key = key;
        }

        /// The role name in command output and in what a user types.
        public String key() {
            return key;
        }

        /// Accepts the role name in any case, with a space or an underscore in place of the hyphen,
        /// such as `Human Review` or `in_progress`.
        public static Optional<Role> parse(String value) {
            String normalized = ROLE_SEPARATORS.replaceFrom(StateNames.normalize(value), '-');
            return Arrays.stream(values())
                    .filter(role -> role.key.equals(normalized))
                    .findAny();
        }
    }

    /// The open list a destination names, or why it names none.
    public sealed interface Destination {
        record Found(TrelloClient.BoardList list) implements Destination {}

        record AmbiguousListName() implements Destination {}

        record UnknownList() implements Destination {}

        record RoleWithoutList(Role role) implements Destination {}

        record RoleWithSeveralLists(Role role, List<TrelloClient.BoardList> lists) implements Destination {
            public RoleWithSeveralLists {
                lists = List.copyOf(lists);
            }
        }
    }
}
