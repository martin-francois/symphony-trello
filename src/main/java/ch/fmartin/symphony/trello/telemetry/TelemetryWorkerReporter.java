package ch.fmartin.symphony.trello.telemetry;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jboss.logging.Logger;

/// Runs the heartbeat due check inside a managed worker: once when the worker is ready and then
/// every minute. Delivery happens on a single daemon thread so a slow request never blocks the
/// scheduler, and shutdown sends nothing.
@Singleton
public final class TelemetryWorkerReporter {
    private static final Logger LOG = Logger.getLogger(TelemetryWorkerReporter.class);

    private final HeartbeatReporter reporter;
    private final ExecutorService deliveries;

    public TelemetryWorkerReporter(TelemetryInstallation installation, Clock clock) {
        this.deliveries = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telemetry-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.reporter = new HeartbeatReporter(
                installation,
                HeartbeatSnapshots.detectingPlatform(installation),
                new PostHogCaptureClient(installation.distribution().endpoint()),
                clock,
                deliveries,
                new SecureRandom()::nextLong,
                LOG::info);
    }

    void onStart(@Observes StartupEvent event) {
        checkQuietly();
    }

    @Scheduled(every = "1m", delayed = "1m", concurrentExecution = ConcurrentExecution.SKIP)
    void tick() {
        checkQuietly();
    }

    void onStop(@Observes ShutdownEvent event) {
        deliveries.shutdownNow();
    }

    private void checkQuietly() {
        try {
            reporter.check();
        } catch (RuntimeException exception) {
            // Telemetry is subordinate: a failure here must never affect Trello or Codex work.
            LOG.debugf(exception, "telemetry check failed");
        }
    }
}
