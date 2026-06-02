package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

record ConnectedBoardManifest(List<ConnectedBoard> boards) {
    ConnectedBoardManifest {
        boards = boards == null ? List.of() : List.copyOf(boards);
    }

    ConnectedBoardManifest withBoard(ConnectedBoard board) {
        List<ConnectedBoard> updated = new ArrayList<>(boards.stream()
                .filter(existing -> !sameBoardOrWorkflow(existing, board))
                .toList());
        updated.add(board);
        return new ConnectedBoardManifest(updated);
    }

    List<ConnectedBoard> boardsReplacedBy(ConnectedBoard board) {
        return boards.stream()
                .filter(existing -> sameBoardOrWorkflow(existing, board))
                .toList();
    }

    ConnectedBoardManifest withoutBoard(String boardId) {
        return new ConnectedBoardManifest(boards.stream()
                .filter(board -> !board.boardId().equals(boardId))
                .toList());
    }

    Optional<ConnectedBoard> findByBoard(String selector) {
        return findAllByBoard(selector).stream().findFirst();
    }

    List<ConnectedBoard> findAllByBoard(String selector) {
        if (selector == null || selector.isBlank()) {
            return List.of();
        }
        String parsed = TrelloBoardIds.parse(selector);
        return boards.stream()
                .filter(board -> matchesBoard(board, selector, parsed))
                .toList();
    }

    private static boolean matchesBoard(ConnectedBoard board, String selector, String parsed) {
        String boardUrlKey = TrelloBoardIds.parse(board.boardUrl());
        return board.boardName().equalsIgnoreCase(selector)
                || equalsIgnoreCase(board.boardId(), selector)
                || equalsIgnoreCase(board.boardKey(), selector)
                || equalsIgnoreCase(boardUrlKey, selector)
                || equalsIgnoreCase(board.boardId(), parsed)
                || equalsIgnoreCase(board.boardKey(), parsed)
                || equalsIgnoreCase(boardUrlKey, parsed);
    }

    private static boolean equalsIgnoreCase(String actual, String expected) {
        return actual != null && expected != null && actual.equalsIgnoreCase(expected);
    }

    Optional<ConnectedBoard> findByWorkflow(Path workflowPath) {
        return boards.stream()
                .filter(board -> PathsEqual.samePath(board.workflowPath(), workflowPath))
                .findFirst();
    }

    private static boolean sameBoardOrWorkflow(ConnectedBoard first, ConnectedBoard second) {
        return first.boardId().equals(second.boardId())
                || PathsEqual.samePath(first.workflowPath(), second.workflowPath());
    }
}
