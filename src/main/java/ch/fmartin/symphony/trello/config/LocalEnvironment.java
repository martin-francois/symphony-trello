package ch.fmartin.symphony.trello.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class LocalEnvironment {
    private static final Path DEFAULT_DOTENV = Path.of(".env");
    private static final String DOTENV_PATH_ENV = "SYMPHONY_TRELLO_DOTENV";

    private LocalEnvironment() {}

    public static Optional<String> get(String name) {
        Map<String, String> environment = System.getenv();
        return get(name, defaultDotenv(environment), environment);
    }

    public static Optional<String> get(String name, Path dotenv) {
        return get(name, dotenv, System.getenv());
    }

    static Optional<String> get(String name, Path dotenv, Map<String, String> environment) {
        String value = environment.get(name);
        if (hasText(value)) {
            return Optional.of(value);
        }
        value = load(dotenv).get(name);
        return hasText(value) ? Optional.of(value) : Optional.empty();
    }

    public static Optional<String> firstPresent(String... names) {
        Map<String, String> environment = System.getenv();
        return firstPresent(defaultDotenv(environment), environment, names);
    }

    public static Optional<String> firstPresent(Path dotenv, Map<String, String> environment, String... names) {
        for (String name : names) {
            String value = environment.get(name);
            if (hasText(value)) {
                return Optional.of(value);
            }
        }
        Map<String, String> dotenvValues = load(dotenv);
        for (String name : names) {
            String value = dotenvValues.get(name);
            if (hasText(value)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Map<String, String> load(Path dotenv) {
        if (!Files.isRegularFile(dotenv)) {
            return Map.of();
        }
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String rawLine : Files.readAllLines(dotenv)) {
                parseLine(rawLine).ifPresent(entry -> values.put(entry.key(), entry.value()));
            }
            return Map.copyOf(values);
        } catch (IOException ignored) {
            return Map.of();
        }
    }

    public static Path defaultDotenv(Map<String, String> environment) {
        String configured = environment.get(DOTENV_PATH_ENV);
        return hasText(configured) ? Path.of(configured) : DEFAULT_DOTENV;
    }

    public static Optional<Path> configuredDotenv() {
        String configured = System.getenv().get(DOTENV_PATH_ENV);
        return hasText(configured) ? Optional.of(Path.of(configured)) : Optional.empty();
    }

    public static Path defaultDotenv() {
        return defaultDotenv(System.getenv());
    }

    private static Optional<Entry> parseLine(String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return Optional.empty();
        }
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).stripLeading();
        }
        int separator = line.indexOf('=');
        if (separator <= 0) {
            return Optional.empty();
        }
        String key = line.substring(0, separator).strip();
        if (!validKey(key)) {
            return Optional.empty();
        }
        String value = unquote(line.substring(separator + 1).strip());
        return Optional.of(new Entry(key, value));
    }

    private static boolean validKey(String key) {
        if (key.isEmpty() || (!Character.isLetter(key.charAt(0)) && key.charAt(0) != '_')) {
            return false;
        }
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if (first == '"' && last == '"') {
                return unescapeDoubleQuoted(value.substring(1, value.length() - 1));
            }
            if (first == '\'' && last == '\'') {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String unescapeDoubleQuoted(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current != '\\' || index == value.length() - 1) {
                unescaped.append(current);
                index++;
                continue;
            }
            char escaped = value.charAt(index + 1);
            switch (escaped) {
                case '"' -> unescaped.append('"');
                case '\\' -> unescaped.append('\\');
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                default -> unescaped.append('\\').append(escaped);
            }
            index += 2;
        }
        return unescaped.toString();
    }

    private record Entry(String key, String value) {}

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
