package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SymphonyVersionTest {
    @Test
    void ordersReleasesNumericallyAndAfterTheirPreReleases() {
        // given
        String snapshotText = "1.10.0-SNAPSHOT";

        // when
        SymphonyVersion snapshot = SymphonyVersion.parse(snapshotText).orElseThrow();
        SymphonyVersion release = SymphonyVersion.parse("1.10.0").orElseThrow();
        SymphonyVersion older = SymphonyVersion.parse("1.9.3").orElseThrow();

        // then
        assertThat(older).isLessThan(snapshot);
        assertThat(snapshot).isLessThan(release);
        assertThat(release.release())
                .as("a version without qualifier is a release")
                .isTrue();
        assertThat(snapshot).hasToString("1.10.0-SNAPSHOT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "test", "1.2", "v1.2.3", "1.2.3 extra", "99999999999.0.0"})
    void rejectsTextThatIsNotASymphonyVersion(String text) {
        // given
        String versionText = text;

        // when
        var parsed = SymphonyVersion.parse(versionText);

        // then
        assertThat(parsed).isEmpty();
    }
}
