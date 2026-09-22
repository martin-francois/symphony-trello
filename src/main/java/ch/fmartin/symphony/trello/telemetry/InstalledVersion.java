package ch.fmartin.symphony.trello.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// Reads the installed release from the installer's `install-context.properties` in the state
/// directory. The installer rewrites that file only after a successful install or update, so the
/// value describes the installation rather than whichever worker build happens to send the report.
public final class InstalledVersion {
    public static final String INSTALL_CONTEXT_FILE = "install-context.properties";
    static final String APP_VERSION_KEY = "app_version";
    private static final Pattern RELEASE = Pattern.compile("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}(?:-[0-9A-Za-z.]{1,20})?$");

    private InstalledVersion() {}

    public static Path installContextPath(Path stateDir) {
        return stateDir.resolve(INSTALL_CONTEXT_FILE);
    }

    public static Optional<String> read(Path stateDir) {
        Path file = installContextPath(stateDir);
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
            properties.load(stream);
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
        return normalize(properties.getProperty(APP_VERSION_KEY));
    }

    /// Only a plain release string passes; branch names, paths, `unknown`, and free text become
    /// absent so the dashboard shows them as unknown.
    static Optional<String> normalize(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        String version = value.strip();
        return RELEASE.matcher(version).matches() ? Optional.of(version) : Optional.empty();
    }
}
