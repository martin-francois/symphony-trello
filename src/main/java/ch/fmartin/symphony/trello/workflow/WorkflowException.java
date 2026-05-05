package ch.fmartin.symphony.trello.workflow;

public final class WorkflowException extends RuntimeException {
    private final String code;

    public WorkflowException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WorkflowException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
