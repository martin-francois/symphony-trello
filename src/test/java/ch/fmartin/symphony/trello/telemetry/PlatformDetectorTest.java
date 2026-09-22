package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlatformDetectorTest {
    @TempDir
    Path tempDir;

    @Test
    void osReleaseReaderKeepsOnlyIdAndVersionIdAndUnquotes() throws IOException {
        // given
        Path osRelease = tempDir.resolve("os-release");
        Files.writeString(
                osRelease,
                """
                PRETTY_NAME="Ubuntu 24.04.3 LTS"
                NAME="Ubuntu"
                VERSION_ID="24.04"
                ID=ubuntu
                ID_LIKE=debian
                HOME_URL="https://www.ubuntu.com/"
                =broken
                ID=ignored-duplicate
                """);

        // when
        Map<String, String> entries = PlatformDetector.readOsRelease(osRelease);

        // then
        assertThat(entries).containsExactlyInAnyOrderEntriesOf(Map.of("ID", "ubuntu", "VERSION_ID", "24.04"));
    }

    @Test
    void osReleaseReaderDropsOverlongValuesAndMissingFiles() throws IOException {
        // given
        Path osRelease = tempDir.resolve("os-release");
        Files.writeString(osRelease, "ID=" + "x".repeat(65) + "\nVERSION_ID=\"\"\n");

        // when
        Map<String, String> entries = PlatformDetector.readOsRelease(osRelease);
        Map<String, String> missing = PlatformDetector.readOsRelease(tempDir.resolve("absent"));

        // then
        assertThat(entries).isEmpty();
        assertThat(missing).isEmpty();
    }

    @Test
    void detectionRunsOnceAndIsCached() {
        // given
        var calls = new AtomicInteger();
        PlatformDetector detector = new PlatformDetector(() -> {
            calls.incrementAndGet();
            return new PlatformFacts("Linux", "aarch64", null, Map.of("ID", "debian", "VERSION_ID", "13"));
        });

        // when
        List<Platform> platforms = List.of(detector.platform(), detector.platform(), detector.platform());

        // then
        assertThat(platforms).containsOnly(new Platform("linux", "13", "debian", "arm64"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void detectorFailureProducesUnknownFieldsInsteadOfThrowing() {
        // given
        PlatformDetector detector = new PlatformDetector(() -> {
            throw new IllegalStateException("native access denied");
        });

        // when
        Platform platform = detector.platform();

        // then
        assertThat(platform).isEqualTo(Platform.unknown());
    }

    @Test
    void systemDetectionOnThisHostProducesAllowlistedValues() {
        // given
        PlatformDetector detector = new PlatformDetector();

        // when
        Platform platform = detector.platform();

        // then
        assertThat(platform.osFamily()).isIn("windows", "macos", "linux", "other", "unknown");
        assertThat(platform.runtimeArch()).isIn("x64", "arm64", "x86", "other", "unknown");
        assertThat(platform.osRelease()).matches("[a-z0-9_.]{1,16}");
    }
}
