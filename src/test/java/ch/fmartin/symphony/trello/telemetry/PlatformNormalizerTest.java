package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

final class PlatformNormalizerTest {
    @CsvSource({
        "Windows 11, windows",
        "Windows Server 2022, windows",
        "Mac OS X, macos",
        "Darwin, macos",
        "Linux, linux",
        "FreeBSD, other",
        "SunOS, other"
    })
    @ParameterizedTest(name = "os.name {0} maps to {1}")
    void osFamilyUsesCoarseBuckets(String osName, String expected) {
        // given
        PlatformFacts facts = new PlatformFacts(osName, "amd64", null, Map.of());

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform.osFamily()).isEqualTo(expected);
    }

    @CsvSource({
        "amd64, x64",
        "x86_64, x64",
        "aarch64, arm64",
        "arm64, arm64",
        "x86, x86",
        "i386, x86",
        "riscv64, other",
        "ppc64le, other"
    })
    @ParameterizedTest(name = "os.arch {0} maps to {1}")
    void runtimeArchNormalizesJvmValues(String osArch, String expected) {
        // given
        PlatformFacts facts = new PlatformFacts("Linux", osArch, null, Map.of("ID", "debian", "VERSION_ID", "13"));

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform.runtimeArch()).isEqualTo(expected);
    }

    @NullAndEmptySource
    @ParameterizedTest
    @ValueSource(strings = " ")
    void missingJvmPropertiesBecomeUnknown(String missing) {
        // given
        PlatformFacts facts = new PlatformFacts(missing, missing, missing, Map.of());

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform).isEqualTo(Platform.unknown());
    }

    @CsvSource({
        "10, 10",
        "11, 11",
        "Server 2019, server_2019",
        "Server 2022, server_2022",
        "Server 2025, server_2025",
        "8.1, unknown",
        "10.0.22631, unknown",
        "Server, unknown"
    })
    @ParameterizedTest(name = "OSHI Windows version {0} reports {1}")
    void windowsReleaseAcceptsOnlyResolvedProductNames(String detected, String expected) {
        // given
        PlatformFacts facts = new PlatformFacts("Windows 10", "amd64", detected, Map.of());

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform).isEqualTo(new Platform("windows", expected, null, "x64"));
    }

    @Test
    void windowsKernelVersionAloneDoesNotDecideTenVersusEleven() {
        // given
        PlatformFacts facts = new PlatformFacts("Windows 10", "amd64", null, Map.of());

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform.osRelease()).isEqualTo(Platform.UNKNOWN);
    }

    @CsvSource({
        "26.0, 26",
        "26.1.2, 26",
        "15.6, 15",
        "11.7.10, 11",
        "10.15.7, 10.15",
        "10.16, 10.16",
        "24.6.0, 24",
        "Sequoia, unknown",
        "9.2, unknown"
    })
    @ParameterizedTest(name = "macOS product version {0} reports {1}")
    void macosReleaseUsesTheProductMajor(String detected, String expected) {
        // given
        PlatformFacts facts = new PlatformFacts("Mac OS X", "aarch64", detected, Map.of());

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform).isEqualTo(new Platform("macos", expected, null, "arm64"));
    }

    @CsvSource({
        "ubuntu, 24.04, ubuntu, 24.04",
        "Ubuntu, 22.04.5 LTS, ubuntu, 22.04",
        "debian, 13, debian, 13",
        "raspbian, 12, raspbian, 12",
        "fedora, 42, fedora, 42",
        "alpine, 3.21.3, alpine, 3.21",
        "opensuse-leap, 15.6, opensuse-leap, 15.6",
        "opensuse-tumbleweed, 20260921, opensuse-tumbleweed, rolling",
        "opensuse-microos, 20260921, opensuse-microos, rolling",
        "arch, , arch, rolling",
        "manjaro, 25.0.6, manjaro, rolling",
        "kali, 2025.3, kali, rolling",
        "rhel, 9.4, rhel, 9",
        "rocky, 9.5, rocky, 9",
        "almalinux, 10.0, almalinux, 10",
        "amzn, 2023, amzn, 2023",
        "linuxmint, 22.1, linuxmint, 22",
        "pop, 24.04, pop, 24.04",
        "nixos, 25.05, nixos, 25.05",
        "gentoo, 2.17, gentoo, rolling",
        "ubuntu, , ubuntu, unknown",
        "ubuntu, jammy, ubuntu, unknown",
        "debian, n/a, debian, unknown",
        "myfork, 1.0, other, unknown",
        "'', 13, other, unknown"
    })
    @ParameterizedTest(name = "os-release ID={0} VERSION_ID={1} reports {2} {3}")
    void linuxReleasePrecisionFollowsTheDistribution(
            String id, String versionId, String expectedDistribution, String expectedRelease) {
        // given
        Map<String, String> osRelease =
                versionId == null ? Map.of("ID", id) : Map.of("ID", id, "VERSION_ID", versionId);
        PlatformFacts facts = new PlatformFacts("Linux", "amd64", null, osRelease);

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform).isEqualTo(new Platform("linux", expectedRelease, expectedDistribution, "x64"));
    }

    @Test
    void linuxWithoutOsReleaseReportsUnknownDistribution() {
        // given
        PlatformFacts facts = new PlatformFacts("Linux", "amd64", "6.12.0", Map.of());

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform).isEqualTo(new Platform("linux", Platform.UNKNOWN, Platform.UNKNOWN, "x64"));
    }

    @Test
    void contaminatedFreeTextNeverPassesThrough() {
        // given
        String contaminated = "Ubuntu /home/alice/secret; DROP TABLE";
        PlatformFacts facts = new PlatformFacts(
                "Linux",
                contaminated,
                contaminated,
                Map.of("ID", contaminated, "VERSION_ID", contaminated, "PRETTY_NAME", contaminated));

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform).isEqualTo(new Platform("linux", Platform.UNKNOWN, Platform.OTHER, Platform.OTHER));
    }

    @Test
    void nonLinuxFamiliesNeverCarryADistribution() {
        // given
        PlatformFacts facts = new PlatformFacts("FreeBSD", "amd64", "14.1", Map.of("ID", "freebsd"));

        // when
        Platform platform = PlatformNormalizer.normalize(facts);

        // then
        assertThat(platform).isEqualTo(new Platform(Platform.OTHER, Platform.UNKNOWN, null, "x64"));
    }
}
