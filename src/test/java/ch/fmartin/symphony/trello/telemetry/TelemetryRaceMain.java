package ch.fmartin.symphony.trello.telemetry;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;

/// Runs one worker's first due check in a separate JVM so tests can race real processes for
/// identity creation, the shared daily claim, and counter increments. Prints the check result,
/// then the installation id.
public final class TelemetryRaceMain {
    private TelemetryRaceMain() {}

    public static void main(String[] arguments) {
        Path stateDir = Path.of(arguments[0]);
        Instant now = Instant.parse(arguments[1]);
        URI endpoint = URI.create(arguments[2]);
        int increments = Integer.parseInt(arguments[3]);
        TelemetryInstallation installation = TelemetryInstallation.of(
                stateDir,
                () -> OptionalInt.of(1),
                TelemetryEnvironment.none(),
                new TelemetryDistribution(endpoint, Optional.of("phc_racetoken")));
        TelemetryService service = new TelemetryService(
                installation,
                new HeartbeatSnapshots(installation, new PlatformDetector()),
                Clock.fixed(now, ZoneOffset.UTC));
        for (int increment = 0; increment < increments; increment++) {
            service.recordSuccessfulOperation(BoardOperation.IMPORT);
        }
        HeartbeatReporter reporter = new HeartbeatReporter(
                installation,
                new HeartbeatSnapshots(installation, new PlatformDetector()),
                new PostHogCaptureClient(endpoint),
                Clock.fixed(now, ZoneOffset.UTC),
                Runnable::run,
                () -> 0,
                message -> {});
        HeartbeatReporter.CheckResult result = reporter.check();
        TelemetryState state = installation.store().orElseThrow().read().stateOrInitial();
        System.out.println(
                result + " " + state.installation().map(Object::toString).orElse("none"));
        System.out.flush();
    }
}
