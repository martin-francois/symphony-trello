package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Optional;

record StartWorkerRequest(
        Optional<String> board,
        Optional<Path> workflow,
        Optional<Path> envPath,
        Optional<Path> appHome,
        Optional<Path> configDir,
        Optional<Path> workspaceRoot,
        Optional<Path> stateHome,
        boolean all) {
    StartWorkerRequest(
            Optional<String> board,
            Optional<Path> workflow,
            Optional<Path> envPath,
            Optional<Path> appHome,
            Optional<Path> configDir,
            Optional<Path> workspaceRoot,
            Optional<Path> stateHome) {
        this(board, workflow, envPath, appHome, configDir, workspaceRoot, stateHome, false);
    }

    boolean plainStart() {
        return board.isEmpty() && workflow.isEmpty() && envPath.isEmpty();
    }
}
