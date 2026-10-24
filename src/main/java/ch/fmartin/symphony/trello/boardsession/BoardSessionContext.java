package ch.fmartin.symphony.trello.boardsession;

import org.jspecify.annotations.NullMarked;

/// Safe identity of the board and workflow an interactive Codex session manages. It holds display
/// values only: no credentials, credential paths, or other private local paths.
@NullMarked
public record BoardSessionContext(
        String boardName, String boardShortLink, String workflowFileName, BoardListRoles listRoles) {}
