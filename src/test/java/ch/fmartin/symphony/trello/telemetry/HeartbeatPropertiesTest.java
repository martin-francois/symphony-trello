package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class HeartbeatPropertiesTest {
    @CsvSource({
        "linux, 24.04, johns-laptop",
        "linux, 24.04, ",
        "linux, 13, ubuntu",
        "linux, 24.04.1, ubuntu",
        "linux, 13.1, debian",
        "linux, 24.04, other",
        "linux, rolling, debian",
        "linux, 2026, arch",
        "windows, 11, ubuntu",
        "windows, 12, ",
        "windows, server_22, ",
        "windows, Server 2022, ",
        "macos, 10, ",
        "macos, 9, ",
        "macos, 26.1, ",
        "macos, 026, ",
        "macos, rolling, ",
        "other, 14.1, ",
        "unknown, unknown, other"
    })
    @ParameterizedTest(name = "{0} release {1} with distribution {2} is rejected")
    void storedPlatformValuesTheNormalizerNeverProducesAreRejected(
            String family, String release, @Nullable String distribution) {
        // given
        HeartbeatProperties stored = properties(family, release, distribution);

        // when
        Optional<String> problem = stored.invariantProblem();

        // then
        assertThat(problem).contains("pending report has a platform release the normalizer never produces");
    }

    @CsvSource({
        "linux, 24.04, ubuntu",
        "linux, 13, debian",
        "linux, rolling, arch",
        "linux, unknown, other",
        "linux, unknown, unknown",
        "windows, 10, ",
        "windows, server_2025, ",
        "windows, unknown, ",
        "macos, 26, ",
        "macos, 10.15, ",
        "other, unknown, ",
        "unknown, unknown, "
    })
    @ParameterizedTest(name = "{0} release {1} with distribution {2} is accepted")
    void storedPlatformValuesTheNormalizerProducesAreAccepted(
            String family, String release, @Nullable String distribution) {
        // given
        HeartbeatProperties stored = properties(family, release, distribution);

        // when
        Optional<String> problem = stored.invariantProblem();

        // then
        assertThat(problem).isEmpty();
    }

    private static HeartbeatProperties properties(String family, String release, @Nullable String distribution) {
        return new HeartbeatProperties(1, "2026-09-22", "1.2.0", family, release, distribution, "x64", 3, 0, 0);
    }
}
