package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class CodexModelAppServerFixture {
    private CodexModelAppServerFixture() {}

    static Path create(Path directory, String body) throws IOException {
        Path appServer = directory.resolve("codex-app-server");
        Files.writeString(appServer, "#!/usr/bin/env bash\nset -euo pipefail\n" + body, StandardCharsets.UTF_8);
        appServer.toFile().setExecutable(true);
        return appServer;
    }
}
