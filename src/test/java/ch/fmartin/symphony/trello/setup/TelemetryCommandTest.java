package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.CliExitCodes.SETUP_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.telemetry.InstalledVersion;
import ch.fmartin.symphony.trello.telemetry.TelemetryDistribution;
import ch.fmartin.symphony.trello.telemetry.TelemetryEnvironment;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore;
import ch.fmartin.symphony.trello.testsupport.CliRunResult;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Process-level behavior of `symphony-trello telemetry ...` and of the board commands' telemetry
/// hooks, run through the real command boundary against an installed-looking state home.
final class TelemetryCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXISTING_BOARD = "https://trello.com/b/input/existing-board";
    /// The source tree ships without a live token; these runs configure a synthetic one.
    private static final String TEST_TOKEN = "phc_" + "commandtest0".repeat(4);

    @TempDir
    Path tempDir;

    private FakeTrelloServer trello;
    private Path configDir;
    private Path stateHome;

    @BeforeEach
    void setUp() throws IOException {
        trello = new FakeTrelloServer().start();
        configDir = Files.createDirectories(tempDir.resolve("config"));
        stateHome = Files.createDirectories(tempDir.resolve("state"));
        Files.writeString(InstalledVersion.installContextPath(stateHome), "installer=install.sh\napp_version=1.2.0\n");
    }

    @AfterEach
    void tearDown() {
        trello.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "preview", "privacy", "enable", "disable", "debug"})
    void everyTelemetrySubcommandHasHelp(String subcommand) {
        // given
        String[] help = {"telemetry", subcommand, "--help"};

        // when
        CliRunResult result = run(Map.of(), help);
        CliRunResult parent = run(Map.of(), "telemetry", "--help");

        // then
        result.assertSuccess().stdoutContains("Usage: symphony-trello telemetry " + subcommand);
        parent.assertSuccess().stdoutContains("Usage: symphony-trello telemetry", subcommand);
    }

    @Test
    void bareTelemetryCommandPrintsUsageAndUnknownSubcommandFails() {
        // given
        Map<String, String> noEnvironment = Map.of();

        // when
        CliRunResult bare = run(noEnvironment, "telemetry");
        CliRunResult unknown = run(noEnvironment, "telemetry", "watch");

        // then
        bare.assertSuccess().stdoutContains("Usage: symphony-trello telemetry");
        unknown.assertFailure(SETUP_FAILURE).stderrContains("setup_invalid_arguments", "watch");
    }

    @Test
    void previewAndStatusAreReadOnlyEvenInAnInstalledContext() {
        // given
        Map<String, String> environment = installedEnvironment();

        // when
        CliRunResult preview = run(environment, "telemetry", "preview");
        CliRunResult status = run(environment, "telemetry", "status");

        // then
        preview.assertSuccess()
                .stdoutContains("\"event\" : \"installation_heartbeat\"", "\"app_version\" : \"1.2.0\"", "not sent");
        status.assertSuccess().stdoutContains("Installed context: yes", "Stored mode: enabled (default");
        assertThat(stateHome.resolve(TelemetryStateStore.STATE_FILE)).doesNotExist();
    }

    @Test
    void disableWithoutATerminalPersistsDirectlyAndEnableRestores() throws IOException {
        // given
        Map<String, String> environment = installedEnvironment();

        // when
        CliRunResult disable = run(environment, "telemetry", "disable");
        JsonNode disabled = state();
        CliRunResult enable = run(environment, "telemetry", "enable");
        JsonNode enabled = state();

        // then
        disable.assertSuccess()
                .stdoutDoesNotContain("Disable telemetry?")
                .stdoutContains("Telemetry disabled. The orchestra will have to play this one by ear.");
        assertThat(disabled.get("mode").asText()).isEqualTo("DISABLED");
        enable.assertSuccess().stdoutContains("Telemetry enabled.");
        assertThat(enabled.get("mode").asText()).isEqualTo("ENABLED");
        assertThat(enabled.get("preference_revision").asLong()).isEqualTo(2);
    }

    @Test
    void enableIsRefusedUnderTheEnvironmentDisableOverride() {
        // given
        Map<String, String> environment = installedEnvironment();
        environment.put(TelemetryEnvironment.DISABLED_VARIABLE, "1");

        // when
        CliRunResult enable = run(environment, "telemetry", "enable");
        CliRunResult status = run(environment, "telemetry", "status");

        // then
        enable.assertFailure(1).stderrContains("cannot be enabled while SYMPHONY_TRELLO_TELEMETRY_DISABLED");
        status.assertSuccess().stdoutContains("Effective mode: disabled (SYMPHONY_TRELLO_TELEMETRY_DISABLED=1)");
    }

    @Test
    void importBoardPrintsTheNoticeOnceAndCountsOnlyNewRegistrations() throws IOException {
        // given
        Path workflow = configDir.resolve("first.WORKFLOW.md");
        Path secondWorkflow = configDir.resolve("second.WORKFLOW.md");

        // when
        CliRunResult first = importBoard(installedEnvironment(), workflow);
        JsonNode afterFirst = state();
        CliRunResult repeated = importBoard(installedEnvironment(), secondWorkflow);
        JsonNode afterRepeat = state();

        // then
        first.assertSuccess()
                .stdoutContainsSubsequence(
                        "Optional usage reporting is enabled.",
                        "Disable: symphony-trello telemetry disable",
                        "Wrote workflow");
        repeated.assertSuccess().stdoutDoesNotContain("Optional usage reporting is enabled.");
        assertThat(afterFirst.get("board_imports_total").asLong()).isEqualTo(1);
        assertThat(afterFirst.get("installation_id").isTextual())
                .as("setup registers before the operation")
                .isTrue();
        assertThat(afterFirst.get("first_worker_deadline").isNull())
                .as("setup does not start the worker countdown")
                .isTrue();
        assertThat(afterRepeat.get("board_imports_total").asLong())
                .as("re-importing an already connected board is not a new operation")
                .isEqualTo(1);
    }

    @Test
    void anInvalidEndpointOverrideStillLetsTheUserDisableAndSeeWhy() throws IOException {
        // given
        Map<String, String> properties = Map.of(
                TelemetryDistribution.TOKEN_PROPERTY,
                TEST_TOKEN,
                TelemetryDistribution.ENDPOINT_PROPERTY,
                "http://127.example.invalid/not-loopback");

        // when
        CliRunResult status = runWithProperties(properties, installedEnvironment(), "telemetry", "status");
        CliRunResult disable = runWithProperties(properties, installedEnvironment(), "telemetry", "disable", "--yes");
        JsonNode disabled = state();

        // then
        status.assertSuccess().stdoutContains("is not a usable endpoint").stdoutDoesNotContain("127.example.invalid");
        disable.assertSuccess().stdoutContains("Telemetry disabled.");
        assertThat(disabled.get("mode").asText()).isEqualTo("DISABLED");
    }

    @Test
    void failedImportCountsNothing() throws IOException {
        // given
        trello.givenRawBoardListsJson("not json");

        // when
        CliRunResult failed = importBoard(installedEnvironment(), configDir.resolve("failed.WORKFLOW.md"));
        JsonNode state = state();

        // then
        failed.assertFailure(SETUP_FAILURE);
        assertThat(state.get("installation_id").isTextual())
                .as("registration happens before the operation")
                .isTrue();
        assertThat(state.get("board_imports_total").asLong()).isZero();
        assertThat(state.get("board_creations_total").asLong()).isZero();
    }

    @Test
    void developmentRunsWithoutAnInstallerContextShowNoNoticeAndWriteNoState() throws IOException {
        // given
        Path developmentConfig = Files.createDirectories(tempDir.resolve("dev").resolve("config"));
        Map<String, String> development = new HashMap<>();
        development.put("SYMPHONY_TRELLO_CONFIG_DIR", developmentConfig.toString());
        development.put(
                "SYMPHONY_TRELLO_WORKSPACE_ROOT", tempDir.resolve("workspaces").toString());
        configDir = developmentConfig;

        // when
        CliRunResult developmentRun = importBoard(development, developmentConfig.resolve("dev.WORKFLOW.md"));

        // then
        developmentRun.assertSuccess().stdoutDoesNotContain("Optional usage reporting is enabled.");
        assertThat(developmentConfig.resolveSibling("state").resolve(TelemetryStateStore.STATE_FILE))
                .as("the default state home is untouched without an installer context")
                .doesNotExist();
    }

    private CliRunResult importBoard(Map<String, String> environment, Path workflow) {
        List<String> args = List.of(
                "import-board",
                "--endpoint",
                trello.endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                EXISTING_BOARD,
                "--workflow",
                workflow.toString(),
                "--manifest",
                configDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                configDir.resolve(".env").toString());
        return run(environment, args.toArray(String[]::new));
    }

    private Map<String, String> installedEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString());
        environment.put("SYMPHONY_TRELLO_STATE_HOME", stateHome.toString());
        environment.put(
                "SYMPHONY_TRELLO_WORKSPACE_ROOT", tempDir.resolve("workspaces").toString());
        return environment;
    }

    private JsonNode state() throws IOException {
        return JSON.readTree(Files.readString(stateHome.resolve(TelemetryStateStore.STATE_FILE)));
    }

    private CliRunResult run(Map<String, String> environment, String... args) {
        return runWithProperties(Map.of(TelemetryDistribution.TOKEN_PROPERTY, TEST_TOKEN), environment, args);
    }

    private CliRunResult runWithProperties(
            Map<String, String> properties, Map<String, String> environment, String... args) {
        var result = new AtomicReference<CliRunResult>();
        SetupSystemProperties.withLookup(properties::get, () -> {
            result.set(runWithCurrentProperties(environment, args));
            return 0;
        });
        return result.get();
    }

    private CliRunResult runWithCurrentProperties(Map<String, String> environment, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var setup = new TrelloBoardSetup(
                new ObjectMapper(),
                () -> CodexModelSelectionDefaults.of(TrelloBoardSetup.CodexModelDefaults.fallback()));
        var workerManager = new LocalWorkerManager(environment);
        String[] effectiveArgs = environment.isEmpty() ? args : withStateHome(args);
        int exitCode = TrelloBoardSetupMain.run(
                effectiveArgs,
                new TrelloBoardSetupService(setup, workerManager, environment),
                new LocalSetup(setup, new ProcessCommandRunner()),
                workerManager,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    /// The installed wrapper injects these paths; direct process runs pass them explicitly.
    private String[] withStateHome(String[] args) {
        if (!"telemetry".equals(args[0])) {
            return args;
        }
        String[] withPaths = new String[args.length + 4];
        withPaths[0] = args[0];
        withPaths[1] = "--config-dir";
        withPaths[2] = configDir.toString();
        withPaths[3] = "--state-home";
        withPaths[4] = stateHome.toString();
        System.arraycopy(args, 1, withPaths, 5, args.length - 1);
        return withPaths;
    }
}
