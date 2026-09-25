package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/// The client and the PostHog handler run in different languages and cannot share constants. These
/// checks fail when one side of the wire contract changes without the other.
final class TelemetryErasureContractTest {
    private static final Path HANDLER = Path.of("infra/posthog/erasure-service.hog.tftpl");
    private static final Path MODULE = Path.of("infra/posthog/modules/project/erasure.tf");
    private static final Path RENDERER = Path.of("scripts/erasure-service.ts");

    @Test
    void handlerAcceptsExactlyTheSignatureLifetimeTheClientRequests() throws IOException {
        // given
        String handler = Files.readString(HANDLER);

        // when
        long lifetime = TelemetryErasureClient.SIGNATURE_LIFETIME.toSeconds();

        // then
        assertThat(handler).contains("expires - issued > " + lifetime + " ");
    }

    @Test
    void handlerModuleAndRendererAcceptTheClientKeyVersions() throws IOException {
        // given
        String pattern = TelemetryCredential.KEY_VERSION.pattern();

        // when
        String handler = Files.readString(HANDLER);
        String module = Files.readString(MODULE);
        String renderer = Files.readString(RENDERER);

        // then
        assertThat(handler).contains("^h1-" + pattern + "-");
        assertThat(module).contains("^" + pattern + "$");
        assertThat(renderer).contains("^" + pattern + "$");
    }

    @Test
    void handlerSignaturesHaveTheShapeOfTheClientSecret() throws IOException {
        // given
        String pattern = TelemetryCredential.SECRET.pattern();

        // when
        String handler = Files.readString(HANDLER);

        // then
        assertThat(handler).contains("match(parts[9], '^" + pattern + "$')");
    }

    @Test
    void deploymentScopeIsAValidClientAudience() throws IOException {
        // given
        String module = Files.readString(MODULE);
        String renderer = Files.readString(RENDERER);

        // when
        String sampleScope = "symphony:12345";

        // then
        assertThat(module).contains("erasure_scope = \"symphony:${posthog_project.this.id}\"");
        assertThat(renderer).contains("/^" + TelemetryErasureEndpoint.AUDIENCE.pattern() + "$/");
        assertThat(sampleScope).matches(TelemetryErasureEndpoint.AUDIENCE);
    }
}
