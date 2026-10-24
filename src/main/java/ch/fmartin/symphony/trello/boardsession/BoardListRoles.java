package ch.fmartin.symphony.trello.boardsession;

import ch.fmartin.symphony.trello.config.StateNames;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// The list roles a connected workflow assigns on its board, as list names.
///
/// `queueLists` are the active lists where new work waits for pickup. A request such as "create a
/// task for this work" may use the queue list only when there is exactly one; otherwise the session
/// must ask the user.
@NullMarked
public record BoardListRoles(
        List<String> activeLists,
        List<String> queueLists,
        Optional<String> inProgressList,
        Optional<String> reviewList,
        Optional<String> blockedList,
        List<String> terminalLists) {
    public BoardListRoles {
        activeLists = List.copyOf(activeLists);
        queueLists = List.copyOf(queueLists);
        terminalLists = List.copyOf(terminalLists);
    }

    /// Uses the generated queue lists when the workflow names them, otherwise the active lists other
    /// than the in-progress list.
    public static BoardListRoles of(
            List<String> activeLists,
            List<String> generatedQueueLists,
            Optional<String> inProgressList,
            Optional<String> reviewList,
            Optional<String> blockedList,
            List<String> terminalLists) {
        List<String> queueLists = generatedQueueLists.isEmpty()
                ? activeLists.stream()
                        .filter(list -> inProgressList
                                .map(inProgress ->
                                        !StateNames.normalize(inProgress).equals(StateNames.normalize(list)))
                                .orElse(true))
                        .toList()
                : generatedQueueLists;
        return new BoardListRoles(activeLists, queueLists, inProgressList, reviewList, blockedList, terminalLists);
    }

    public Optional<String> defaultNewCardList() {
        return queueLists.size() == 1 ? Optional.of(queueLists.getFirst()) : Optional.empty();
    }

    /// The list names that hold `role`; a single-list role yields at most one name.
    List<String> lists(Role role) {
        return switch (role) {
            case ACTIVE -> activeLists;
            case QUEUE -> queueLists;
            case IN_PROGRESS -> inProgressList.map(List::of).orElse(List.of());
            case REVIEW -> reviewList.map(List::of).orElse(List.of());
            case BLOCKED -> blockedList.map(List::of).orElse(List.of());
            case TERMINAL -> terminalLists;
        };
    }

    /// Every workflow list role, in the order the session context and board overview show them.
    enum Role {
        ACTIVE("active", "active lists", false),
        QUEUE("queue", "queue lists for new work", false),
        IN_PROGRESS("in_progress", "in-progress list", true),
        REVIEW("review", "review list", true),
        BLOCKED("blocked", "blocked list", true),
        TERMINAL("terminal", "terminal lists", false);

        private final String key;
        private final String label;
        private final boolean singleList;

        Role(String key, String label, boolean singleList) {
            this.key = key;
            this.label = label;
            this.singleList = singleList;
        }

        /// Name of the role in the board overview tool result.
        String key() {
            return key;
        }

        /// Lower-case description of the role in session instructions.
        String label() {
            return label;
        }

        boolean singleList() {
            return singleList;
        }
    }
}
