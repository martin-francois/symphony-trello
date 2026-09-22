package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

final class TelemetryCredentialTest {
    private static final UUID INSTALLATION = UUID.fromString("74a69e87-089d-4fd1-8ac2-df8aab052cb1");
    private static final String TEST_SECRET = "0".repeat(64);

    @Test
    void agreesWithTheNodeOwnershipReference() {
        // given
        var credential = new TelemetryCredential(INSTALLATION, "k1", TEST_SECRET);
        String unsigned = "h1|erase|test|h1-k1-74a69e87-089d-4fd1-8ac2-df8aab052cb1"
                + "|74a69e87-089d-4fd1-8ac2-df8aab052cb1|1790070000|1790070900";

        // when
        String signature = credential.sign(unsigned);

        // then
        assertThat(signature).isEqualTo("b6e183044f5576e5b8f3513ac84a10440e30d2c8f6dd92977bc095781dd967df");
        assertThat(credential.subject()).isEqualTo("h1-k1-%s", INSTALLATION);
        assertThat(credential.sign(unsigned.replace("erase", "status"))).isNotEqualTo(signature);
        assertThat(credential.sign(unsigned.replace("|test|", "|production|"))).isNotEqualTo(signature);
    }

    @Test
    void diagnosticRenderingNeverPrintsTheSecret() {
        // given
        var credential = new TelemetryCredential(INSTALLATION, "k1", TEST_SECRET);

        // when
        String diagnostic = credential.toString();

        // then
        assertThat(diagnostic).contains("secret=redacted").doesNotContain(TEST_SECRET);
    }

    @NullAndEmptySource
    @ParameterizedTest
    @ValueSource(strings = {"invalid", "k0", "k10000", "k1|erase"})
    void rejectsInvalidKeyVersionsWithoutEchoingTheirValue(String version) {
        // given
        String candidate = version;

        // when
        Throwable failure = catchThrowable(() -> new TelemetryCredential(INSTALLATION, candidate, TEST_SECRET));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownership credential has an unsupported key version");
    }

    @NullAndEmptySource
    @ParameterizedTest
    @ValueSource(strings = {"invalid", "ABCDEF", "ffffffff"})
    void rejectsInvalidSecretsWithoutEchoingTheirValue(String secret) {
        // given
        String candidate = secret;

        // when
        Throwable failure = catchThrowable(() -> new TelemetryCredential(INSTALLATION, "k1", candidate));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownership credential has an invalid secret");
    }

    @Test
    void rejectsNonRandomInstallationIds() {
        // given
        UUID invalidId = UUID.fromString("00000000-0000-0000-0000-000000000000");

        // when
        Throwable failure = catchThrowable(() -> new TelemetryCredential(invalidId, "k1", TEST_SECRET));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ownership credential requires a random installation UUID");
    }
}
