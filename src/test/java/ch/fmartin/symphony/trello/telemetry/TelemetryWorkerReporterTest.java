package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TelemetryWorkerReporterTest {
    @TempDir
    Path tempDir;

    @Test
    void developmentWorkerRunsEveryLifecycleHookWithoutWritingStateOrFailing() {
        // given
        TelemetryInstallation development = TelemetryFixture.installation(tempDir.resolve("state"));
        TelemetryWorkerReporter reporter =
                new TelemetryWorkerReporter(development, Clock.fixed(TelemetryFixture.NOON, ZoneOffset.UTC));

        // when
        reporter.onStart(new StartupEvent());
        reporter.tick();
        reporter.onStop(new ShutdownEvent());

        // then
        assertThat(Files.exists(tempDir.resolve("state").resolve(TelemetryStateStore.STATE_FILE)))
                .as("a worker outside an installation never creates telemetry state")
                .isFalse();
    }

    @Test
    void anInvalidEndpointOverrideDoesNotBreakTheWorkerBean() throws IOException {
        // given
        Map<String, String> overrides = Map.of(TelemetryDistribution.ENDPOINT_PROPERTY, "::not a uri::");
        TelemetryDistribution invalid = TelemetryDistribution.load(overrides::get);
        TelemetryInstallation installation = TelemetryFixture.installation(
                TelemetryFixture.installedStateDir(tempDir), TelemetryEnvironment.none(), invalid);
        TelemetryWorkerReporter reporter =
                new TelemetryWorkerReporter(installation, Clock.fixed(TelemetryFixture.NOON, ZoneOffset.UTC));

        // when
        reporter.onStart(new StartupEvent());
        reporter.tick();

        // then
        assertThat(installation.networkEligible())
                .as("nothing can be sent with an invalid endpoint")
                .isFalse();
        assertThat(Files.exists(tempDir.resolve("state").resolve(TelemetryStateStore.STATE_FILE)))
                .as("an unconfigured worker registers nothing")
                .isFalse();
    }

    @Test
    void absentInstallationIsInertAndShutdownSendsNothing() {
        // given
        TelemetryInstallation absent =
                TelemetryInstallation.absent(TelemetryEnvironment.none(), TelemetryFixture.unconfiguredDistribution());
        TelemetryWorkerReporter reporter =
                new TelemetryWorkerReporter(absent, Clock.fixed(TelemetryFixture.NOON, ZoneOffset.UTC));

        // when
        reporter.onStart(new StartupEvent());
        reporter.onStop(new ShutdownEvent());

        // then
        assertThat(absent.store()).isEmpty();
        assertThat(absent.networkEligible())
                .as("no state directory means no eligibility")
                .isFalse();
    }
}
