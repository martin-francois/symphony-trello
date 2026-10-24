package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @CsvSource({
        "1.5.0-rc.9, 1.5.0-rc.10",
        "1.5.0-rc.2, 1.5.0-rc.11",
        "1.5.0-alpha, 1.5.0-alpha.1",
        "1.5.0-alpha.1, 1.5.0-alpha.beta",
        "1.5.0-alpha.beta, 1.5.0-beta",
        "1.5.0-beta.11, 1.5.0-rc.1",
        "1.5.0-rc.99999999999, 1.5.0-rc.100000000000",
        "1.5.0-rc.10, 1.5.0"
    })
    @ParameterizedTest(name = "{0} < {1}")
    void ordersPreReleasesBySemanticVersioningPrecedence(String olderText, String newerText) {
        // given
        SymphonyVersion older = SymphonyVersion.parse(olderText).orElseThrow();

        // when
        SymphonyVersion newer = SymphonyVersion.parse(newerText).orElseThrow();

        // then
        assertThat(older).isLessThan(newer);
        assertThat(newer).isGreaterThan(older);
    }

    @Test
    void ranksNumericallyEqualQualifiersConsistentlyWithEquals() {
        // given
        SymphonyVersion padded = SymphonyVersion.parse("1.5.0-rc.01").orElseThrow();

        // when
        SymphonyVersion plain = SymphonyVersion.parse("1.5.0-rc.1").orElseThrow();

        // then
        assertThat(padded).isNotEqualTo(plain).isNotEqualByComparingTo(plain);
        assertThat(padded).isLessThan(SymphonyVersion.parse("1.5.0-rc.2").orElseThrow());
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
