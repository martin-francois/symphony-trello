package ch.fmartin.symphony.trello.telemetry;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Raw platform inputs before normalization: JVM properties, the OSHI operating-system version,
/// and the parsed `/etc/os-release` entries on Linux. Any value may be missing or malformed;
/// [PlatformNormalizer] never forwards these strings as they are.
public record PlatformFacts(
        @Nullable String osName,
        @Nullable String osArch,
        @Nullable String detectedVersion,
        Map<String, String> osRelease) {

    public PlatformFacts {
        osRelease = Map.copyOf(osRelease);
    }

    public static PlatformFacts empty() {
        return new PlatformFacts(null, null, null, Map.of());
    }
}
