package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class CodexModelAppServerFixture {
    private CodexModelAppServerFixture() {}

    static Path createPaginated(Path directory, String firstPageData, String secondPageData) throws IOException {
        return create(
                directory,
                """
                while IFS= read -r line; do
                  case "$line" in
                    *'"method":"initialize"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
                      ;;
                    *'"method":"model/list"'*'"cursor":"page-2"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":3,"result":{"data":__SECOND_PAGE_DATA__}}'
                      ;;
                    *'"method":"model/list"'*)
                      printf '%s\\n' '{"jsonrpc":"2.0","id":2,"result":{"data":__FIRST_PAGE_DATA__,"nextCursor":"page-2"}}'
                      ;;
                  esac
                done
                """
                        .replace("__FIRST_PAGE_DATA__", firstPageData.strip())
                        .replace("__SECOND_PAGE_DATA__", secondPageData.strip()));
    }

    static Path create(Path directory, String body) throws IOException {
        Path appServer = directory.resolve("codex-app-server");
        Files.writeString(appServer, "#!/usr/bin/env bash\nset -euo pipefail\n" + body, StandardCharsets.UTF_8);
        appServer.toFile().setExecutable(true);
        return appServer;
    }
}
