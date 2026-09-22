package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TelemetryDistributionTest {
    private static final String LONG_TOKEN = "phc_" + "a1B2".repeat(11);

    @Test
    void sourceTreeDefaultsPointAtProductionWithoutAToken() {
        // given
        Map<String, String> noOverrides = Map.of();

        // when
        TelemetryDistribution distribution = TelemetryDistribution.load(noOverrides::get);

        // then
        assertThat(distribution.endpoint()).isEqualTo(TelemetryDistribution.PRODUCTION_ENDPOINT);
        assertThat(distribution.ready())
                .as("only the release workflow injects a token, so builds from the repository never send")
                .isFalse();
    }

    @Test
    void systemPropertiesOverrideEndpointAndToken() {
        // given
        Map<String, String> overrides = Map.of(
                TelemetryDistribution.ENDPOINT_PROPERTY,
                "http://127.0.0.1:18443/i/v0/e/",
                TelemetryDistribution.TOKEN_PROPERTY,
                LONG_TOKEN);

        // when
        TelemetryDistribution distribution = TelemetryDistribution.load(overrides::get);

        // then
        assertThat(distribution.endpoint()).isEqualTo(URI.create("http://127.0.0.1:18443/i/v0/e/"));
        assertThat(distribution.projectToken()).contains(LONG_TOKEN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<unset>",
                "",
                "  ",
                "phx_personal",
                "phc_with space",
                "sk_live_secret",
                "phc_",
                "phc_short",
                "phc_under_score0000000000000000000000000000000000000000"
            })
    void placeholderAndForeignTokensAreTreatedAsUnconfigured(String token) {
        // given
        String candidate = token;

        // when
        Optional<String> valid = TelemetryDistribution.validToken(candidate);

        // then
        assertThat(valid).isEmpty();
    }

    @Test
    void anInvalidEndpointOverrideBecomesADiagnosableNonSendingConfiguration() {
        // given
        Map<String, String> overrides = Map.of(
                TelemetryDistribution.ENDPOINT_PROPERTY,
                "http://127.example.invalid/not-loopback",
                TelemetryDistribution.TOKEN_PROPERTY,
                LONG_TOKEN);

        // when
        TelemetryDistribution distribution = TelemetryDistribution.load(overrides::get);

        // then
        assertThat(distribution.ready())
                .as("an invalid override never sends anywhere")
                .isFalse();
        assertThat(distribution.problem()).get().asString().contains(TelemetryDistribution.ENDPOINT_PROPERTY);
        assertThat(distribution.problem()).get().asString().doesNotContain("127.example.invalid");
    }

    @Test
    void aMalformedEndpointOverrideDoesNotThrow() {
        // given
        Map<String, String> overrides = Map.of(TelemetryDistribution.ENDPOINT_PROPERTY, "::not a uri::");

        // when
        TelemetryDistribution distribution = TelemetryDistribution.load(overrides::get);

        // then
        assertThat(distribution.ready()).as("a malformed override cannot send").isFalse();
        assertThat(distribution.problem()).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.com/i/v0/e/",
                "ftp://eu.i.posthog.com/",
                "https:///no-host",
                "http://127.example.invalid/i/v0/e/",
                "http://[::2]/i/v0/e/"
            })
    void nonHttpsNonLoopbackEndpointsAreRejected(String endpoint) {
        // given
        URI candidate = URI.create(endpoint);

        // when
        ThrowingCallable construction = () -> new TelemetryDistribution(candidate, Optional.empty());

        // then
        assertThatIllegalArgumentException().isThrownBy(construction);
    }
}
