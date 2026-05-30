package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.ImportBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.NewBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.WorkspaceListRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class TrelloBoardSetupService {
    private final TrelloBoardSetup setup;
    private final LocalWorkerManager workerManager;
    private final Map<String, String> environment;

    TrelloBoardSetupService(TrelloBoardSetup setup) {
        this(setup, new LocalWorkerManager(System.getenv()), System.getenv());
    }

    TrelloBoardSetupService(TrelloBoardSetup setup, LocalWorkerManager workerManager, Map<String, String> environment) {
        this.setup = setup;
        this.workerManager = workerManager;
        this.environment = Map.copyOf(environment);
    }

    TrelloBoardSetup.NewBoardResult createRecommendedBoard(NewBoardRequest request) {
        return setup.createRecommendedBoard(request);
    }

    TrelloBoardSetup.NewBoardResult createRecommendedBoard(
            NewBoardRequest request, TrelloBoardSetupMain.BoardSetupOptions options) {
        return setup(options).createRecommendedBoard(request);
    }

    TrelloBoardSetup.ImportBoardResult importExistingBoard(ImportBoardRequest request) {
        return setup.importExistingBoard(request);
    }

    TrelloBoardSetup.ImportBoardResult importExistingBoard(
            ImportBoardRequest request, TrelloBoardSetupMain.BoardSetupOptions options) {
        return setup(options).importExistingBoard(request);
    }

    void preflightConnectedBoardManifest(Path manifestPath) {
        ConnectedBoardRepository boards = new ConnectedBoardRepository(manifestPath);
        try {
            boards.loadValidated();
            boards.validateWritable();
        } catch (IOException exception) {
            throw new TrelloBoardSetupException(
                    "setup_manifest_unavailable",
                    "Could not read or write the connected-board manifest. Check the config directory permissions.",
                    exception);
        }
    }

    void persistConnectedBoard(
            TrelloBoardSetup.NewBoardResult result,
            Path envPath,
            Path workspaceRoot,
            GitHubIntegration githubIntegration,
            Path manifestPath)
            throws IOException {
        persistConnectedBoard(ConnectedBoard.from(result, envPath, workspaceRoot, githubIntegration), manifestPath);
    }

    void persistConnectedBoard(
            TrelloBoardSetup.ImportBoardResult result,
            Path envPath,
            Path workspaceRoot,
            GitHubIntegration githubIntegration,
            Path manifestPath)
            throws IOException {
        persistConnectedBoard(ConnectedBoard.from(result, envPath, workspaceRoot, githubIntegration), manifestPath);
    }

    void listWorkspaces(WorkspaceListRequest request, PrintStream out) {
        printWorkspaces(out, setup.listWorkspaces(request));
    }

    private void persistConnectedBoard(ConnectedBoard board, Path manifestPath) throws IOException {
        ConnectedBoardRepository boards = new ConnectedBoardRepository(manifestPath);
        try {
            ConnectedBoardManifest manifest = boards.loadValidated();
            stopReplacedBoards(manifestPath, board.workspaceRoot(), manifest.boardsReplacedBy(board));
            boards.save(manifest.withBoard(board));
        } catch (IOException exception) {
            throw new TrelloBoardSetupException(
                    "setup_manifest_write_failed",
                    "Could not update the connected-board manifest. Check the config directory permissions.",
                    exception);
        }
    }

    private void stopReplacedBoards(Path manifestPath, Path workspaceRoot, List<ConnectedBoard> replacedBoards) {
        if (replacedBoards.isEmpty()) {
            return;
        }
        List<Path> stoppedWorkflowPaths = new ArrayList<>();
        LocalWorkerPaths paths = localWorkerPaths(manifestPath, workspaceRoot);
        for (ConnectedBoard board : replacedBoards) {
            if (stoppedWorkflowPaths.stream().anyMatch(stopped -> PathsEqual.samePath(stopped, board.workflowPath()))) {
                continue;
            }
            try {
                workerManager.stop(
                        paths, board, new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new TrelloBoardSetupException(
                        "setup_stop_failed",
                        "Could not stop Symphony for \"" + board.boardName() + "\": " + exception.getMessage(),
                        exception);
            }
            stoppedWorkflowPaths.add(board.workflowPath());
        }
    }

    private LocalWorkerPaths localWorkerPaths(Path manifestPath, Path workspaceRoot) {
        Path configDir = manifestPath.toAbsolutePath().normalize().getParent();
        return LocalWorkerPaths.from(
                Optional.empty(),
                Optional.ofNullable(configDir),
                Optional.of(workspaceRoot),
                Optional.empty(),
                environment);
    }

    private TrelloBoardSetup setup(TrelloBoardSetupMain.BoardSetupOptions options) {
        if (!options.hasExplicitCodexModelRequest()) {
            return setup;
        }
        return setup.withCodexModelOverrides(
                setup.resolvedCodexModelSelectionDefaults(), options.codexModel(), options.codexReasoningEffort());
    }

    private static void printWorkspaces(PrintStream out, List<TrelloBoardSetup.WorkspaceInfo> workspaces) {
        if (workspaces.isEmpty()) {
            out.println("No Trello workspaces found for this token.");
            return;
        }
        out.println("Trello workspaces:");
        for (TrelloBoardSetup.WorkspaceInfo workspace : workspaces) {
            out.println("  %s  %s".formatted(workspace.id(), workspace.displayName()));
        }
    }
}
