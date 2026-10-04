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
}
