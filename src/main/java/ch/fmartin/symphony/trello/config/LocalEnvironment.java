package ch.fmartin.symphony.trello.config;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_BYTE_ORDER_MARK;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
        return firstPresentValue(environment, names).or(() -> firstPresentValue(load(dotenv), names));
    }

    private static Optional<String> firstPresentValue(Map<String, String> values, String[] names) {
        return Arrays.stream(names)
                .map(values::get)
                .filter(LocalEnvironment::hasText)
                .findFirst();
    }

    public static Map<String, String> load(Path dotenv) {
        if (!Files.isRegularFile(dotenv)) {
            return Map.of();
        }
        try {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> lines = Files.readAllLines(dotenv);
            for (int index = 0; index < lines.size(); index++) {
                String rawLine = index == 0 ? stripLeadingByteOrderMark(lines.get(index)) : lines.get(index);
                parseLine(rawLine).ifPresent(entry -> values.put(entry.key(), entry.value()));
            }
            return Map.copyOf(values);
        } catch (IOException ignored) {
            return Map.of();
        }
    }

    /**
     * UTF-8 editors, notably on Windows, commonly write a byte order mark at the start of the
     * file. Exactly one leading mark is ignorable so the first key is not silently dropped.
     */
    private static String stripLeadingByteOrderMark(String firstLine) {
        return firstLine.startsWith(UNICODE_BYTE_ORDER_MARK) ? firstLine.substring(1) : firstLine;
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
        String value = parseValue(line.substring(separator + 1).strip());
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

    private static String parseValue(String raw) {
        if (raw.startsWith("\"")) {
            return parseQuoted(raw, '"', true).orElseGet(() -> unquote(raw));
        }
        if (raw.startsWith("'")) {
            return parseQuoted(raw, '\'', false).orElseGet(() -> unquote(raw));
        }
        return stripUnquotedTrailingComment(raw);
    }

    /**
     * Parses a quoted value and tolerates a trailing {@code # comment} after the closing quote.
     * Returns empty when the text after the closing quote is not a comment, so ambiguous
     * hand-written lines keep the whole-line interpretation instead of silently losing text.
     */
    private static Optional<String> parseQuoted(String raw, char quote, boolean unescape) {
        int closing = closingQuoteIndex(raw, quote, unescape);
        if (closing < 0) {
            return Optional.empty();
        }
        String rest = raw.substring(closing + 1).strip();
        if (!rest.isEmpty() && !rest.startsWith("#")) {
            return Optional.empty();
        }
        String inner = raw.substring(1, closing);
        return Optional.of(unescape ? unescapeDoubleQuoted(inner) : inner);
    }

    private static int closingQuoteIndex(String raw, char quote, boolean honorEscapes) {
        int index = 1;
        while (index < raw.length()) {
            char current = raw.charAt(index);
            if (honorEscapes && current == '\\' && index < raw.length() - 1) {
                index += 2;
                continue;
            }
            if (current == quote) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static String stripUnquotedTrailingComment(String value) {
        for (int index = 1; index < value.length(); index++) {
            if (value.charAt(index) == '#' && Character.isWhitespace(value.charAt(index - 1))) {
                return value.substring(0, index).strip();
            }
        }
        return value;
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
