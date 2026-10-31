package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.telemetry.InstalledVersion;
import ch.fmartin.symphony.trello.telemetry.TelemetryDistribution;
import ch.fmartin.symphony.trello.telemetry.TelemetryEnvironment;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore;
import ch.fmartin.symphony.trello.testsupport.SetupRunResult;
import ch.fmartin.symphony.trello.testsupport.TestEnv;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// Guided setup shows the usage-reporting notice before the Trello work and counts the first
/// successful creation, but only inside an installed context that is not disabled.
final class LocalSetupTelemetryTest extends LocalSetupFixtureSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Path stateDir;

    @BeforeEach
    void installContext() throws IOException {
        stateDir = Files.createDirectories(tempDir.resolve(".local/state/symphony-trello"));
        Files.writeString(InstalledVersion.installContextPath(stateDir), "installer=install.sh\napp_version=1.2.0\n");
    }

    @Test
    void guidedSetupPrintsTheNoticeBeforeTrelloAndCountsTheFirstCreation() throws IOException {
        // given
        LocalSetup installed = setupWithEnvironment(installedEnvironment());

        // when
        SetupRunResult result = createBoard(installed, fixture.envPath());
        JsonNode state = JSON.readTree(Files.readString(stateDir.resolve(TelemetryStateStore.STATE_FILE)));

        // then
        result.assertSuccess()
                .stdoutContainsSubsequence(
                        "Checking prerequisites",
                        "Optional usage reporting is enabled.",
                        "Preview: symphony-trello telemetry preview",
                        "Validating Trello",
                        "Board connected:");
        assertThat(state.get("board_creations_total").asLong()).isOne();
        assertThat(state.get("board_imports_total").asLong()).isZero();
        assertThat(state.get("installation_id").isTextual())
                .as("the installation is registered")
                .isTrue();
    }

    @Test
    void guidedSetupUsesExplicitDotenvOptOutBeforeNoticeAndCounters() throws IOException {
        // given
        Map<String, String> environment = installedEnvironment();
        Files.createDirectories(fixture.configDir());
        Path configured = Files.writeString(fixture.envPath(), TelemetryEnvironment.DISABLED_VARIABLE + "=0\n");
        environment.put("SYMPHONY_TRELLO_DOTENV", configured.toString());
        Path selected = Files.writeString(
                tempDir.resolve(".env.selected"),
                TestEnv.trelloApiEndpoint(trello.endpoint()) + TelemetryEnvironment.DISABLED_VARIABLE + "=1\n");
        LocalSetup installed = setupWithEnvironment(environment);

        // when
        SetupRunResult result = createBoard(installed, selected);

        // then
        result.assertSuccess().stdoutDoesNotContain("Optional usage reporting is enabled.");
        assertThat(stateDir.resolve(TelemetryStateStore.STATE_FILE)).doesNotExist();
    }

    @Test
    void aSeparateConfigDirectoryDoesNotUseTheInstalledTelemetryState() {
        // given
        LocalSetup installed = setupWithEnvironment(installedEnvironment());
        Path isolatedConfig = tempDir.resolve("isolated/config");

        // when
        SetupRunResult result = withTestToken(() -> runSetup(
                installed,
                "--non-interactive",
                "--endpoint",
                fixture.endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Isolated Board",
                "--config-dir",
                isolatedConfig.toString(),
                "--workflow",
                isolatedConfig.resolve("WORKFLOW.md").toString(),
                "--env",
                isolatedConfig.resolve(".env").toString(),
                "--no-start",
                "--no-github"));

        // then
        result.assertSuccess().stdoutDoesNotContain("Optional usage reporting is enabled.");
        assertThat(stateDir.resolve(TelemetryStateStore.STATE_FILE)).doesNotExist();
        assertThat(isolatedConfig.resolveSibling("state").resolve(TelemetryStateStore.STATE_FILE))
                .doesNotExist();
    }

    @Test
    void disconnectingABoardKeepsTheOperationTotals() throws IOException {
        // given
        LocalSetup installed = setupWithEnvironment(installedEnvironment());
        withTestToken(() -> runSetup(
                installed,
                "--non-interactive",
                "--endpoint",
                fixture.endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Disconnected Board",
                "--workflow",
                fixture.workflowPath().toString(),
                "--env",
                fixture.envPath().toString(),
                "--no-github"));

        // when
        SetupRunResult disconnect = withTestToken(() -> runSetupWithInput(
                installed,
                "3\n1\n",
                "--endpoint",
                fixture.endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--env",
                fixture.envPath().toString(),
                "--no-github"));
        JsonNode state = JSON.readTree(Files.readString(stateDir.resolve(TelemetryStateStore.STATE_FILE)));

        // then
        disconnect.assertSuccess().stdoutContains("Symphony will stop managing \"Disconnected Board\"");
        assertThat(state.get("board_creations_total").asLong())
                .as("operation totals are never decremented")
                .isOne();
    }

    @Test
    void disabledEnvironmentOrMissingInstallContextShowsNoNoticeAndWritesNoState() throws IOException {
        // given
        Map<String, String> disabled = installedEnvironment();
        disabled.put(TelemetryEnvironment.DISABLED_VARIABLE, "true");
        LocalSetup disabledSetup = setupWithEnvironment(disabled);
        Files.delete(InstalledVersion.installContextPath(stateDir));
        LocalSetup developmentSetup = setupWithEnvironment(installedEnvironment());

        // when
        SetupRunResult first = withTestToken(() -> runSetup(
                disabledSetup, "--dry-run", "--workflow", fixture.workflowPath().toString()));
        SetupRunResult second = withTestToken(() -> runSetup(
                developmentSetup,
                "--dry-run",
                "--workflow",
                fixture.workflowPath().toString()));

        // then
        first.assertSuccess().stdoutDoesNotContain("Optional usage reporting");
        second.assertSuccess().stdoutDoesNotContain("Optional usage reporting");
        assertThat(stateDir.resolve(TelemetryStateStore.STATE_FILE)).doesNotExist();
    }

    private SetupRunResult createBoard(LocalSetup installed, Path envPath) {
        return withTestToken(() -> runSetup(
                installed,
                "--non-interactive",
                "--endpoint",
                fixture.endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Telemetry Board",
                "--workflow",
                fixture.workflowPath().toString(),
                "--env",
                envPath.toString(),
                "--no-github"));
    }

    /// The source tree ships without a live token; the setup path only registers when one exists.
    private SetupRunResult withTestToken(Supplier<SetupRunResult> run) {
        var result = new AtomicReference<SetupRunResult>();
        Map<String, String> properties = Map.of(
                TelemetryDistribution.TOKEN_PROPERTY, "phc_" + "localsetup0".repeat(4),
                InstalledCliDefaults.INSTALLED_CONFIG_DIR_PROPERTY,
                        fixture.configDir().toString(),
                InstalledCliDefaults.INSTALLED_STATE_HOME_PROPERTY, stateDir.toString());
        SetupSystemProperties.withLookup(properties::get, () -> {
            result.set(run.get());
            return 0;
        });
        return result.get();
    }

    private Map<String, String> installedEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("SYMPHONY_TRELLO_CONFIG_DIR", fixture.configDir().toString());
        environment.put("SYMPHONY_TRELLO_STATE_HOME", stateDir.toString());
        environment.put("SYMPHONY_TRELLO_COMMAND", "symphony-trello");
        return environment;
    }
}
