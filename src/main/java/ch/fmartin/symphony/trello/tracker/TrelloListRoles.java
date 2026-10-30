package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.StateNames;
import com.google.common.base.CharMatcher;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The list roles one workflow gives the lists of its board, as list names.
///
/// `active` holds the active lists where new work waits for pickup: the workflow's active lists
/// other than the in-progress and merging lists. The human-review and merging lists are not workflow
/// settings; setup records them when it generates the workflow, so a hand-written workflow may have
/// neither.
@NullMarked
public record TrelloListRoles(
        List<String> active,
        @Nullable String inProgress,
        @Nullable String humanReview,
        @Nullable String blocked,
        @Nullable String merging,
        List<String> terminal) {
    /// Every [Role] key in [Role] order, for help text that needs a constant.
    /// `TrelloListRolesTest` checks it against the enum.
    public static final String ROLE_NAMES = "active, in-progress, human-review, blocked, merging, terminal";

    private static final CharMatcher ROLE_SEPARATORS = CharMatcher.anyOf(" _").precomputed();

    public TrelloListRoles {
        active = List.copyOf(active);
        terminal = List.copyOf(terminal);
    }

    public static TrelloListRoles of(EffectiveConfig config, @Nullable String humanReview, @Nullable String merging) {
        EffectiveConfig.TrackerConfig tracker = config.tracker();
        String inProgress = blankToNull(tracker.inProgressState());
        String mergingList = blankToNull(merging);
        List<String> waitingForPickup = tracker.activeStates().stream()
                .filter(list -> !sameList(list, inProgress) && !sameList(list, mergingList))
                .toList();
        return new TrelloListRoles(
                waitingForPickup,
                inProgress,
                blankToNull(humanReview),
                blankToNull(tracker.blockedState()),
                mergingList,
                tracker.terminalStates());
    }

    /// The list names that hold `role`, in workflow order.
    public List<String> listNames(Role role) {
        return switch (role) {
            case ACTIVE -> active;
            case IN_PROGRESS -> optionalList(inProgress);
            case HUMAN_REVIEW -> optionalList(humanReview);
            case BLOCKED -> optionalList(blocked);
            case MERGING -> optionalList(merging);
            case TERMINAL -> terminal;
        };
    }

    /// Every role that names `listName`, in [Role] order.
    public List<Role> rolesOf(String listName) {
        return Arrays.stream(Role.values())
                .filter(role -> holds(role, listName))
                .toList();
    }

    private boolean holds(Role role, String listName) {
        return listNames(role).stream().anyMatch(name -> sameList(name, listName));
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
                openLists.stream().filter(list -> holds(role, list.name())).toList();
        return switch (lists.size()) {
            case 0 -> new Destination.RoleWithoutList(role);
            case 1 -> new Destination.Found(lists.getFirst());
            default -> new Destination.RoleWithSeveralLists(role, lists);
        };
    }

    private static List<String> optionalList(@Nullable String listName) {
        return Stream.ofNullable(listName).toList();
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
