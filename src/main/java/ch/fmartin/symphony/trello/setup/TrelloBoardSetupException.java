package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.Optional;

public class TrelloBoardSetupException extends RuntimeException {
    private final String code;
    private final Integer statusCode;
    private final Path dotenvPath;
    private final String trelloApiKeyEnvironmentName;
    private final String trelloApiTokenEnvironmentName;
    private final TrelloCredentialSource trelloApiKeyCredentialSource;
    private final TrelloCredentialSource trelloApiTokenCredentialSource;

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
        this(code, message, statusCode, cause, dotenvPath, null, null, null, null);
    }

    private TrelloBoardSetupException(
            String code,
            String message,
            Integer statusCode,
            Throwable cause,
            Path dotenvPath,
            String trelloApiKeyEnvironmentName,
            String trelloApiTokenEnvironmentName,
            TrelloCredentialSource trelloApiKeyCredentialSource,
            TrelloCredentialSource trelloApiTokenCredentialSource) {
        super(message, cause);
        this.code = code;
        this.statusCode = statusCode;
        this.dotenvPath = dotenvPath;
        this.trelloApiKeyEnvironmentName = trelloApiKeyEnvironmentName;
        this.trelloApiTokenEnvironmentName = trelloApiTokenEnvironmentName;
        this.trelloApiKeyCredentialSource = trelloApiKeyCredentialSource;
        this.trelloApiTokenCredentialSource = trelloApiTokenCredentialSource;
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

    Optional<TrelloCredentialSource> trelloApiKeyCredentialSource() {
        return Optional.ofNullable(trelloApiKeyCredentialSource);
    }

    Optional<TrelloCredentialSource> trelloApiTokenCredentialSource() {
        return Optional.ofNullable(trelloApiTokenCredentialSource);
    }

    TrelloBoardSetupException withDotenvPath(Path path) {
        return new TrelloBoardSetupException(
                code,
                getMessage(),
                statusCode,
                getCause(),
                path,
                trelloApiKeyEnvironmentName,
                trelloApiTokenEnvironmentName,
                trelloApiKeyCredentialSource,
                trelloApiTokenCredentialSource);
    }

    TrelloBoardSetupException withTrelloCredentialEnvironmentNames(String apiKeyName, String apiTokenName) {
        return new TrelloBoardSetupException(
                code,
                getMessage(),
                statusCode,
                getCause(),
                dotenvPath,
                apiKeyName,
                apiTokenName,
                trelloApiKeyCredentialSource,
                trelloApiTokenCredentialSource);
    }

    TrelloBoardSetupException withTrelloCredentialSources(
            TrelloCredentialSource apiKeySource, TrelloCredentialSource apiTokenSource) {
        return new TrelloBoardSetupException(
                code,
                getMessage(),
                statusCode,
                getCause(),
                dotenvPath,
                trelloApiKeyEnvironmentName,
                trelloApiTokenEnvironmentName,
                apiKeySource,
                apiTokenSource);
    }

    enum TrelloCredentialSource {
        SHELL_ENVIRONMENT,
        DOTENV_FILE,
        WORKFLOW_CONFIG,
        MISSING
    }
}
