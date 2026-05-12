package ch.fmartin.symphony.trello.setup;

record WorkflowValidation(boolean ok, String message) {
    static WorkflowValidation valid() {
        return new WorkflowValidation(true, "");
    }

    static WorkflowValidation warn(String message) {
        return new WorkflowValidation(false, message);
    }
}
