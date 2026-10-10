package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Connected-board selector rules shared by every command that accepts `--board` or `--workflow`,
/// so lifecycle commands and the interactive Codex session report the same not-found, ambiguous,
/// and conflicting-selector errors.
final class ConnectedBoardSelection {
    private ConnectedBoardSelection() {}

    static void rejectConflictingSelectors(Optional<String> board, Optional<Path> workflow) {
        if (board.isPresent() && workflow.isPresent()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_selection_conflict", "--board and --workflow cannot be used together.");
        }
    }

    static ConnectedBoard byBoard(ConnectedBoardManifest manifest, String selector) {
        List<ConnectedBoard> matches = manifest.findAllByBoard(selector);
        if (matches.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_not_found", "No connected Trello board matches \"" + selector + "\".");
        }
        if (matches.size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_ambiguous",
                    "Multiple connected boards match --board. Re-run with a board id, short link, or --workflow.");
        }
        return matches.getFirst();
    }

    /// Returns the connected-board row for a normalized workflow path, or empty when the workflow is
    /// not connected. Several rows for one workflow are a damaged manifest, not a choice.
    static Optional<ConnectedBoard> byWorkflow(ConnectedBoardManifest manifest, Path normalizedWorkflowPath) {
        List<ConnectedBoard> matches = manifest.findAllByWorkflow(normalizedWorkflowPath);
        if (matches.size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_worker_workflow_ambiguous",
                    "Multiple connected-board rows reference --workflow. Repair "
                            + ConnectedBoardManifest.FILE_NAME
                            + ", then rerun the command.");
        }
        return matches.stream().findAny();
    }
}
