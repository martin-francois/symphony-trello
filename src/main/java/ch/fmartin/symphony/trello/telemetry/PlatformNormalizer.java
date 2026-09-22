package ch.fmartin.symphony.trello.telemetry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// Maps raw platform facts onto the fixed vocabulary of the heartbeat. Unknown inputs become
/// `unknown` or `other`; free text never passes through.
final class PlatformNormalizer {
    /// Linux distributions whose `VERSION_ID` names a daily snapshot rather than a supported release.
    private static final Set<String> ROLLING_DISTRIBUTIONS = Set.of(
            "arch",
            "manjaro",
            "endeavouros",
            "cachyos",
            "garuda",
            "gentoo",
            "void",
            "kali",
            "opensuse-tumbleweed",
            "opensuse-microos",
            "opensuse-slowroll");
    /// Distributions where the minor release matters for support, for example Ubuntu 24.04.
    private static final Set<String> MAJOR_MINOR_DISTRIBUTIONS =
            Set.of("ubuntu", "pop", "alpine", "opensuse-leap", "sles", "nixos");
    /// Distributions identified by their major release, for example Debian 13 or Fedora 42.
    private static final Set<String> MAJOR_DISTRIBUTIONS = Set.of(
            "debian",
            "raspbian",
            "fedora",
            "nobara",
            "rhel",
            "centos",
            "rocky",
            "almalinux",
            "ol",
            "amzn",
            "linuxmint",
            "elementary",
            "zorin");
    private static final Pattern RELEASE_NUMBER = Pattern.compile("^(\\d{1,4})(?:\\.(\\d{1,4}))?(?:[.\\-+].*)?$");
    private static final Pattern WINDOWS_SERVER = Pattern.compile("^Server (\\d{4})$");
    private static final int MACOS_LEGACY_MAJOR = 10;

    private PlatformNormalizer() {}

    static Platform normalize(PlatformFacts facts) {
        OsFamily family = OsFamily.fromOsName(facts.osName());
        String arch = runtimeArch(facts.osArch());
        return switch (family) {
            case WINDOWS -> new Platform(family.wireName(), windowsRelease(facts.detectedVersion()), null, arch);
            case MACOS -> new Platform(family.wireName(), macosRelease(facts.detectedVersion()), null, arch);
            case LINUX -> {
                String distribution = linuxDistribution(facts.osRelease());
                yield new Platform(
                        family.wireName(), linuxRelease(distribution, facts.osRelease()), distribution, arch);
            }
            case OTHER, UNKNOWN -> new Platform(family.wireName(), Platform.UNKNOWN, null, arch);
        };
    }

    static String runtimeArch(@Nullable String osArch) {
        if (osArch == null || osArch.isBlank()) {
            return Platform.UNKNOWN;
        }
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> Platform.OTHER;
        };
    }

    /// OSHI resolves the Windows build number to the product name, so "10" here already means a
    /// build below 22000 and "11" a build at or above it; the kernel version alone is never used.
    static String windowsRelease(@Nullable String detectedVersion) {
        if (detectedVersion == null) {
            return Platform.UNKNOWN;
        }
        String version = detectedVersion.strip();
        if ("10".equals(version) || "11".equals(version)) {
            return version;
        }
        Matcher server = WINDOWS_SERVER.matcher(version);
        return server.matches() ? "server_" + server.group(1) : Platform.UNKNOWN;
    }

    /// macOS 11 and later are identified by the product major release; 10.x keeps the minor release
    /// because those were separate products.
    static String macosRelease(@Nullable String detectedVersion) {
        Matcher matcher = releaseNumber(detectedVersion);
        if (matcher == null) {
            return Platform.UNKNOWN;
        }
        int major = Integer.parseInt(matcher.group(1));
        String minor = matcher.group(2);
        if (major > MACOS_LEGACY_MAJOR) {
            return Integer.toString(major);
        }
        return major == MACOS_LEGACY_MAJOR && minor != null ? major + "." + minor : Platform.UNKNOWN;
    }

    static String linuxDistribution(Map<String, String> osRelease) {
        String id = osRelease.get("ID");
        if (id == null || id.isBlank()) {
            return osRelease.isEmpty() ? Platform.UNKNOWN : Platform.OTHER;
        }
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        if (ROLLING_DISTRIBUTIONS.contains(normalized)
                || MAJOR_MINOR_DISTRIBUTIONS.contains(normalized)
                || MAJOR_DISTRIBUTIONS.contains(normalized)) {
            return normalized;
        }
        return Platform.OTHER;
    }

    static String linuxRelease(String distribution, Map<String, String> osRelease) {
        if (ROLLING_DISTRIBUTIONS.contains(distribution)) {
            return Platform.ROLLING;
        }
        Matcher matcher = releaseNumber(osRelease.get("VERSION_ID"));
        if (matcher == null) {
            return Platform.UNKNOWN;
        }
        if (MAJOR_MINOR_DISTRIBUTIONS.contains(distribution)) {
            String minor = matcher.group(2);
            return minor == null ? Platform.UNKNOWN : matcher.group(1) + "." + minor;
        }
        if (MAJOR_DISTRIBUTIONS.contains(distribution)) {
            return matcher.group(1);
        }
        return Platform.UNKNOWN;
    }

    private static @Nullable Matcher releaseNumber(@Nullable String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = RELEASE_NUMBER.matcher(value.strip());
        return matcher.matches() ? matcher : null;
    }
}
