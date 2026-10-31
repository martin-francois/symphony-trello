package ch.fmartin.symphony.trello.telemetry;

import com.google.common.collect.ImmutableSet;
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
    /// Every distribution ID a heartbeat may name besides `other` and `unknown`.
    static final Set<String> ALLOWLISTED_DISTRIBUTIONS = ImmutableSet.<String>builder()
            .addAll(ROLLING_DISTRIBUTIONS)
            .addAll(MAJOR_MINOR_DISTRIBUTIONS)
            .addAll(MAJOR_DISTRIBUTIONS)
            .build();
    private static final Pattern RELEASE_NUMBER = Pattern.compile("^(\\d{1,4})(?:\\.(\\d{1,4}))?(?:[.\\-+].*)?$");
    private static final String SERVER_YEAR = "(\\d{4})";
    private static final String SERVER_PREFIX = "server_";
    private static final Pattern WINDOWS_SERVER = Pattern.compile("^Server " + SERVER_YEAR + "$");
    private static final Pattern NORMALIZED_WINDOWS_SERVER = Pattern.compile("^" + SERVER_PREFIX + SERVER_YEAR + "$");
    private static final int MACOS_LEGACY_MAJOR = 10;
    private static final String VERSION_ID = "VERSION_ID";

    private PlatformNormalizer() {}

    static Platform normalize(PlatformFacts facts) {
        OsFamily family = OsFamily.fromOsName(facts.osName());
        RuntimeArch arch = RuntimeArch.fromOsArch(facts.osArch());
        return switch (family) {
            case WINDOWS -> new Platform(family, windowsRelease(facts.detectedVersion()), null, arch);
            case MACOS -> new Platform(family, macosRelease(facts.detectedVersion()), null, arch);
            case LINUX -> {
                String distribution = linuxDistribution(facts.osRelease());
                yield new Platform(family, linuxRelease(distribution, facts.osRelease()), distribution, arch);
            }
            case OTHER, UNKNOWN -> new Platform(family, Platform.UNKNOWN, null, arch);
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
        return server.matches() ? SERVER_PREFIX + server.group(1) : Platform.UNKNOWN;
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
        return ALLOWLISTED_DISTRIBUTIONS.contains(normalized) ? normalized : Platform.OTHER;
    }

    static String linuxRelease(String distribution, Map<String, String> osRelease) {
        if (ROLLING_DISTRIBUTIONS.contains(distribution)) {
            return Platform.ROLLING;
        }
        Matcher matcher = releaseNumber(osRelease.get(VERSION_ID));
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

    /// Whether [#normalize] can return this combination. A stored pending report is input, so a
    /// hand-edited value must not reach the wire. The macOS and Linux release rules map each of
    /// their own outputs onto itself, so those checks rerun the rule instead of restating it.
    static boolean isNormalized(OsFamily family, String osRelease, @Nullable String linuxDistribution) {
        if (family == OsFamily.LINUX) {
            if (!isLinuxDistribution(linuxDistribution)) {
                return false;
            }
        } else if (linuxDistribution != null) {
            return false;
        }
        if (Platform.UNKNOWN.equals(osRelease)) {
            return true;
        }
        return switch (family) {
            case WINDOWS ->
                windowsRelease(osRelease).equals(osRelease)
                        || NORMALIZED_WINDOWS_SERVER.matcher(osRelease).matches();
            case MACOS -> macosRelease(osRelease).equals(osRelease);
            case LINUX ->
                linuxDistribution != null
                        && linuxRelease(linuxDistribution, Map.of(VERSION_ID, osRelease))
                                .equals(osRelease);
            case OTHER, UNKNOWN -> false;
        };
    }

    private static boolean isLinuxDistribution(@Nullable String distribution) {
        return distribution != null
                && (ALLOWLISTED_DISTRIBUTIONS.contains(distribution)
                        || Platform.OTHER.equals(distribution)
                        || Platform.UNKNOWN.equals(distribution));
    }

    private static @Nullable Matcher releaseNumber(@Nullable String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = RELEASE_NUMBER.matcher(value.strip());
        return matcher.matches() ? matcher : null;
    }
}
