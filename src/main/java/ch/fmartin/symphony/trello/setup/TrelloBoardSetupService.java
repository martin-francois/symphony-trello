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

    void preflightWorkflowWrite(Path workflowPath, boolean force) {
        setup.preflightWorkflowWrite(workflowPath, force);
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

    void preflightRequestedServerPort(Integer requestedPort, Path workflowPath, boolean force, Path manifestPath) {
        if (requestedPort == null || requestedPort == 0) {
            return;
        }

        ConnectedBoardManifest manifest;
        try {
            manifest = new ConnectedBoardRepository(manifestPath).loadValidated();
        } catch (IOException exception) {
            throw new TrelloBoardSetupException(
                    "setup_manifest_unavailable",
                    "Could not read or write the connected-board manifest. Check the config directory permissions.",
                    exception);
        }
        Optional<ConnectedBoard> replaceableBoard = force
                ? manifest.boards().stream()
                        .filter(board -> board.serverPort() == requestedPort)
                        .filter(board -> PathsEqual.samePath(board.workflowPath(), workflowPath))
                        .findAny()
                : Optional.empty();
        boolean reservedByAnotherWorkflow = manifest.boards().stream()
                .filter(board -> board.serverPort() == requestedPort)
                .anyMatch(board -> !sameConnectedBoard(replaceableBoard, board));
        if (reservedByAnotherWorkflow) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already reserved by another connected workflow.".formatted(requestedPort));
        }
        if (LocalHealthChecker.portAcceptsConnections(requestedPort)
                && !canReuseLivePortForForcedWorkflowUpdate(manifestPath, replaceableBoard)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already in use on 127.0.0.1.".formatted(requestedPort));
        }
    }

    private boolean canReuseLivePortForForcedWorkflowUpdate(
            Path manifestPath, Optional<ConnectedBoard> replaceableBoard) {
        return replaceableBoard
                .map(board -> canStopRunningWorker(manifestPath, board))
                .orElse(false);
    }

    private static boolean sameConnectedBoard(Optional<ConnectedBoard> candidate, ConnectedBoard board) {
        return candidate
                .map(replaceable -> replaceable.boardId().equals(board.boardId()))
                .orElse(false);
    }

    private boolean canStopRunningWorker(Path manifestPath, ConnectedBoard board) {
        try {
            return workerManager.canStopRunningWorker(localWorkerPaths(manifestPath, board.workspaceRoot()), board);
        } catch (IOException exception) {
            return false;
        }
    }

    void persistConnectedBoard(
            TrelloBoardSetup.NewBoardResult result,
            Path envPath,
            Path workspaceRoot,
            GitHubIntegration githubIntegration,
            Path manifestPath,
            PrintStream out)
            throws IOException {
        persistConnectedBoard(
                ConnectedBoard.from(result, envPath, workspaceRoot, githubIntegration), manifestPath, out);
    }

    void persistConnectedBoard(
            TrelloBoardSetup.ImportBoardResult result,
            Path envPath,
            Path workspaceRoot,
            GitHubIntegration githubIntegration,
            Path manifestPath,
            PrintStream out)
            throws IOException {
        persistConnectedBoard(
                ConnectedBoard.from(result, envPath, workspaceRoot, githubIntegration), manifestPath, out);
    }

    Map<String, String> environment() {
        return environment;
    }

    void listWorkspaces(WorkspaceListRequest request, PrintStream out) {
        printWorkspaces(out, setup.listWorkspaces(request));
    }

    private void persistConnectedBoard(ConnectedBoard board, Path manifestPath, PrintStream out) throws IOException {
        boolean restartReplacedWorker;
        ConnectedBoardRepository boards = new ConnectedBoardRepository(manifestPath);
        try {
            ConnectedBoardManifest manifest = boards.loadValidated();
            List<ConnectedBoard> replacedBoards = manifest.boardsReplacedBy(board);
            restartReplacedWorker = replacedBoards.stream()
                    .anyMatch(replacedBoard -> canStopRunningWorker(manifestPath, replacedBoard));
            stopReplacedBoards(manifestPath, board.workspaceRoot(), replacedBoards);
            boards.save(manifest.withBoard(board));
        } catch (IOException exception) {
            throw new TrelloBoardSetupException(
                    "setup_manifest_write_failed",
                    "Could not update the connected-board manifest. Check the config directory permissions.",
                    exception);
        }
        if (restartReplacedWorker) {
            restartUpdatedBoardWorker(board, manifestPath, out);
        }
    }

    private void restartUpdatedBoardWorker(ConnectedBoard board, Path manifestPath, PrintStream out) {
        out.println("This update stopped the running worker for \"" + board.boardName()
                + "\". Restarting it with the updated workflow.");
        LocalWorkerPaths paths = localWorkerPaths(manifestPath, board.workspaceRoot());
        Path envPath = (board.envPath() == null ? paths.defaultEnvPath() : board.envPath())
                .toAbsolutePath()
                .normalize();
        try {
            workerManager.start(paths, board, envPath, out);
        } catch (IOException | TrelloBoardSetupException exception) {
            out.println("Could not restart the worker for \"" + board.boardName() + "\": " + exception.getMessage());
            out.println("Start it again with the start command shown under Next.");
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
