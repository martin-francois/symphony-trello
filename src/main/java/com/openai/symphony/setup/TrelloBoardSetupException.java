package com.openai.symphony.setup;

public class TrelloBoardSetupException extends RuntimeException {
    private final String code;
    private final Integer statusCode;

    public TrelloBoardSetupException(String code, String message) {
        this(code, message, null, null);
    }

    public TrelloBoardSetupException(String code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public TrelloBoardSetupException(String code, String message, int statusCode) {
        this(code, message, statusCode, null);
    }

    private TrelloBoardSetupException(String code, String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.statusCode = statusCode;
    }

    public String code() {
        return code;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
