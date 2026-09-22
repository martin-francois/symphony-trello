package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.telemetry.ConnectedBoardInventory;
import ch.fmartin.symphony.trello.telemetry.HeartbeatSnapshots;
import ch.fmartin.symphony.trello.telemetry.TelemetryDistribution;
import ch.fmartin.symphony.trello.telemetry.TelemetryEnvironment;
import ch.fmartin.symphony.trello.telemetry.TelemetryInstallation;
import ch.fmartin.symphony.trello.telemetry.TelemetryService;
import ch.fmartin.symphony.trello.time.ApplicationClock;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/// Wires telemetry to the installed layout: the state home holds `telemetry.json`, and the
/// connected-board manifest in the config directory supplies the board count.
final class TelemetryInstallations {
    private TelemetryInstallations() {}

    static TelemetryInstallation forPaths(LocalWorkerPaths paths, Map<String, String> environment) {
        return forPaths(paths, paths.manifestPath(), environment);
    }

    /// Direct `new-board` and `import-board` runs may name a manifest outside the config directory;
    /// the board count must come from that same file, not from the default file name.
    static TelemetryInstallation forPaths(LocalWorkerPaths paths, Path manifestPath, Map<String, String> environment) {
        return TelemetryInstallation.of(
                paths.stateHome(),
                inventory(manifestPath),
                TelemetryEnvironment.from(environment),
                TelemetryDistribution.load(SetupSystemProperties::get));
    }

    /// Managed workers receive the config and state directories from the environment that
    /// `LocalWorkerManager` sets; anything else is a development or test run without telemetry.
    static TelemetryInstallation forWorker(Map<String, String> environment) {
        TelemetryEnvironment telemetryEnvironment = TelemetryEnvironment.from(environment);
        TelemetryDistribution distribution = TelemetryDistribution.load(SetupSystemProperties::get);
        if (!LocalWorkerPaths.managedWorkerEnvironmentPresent(environment)) {
            return TelemetryInstallation.absent(telemetryEnvironment, distribution);
        }
        try {
            LocalWorkerPaths paths = LocalWorkerPaths.from(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), environment);
            return forPaths(paths, environment);
        } catch (RuntimeException exception) {
            return TelemetryInstallation.absent(telemetryEnvironment, distribution);
        }
    }

    static TelemetryService service(LocalWorkerPaths paths, Map<String, String> environment) {
        return service(paths, paths.manifestPath(), environment);
    }

    static TelemetryService service(LocalWorkerPaths paths, Path manifestPath, Map<String, String> environment) {
        TelemetryInstallation installation = forPaths(paths, manifestPath, environment);
        return new TelemetryService(
                installation, HeartbeatSnapshots.detectingPlatform(installation), ApplicationClock.systemUtc());
    }

    static ConnectedBoardInventory inventory(Path manifestPath) {
        return () -> {
            try {
                ConnectedBoardRepository.ManifestLoadResult loaded =
                        new ConnectedBoardRepository(manifestPath).loadForCheck();
                if (!loaded.usableRows() || !loaded.warnings().isEmpty()) {
                    return OptionalInt.empty();
                }
                long distinct = loaded.manifest().boards().stream()
                        .map(ConnectedBoard::boardId)
                        .distinct()
                        .count();
                return OptionalInt.of(Math.toIntExact(distinct));
            } catch (IOException | RuntimeException exception) {
                return OptionalInt.empty();
            }
        };
    }
}
