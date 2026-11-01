package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

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

    private static final String PROBE_VARIABLE = "SYMPHONY_TRELLO_FIXTURE_PROBE";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    static Path create(Path directory, String body) throws IOException {
        Path appServer = directory.resolve("codex-app-server");
        Files.writeString(
                appServer,
                "#!/usr/bin/env bash\nset -euo pipefail\nif [ \"${" + PROBE_VARIABLE + ":-}\" = 1 ]; then exit 0; fi\n"
                        + body);
        appServer.toFile().setExecutable(true);
        awaitExecutable(appServer);
        return appServer;
    }

    /// Parallel tests fork while this file's write handle is open, and a forked child holds that handle
    /// until its own exec. Launching the file before then fails with "Text file busy" (ETXTBSY), which
    /// the resolver turns into its fallback defaults. Once the script has started cleanly, no child
    /// holds it open any more.
    private static void awaitExecutable(Path appServer) throws IOException {
        long deadline = System.nanoTime() + PROBE_TIMEOUT.toNanos();
        while (true) {
            try {
                var probe = new ProcessBuilder(appServer.toString());
                probe.environment().put(PROBE_VARIABLE, "1");
                probe.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
                if (probe.start().waitFor() == 0) {
                    return;
                }
                throw new IOException("Fixture probe failed for " + appServer);
            } catch (IOException e) {
                if (!e.getMessage().contains("Text file busy") || System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.onSpinWait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while probing " + appServer, e);
            }
        }
    }
}
