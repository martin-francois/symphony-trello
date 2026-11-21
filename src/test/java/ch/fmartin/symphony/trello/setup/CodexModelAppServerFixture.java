package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

final class CodexModelAppServerFixture {
    private CodexModelAppServerFixture() {}

    static Path createPaginated(Path directory, String firstPageData, String secondPageData) throws IOException {
        return create(directory, """
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
                """.replace("__FIRST_PAGE_DATA__", firstPageData.strip())
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
        awaitExecutable(appServer, PROBE_TIMEOUT);
        return appServer;
    }

    /// Parallel tests fork while this file's write handle is open, and a forked child holds that handle
    /// until its own exec. Launching the file before then fails with "Text file busy" (ETXTBSY), which
    /// the resolver turns into its fallback defaults. Once the script has started cleanly, no child
    /// holds it open any more.
    static void awaitExecutable(Path appServer, Duration timeout) throws IOException {
        awaitExecutable(appServer, timeout, () -> {});
    }

    /// The observer lets the fixture regression release its held writer only after a real ETXTBSY.
    static void awaitExecutable(Path appServer, Duration timeout, Runnable executableBusy) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                var probe = new ProcessBuilder(appServer.toString());
                probe.environment().put(PROBE_VARIABLE, "1");
                probe.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process process = probe.start();
                try {
                    Duration remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
                    if (!process.waitFor(remaining)) {
                        throw new IOException("cannot probe fixture: expected completion before the deadline");
                    }
                    if (process.exitValue() != 0) {
                        throw new IOException("cannot probe fixture: expected exit code zero");
                    }
                    return;
                } finally {
                    process.destroyForcibly();
                }
            } catch (IOException e) {
                if (e.getMessage() == null
                        || !e.getMessage().contains("Text file busy")
                        || System.nanoTime() - deadline >= 0) {
                    throw e;
                }
                executableBusy.run();
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("cannot probe fixture: interrupted before executable launch", e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("cannot probe fixture: interrupted while awaiting completion", e);
            }
        }
    }
}
