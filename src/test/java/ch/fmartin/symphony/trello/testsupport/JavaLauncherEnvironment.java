package ch.fmartin.symphony.trello.testsupport;

import java.util.List;
import java.util.Map;

/// Clears the variables that make a child JVM print `Picked up ...` on stderr, which breaks tests
/// that read that stream.
public final class JavaLauncherEnvironment {
    private static final List<String> LAUNCHER_VARIABLES =
            List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");

    private JavaLauncherEnvironment() {}

    public static void removeLauncherOptions(Map<String, String> environment) {
        for (String variable : LAUNCHER_VARIABLES) {
            environment.remove(variable);
        }
    }
}
