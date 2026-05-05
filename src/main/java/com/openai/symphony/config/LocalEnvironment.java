package com.openai.symphony.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class LocalEnvironment {
    private static final Path DEFAULT_DOTENV = Path.of(".env");

    private LocalEnvironment() {}

    public static Optional<String> get(String name) {
        return get(name, DEFAULT_DOTENV, System.getenv());
    }

    static Optional<String> get(String name, Path dotenv, Map<String, String> environment) {
        String value = environment.get(name);
        if (hasText(value)) {
            return Optional.of(value);
        }
        value = load(dotenv).get(name);
        return hasText(value) ? Optional.of(value) : Optional.empty();
    }

    static Map<String, String> load(Path dotenv) {
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
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private record Entry(String key, String value) {}

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
