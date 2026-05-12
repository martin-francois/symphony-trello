package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Optional;

record WorkerStatusRequest(
        Optional<String> board,
        Optional<Path> workflow,
        Optional<Path> appHome,
        Optional<Path> configDir,
        Optional<Path> workspaceRoot,
        Optional<Path> stateHome) {}
