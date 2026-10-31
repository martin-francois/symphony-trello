package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.time.ApplicationClock;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Opt-in maintainer harness: verifies erasure and same-ID reuse against the dedicated PostHog
/// test project. It runs only when [ExperimentBinding#ENABLE_VARIABLE] is `1` and every binding
/// variable is set, so `verify` and CI skip it. The exact invocation, including how to resume a
/// run PostHog has not finished, is in `docs/telemetry-erasure-verification.md`.
final class TelemetryErasureExperimentIT {
    static final int REQUEST_BUDGET = 150;
    static final Duration WAIT_BUDGET = Duration.ofMinutes(15);

    @Test
    void erasureAndSameIdReuseAgainstTheTestProject() throws Exception {
        // given
        Map<String, String> environment = System.getenv();
        assumeTrue(
                ExperimentBinding.enabled(environment),
                "Set " + ExperimentBinding.ENABLE_VARIABLE
                        + "=1 and the binding variables to run the live erasure experiment.");
        ExperimentBinding binding = ExperimentBinding.fromEnvironment(environment);
        Clock clock = ApplicationClock.systemUtc();
        ErasureExperiment.prepare(binding, clock);
        ExperimentBudget budget = ExperimentBudget.realTime(REQUEST_BUDGET, WAIT_BUDGET);
        PostHogManagementClient client = new PostHogManagementClient(
                binding.managementHost(), binding.projectId(), binding.keySupplier(), budget);

        // when
        ErasureExperiment.Outcome outcome = new ErasureExperiment(binding, client, clock, budget, System.out).run();

        // then
        System.out.println("outcome: " + outcome.kind() + " at " + outcome.phase() + ": " + outcome.message());
        assertThat(outcome.kind())
                .as("phase %s: %s", outcome.phase(), outcome.message())
                .isNotEqualTo(ErasureExperiment.Outcome.Kind.FAILED);
    }
}
