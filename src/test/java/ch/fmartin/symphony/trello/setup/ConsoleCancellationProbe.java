package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

/// Publishes readiness only after the JVM shutdown hook can record native cancellation.
final class ConsoleCancellationProbe {
    private ConsoleCancellationProbe() {}

    public static void main(String[] arguments) throws Exception {
        Path ready = Path.of(arguments[0]);
        String identity = arguments[1] + "\n" + ProcessHandle.current().pid() + "\n";
        Path shutdown = shutdownPath(ready);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            try {
                publish(shutdown, identity);
            } catch (IOException failure) {
                throw new UncheckedIOException(
                        "cannot record console cancellation \"shutdown marker\": write the owned test evidence",
                        failure);
            }
        }));
        Files.writeString(Path.of(arguments[2]), "tutorial-waiting\n", StandardOpenOption.APPEND);
        publish(ready, identity);
        // Only native cancellation ends this child; a timed exit would manufacture a passing proof.
        new CountDownLatch(1).await();
    }

    static Path shutdownPath(Path ready) {
        return ready.resolveSibling(ready.getFileName() + ".shutdown");
    }

    private static void publish(Path destination, String identity) throws IOException {
        Path pending = destination.resolveSibling(destination.getFileName() + ".pending");
        Files.writeString(pending, identity);
        Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
    }
}
