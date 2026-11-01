package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.Base64;
import java.util.List;

/// Creates protected DACLs with the directories; inherited grants are never temporarily accepted.
final class WindowsPrivateDirectories {
    private WindowsPrivateDirectories() {}

    static void create(List<Path> paths, UserPrincipal owner) throws IOException {
        if (paths.isEmpty()) {
            return;
        }
        var request =
                new Request(owner.getName(), paths.stream().map(Path::toString).toList());
        String payload =
                Base64.getEncoder().encodeToString(CardHelperProtocol.json().writeValueAsBytes(request));
        try (var resource = WindowsPrivateDirectories.class.getResourceAsStream("/windows-private-directories.ps1")) {
            if (resource == null) {
                throw new IOException(
                        "cannot create card helper directories: reinstall the missing Windows security helper");
            }
            String script = new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("@REQUEST@", payload);
            CommandResult result = new ProcessCommandRunner()
                    .run(
                            "powershell.exe",
                            "-NoProfile",
                            "-NonInteractive",
                            "-EncodedCommand",
                            Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
            if (!result.success()) {
                throw new IOException("cannot create card helper directories: Windows security setup exited with code "
                        + result.exitCode() + "; check account permissions");
            }
        }
    }

    private record Request(String owner, List<String> paths) {}
}
