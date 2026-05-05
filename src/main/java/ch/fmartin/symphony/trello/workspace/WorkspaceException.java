package ch.fmartin.symphony.trello.workspace;

public final class WorkspaceException extends RuntimeException {
    private final String code;

    public WorkspaceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WorkspaceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
