package ch.fmartin.symphony.trello.telemetry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/// The resolved installation context telemetry works in: where state lives, how boards are
/// counted, which release is installed, and whether this process runs inside an installer-managed
/// installation at all. Development, test, and CI runs resolve to a context that never sends.
public record TelemetryInstallation(
        Optional<Path> stateDir,
        ConnectedBoardInventory boards,
        Supplier<Optional<String>> installedVersion,
        TelemetryEnvironment environment,
        TelemetryDistribution distribution) {

    public static TelemetryInstallation absent(TelemetryEnvironment environment, TelemetryDistribution distribution) {
        return new TelemetryInstallation(
                Optional.empty(), ConnectedBoardInventory.unavailable(), Optional::empty, environment, distribution);
    }

    public static TelemetryInstallation of(
            Path stateDir,
            ConnectedBoardInventory boards,
            TelemetryEnvironment environment,
            TelemetryDistribution distribution) {
        Path directory = stateDir.toAbsolutePath().normalize();
        return new TelemetryInstallation(
                Optional.of(directory), boards, () -> InstalledVersion.read(directory), environment, distribution);
    }

    /// True only when the installer wrote its context into this state directory. That file is the
    /// distribution's marker for an end-user installation, as opposed to a source checkout run.
    public boolean installed() {
        return stateDir.map(InstalledVersion::installContextPath)
                .filter(Files::isRegularFile)
                .isPresent();
    }

    /// The mode that applies to this process for a stored preference.
    public EffectiveTelemetry effective(TelemetryMode stored) {
        return EffectiveTelemetry.resolve(stored, environment);
    }

    /// The state store, present only inside an installed context; development runs get nothing.
    public Optional<TelemetryStateStore> installedStore() {
        return installed() ? stateDir.map(TelemetryStateStore::new) : Optional.empty();
    }

    public Optional<TelemetryStateStore> store() {
        return stateDir.map(TelemetryStateStore::new);
    }

    /// A report can leave this machine only from an installed context with a configured token.
    public boolean networkEligible() {
        return installed() && distribution.ready();
    }
}
