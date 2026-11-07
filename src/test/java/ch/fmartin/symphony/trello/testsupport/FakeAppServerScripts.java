package ch.fmartin.symphony.trello.testsupport;

import java.io.File;
import java.nio.file.Path;

/// Keeps shell-based protocol fixtures consistent with the app server's profile acknowledgement.
public final class FakeAppServerScripts {
    private FakeAppServerScripts() {}

    public static String shellPath(Path path) {
        return "'" + path.toString().replace(File.separatorChar, '/').replace("'", "'\\''") + "'";
    }

    public static String command(Path executable, Path... arguments) {
        var result = new StringBuilder(shellPath(executable));
        for (Path argument : arguments) {
            result.append(' ').append(shellPath(argument));
        }
        return result.toString();
    }

    public static String acknowledgePermissions(String script) {
        return script.replace(
                        "while IFS= read -r line; do",
                        """
                while IFS= read -r line; do
                  permissions=$(printf '%s' "$line" | sed -n 's/.*"permissions":"\\([^"]*\\)".*/\\1/p')
                """)
                .replace(
                        "\"thread\":{\"id\":",
                        "\"activePermissionProfile\":{\"id\":\"'\"$permissions\"'\"},\"thread\":{\"id\":")
                .replace(
                        "\\\"thread\\\":{\\\"id\\\":",
                        "\\\"activePermissionProfile\\\":{\\\"id\\\":\\\"$permissions\\\"},\\\"thread\\\":{\\\"id\\\":");
    }
}
