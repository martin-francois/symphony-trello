package ch.fmartin.symphony.trello.telemetry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/// What a report takes from the installer's `install-context.properties` in the state directory:
/// the installed release and the names of the location overrides. The installer rewrites that file
/// only after a successful install or update, so both describe the installation rather than
/// whichever worker build happens to send the report. Each value is absent when the file is
/// missing, damaged, or has no such key.
public record InstallContext(Optional<String> appVersion, Optional<List<String>> installOverrides) {
    public static final InstallContext UNKNOWN = new InstallContext(Optional.empty(), Optional.empty());
    static final String INSTALL_OVERRIDES_KEY = "install_overrides";

    public InstallContext {
        installOverrides = installOverrides.map(List::copyOf);
    }

    public static InstallContext read(Path stateDir) {
        var properties = new Properties();
        try {
            for (String line : Files.readAllLines(InstalledVersion.installContextPath(stateDir))) {
                int separator = line.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String key = line.substring(0, separator);
                if (key.equals(InstalledVersion.APP_VERSION_KEY) || key.equals(INSTALL_OVERRIDES_KEY)) {
                    // Installers write literal values, including unescaped Windows paths on other lines.
                    properties.setProperty(key, line.substring(separator + 1));
                }
            }
        } catch (IOException exception) {
            // A missing or damaged installer file only means the values are unknown, reported as null.
            return UNKNOWN;
        }
        return new InstallContext(
                InstalledVersion.normalize(properties.getProperty(InstalledVersion.APP_VERSION_KEY)),
                InstallOverride.parse(properties.getProperty(INSTALL_OVERRIDES_KEY)));
    }
}
