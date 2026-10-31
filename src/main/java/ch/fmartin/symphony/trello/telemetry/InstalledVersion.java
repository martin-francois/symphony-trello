package ch.fmartin.symphony.trello.telemetry;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// The installed release as the installer's `install-context.properties` records it; InstallContext
/// reads the file, this class says where it is and which release strings pass.
public final class InstalledVersion {
    public static final String INSTALL_CONTEXT_FILE = "install-context.properties";
    static final String APP_VERSION_KEY = "app_version";
    private static final Pattern RELEASE = Pattern.compile("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}(?:-[0-9A-Za-z.]{1,20})?$");

    private InstalledVersion() {}

    public static Path installContextPath(Path stateDir) {
        return stateDir.resolve(INSTALL_CONTEXT_FILE);
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
