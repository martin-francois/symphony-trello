package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.process.PlatformEnvironment;
import com.google.common.base.Splitter;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/// Trusted lifecycle operations. Card requests have no route to these methods.
final class CardHelperManager {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(5);
    private static final String HELPER_MARKER = "-Dsymphony.trello.card_helper_root=";

    private final ManagedProcessPlatform platform;
    private final Duration startTimeout;

    CardHelperManager() {
        this(
                PlatformEnvironment.current().windows()
                        ? new WindowsManagedProcessPlatform()
                        : new PosixManagedProcessPlatform());
    }

    CardHelperManager(ManagedProcessPlatform platform) {
        this(platform, START_TIMEOUT);
    }

    CardHelperManager(ManagedProcessPlatform platform, Duration startTimeout) {
        this.platform = platform;
        this.startTimeout = startTimeout;
    }

    void startAll(LocalWorkerPaths paths, PrintStream out) throws IOException {
        for (LocalWorkerPaths installation : CardHelperInstallations.find(paths)) {
            start(installation, out);
        }
    }

    void stopAll(LocalWorkerPaths paths, PrintStream out) throws IOException {
        for (LocalWorkerPaths installation : CardHelperInstallations.find(paths)) {
            stop(installation, out);
        }
    }

    void start(LocalWorkerPaths paths, PrintStream out) throws IOException {
        CardHelperFiles files = CardHelperFiles.from(paths);
        files.initialize(paths.configDir());
        CardHelperInstallations.save(paths, files);
        if (running(files).isPresent()) {
            out.println("Card helper is already running.");
            return;
        }
        try (var watcher = files.root().getFileSystem().newWatchService()) {
            files.root().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            ManagedProcessHandle started = platform.start(
                    command(paths, files),
                    paths.appHome(),
                    Map.of(
                            "SYMPHONY_TRELLO_CONFIG_DIR", paths.configDir().toString(),
                            "SYMPHONY_TRELLO_MANIFEST", paths.manifestPath().toString(),
                            "SYMPHONY_TRELLO_STATE_HOME", paths.stateHome().toString(),
                            "CLASSPATH", applicationClasspath()),
                    files.privateState().resolve("stdout.log"),
                    files.privateState().resolve("stderr.log"));
            try {
                long deadline = System.nanoTime() + startTimeout.toNanos();
                while (running(files).isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new IOException(
                                "cannot start card helper: inspect the helper logs from a trusted terminal");
                    }
                    var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                    if (key != null) {
                        key.pollEvents();
                        if (!key.reset()) {
                            throw new IOException("cannot start card helper: restore the helper directory and retry");
                        }
                    }
                }
                out.println("Started card helper.");
            } catch (IOException | RuntimeException | InterruptedException failure) {
                try {
                    if (!platform.stop(started.pid(), STOP_TIMEOUT, KILL_TIMEOUT)) {
                        failure.addSuppressed(new IOException(
                                "cannot stop unready card helper: inspect the helper process from a trusted terminal"));
                    }
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("cannot wait for card helper: check its status from a trusted terminal", interrupted);
        }
    }

    void stop(LocalWorkerPaths paths, PrintStream out) throws IOException {
        CardHelperFiles files = CardHelperFiles.from(paths);
        Optional<CardHelperProtocol.Ready> ready = running(files);
        if (ready.isEmpty()) {
            out.println("Card helper is stopped.");
            return;
        }
        if (!platform.stop(ready.orElseThrow().pid(), STOP_TIMEOUT, KILL_TIMEOUT)) {
            throw new IOException("cannot stop card helper: inspect the managed process before retrying");
        }
        files.removeStoppedReadiness(ready.orElseThrow());
        out.println("Stopped card helper.");
    }

    void status(LocalWorkerPaths paths, PrintStream out) throws IOException {
        out.println(
                running(CardHelperFiles.from(paths)).isPresent()
                        ? "Card helper is running."
                        : "Card helper is stopped.");
    }

    private Optional<CardHelperProtocol.Ready> running(CardHelperFiles files) throws IOException {
        Optional<CardHelperProtocol.Ready> recorded = liveRecord(files);
        if (recorded.isEmpty()) {
            return Optional.empty();
        }
        CardHelperProtocol.Ready ready = recorded.get();
        List<String> arguments = platform.commandArguments(ready.pid())
                .filter(Predicate.not(List::isEmpty))
                .orElseThrow(
                        () -> new IOException(
                                "cannot verify card helper: inspect the recorded process from a trusted terminal before retrying"));
        return ownsProcess(arguments, files) ? Optional.of(ready) : Optional.empty();
    }

    private Optional<CardHelperProtocol.Ready> liveRecord(CardHelperFiles files) {
        try {
            CardHelperProtocol.Ready ready = CardHelperProtocol.json()
                    .readValue(
                            CardHelperFiles.read(files.ready(), CardHelperProtocol.MAX_REQUEST_BYTES),
                            CardHelperProtocol.Ready.class);
            if (ready.version() != CardHelperProtocol.VERSION || ready.generation() == null) {
                return Optional.empty();
            }
            if (!platform.isAlive(ready.pid())) {
                return Optional.empty();
            }
            return Optional.of(ready);
        } catch (IOException | IllegalArgumentException ignored) {
            // Missing, stale and invalid records never authorize signalling another process.
            return Optional.empty();
        }
    }

    static boolean ownsProcess(List<String> arguments, CardHelperFiles files) {
        return arguments.contains(CardHelperMain.class.getName()) && arguments.contains(marker(files));
    }

    private static String marker(CardHelperFiles files) {
        return HELPER_MARKER + files.root().toAbsolutePath().normalize();
    }

    private static String applicationClasspath() throws IOException {
        List<Path> entries = new ArrayList<>();
        try {
            // Source launchers load the application separately from their bootstrap classpath.
            if (CardHelperMain.class.getClassLoader()
                    instanceof URLClassLoader loader) { // NOPMD - the application owns this borrowed classloader.
                for (var url : loader.getURLs()) {
                    entries.add(Path.of(url.toURI()));
                }
            } else {
                for (String entry : Splitter.on(File.pathSeparatorChar).split(System.getProperty("java.class.path"))) {
                    entries.add(Path.of(entry));
                }
            }
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("cannot resolve card helper classpath: use local application files", failure);
        }
        return entries.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> command(LocalWorkerPaths paths, CardHelperFiles files) {
        String java = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        PlatformEnvironment.current().windows() ? "java.exe" : "java")
                .toString();
        return List.of(
                java,
                marker(files),
                CardHelperMain.class.getName(),
                paths.configDir().toString(),
                files.root().toString());
    }
}
