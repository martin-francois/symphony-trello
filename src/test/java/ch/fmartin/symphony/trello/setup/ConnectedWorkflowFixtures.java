package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Registers isolated packaged-process fixtures without making Trello setup requests.
public final class ConnectedWorkflowFixtures {
    private ConnectedWorkflowFixtures() {}

    public static void register(Path workflow, int port) throws IOException {
        Path managed = workflow.toAbsolutePath().normalize();
        Path configDir = managed.getParent();
        var config = new ConfigResolver().resolve(new WorkflowLoader().load(managed));
        String board = config.tracker().boardId();
        var connection = new ConnectedBoard(
                board,
                board,
                "Fixture board",
                "https://trello.com/b/" + board,
                managed,
                configDir.resolve(".env"),
                config.workspace().root(),
                port,
                false,
                List.of(),
                false);
        var repository = new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME));
        try (var _ = repository.lock()) {
            repository.save(repository.loadForLifecycle().withBoard(connection));
        }
    }
}
