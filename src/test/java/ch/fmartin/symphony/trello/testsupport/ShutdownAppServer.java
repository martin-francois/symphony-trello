package ch.fmartin.symphony.trello.testsupport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/// A real, test-owned protocol process whose shutdown hook waits for a socket acknowledgement.
public final class ShutdownAppServer {
    private ShutdownAppServer() {}

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        boolean completeTurn = Boolean.parseBoolean(arguments[1]);
        boolean spawnChild = Boolean.parseBoolean(arguments[2]);
        if (spawnChild) {
            Path childDirectory = Files.createDirectory(directory.resolve("child"));
            Process child = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            ShutdownAppServer.class.getName(),
                            childDirectory.toString(),
                            "false",
                            "false")
                    .start();
            Files.writeString(directory.resolve("child-process"), Long.toString(child.pid()));
        }
        var shutdown = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        shutdown.setSoTimeout(10_000);
        Files.writeString(
                directory.resolve("process"),
                Long.toString(ProcessHandle.current().pid()));
        Files.writeString(directory.resolve("port"), Integer.toString(shutdown.getLocalPort()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (shutdown) {
                Files.writeString(directory.resolve("stopping"), "awaiting test acknowledgement");
                try (var release = shutdown.accept()) {
                    release.setSoTimeout(10_000);
                    release.getInputStream().read();
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot finish test shutdown: expected acknowledgement", e);
            }
        }));
        Files.writeString(directory.resolve("ready"), "shutdown hook installed");
        if (spawnChild) {
            BoundedWait.until(
                    Duration.ofSeconds(5),
                    () -> Files.exists(directory.resolve("child/ready")),
                    "the child shutdown hook is installed before protocol completion");
        }
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line = input.readLine(); line != null; line = input.readLine()) {
                if (line.contains("\"method\":\"initialize\"")) {
                    System.out.println("{\"id\":1,\"result\":{}}");
                } else if (line.contains("\"method\":\"thread/start\"")) {
                    System.out.println("{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread\"}}}");
                } else if (line.contains("\"method\":\"turn/start\"")) {
                    System.out.println("{\"id\":3,\"result\":{\"turn\":{\"id\":\"turn\"}}}");
                    Files.writeString(directory.resolve("started"), "turn started");
                    if (completeTurn) {
                        BoundedWait.until(
                                Duration.ofSeconds(5),
                                () -> Files.exists(directory.resolve("complete-turn")),
                                "the test releases turn completion after updating tracker state");
                        System.out.println(
                                "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread\",\"turn\":{\"id\":\"turn\",\"status\":\"completed\"}}}");
                    }
                }
            }
        }
        System.exit(0);
    }
}
