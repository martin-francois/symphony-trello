package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class TrelloCredentialStore {
    private final Map<String, String> environment;

    TrelloCredentialStore(Map<String, String> environment) {
        this.environment = environment;
    }

    CredentialSelection loadOrPrompt(LocalSetup.Options options, Terminal terminal) throws IOException {
        return loadOrPrompt(options, options.envPath(), terminal);
    }

    CredentialSelection loadOrPrompt(LocalSetup.Options options, Path envPath, Terminal terminal) throws IOException {
        Map<String, String> dotenv = LocalEnvironment.load(envPath);
        CredentialValue key =
                credentialValue(options.apiKey(), envValue("TRELLO_API_KEY"), dotenv.get("TRELLO_API_KEY"));
        CredentialValue token =
                credentialValue(options.apiToken(), envValue("TRELLO_API_TOKEN"), dotenv.get("TRELLO_API_TOKEN"));
        if ((blank(key) || blank(token)) && options.nonInteractive()) {
            throw new TrelloBoardSetupException(
                    "setup_missing_trello_credentials",
                    "TRELLO_API_KEY and TRELLO_API_TOKEN are required in non-interactive setup.");
        }
        if (blank(key)) {
            terminal.info("");
            terminal.info("Trello access");
            terminal.info("Open Trello Power-Ups admin to create an API key for your Workspace:");
            terminal.info("  https://trello.com/power-ups/admin");
            terminal.info("Detailed step-by-step guide:");
            terminal.info(
                    "  https://github.com/martin-francois/symphony-trello#one-time-browser-setup-workspace-api-key-token");
            key = CredentialValue.direct(terminal.readLine("Trello API key: "));
        }
        if (blank(token)) {
            char[] password = terminal.readSecret("Trello token: ");
            token = CredentialValue.direct(password == null ? null : new String(password));
        }
        return new CredentialSelection(key, token);
    }

    void write(CredentialSelection credentials, Path envPath, Terminal terminal) throws IOException {
        write(credentials, envPath, terminal, true);
    }

    void write(CredentialSelection credentials, Path envPath, Terminal terminal, boolean validatePath)
            throws IOException {
        if (validatePath) {
            validateEnvPath(envPath);
        }
        writeEnvFile(envPath, updatedEnvLines(credentials, envPath));
        terminal.info("");
        terminal.info("Saving Trello credentials...");
        terminal.info("  OK  Credentials saved: " + envPath);
        terminal.info("  OK  Trello API key: " + redact(credentials.apiKey()));
        terminal.info("  OK  Trello token: " + redact(credentials.apiToken()));
    }

    CredentialSelection loadExisting(LocalSetup.Options options, Path envPath) {
        Map<String, String> dotenv = LocalEnvironment.load(envPath);
        CredentialValue key =
                credentialValue(options.apiKey(), envValue("TRELLO_API_KEY"), dotenv.get("TRELLO_API_KEY"));
        CredentialValue token =
                credentialValue(options.apiToken(), envValue("TRELLO_API_TOKEN"), dotenv.get("TRELLO_API_TOKEN"));
        return new CredentialSelection(key, token);
    }

    private Optional<String> envValue(String name) {
        String value = environment.get(name);
        return blank(value) ? Optional.empty() : Optional.of(value);
    }

    private static CredentialValue credentialValue(
            Optional<String> directValue, Optional<String> environmentValue, String dotenvValue) {
        return directValue
                .map(CredentialValue::direct)
                .or(() -> environmentValue.map(CredentialValue::environment))
                .orElseGet(() -> CredentialValue.dotenv(dotenvValue));
    }

    static void validateWritableEnvUpdate(CredentialSelection credentials, Path envPath, boolean validatePath)
            throws IOException {
        if (validatePath) {
            validateEnvPath(envPath);
        }
        validateWritableEnvPath(envPath);
        updatedEnvLines(credentials, envPath);
        Path absolute = envPath.toAbsolutePath().normalize();
        if (Files.isRegularFile(absolute)) {
            return;
        }
        Path parent = absolute.getParent();
        Path probe = parent == null
                ? Files.createTempFile(".env-write-probe-", ".tmp")
                : Files.createTempFile(parent, ".env-write-probe-", ".tmp");
        try {
            writeEnvFile(probe, List.of("# Symphony for Trello dotenv write probe"));
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static List<String> updatedEnvLines(CredentialSelection credentials, Path envPath) throws IOException {
        List<String> lines =
                Files.isRegularFile(envPath) ? Files.readAllLines(envPath, StandardCharsets.UTF_8) : new ArrayList<>();
        if (credentials.persistApiKey()) {
            lines = upsertEnv(lines, "TRELLO_API_KEY", credentials.apiKey());
        }
        if (credentials.persistApiToken()) {
            lines = upsertEnv(lines, "TRELLO_API_TOKEN", credentials.apiToken());
        }
        return lines;
    }

    private static void writeEnvFile(Path envPath, List<String> lines) throws IOException {
        Path parent = envPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(envPath)) {
            Files.write(envPath, lines, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            if (supportsPosixFilePermissions(
                    parent == null ? envPath.toAbsolutePath().normalize() : parent)) {
                Files.createFile(envPath, PosixFilePermissions.asFileAttribute(ownerOnlyEnvPermissions()));
            } else {
                Files.createFile(envPath);
            }
            Files.write(envPath, lines, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        }
        secureEnvPermissions(envPath);
    }

    static void validateEnvPath(Path envPath) {
        Path fileName = envPath.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        if (".env.example".equals(name)
                || ".env.template".equals(name)
                || (!".env".equals(name) && !name.startsWith(".env."))) {
            throw new TrelloBoardSetupException(
                    "setup_env_path_not_ignored",
                    "--env must point to an ignored dotenv file named .env or .env.NAME, not a tracked template.");
        }
    }

    static void validateWritableEnvPath(Path envPath) throws IOException {
        Path absolute = envPath.toAbsolutePath().normalize();
        if (Files.exists(absolute) && !Files.isRegularFile(absolute)) {
            throw new IOException("Selected dotenv path is not a regular file.");
        }
        if (Files.isRegularFile(absolute)) {
            try (FileChannel ignored = FileChannel.open(absolute, StandardOpenOption.WRITE)) {
                return;
            }
        }
        Path parent = absolute.getParent();
        if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
            throw new IOException("Selected dotenv parent is not a directory.");
        }
        if (parent != null) {
            Files.createDirectories(parent);
            Path probe = Files.createTempFile(parent, ".env-write-probe-", ".tmp");
            Files.deleteIfExists(probe);
        }
    }

    private static void secureEnvPermissions(Path envPath) throws IOException {
        if (!supportsPosixFilePermissions(envPath)) {
            return;
        }
        Files.setPosixFilePermissions(envPath, ownerOnlyEnvPermissions());
    }

    private static boolean supportsPosixFilePermissions(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private static Set<PosixFilePermission> ownerOnlyEnvPermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    static List<String> upsertEnv(List<String> lines, String key, String value) {
        List<String> updated = new ArrayList<>();
        boolean replaced = false;
        for (String line : lines) {
            if (definesEnvKey(line, key)) {
                updated.add(key + "=" + dotenvValue(value));
                replaced = true;
            } else {
                updated.add(line);
            }
        }
        if (!replaced) {
            updated.add(key + "=" + dotenvValue(value));
        }
        return updated;
    }

    static String dotenvValue(String value) {
        if (value.contains("\n") || value.contains("\r")) {
            throw new TrelloBoardSetupException("setup_env_value_multiline", "Dotenv values cannot contain newlines.");
        }
        if (Pattern.matches("[A-Za-z0-9_./:@%+=,-]+", value)) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> escaped.append(c);
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private static boolean definesEnvKey(String line, String expectedKey) {
        String candidate = line.strip();
        if (candidate.startsWith("export ")) {
            candidate = candidate.substring("export ".length()).stripLeading();
        }
        int separator = candidate.indexOf('=');
        if (separator <= 0) {
            return false;
        }
        return candidate.substring(0, separator).strip().equals(expectedKey);
    }

    static String redact(String value) {
        if (blank(value)) {
            return "";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, Math.min(4, value.length())) + "*".repeat(Math.max(4, value.length() - 4));
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static boolean blank(CredentialValue value) {
        return value == null || blank(value.value());
    }

    record CredentialSelection(CredentialValue apiKeyValue, CredentialValue apiTokenValue) {
        TrelloCredentials credentials() {
            return new TrelloCredentials(apiKeyValue.value(), apiTokenValue.value());
        }

        String apiKey() {
            return apiKeyValue.value();
        }

        String apiToken() {
            return apiTokenValue.value();
        }

        boolean persist() {
            return persistApiKey() || persistApiToken();
        }

        boolean persistApiKey() {
            return apiKeyValue.persist();
        }

        boolean persistApiToken() {
            return apiTokenValue.persist();
        }

        String sourceDescription(Path envPath) {
            if (apiKeyValue.source() == CredentialSource.ENVIRONMENT
                    && apiTokenValue.source() == CredentialSource.ENVIRONMENT) {
                return "environment variables";
            }
            if (apiKeyValue.source() == CredentialSource.DOTENV && apiTokenValue.source() == CredentialSource.DOTENV) {
                return envPath.toString();
            }
            return "environment variables and " + envPath;
        }
    }

    record CredentialValue(String value, boolean persist, CredentialSource source) {
        static CredentialValue direct(String value) {
            return new CredentialValue(value, true, CredentialSource.DIRECT);
        }

        static CredentialValue environment(String value) {
            return new CredentialValue(value, false, CredentialSource.ENVIRONMENT);
        }

        static CredentialValue dotenv(String value) {
            return new CredentialValue(value, false, CredentialSource.DOTENV);
        }
    }

    enum CredentialSource {
        DIRECT,
        ENVIRONMENT,
        DOTENV
    }
}
