package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.telemetry.TelemetryOwnership.Phase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The client and the PostHog handler run in different languages and cannot share constants. These
/// checks fail when one side of the wire contract changes without the other.
final class TelemetryErasureContractTest {
    private static final Path HANDLER = Path.of("infra/posthog/erasure-service.hog.tftpl");
    private static final Path MODULE = Path.of("infra/posthog/modules/project/erasure.tf");
    private static final Path RENDERER = Path.of("scripts/erasure-service.ts");
    private static final Path LIFECYCLE_RUNNER = Path.of("scripts/erasure-lifecycle-live.ts");
    private static final Path RELEASE_PACKAGER = Path.of("scripts/package-release-assets.sh");
    private static final Path FLAG_PRUNER = Path.of("scripts/posthog-erasure-flags.ts");
    private static final Pattern HANDLER_STATUS = Pattern.compile("(?:state := |'key': )'([a-z]+)'");
    private static final Pattern PRUNER_STATUS = Pattern.compile("state === \"([a-z]+)\"");
    private static final Pattern RUNNER_STATUS =
            Pattern.compile("(?:observed ===|observed =|\\?\\?|\\) ===) \"([a-z]+)\"");

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
    void lifecycleRunnerSignsWithTheClientSignatureLifetime() throws IOException {
        // given
        String runner = Files.readString(LIFECYCLE_RUNNER);

        // when
        long lifetime = TelemetryErasureClient.SIGNATURE_LIFETIME.toSeconds();

        // then
        assertThat(runner).contains("export const SIGNATURE_LIFETIME_SECONDS = " + lifetime + ";");
    }

    @Test
    void handlerAcceptsTheClientIssuancePayloadSignedFormatAndActions() throws IOException {
        // given
        String handler = Files.readString(HANDLER);

        // when
        String runner = Files.readString(LIFECYCLE_RUNNER);

        // then
        assertThat(handler)
                .contains("if (p == '" + TelemetryErasureClient.ISSUE_PAYLOAD + "')")
                .contains("parts[1] != '" + TelemetryErasureClient.SIGNED_FORMAT + "'");
        assertThat(TelemetryErasureClient.Action.values())
                .allSatisfy(action -> assertThat(handler).contains("parts[2] != '" + action.wireName() + "'"));
        assertThat(runner)
                .contains("call(\"" + TelemetryErasureClient.ISSUE_PAYLOAD + "\")")
                .contains("[\"" + TelemetryErasureClient.SIGNED_FORMAT
                        + "\", action, scope, subject, period, operation, now, now + SIGNATURE_LIFETIME_SECONDS]");
    }

    @Test
    void handlerAndLifecycleRunnerDeriveTheStatusFlagKeyLikeTheClient() throws IOException {
        // given
        String prefix = TelemetryErasureClient.STATUS_FLAG_PREFIX;
        String context = TelemetryErasureClient.STATUS_KEY_CONTEXT;

        // when
        String handler = Files.readString(HANDLER);
        String runner = Files.readString(LIFECYCLE_RUNNER);

        // then
        assertThat(handler)
                .contains("concat('" + prefix + "', sha256HmacChainHex([key, concat('" + context
                        + "${scope}|', parts[5])]))");
        assertThat(runner).contains("`" + prefix + "${hmac(owner.secret, `" + context + "${scope}|");
    }

    @Test
    void releasePackagerWritesAndVerifiesTheErasureKeysTheClientReads() throws IOException {
        // given
        String packager = Files.readString(RELEASE_PACKAGER);

        // when
        String endpoint = TelemetryDistribution.ERASURE_ENDPOINT_KEY;
        String audience = TelemetryDistribution.ERASURE_AUDIENCE_KEY;

        // then
        assertThat(packager)
                .contains("'" + endpoint + "=%s\\n" + audience + "=%s\\n'")
                .contains("\"" + endpoint + "=$SYMPHONY_TRELLO_ERASURE_ENDPOINT\"")
                .contains("\"" + audience + "=$SYMPHONY_TRELLO_ERASURE_AUDIENCE\"");
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

    @Test
    void handlerAndScriptsUseExactlyTheClientStatusValues() throws IOException {
        // given
        List<String> wireNames = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            wireNames.add(phase.wireName());
        }

        // when
        List<String> handler = statuses(HANDLER_STATUS, Files.readString(HANDLER));
        List<String> pruner = statuses(PRUNER_STATUS, Files.readString(FLAG_PRUNER));
        List<String> runner = statuses(RUNNER_STATUS, Files.readString(LIFECYCLE_RUNNER));

        // then
        assertThat(handler).as("statuses the handler publishes").hasSameElementsAs(wireNames);
        assertThat(pruner).as("statuses the flag pruner compares").isNotEmpty().isSubsetOf(wireNames);
        assertThat(runner)
                .as("statuses the lifecycle runner compares")
                .isNotEmpty()
                .isSubsetOf(wireNames);
    }

    private static List<String> statuses(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
