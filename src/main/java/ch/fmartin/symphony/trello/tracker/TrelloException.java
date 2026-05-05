package ch.fmartin.symphony.trello.tracker;

public final class TrelloException extends RuntimeException {
    private final String code;
    private final int statusCode;

    public TrelloException(String code, String message) {
        this(code, message, -1, null);
    }

    public TrelloException(String code, String message, Throwable cause) {
        this(code, message, -1, cause);
    }

    public TrelloException(String code, String message, int statusCode) {
        this(code, message, statusCode, null);
    }

    private TrelloException(String code, String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.statusCode = statusCode;
    }

    public String code() {
        return code;
    }

    public int statusCode() {
        return statusCode;
    }
}
