package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
        assertThat(problem).hasValue("pending report has a platform release the normalizer never produces");
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

    @CsvSource({
        "'', ",
        "SYMPHONY_HOME, ",
        "'SYMPHONY_HOME,--prefix,microos-var', ",
        "SYMPHONY_HOME_VALUE=/home/alice, pending report has an unknown install override",
        "'--prefix,--prefix', pending report has an unknown install override",
        "/var/lib/symphony, pending report has an unknown install override"
    })
    @ParameterizedTest(name = "overrides [{0}] give problem [{1}]")
    void storedOverridesMustBeDistinctNamesFromTheFixedList(String overrides, @Nullable String expected) {
        // given
        HeartbeatProperties stored = properties(List.of(overrides.isEmpty() ? new String[0] : overrides.split(",")));

        // when
        Optional<String> problem = stored.invariantProblem();

        // then
        assertThat(problem).isEqualTo(Optional.ofNullable(expected));
    }

    private static HeartbeatProperties properties(@Nullable List<String> overrides) {
        return new HeartbeatProperties(1, "2026-09-22", "1.2.0", "linux", "24.04", "ubuntu", "x64", overrides, 3, 0, 0);
    }

    private static HeartbeatProperties properties(String family, String release, @Nullable String distribution) {
        return new HeartbeatProperties(1, "2026-09-22", "1.2.0", family, release, distribution, "x64", null, 3, 0, 0);
    }
}
