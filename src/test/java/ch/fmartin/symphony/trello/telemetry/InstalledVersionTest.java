package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class InstalledVersionTest {
    @CsvSource({
        "1.2.0, 1.2.0",
        "' 1.2.0 ', 1.2.0",
        "1.2.0-rc.1, 1.2.0-rc.1",
        "unknown, ",
        "feature/telemetry, ",
        "/home/alice/app, ",
        "1.2, ",
        "v1.2.0, ",
        "1.2.0-with-a-very-long-suffix-that-keeps-going, "
    })
    @ParameterizedTest(name = "app_version {0} normalizes to {1}")
    void onlyPlainReleaseStringsPass(String raw, String expected) {
        // given
        String candidate = raw;

        // when
        Optional<String> version = InstalledVersion.normalize(candidate);

        // then
        assertThat(version).isEqualTo(Optional.ofNullable(expected));
    }
}
