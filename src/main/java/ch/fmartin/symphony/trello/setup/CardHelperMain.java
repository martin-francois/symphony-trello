package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.CliExitCodes;
import java.io.IOException;
import java.nio.file.Path;

/// Trusted process entry point. The launcher supplies fixed installation paths; requests never
/// choose those paths or an execution mode.
public final class CardHelperMain {
    private CardHelperMain() {}

    public static void main(String... arguments) {
        if (arguments.length != 2) {
            System.err.println("cannot start card helper: expected config and helper directories");
            System.exit(CliExitCodes.SETUP_FAILURE);
            return;
        }
        var environment = System.getenv();
        var commands = new BoardCardCommands(new LocalWorkerManager(environment), environment);
        try (var server =
                new CardHelperServer(Path.of(arguments[0]), new CardHelperFiles(Path.of(arguments[1])), commands)) {
            var shutdown = new Thread(() -> closeForShutdown(server), "card-helper-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                server.serve();
            } finally {
                removeShutdownHook(shutdown);
            }
        } catch (IOException | RuntimeException failure) {
            // Credentials and parser excerpts stay out of the helper's persistent logs.
            System.err.println(
                    "cannot run card helper: run diagnostics from a trusted terminal and restart the helper");
            System.exit(CliExitCodes.SETUP_FAILURE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeForShutdown(CardHelperServer server) {
        try {
            server.close();
        } catch (IOException failure) {
            System.err.println("cannot close card helper: inspect the board before retrying any pending operation");
        }
    }

    private static void removeShutdownHook(Thread shutdown) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (IllegalStateException ignored) {
            // JVM shutdown already owns and runs this hook.
            return;
        }
    }
}
