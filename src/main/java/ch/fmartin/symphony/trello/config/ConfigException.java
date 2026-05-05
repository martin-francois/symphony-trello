package ch.fmartin.symphony.trello.config;

public final class ConfigException extends RuntimeException {
    private final String code;

    public ConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
