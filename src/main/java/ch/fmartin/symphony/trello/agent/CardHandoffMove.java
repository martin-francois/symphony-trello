package ch.fmartin.symphony.trello.agent;

/// Identity of an authorized, resolved current-card move, registered before its Trello request.
public record CardHandoffMove(
        String operationId,
        String workerIdentity,
        String threadId,
        String turnId,
        String cardId,
        String boardId,
        String listId) {}
