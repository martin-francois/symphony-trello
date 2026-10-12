package ch.fmartin.symphony.trello.process;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// Environment variables together with whether they belong to a Windows host, so lookups follow
/// that platform's rules for variable names.
public record PlatformEnvironment(Map<String, String> variables, boolean windows) {
    public PlatformEnvironment {
        variables = Map.copyOf(variables);
    }

    public static PlatformEnvironment of(Map<String, String> variables, String osName) {
        return new PlatformEnvironment(variables, isWindows(osName));
    }

    public static PlatformEnvironment current() {
        return of(System.getenv(), System.getProperty("os.name"));
    }

    public static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /// Returns the variable's value. Windows variable names are case-insensitive, and `PATH` is
    /// often spelled `Path` there, so on Windows a differently cased name also matches.
    public Optional<String> value(String name) {
        return Optional.ofNullable(variables.get(name))
                .or(() -> windows
                        ? variables.entrySet().stream()
                                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                                .map(Map.Entry::getValue)
                                .findAny()
                        : Optional.empty());
    }
}
