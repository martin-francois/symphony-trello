package ch.fmartin.symphony.trello.testsupport;

import java.util.List;
import java.util.Map;

public final class JavaLauncherEnvironment {
    private static final List<String> LAUNCHER_VARIABLES =
            List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");

    private JavaLauncherEnvironment() {}

    public static void removeLauncherOptions(Map<String, String> environment) {
        if (environment != null) {
            for (String variable : LAUNCHER_VARIABLES) {
                environment.remove(variable);
            }
        }
    }
}
