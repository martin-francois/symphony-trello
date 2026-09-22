package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.telemetry.HeartbeatReporter.CheckResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Races real JVM processes, not threads, against one state directory: separate processes must
/// agree on one identity, one accepted report per day, and every counter increment.
final class TelemetryMultiJvmTest {
    private static final int PROCESSES = 4;
    private static final int INCREMENTS_PER_PROCESS = 5;
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(90);
    private static final Instant AFTER_GRACE = TelemetryFixture.NOON.plus(TelemetryNotice.FIRST_REPORT_GRACE);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger accepted = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/i/v0/e/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            accepted.incrementAndGet();
            byte[] body = "{\"status\":\"Ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void concurrentFirstWorkersShareOneIdentityAndOneDailyReportAndLoseNoCounters() throws Exception {
        // given
        Path stateDir = TelemetryFixture.installedStateDir(tempDir);
        TelemetryStateStore store = new TelemetryStateStore(stateDir);
        // The first process starts the grace period; racing it at the deadline makes every process due.
        store.update(state -> TelemetryStateStore.Update.write(
                state.withFirstWorkerDeadline(AFTER_GRACE).withNotice(TelemetryNotice.REVISION, TelemetryFixture.NOON),
                null));
        List<Process> processes = new ArrayList<>();
        for (int process = 0; process < PROCESSES; process++) {
            processes.add(launch(stateDir, AFTER_GRACE));
        }

        // when
        List<String> outputs = new ArrayList<>();
        for (Process process : processes) {
            assertThat(process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .as("each racing JVM exits within the timeout")
                    .isTrue();
            outputs.add(lastLine(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
        }
        TelemetryState state = store.read().stateOrInitial();

        // then
        assertThat(outputs).allSatisfy(output -> assertThat(output)
                .endsWith(state.installationId().toString()));
        assertThat(outputs)
                .filteredOn(output -> output.startsWith(CheckResult.DISPATCHED.name()))
                .as("exactly one process wins the daily claim; the rest wait or see it done")
                .hasSize(1);
        assertThat(accepted).hasValue(1);
        assertThat(state.lastReportedDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(state.boardImportsTotal()).isEqualTo((long) PROCESSES * INCREMENTS_PER_PROCESS);
        assertThat(state.claim()).isNull();
    }

    /// The result line is the last line; anything before it is JVM or logging noise.
    private static String lastLine(String output) {
        String stripped = output.strip();
        return stripped.substring(stripped.lastIndexOf('\n') + 1);
    }

    private Process launch(Path stateDir, Instant now) throws IOException {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-Djava.util.logging.manager=org.jboss.logmanager.LogManager",
                        "-cp",
                        System.getProperty("java.class.path"),
                        TelemetryRaceMain.class.getName(),
                        stateDir.toString(),
                        now.toString(),
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/i/v0/e/",
                        Integer.toString(INCREMENTS_PER_PROCESS))
                .redirectErrorStream(true)
                .start();
    }
}
