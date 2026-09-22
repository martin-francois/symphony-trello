package ch.fmartin.symphony.trello.telemetry;

import com.google.common.base.Suppliers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;
import oshi.ffm.SystemInfo;
import oshi.software.os.OperatingSystem;

/// Collects raw platform facts once per process: JVM properties, the OSHI operating-system version
/// through the Foreign Function and Memory implementation, and the standard `/etc/os-release`
/// entries on Linux. Detection failures degrade to unknown fields and never throw.
public final class PlatformDetector {
    private static final Logger LOG = Logger.getLogger(PlatformDetector.class);
    private static final Path OS_RELEASE = Path.of("/etc/os-release");
    private static final Set<String> OS_RELEASE_KEYS = Set.of("ID", "VERSION_ID");
    private static final int MAX_OS_RELEASE_VALUE_LENGTH = 64;

    private final Supplier<Platform> platform;

    public PlatformDetector() {
        this(PlatformDetector::detectSystem);
    }

    PlatformDetector(Supplier<PlatformFacts> facts) {
        this.platform = Suppliers.memoize(() -> PlatformNormalizer.normalize(factsOrEmpty(facts)));
    }

    public Platform platform() {
        return platform.get();
    }

    private static PlatformFacts factsOrEmpty(Supplier<PlatformFacts> facts) {
        try {
            return facts.get();
        } catch (RuntimeException | LinkageError exception) {
            LOG.debugf(exception, "platform detection failed");
            return PlatformFacts.empty();
        }
    }

    static PlatformFacts detectSystem() {
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        Map<String, String> osRelease =
                OsFamily.fromOsName(osName) == OsFamily.LINUX ? readOsRelease(OS_RELEASE) : Map.of();
        return new PlatformFacts(osName, osArch, oshiVersion(), osRelease);
    }

    private static @Nullable String oshiVersion() {
        try {
            OperatingSystem operatingSystem = new SystemInfo().getOperatingSystem();
            return operatingSystem.getVersionInfo().getVersion();
        } catch (RuntimeException | LinkageError exception) {
            // Native access can be unavailable or restricted; the heartbeat then reports unknown fields.
            LOG.debugf(exception, "platform detection unavailable");
            return null;
        }
    }

    static Map<String, String> readOsRelease(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException | RuntimeException exception) {
            return Map.of();
        }
        Map<String, String> entries = new HashMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).strip();
            if (!OS_RELEASE_KEYS.contains(key)) {
                continue;
            }
            String value = unquote(line.substring(separator + 1).strip());
            if (!value.isEmpty() && value.length() <= MAX_OS_RELEASE_VALUE_LENGTH) {
                entries.putIfAbsent(key, value);
            }
        }
        return Map.copyOf(entries);
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
