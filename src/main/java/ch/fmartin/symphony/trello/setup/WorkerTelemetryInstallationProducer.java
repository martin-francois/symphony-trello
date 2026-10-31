package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.telemetry.TelemetryInstallation;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/// Gives the Quarkus worker its telemetry context from the environment that the managed worker
/// launcher sets.
@Singleton
public class WorkerTelemetryInstallationProducer {
    @Produces
    @Singleton
    TelemetryInstallation telemetryInstallation() {
        return TelemetryInstallations.forWorker(System.getenv());
    }
}
