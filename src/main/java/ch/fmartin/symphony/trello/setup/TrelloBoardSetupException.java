package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Optional;

public class TrelloBoardSetupException extends RuntimeException {
    private final String code;
    private final Integer statusCode;
    private final Path dotenvPath;
    private final String trelloApiKeyEnvironmentName;
    private final String trelloApiTokenEnvironmentName;

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
        this(code, message, statusCode, cause, null);
    }

    private TrelloBoardSetupException(
            String code, String message, Integer statusCode, Throwable cause, Path dotenvPath) {
        this(code, message, statusCode, cause, dotenvPath, null, null);
    }

    private TrelloBoardSetupException(
            String code,
            String message,
            Integer statusCode,
            Throwable cause,
            Path dotenvPath,
            String trelloApiKeyEnvironmentName,
            String trelloApiTokenEnvironmentName) {
        super(message, cause);
        this.code = code;
        this.statusCode = statusCode;
        this.dotenvPath = dotenvPath;
        this.trelloApiKeyEnvironmentName = trelloApiKeyEnvironmentName;
        this.trelloApiTokenEnvironmentName = trelloApiTokenEnvironmentName;
    }

    public String code() {
        return code;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public Optional<Path> dotenvPath() {
        return Optional.ofNullable(dotenvPath);
    }

    public Optional<String> trelloApiKeyEnvironmentName() {
        return Optional.ofNullable(trelloApiKeyEnvironmentName);
    }

    public Optional<String> trelloApiTokenEnvironmentName() {
        return Optional.ofNullable(trelloApiTokenEnvironmentName);
    }

    TrelloBoardSetupException withDotenvPath(Path path) {
        return new TrelloBoardSetupException(
                code,
                getMessage(),
                statusCode,
                getCause(),
                path,
                trelloApiKeyEnvironmentName,
                trelloApiTokenEnvironmentName);
    }

    TrelloBoardSetupException withTrelloCredentialEnvironmentNames(String apiKeyName, String apiTokenName) {
        return new TrelloBoardSetupException(
                code, getMessage(), statusCode, getCause(), dotenvPath, apiKeyName, apiTokenName);
    }
}
