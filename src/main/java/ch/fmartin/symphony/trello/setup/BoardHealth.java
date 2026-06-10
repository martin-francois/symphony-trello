package ch.fmartin.symphony.trello.setup;

import java.util.Optional;

record BoardHealth(
        BoardHealthKind kind,
        int port,
        Optional<String> actualWorkflowPath,
        Optional<String> actualBoardId,
        Optional<Long> workerPid) {
    BoardHealth(BoardHealthKind kind, int port, Optional<String> actualWorkflowPath, Optional<String> actualBoardId) {
        this(kind, port, actualWorkflowPath, actualBoardId, Optional.empty());
    }
}
