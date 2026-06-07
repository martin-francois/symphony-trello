package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.domain.Card;

record WorkerExitState(boolean retry, boolean cleanupWorkspace, Card card) {
    static WorkerExitState retry(Card card) {
        return new WorkerExitState(true, false, card);
    }

    static WorkerExitState complete(boolean cleanupWorkspace, Card card) {
        return new WorkerExitState(false, cleanupWorkspace, card);
    }
}
