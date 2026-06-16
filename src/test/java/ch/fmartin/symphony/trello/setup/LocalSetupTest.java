package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.ManifestAssertions.assertThatManifest;
import static ch.fmartin.symphony.trello.setup.TerminalTranscriptAssertions.assertThatTranscript;
import static ch.fmartin.symphony.trello.setup.WorkflowAssertions.assertThatWorkflow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class LocalSetupTest extends LocalSetupFixtureSupport {
    @Test
    void dryRunReportsPlannedWorkflowWithoutChangingTrello() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.dry-run.md");

        // when
        SetupRunResult result = runSetup("--dry-run", "--workflow", workflow.toString());

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Symphony for Trello setup",
                        "Checking prerequisites",
                        "Dry run",
                        "WOULD write workflows under:");
        assertThat(trello.createdLists()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--codex-model", "--codex-reasoning-effort"})
    void dryRunRejectsBlankCodexModelOverridesBeforePlannedSetupOutput(String optionName) {
        // given

        // when
        SetupRunResult result = runSetup("--dry-run", "--non-interactive", "--no-start", optionName, " ");

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_arguments", optionName + " must not be blank.")
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "WOULD write workflows");
        assertThat(trello.createdLists()).isEmpty();
    }

    @MethodSource("blankSetupLocalOptionValues")
    @ParameterizedTest
    void dryRunRejectsBlankSetupOptionValuesBeforePlannedSetupOutput(BlankSetupLocalOption option) {
        // given

        // when
        SetupRunResult result = runSetup(option.commandArray());

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_arguments", option.expectedMessage())
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "WOULD write workflows");
        assertThat(trello.createdLists()).isEmpty();
    }

    private static Stream<BlankSetupLocalOption> blankSetupLocalOptionValues() {
        return Stream.of(
                new BlankSetupLocalOption("--board-name", "--board-name", " "),
                new BlankSetupLocalOption(
                        "--board",
                        "--board",
                        " ",
                        "--board must not be empty. Provide a Trello board name, id, or short link, or omit --board to use the command's default scope."),
                new BlankSetupLocalOption("--workspace-id", "--workspace-id", " "),
                new BlankSetupLocalOption("--active", "--active", ""),
                new BlankSetupLocalOption("--active", "--active", ","),
                new BlankSetupLocalOption("--active", "--active", "Ready for Codex, ,Inbox"),
                new BlankSetupLocalOption("--terminal", "--terminal", ""),
                new BlankSetupLocalOption("--terminal", "--terminal", "Done,   "),
                new BlankSetupLocalOption("--in-progress", "--in-progress", ""),
                new BlankSetupLocalOption("--blocked", "--blocked", " "),
                new BlankSetupLocalOption(
                        "--workflow",
                        "--workflow",
                        "",
                        "--workflow must not be empty. Provide a workflow path, or omit --workflow to use the command's default scope."),
                new BlankSetupLocalOption("--add-path", "--add-path", "", "--add-path must not be empty."),
                new BlankSetupLocalOption("--add-path", "--add-path", ",", "--add-path must not be empty."),
                new BlankSetupLocalOption("--add-path", "--add-path", "/tmp,,/root", "--add-path must not be empty."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--codex-model", "--codex-reasoning-effort"})
    void dryRunRejectsControlCharactersInCodexModelOverridesBeforePlannedSetupOutput(String optionName) {
        // given
        String invalidValue = "bad\nvalue";

        // when
        SetupRunResult result = runSetup("--dry-run", "--non-interactive", "--no-start", optionName, invalidValue);

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        optionName + " must not contain control characters.")
                .stderrDoesNotContain(invalidValue, "Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "WOULD write workflows");
        assertThat(trello.createdLists()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"--active", "--terminal", "--in-progress", "--blocked"})
    void dryRunRejectsControlCharactersInListSelectorsBeforePlannedSetupOutput(String optionName) {
        // given
        String invalidValue = "Bad\n# injected\u001B[31mred\u001B[0m";

        // when
        SetupRunResult result = runSetup("--dry-run", "--non-interactive", "--no-start", optionName, invalidValue);

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        optionName + " must not contain control characters.")
                .stderrDoesNotContain(invalidValue, "\n# injected", "\u001B", "Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "WOULD write workflows", invalidValue);
        assertThat(trello.createdLists()).isEmpty();
    }

    private record BlankSetupLocalOption(String optionName, String option, String value, String expectedMessage) {
        BlankSetupLocalOption(String optionName, String option, String value) {
            this(optionName, option, value, optionName + " must not be blank.");
        }

        String[] commandArray() {
            return new String[] {"--dry-run", "--non-interactive", "--no-start", option, value};
        }
    }

    @MethodSource("blankSetupLocalSupportedBoardSelectors")
    @ParameterizedTest(name = "{0}")
    void lifecycleSubcommandsRejectBlankSupportedBoardSelectorsBeforeBroadSelection(String name, String... command) {
        // given

        // when
        SetupRunResult result = runSetup(command);

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_arguments", "--board must not be empty.")
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Symphony setup check", "Health check");
    }

    private static Stream<Arguments> blankSetupLocalSupportedBoardSelectors() {
        return Stream.of(
                Arguments.of("check blank board", new String[] {"check", "--board", ""}),
                Arguments.of("repair-port blank board", new String[] {"repair-port", "--board", "   "}),
                Arguments.of("configure-github blank board", new String[] {"configure-github", "--board", ""}));
    }

    @Test
    void setupLocalRejectsControlCharactersInPathOptions() {
        // given
        String badWorkflow = "bad\nworkflow.WORKFLOW.md";
        String badWorkspaceRoot = "bad\nworkspace-root";
        String badConfigDir = "bad\nconfig";
        String badManifest = "bad\nmanifest.json";
        String badEnv = "bad\nenv";
        String badAddPath = "bad\nadd-path";

        List<InvalidPathOptionCase> cases = List.of(
                new InvalidPathOptionCase("--workflow", "--dry-run", "--workflow", badWorkflow),
                new InvalidPathOptionCase("--workspace-root", "--dry-run", "--workspace-root", badWorkspaceRoot),
                new InvalidPathOptionCase("--config-dir", "--dry-run", "--config-dir", badConfigDir),
                new InvalidPathOptionCase("--manifest", "--dry-run", "--manifest", badManifest),
                new InvalidPathOptionCase("--env", "--dry-run", "--env", badEnv),
                new InvalidPathOptionCase("--add-path", "--dry-run", "--add-path", badAddPath));

        // when
        List<SetupRunResult> results = cases.stream()
                .map(invalidCase -> runSetup(invalidCase.commandArray()))
                .toList();

        // then
        for (int index = 0; index < cases.size(); index++) {
            InvalidPathOptionCase invalidCase = cases.get(index);
            SetupRunResult result = results.get(index);
            result.assertFailure(2)
                    .stderrContains(
                            "setup_failed code=setup_invalid_arguments",
                            invalidCase.optionName() + " must not contain control characters")
                    .stdoutDoesNotContain("WOULD write workflows", badWorkflow, badWorkspaceRoot, badConfigDir)
                    .stderrDoesNotContain(
                            badWorkflow,
                            badWorkspaceRoot,
                            badConfigDir,
                            badManifest,
                            badEnv,
                            badAddPath,
                            "Troubleshooting report written");
        }
    }

    @Test
    void setupLocalRejectsInvalidAddPathValuesBeforePlannedOutput() {
        // given
        Path rootEquivalentPath = tempDir.getRoot().resolve("symphony").resolve("..");
        record InvalidAddPathScenario(String name, String expectedCode, String expectedMessage, List<String> command) {
            String[] commandArray() {
                return command.toArray(String[]::new);
            }
        }
        List<InvalidAddPathScenario> scenarios = List.of(
                new InvalidAddPathScenario(
                        "blank add path",
                        "setup_invalid_arguments",
                        "--add-path must not be empty.",
                        List.of("--dry-run", "--non-interactive", "--board-name", "Dry Add Blank", "--add-path", "")),
                new InvalidAddPathScenario(
                        "relative add path",
                        "setup_invalid_arguments",
                        "--add-path must be an absolute path.",
                        List.of(
                                "--dry-run",
                                "--non-interactive",
                                "--board-name",
                                "Dry Add Relative",
                                "--add-path",
                                "relative/path")),
                new InvalidAddPathScenario(
                        "dot add path",
                        "setup_invalid_arguments",
                        "--add-path must be an absolute path.",
                        List.of("--dry-run", "--non-interactive", "--board-name", "Dry Add Dot", "--add-path", ".")),
                new InvalidAddPathScenario(
                        "broad dry run add path",
                        "setup_broad_path_requires_confirmation",
                        "Refusing to allow the whole filesystem. Re-run with --allow-all-paths if that is intentional.",
                        List.of("--dry-run", "--board-name", "Dry Add Slash", "--add-path", "/")),
                new InvalidAddPathScenario(
                        "root-equivalent dry run add path",
                        "setup_broad_path_requires_confirmation",
                        "Refusing to allow the whole filesystem. Re-run with --allow-all-paths if that is intentional.",
                        List.of(
                                "--dry-run",
                                "--board-name",
                                "Dry Add Root Equivalent",
                                "--add-path",
                                rootEquivalentPath.toString())));

        // when
        List<SetupRunResult> results = scenarios.stream()
                .map(scenario -> runSetup(scenario.commandArray()))
                .toList();

        // then
        for (int index = 0; index < scenarios.size(); index++) {
            InvalidAddPathScenario scenario = scenarios.get(index);
            SetupRunResult result = results.get(index);
            result.assertFailure(2)
                    .stderrContains("setup_failed code=" + scenario.expectedCode(), scenario.expectedMessage())
                    .stderrDoesNotContain("Troubleshooting report written")
                    .stdoutDoesNotContain("Dry run", "WOULD write workflows");
        }
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalAcceptsCommaSeparatedAbsoluteAddPathsWithSpaces() {
        // given
        Path firstPath = tempDir.resolve("first allowed path");
        Path secondPath = tempDir.resolve("second allowed path");

        // when
        SetupRunResult result =
                runSetup("--dry-run", "--board-name", "Dry Add Absolute", "--add-path", firstPath + ", " + secondPath);

        // then
        result.assertSuccess().stdoutContains("Dry run", "WOULD write workflows under:");
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalAcceptsHomeShorthandAddPaths() {
        // given

        // when
        SetupRunResult result =
                runSetup("--dry-run", "--board-name", "Dry Add Home", "--add-path", "~,~/project,~/../project");

        // then
        result.assertSuccess().stdoutContains("Dry run", "WOULD write workflows under:");
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalRejectsInvalidWorkspaceRootValuesBeforePlannedOutput() throws Exception {
        // given
        Path workspaceFile = tempDir.resolve("workspace-root-file");
        Files.writeString(workspaceFile, "not a directory", StandardCharsets.UTF_8);
        Path workflow = tempDir.resolve("WORKFLOW.invalid-workspace-root.md");
        record InvalidWorkspaceRootScenario(String expectedMessage, String workspaceRoot) {}
        List<InvalidWorkspaceRootScenario> scenarios = List.of(
                new InvalidWorkspaceRootScenario("--workspace-root must not be empty.", ""),
                new InvalidWorkspaceRootScenario("--workspace-root must not be empty.", "   "),
                new InvalidWorkspaceRootScenario("--workspace-root must be an absolute path.", "."),
                new InvalidWorkspaceRootScenario("--workspace-root must be an absolute path.", " ./relative "),
                new InvalidWorkspaceRootScenario("--workspace-root must be a directory.", workspaceFile.toString()));

        // when
        List<SetupRunResult> results = scenarios.stream()
                .map(scenario -> runSetup(
                        "--dry-run",
                        "--board-name",
                        "Dry Workspace Root",
                        "--workflow",
                        workflow.toString(),
                        "--workspace-root",
                        scenario.workspaceRoot()))
                .toList();

        // then
        for (int index = 0; index < scenarios.size(); index++) {
            InvalidWorkspaceRootScenario scenario = scenarios.get(index);
            SetupRunResult result = results.get(index);
            result.assertFailure(2)
                    .stderrContains("setup_failed code=setup_invalid_arguments", scenario.expectedMessage())
                    .stderrDoesNotContain("Troubleshooting report written", workspaceFile.toString(), "not a directory")
                    .stdoutDoesNotContain("Dry run", "WOULD write workflows");
        }
        assertThat(workflow).doesNotExist();
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalPromptsBeforeUsingFilesystemRootWorkspaceRoot() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.root-workspace-declined.md");

        // when
        SetupRunResult result = runSetupWithInput(
                "n\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Root Workspace Declined",
                "--workflow",
                workflow.toString(),
                "--workspace-root",
                "/",
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Using / as the workspace root lets Symphony create per-card workspaces from the whole filesystem root.",
                        "This is unsafe unless you intentionally want that.",
                        "Use / as the workspace root anyway? [y/N]",
                        "Workspace-root selection cancelled.")
                .stdoutDoesNotContain("Created Trello board", "Wrote workflow", "Dry run");
        assertThat(workflow).doesNotExist();
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalPromptsBeforeUsingRootEquivalentWorkspaceRoot() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.root-equivalent-workspace-declined.md");
        Path rootEquivalent =
                tempDir.toAbsolutePath().getRoot().resolve("root-equivalent").resolve("..");

        // when
        SetupRunResult result = runSetupWithInput(
                "n\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Root Equivalent Workspace Declined",
                "--workflow",
                workflow.toString(),
                "--workspace-root",
                rootEquivalent.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Using / as the workspace root lets Symphony create per-card workspaces from the whole filesystem root.",
                        "Use / as the workspace root anyway? [y/N]",
                        "Workspace-root selection cancelled.")
                .stdoutDoesNotContain("Created Trello board", "Wrote workflow", "Dry run");
        assertThat(workflow).doesNotExist();
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalNonInteractiveAllowsFilesystemRootWorkspaceRoot() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.root-workspace-non-interactive.md");
        Path env = tempDir.resolve(".env.root-workspace");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Root Workspace Allowed",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--workspace-root",
                "/",
                "--no-github",
                "--no-start");

        // then
        result.assertSuccess();
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("root: \"/\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void dryRunRejectsBlankWorkflowPathBeforePlannedSetupOutput(String workflowPath) {
        // given

        // when
        SetupRunResult result = runSetup(
                "--dry-run",
                "--non-interactive",
                "--board",
                "https://trello.com/b/input/queue",
                "--workflow",
                workflowPath);

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_arguments", "--workflow must not be empty.")
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "WOULD write workflows");
        assertThat(trello.createdLists()).isEmpty();
    }

    @MethodSource("blankSetupLocalPathOptionScenarios")
    @ParameterizedTest
    void setupLocalRejectsBlankConfigAndManifestPathOptionsBeforePlannedOutput(
            InvalidSetupPathOptionScenario scenario) {
        // given

        // when
        SetupRunResult result = runSetup(scenario.commandArray());

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_arguments", scenario.expectedMessage())
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "WOULD write workflows", "Symphony setup check");
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalRejectsUnusableConfigAndManifestPathsBeforePlannedOutput() throws Exception {
        // given
        Path configFile = tempDir.resolve("config-file");
        Path manifestDirectory = tempDir.resolve("manifest-directory");
        Path invalidManifestFile = tempDir.resolve("invalid-manifest.json");
        Path manifestParentFile = tempDir.resolve("manifest-parent-file");
        Path manifestWithFileParent = manifestParentFile.resolve("connected-boards.json");
        Files.writeString(configFile, "file", StandardCharsets.UTF_8);
        Files.createDirectories(manifestDirectory);
        Files.writeString(invalidManifestFile, "file", StandardCharsets.UTF_8);
        Files.writeString(manifestParentFile, "file", StandardCharsets.UTF_8);
        List<InvalidSetupPathOptionScenario> scenarios = List.of(
                setupLocalPathScenario(
                        "setup config file",
                        "--config-dir must be a directory.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--config-dir",
                        configFile.toString()),
                setupLocalPathScenario(
                        "check config file",
                        "--config-dir must be a directory.",
                        "check",
                        "--dry-run",
                        "--board",
                        "Test Board 4",
                        "--config-dir",
                        configFile.toString()),
                setupLocalPathScenario(
                        "configure github config file",
                        "--config-dir must be a directory.",
                        "configure-github",
                        "--dry-run",
                        "--board",
                        "Test Board 4",
                        "--config-dir",
                        configFile.toString()),
                setupLocalPathScenario(
                        "setup manifest directory",
                        "--manifest must be a file path.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--manifest",
                        manifestDirectory.toString()),
                setupLocalPathScenario(
                        "configure github manifest directory",
                        "--manifest must be a file path.",
                        "configure-github",
                        "--board",
                        "Test Board 4",
                        "--manifest",
                        manifestDirectory.toString()),
                setupLocalPathScenario(
                        "setup invalid manifest file",
                        "--manifest must be a readable connected-board manifest JSON file.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--manifest",
                        invalidManifestFile.toString()),
                setupLocalPathScenario(
                        "setup manifest parent file",
                        "--manifest must be a readable connected-board manifest JSON file.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--manifest",
                        manifestWithFileParent.toString()),
                setupLocalPathScenario(
                        "configure github invalid manifest file",
                        "--manifest must be a readable connected-board manifest JSON file.",
                        "configure-github",
                        "--board",
                        "Test Board 4",
                        "--manifest",
                        invalidManifestFile.toString()),
                setupLocalPathScenario(
                        "configure github manifest parent file",
                        "--manifest must be a readable connected-board manifest JSON file.",
                        "configure-github",
                        "--board",
                        "Test Board 4",
                        "--manifest",
                        manifestWithFileParent.toString()));

        // when
        List<SetupRunResult> results = scenarios.stream()
                .map(scenario -> runSetup(scenario.commandArray()))
                .toList();

        // then
        for (int index = 0; index < scenarios.size(); index++) {
            InvalidSetupPathOptionScenario scenario = scenarios.get(index);
            SetupRunResult result = results.get(index);
            result.assertFailure(2)
                    .stderrContains("setup_failed code=setup_invalid_arguments", scenario.expectedMessage())
                    .stderrDoesNotContain(
                            "Troubleshooting report written",
                            configFile.toString(),
                            manifestDirectory.toString(),
                            invalidManifestFile.toString(),
                            manifestParentFile.toString(),
                            manifestWithFileParent.toString())
                    .stdoutDoesNotContain("Dry run", "WOULD write workflows", "Symphony setup check");
        }
        assertThat(trello.createdLists()).isEmpty();
    }

    @MethodSource("invalidEndpointValues")
    @ParameterizedTest
    @SuppressWarnings("JUnitValueSource")
    void dryRunRejectsInvalidEndpointBeforePlannedSetupOutput(String invalidEndpoint) {
        // given
        String boardName = "Endpoint Dry Run";

        // when
        SetupRunResult result = runSetup("--dry-run", "--endpoint", invalidEndpoint, "--board-name", boardName);

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--endpoint must point to the Trello REST API base, for example https://api.trello.com/1")
                .stderrDoesNotContain(invalidEndpoint, "Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "WOULD write workflows");
    }

    @Test
    void setupLocalRejectsControlCharactersInBoardNameBeforeTrelloRequest() {
        // given
        String badBoardName = "Local\nQueue";

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                badBoardName,
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", "--board-name must not contain control characters")
                .stderrDoesNotContain(badBoardName, "Troubleshooting report written")
                .stdoutDoesNotContain("Board connected", badBoardName);
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalRejectsControlCharactersInBoardSelectorBeforeTrelloRequest() {
        // given
        String badBoardSelector = "board\nselector";

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                badBoardSelector,
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", "--board must not contain control characters")
                .stderrDoesNotContain(badBoardSelector, "Troubleshooting report written")
                .stdoutDoesNotContain("Board connected", badBoardSelector);
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void setupLocalRejectsUnconnectedPlainBoardNameBeforeTrelloLookup() {
        // given
        Path isolatedConfig = tempDir.resolve("isolated-config");
        Path workflow = isolatedConfig.resolve("WORKFLOW.unconnected-board.md");
        Path env = tempDir.resolve(".env.unconnected-board");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--config-dir",
                isolatedConfig.toString(),
                "--board",
                "Unconnected Board",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github",
                "--no-start");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.")
                .stderrDoesNotContain("Trello rejected", "Troubleshooting report written")
                .stdoutDoesNotContain("Validating Trello", "Board connected");
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void setupLocalRejectsControlCharactersInWorkspaceIdBeforeTrelloRequest() {
        // given
        String badWorkspaceId = "workspace\nId";

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Local Queue",
                "--workspace-id",
                badWorkspaceId,
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workspace-id must not contain control characters")
                .stderrDoesNotContain(badWorkspaceId, "Troubleshooting report written")
                .stdoutDoesNotContain("Board connected", badWorkspaceId);
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace/path", "https://trello.com/w/workspace", "C:\\workspace"})
    void setupLocalRejectsUrlOrPathWorkspaceIdBeforePlannedSetupOutput(String badWorkspaceId) {
        // given

        // when
        SetupRunResult result = runSetup(
                "--dry-run",
                "--non-interactive",
                "--no-start",
                "--board-name",
                "Local Queue",
                "--workspace-id",
                badWorkspaceId,
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workspace-id must be a Trello Workspace id, not a URL or path.")
                .stderrDoesNotContain(badWorkspaceId, "Troubleshooting report written")
                .stdoutDoesNotContain("Dry run", "Board connected", badWorkspaceId);
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
    }

    @MethodSource("githubAuthCheckScenarios")
    @ParameterizedTest
    void checkReportsGithubAuthState(GithubAuthCheckScenario scenario) {
        // given

        // when
        SetupRunResult result = runSetup("check", scenario.githubFlag());

        // then
        if (scenario.expectedExitCode() == 0) {
            result.assertSuccess();
        } else {
            result.assertFailure(scenario.expectedExitCode()).stderrEmpty();
        }
        result.stdoutContains(scenario.expectedOutput()).stdoutDoesNotContain(scenario.forbiddenOutput());
    }

    private static Stream<GithubAuthCheckScenario> githubAuthCheckScenarios() {
        return Stream.of(
                new GithubAuthCheckScenario(
                        "--no-github treats missing GitHub auth as optional",
                        "--no-github",
                        0,
                        "OPTIONAL GitHub CLI authenticated",
                        "NEEDED  GitHub CLI authenticated"),
                new GithubAuthCheckScenario(
                        "--github treats missing GitHub auth as needed",
                        "--github",
                        2,
                        "NEEDED  GitHub CLI authenticated",
                        "OPTIONAL GitHub CLI authenticated"));
    }

    private record GithubAuthCheckScenario(
            String name, String githubFlag, int expectedExitCode, String expectedOutput, String forbiddenOutput) {
        @Override
        public String toString() {
            return name;
        }
    }

    @MethodSource("ambiguousConnectedBoardSelectorCommands")
    @ParameterizedTest
    void setupLocalRejectsAmbiguousConnectedBoardNameSelectors(AmbiguousSelectorCommand command) throws Exception {
        // given
        DuplicateConnectedBoardsFixture duplicates = duplicateConnectedBoards();

        // when
        SetupRunResult result = runSetup(command.commandArray());

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_worker_board_ambiguous",
                        "Multiple connected boards match --board. Re-run with a board id or short link.")
                .stderrDoesNotContain(
                        "Duplicate Private Board",
                        "private-board-id-one",
                        "private-board-id-two",
                        "private-key-one",
                        "private-key-two",
                        "https://trello.example/private-one",
                        "https://trello.example/private-two",
                        duplicates.firstWorkflow().toString(),
                        duplicates.secondWorkflow().toString(),
                        tempDir.toString(),
                        "Troubleshooting report written");
        result.stdoutDoesNotContain(
                "Dry run",
                "WOULD",
                "Duplicate Private Board",
                "private-board-id-one",
                "private-board-id-two",
                duplicates.firstWorkflow().toString(),
                duplicates.secondWorkflow().toString());
    }

    @Test
    void setupLocalWithInteractiveMultipleBoardsRejectsBlankBoardSelectionForCodexAccessUpdate() throws Exception {
        // given
        duplicateConnectedBoards();

        // when
        SetupRunResult result = runSetupWithInput(
                "\n",
                "--no-github",
                "--add-path",
                tempDir.resolve("selected-path").toString());

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_board_selection_required", "Board selection is required.")
                .stderrDoesNotContain("Duplicate Private Board", "Troubleshooting report written");
    }

    @Test
    void configureGithubWithInteractiveMultipleNonGithubBoardsRejectsBlankBoardSelection() throws Exception {
        // given
        duplicateConnectedBoards();

        // when
        SetupRunResult result = runSetupWithInput(
                "\n", "configure-github", "--endpoint", endpoint(), "--key", "key", "--token", "token");

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_github_upgrade_board_required", "Board selection is required.")
                .stderrDoesNotContain("Troubleshooting report written");
    }

    private static Stream<AmbiguousSelectorCommand> ambiguousConnectedBoardSelectorCommands() {
        return Stream.of(
                ambiguousSelectorCommand("check", "check", "--board", "Duplicate Private Board"),
                ambiguousSelectorCommand(
                        "repair-port", "repair-port", "--dry-run", "--board", "Duplicate Private Board"),
                ambiguousSelectorCommand(
                        "configure-github",
                        "configure-github",
                        "--non-interactive",
                        "--board",
                        "Duplicate Private Board",
                        "--key",
                        "key",
                        "--token",
                        "token"),
                ambiguousSelectorCommand(
                        "top-level",
                        "--dry-run",
                        "--non-interactive",
                        "--force",
                        "--board",
                        "Duplicate Private Board",
                        "--key",
                        "key",
                        "--token",
                        "token"),
                ambiguousSelectorCommand(
                        "top-level-real-run",
                        "--non-interactive",
                        "--board",
                        "Duplicate Private Board",
                        "--key",
                        "key",
                        "--token",
                        "token"));
    }

    private static AmbiguousSelectorCommand ambiguousSelectorCommand(String name, String... command) {
        return new AmbiguousSelectorCommand(name, List.of(command));
    }

    @Test
    void setupLocalCheckSuggestsShortLinkRepairSelectorForQuoteContainingBoardNames() throws Exception {
        // given
        // The suggested command wraps the selector in plain double quotes and the CLI rejects
        // control characters in arguments, so a quote-containing board name can never be a
        // copyable runnable suggestion; the opaque board key must be suggested instead.
        Path workflow = tempDir.resolve("WORKFLOW.quoted-name-repair.md");
        Path env = tempDir.resolve(".env.quoted-name-repair");
        int port = availablePort();
        writeWorkflow(workflow, "board-1", port);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeManifest(
                """
                {
                  "boards": [
                    {
                      "boardId": "board-1",
                      "boardKey": "abc123",
                      "boardName": "Plan \\"B\\" Queue",
                      "boardUrl": "https://trello.example/abc123",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(json(workflow), json(env), json(tempDir.resolve("workspaces")), port));
        commands.stopHealthServer(workflow.toString());
        commands.startHealthServer(workflow, "other-board");

        // when
        SetupRunResult result = runSetup("check", "--board", "board-1");

        // then
        result.assertFailure(2)
                .stdoutContains(
                        "  WARN  \"Plan \\\"B\\\" Queue\" local server:",
                        "Suggested fix: symphony-trello setup-local repair-port --board \"abc123\"")
                .stdoutDoesNotContain("repair-port --board \"Plan");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pay $(touch /tmp/probe) Queue", "Tick `id` Queue", "Try!Boom Queue", "Deploy 100% Done"})
    void setupLocalCheckSuggestsShortLinkRepairSelectorForShellExpandingBoardNames(String boardName) throws Exception {
        // given
        // Inside the suggestion's plain double quotes, $ and backticks expand in POSIX shells and
        // PowerShell, ! triggers interactive bash history expansion, and paired % expands in cmd.
        // Board names are external Trello data, so the generated command must use the opaque key
        // instead; the human-readable WARN line still shows the display-quoted name.
        Path workflow = tempDir.resolve("WORKFLOW.expanding-name-repair.md");
        Path env = tempDir.resolve(".env.expanding-name-repair");
        int port = availablePort();
        writeWorkflow(workflow, "board-1", port);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeManifest(
                """
                {
                  "boards": [
                    {
                      "boardId": "board-1",
                      "boardKey": "abc123",
                      "boardName": "%s",
                      "boardUrl": "https://trello.example/abc123",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(boardName, json(workflow), json(env), json(tempDir.resolve("workspaces")), port));
        commands.stopHealthServer(workflow.toString());
        commands.startHealthServer(workflow, "other-board");

        // when
        SetupRunResult result = runSetup("check", "--board", "board-1");

        // then
        result.assertFailure(2)
                .stdoutContains(
                        "  WARN  \"" + boardName + "\" local server:",
                        "Suggested fix: symphony-trello setup-local repair-port --board \"abc123\"")
                .stdoutDoesNotContain("repair-port --board \"" + boardName + "\"");
    }

    @Test
    void setupLocalCheckUsesShortLinkRepairSelectorForDuplicateBoardNames() throws Exception {
        // given
        DuplicateConnectedBoardsFixture duplicates = duplicateConnectedBoards();
        commands.stopHealthServer(duplicates.firstWorkflow().toString());
        commands.startHealthServer(duplicates.firstWorkflow(), "other-board");

        // when
        SetupRunResult result = runSetup("check", "--board", "private-key-one");

        // then
        result.assertFailure(2)
                .stdoutContains("Suggested fix: symphony-trello setup-local repair-port --board \"private-key-one\"")
                .stdoutDoesNotContain("setup-local repair-port --board \"Duplicate Private Board\"");
    }

    private record AmbiguousSelectorCommand(String name, List<String> command) {
        private String[] commandArray() {
            return command.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record DuplicateConnectedBoardsFixture(Path firstWorkflow, Path secondWorkflow) {}

    private record InvalidPathOptionCase(String optionName, List<String> command) {
        private InvalidPathOptionCase(String optionName, String... command) {
            this(optionName, List.of(command));
        }

        private String[] commandArray() {
            return command.toArray(String[]::new);
        }
    }

    private record InvalidSetupPathOptionScenario(String name, String expectedMessage, List<String> command) {
        private String[] commandArray() {
            return command.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record UnsupportedLifecycleOptionScenario(String subcommand, String optionName, List<String> command) {
        private UnsupportedLifecycleOptionScenario(String subcommand, String optionName, String... command) {
            this(subcommand, optionName, List.of(command));
        }

        private String[] commandArray() {
            return command.toArray(String[]::new);
        }
    }

    @Test
    void nonInteractiveSetupCreatesNonGithubBoardAndWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.local.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Local Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "GitHub integration skipped",
                        "Board connected: \"Local Queue\"",
                        "Starting Symphony",
                        "Symphony is connected to \"Local Queue\"",
                        "You're good to go - your Trello board is now a queue for Codex work.",
                        "Symphony picks it up, moves it to \"In Progress\", runs Codex, and keeps the Trello card updated.",
                        "GitHub PR flow to a connected board")
                .stdoutDoesNotContain(
                        "TRELLO_API_TOKEN=token", "Log:", "workflow's Trello handoff lists", "\u2019", "\u2014");
        assertThat(trello.createdLists())
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
        assertThat(env).content(StandardCharsets.UTF_8).contains("TRELLO_API_KEY=key", "TRELLO_API_TOKEN=token");
        assertOwnerOnlyWhenPosix(env);
        assertThatWorkflow(workflow).hasNoGithubFlow().doesNotHaveMerging();
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedEnvFiles).containsExactly(env.toString());
    }

    @Test
    void nonInteractiveSetupMissingCredentialsPrintsConfiguredEnvPath() {
        // given
        Path env = tempDir.resolve(".env.custom-credentials");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--board-name",
                "Local Queue",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_missing_trello_credentials",
                        "Next step: Provide Trello credentials with --key and --token",
                        "this .env credential file:\n  " + SetupDiagnosticReporter.displayPath(env));
        assertThat(result.stderr()).doesNotContain("Troubleshooting report written", "usually", "local .env file");
    }

    @Test
    void interactiveSetupPromptsForCodexModelAndReasoningDefaults() throws Exception {
        // given
        LocalSetup modelBackedSetup =
                setupWithCodexDefaults(new TrelloBoardSetup.CodexModelDefaults("gpt-recommended", "high"));
        Path workflow = tempDir.resolve("WORKFLOW.guided-model.md");
        Path env = tempDir.resolve(".env.guided-model");

        // when
        SetupRunResult result = runSetupWithInput(
                modelBackedSetup,
                "\n\n\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Codex model",
                        "Model [gpt-recommended]: ",
                        "Reasoning effort choices: minimal, low, medium, high",
                        "Reasoning effort [high]: ");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-recommended\"", "reasoning_effort: \"high\"");
    }

    @Test
    void interactiveSetupUsesSelectedModelReasoningDefaultFromDiscoveredCatalog() throws Exception {
        // given
        LocalSetup catalogBackedSetup = setupWithCodexSelectionDefaults(new CodexModelSelectionDefaults(
                new TrelloBoardSetup.CodexModelDefaults("gpt-5.5", "medium"),
                Map.of("gpt-5.5", "medium", "gpt-6", "high")));
        Path workflow = tempDir.resolve("WORKFLOW.selected-catalog-model.md");
        Path env = tempDir.resolve(".env.selected-catalog-model");

        // when
        SetupRunResult result = runSetupWithInput(
                catalogBackedSetup,
                "\n\ngpt-6\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess().stdoutContains("Model [gpt-5.5]: ", "Reasoning effort [high]: ");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-6\"", "reasoning_effort: \"high\"")
                .doesNotContain("reasoning_effort: \"medium\"");
    }

    @Test
    void interactiveSetupWritesSelectedCodexModelAndReasoning() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.selected-model.md");
        Path env = tempDir.resolve(".env.selected-model");

        // when
        SetupRunResult result = runSetupWithInput(
                "\n\ngpt-selected\nlow\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-selected\"", "reasoning_effort: \"low\"");
    }

    @Test
    void interactiveSetupReplacesExistingWorkflowValuesWithSelectedCodexModel() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.selected-replace-model.md");
        Path env = tempDir.resolve(".env.selected-replace-model");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-old"
                  reasoning_effort: "medium"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetupWithInput(
                "gpt-selected\nlow\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Selected Replace Model Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-selected\"", "reasoning_effort: \"low\"")
                .doesNotContain("gpt-old");
    }

    @Test
    void interactiveSetupPromptsWithExistingWorkflowCodexValuesWhenForced() throws Exception {
        // given
        LocalSetup modelBackedSetup = setupWithCodexSelectionDefaults(new CodexModelSelectionDefaults(
                new TrelloBoardSetup.CodexModelDefaults("gpt-recommended", "medium"),
                Map.of("gpt-recommended", "medium", "gpt-6", "high")));
        Path workflow = tempDir.resolve("WORKFLOW.existing-model-prompt.md");
        Path env = tempDir.resolve(".env.existing-model-prompt");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-old"
                  reasoning_effort: "low"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetupWithInput(
                modelBackedSetup,
                "\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Existing Model Prompt Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("Model [gpt-old]: ", "Reasoning effort [low]: ")
                .stdoutDoesNotContain("Model [gpt-recommended]: ", "Reasoning effort [high]: ");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-old\"", "reasoning_effort: \"low\"")
                .doesNotContain("gpt-recommended", "reasoning_effort: \"high\"");
    }

    @Test
    void interactiveSetupPreservesExistingReasoningWhenOnlyModelChanges() throws Exception {
        // given
        LocalSetup catalogBackedSetup = setupWithCodexSelectionDefaults(new CodexModelSelectionDefaults(
                new TrelloBoardSetup.CodexModelDefaults("gpt-5.5", "medium"),
                Map.of("gpt-5.5", "medium", "gpt-6", "high")));
        Path workflow = tempDir.resolve("WORKFLOW.existing-reasoning-model-change.md");
        Path env = tempDir.resolve(".env.existing-reasoning-model-change");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-old"
                  reasoning_effort: "low"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetupWithInput(
                catalogBackedSetup,
                "gpt-6\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Existing Reasoning Model Change Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("Model [gpt-old]: ", "Reasoning effort [low]: ")
                .stdoutDoesNotContain("Reasoning effort [high]: ");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-6\"", "reasoning_effort: \"low\"")
                .doesNotContain("model: \"gpt-old\"", "reasoning_effort: \"high\"");
    }

    @Test
    void setupPreservesExistingReasoningOmissionWhenModelIsUnchanged() throws Exception {
        // given
        LocalSetup catalogBackedSetup = setupWithCodexSelectionDefaults(new CodexModelSelectionDefaults(
                new TrelloBoardSetup.CodexModelDefaults("gpt-5.5", "medium"),
                Map.of("gpt-existing", "high", "gpt-5.5", "medium")));
        Path workflow = tempDir.resolve("WORKFLOW.existing-model-reasoning-omitted.md");
        Path env = tempDir.resolve(".env.existing-model-reasoning-omitted");
        int selectedPort = availablePortOtherThan(ConfigDefaults.DEFAULT_SERVER_PORT);
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-existing"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                catalogBackedSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Existing Model Reasoning Omitted Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--server-port",
                Integer.toString(selectedPort),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-existing\"")
                .doesNotContain("reasoning_effort:");
    }

    @Test
    void interactiveSetupUsesSelectedModelReasoningDefaultWhenExistingWorkflowOmitsReasoning() throws Exception {
        // given
        LocalSetup catalogBackedSetup = setupWithCodexSelectionDefaults(new CodexModelSelectionDefaults(
                new TrelloBoardSetup.CodexModelDefaults("gpt-5.5", "medium"),
                Map.of("gpt-5.5", "medium", "gpt-6", "high")));
        Path workflow = tempDir.resolve("WORKFLOW.existing-model-without-reasoning.md");
        Path env = tempDir.resolve(".env.existing-model-without-reasoning");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetupWithInput(
                catalogBackedSetup,
                "gpt-6\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Existing Model Without Reasoning Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess().stdoutContains("Model [keep workflow default]: ", "Reasoning effort [high]: ");
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("model: \"gpt-6\"", "reasoning_effort: \"high\"");
    }

    @Test
    void nonInteractiveSetupWritesExplicitCodexModelAndDefaultReasoning() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.explicit-model.md");
        Path env = tempDir.resolve(".env.explicit-model");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Explicit Model Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--codex-model",
                "gpt-explicit",
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-explicit\"", "reasoning_effort: \"medium\"");
    }

    @Test
    void explicitCodexModelOptionsReplaceExistingWorkflowValuesWhenForced() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.replace-model.md");
        Path env = tempDir.resolve(".env.replace-model");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-old"
                  reasoning_effort: "low"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Replace Model Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--codex-model",
                "gpt-new",
                "--codex-reasoning-effort",
                "high",
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-new\"", "reasoning_effort: \"high\"")
                .doesNotContain("gpt-old", "reasoning_effort: \"low\"");
    }

    @Test
    void partialCodexModelOverridePreservesExistingReasoningWhenForced() throws Exception {
        // given
        LocalSetup catalogBackedSetup = setupWithCodexSelectionDefaults(new CodexModelSelectionDefaults(
                new TrelloBoardSetup.CodexModelDefaults("gpt-5.5", "medium"),
                Map.of("gpt-5.5", "medium", "gpt-new", "high")));
        Path workflow = tempDir.resolve("WORKFLOW.partial-replace-model.md");
        Path env = tempDir.resolve(".env.partial-replace-model");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-old"
                  reasoning_effort: "low"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                catalogBackedSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Partial Replace Model Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--codex-model",
                "gpt-new",
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-new\"", "reasoning_effort: \"low\"")
                .doesNotContain("reasoning_effort: \"high\"")
                .doesNotContain("gpt-old");
    }

    @Test
    void explicitCodexModelWritesFieldsWhenDiscoveryDoesNotSupportFirstClassFields() throws Exception {
        // given
        LocalSetup unsupportedModelSetup =
                setupWithCodexDefaults(TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields());
        Path workflow = tempDir.resolve("WORKFLOW.unsupported-explicit-model.md");
        Path env = tempDir.resolve(".env.unsupported-explicit-model");

        // when
        SetupRunResult result = runSetup(
                unsupportedModelSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Unsupported Explicit Model Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--codex-model",
                "gpt-explicit",
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-explicit\"", "reasoning_effort: \"medium\"");
    }

    @Test
    void explicitCodexModelWritesFallbackReasoningWhenUnsupportedDiscoveryPreservesExistingOmission() throws Exception {
        // given
        LocalSetup unsupportedModelSetup =
                setupWithCodexDefaults(TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields());
        Path workflow = tempDir.resolve("WORKFLOW.unsupported-explicit-existing-omitted.md");
        Path env = tempDir.resolve(".env.unsupported-explicit-existing-omitted");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                unsupportedModelSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Unsupported Explicit Existing Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--codex-model",
                "gpt-explicit",
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-explicit\"", "reasoning_effort: \"medium\"");
    }

    @Test
    void setupOmitsCodexModelPromptAndFieldsWhenFirstClassFieldsUnsupported() throws Exception {
        // given
        LocalSetup unsupportedModelSetup =
                setupWithCodexDefaults(TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields());
        Path workflow = tempDir.resolve("WORKFLOW.unsupported-model.md");
        Path env = tempDir.resolve(".env.unsupported-model");

        // when
        SetupRunResult result = runSetupWithInput(
                unsupportedModelSetup,
                "\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess().stdoutDoesNotContain("Codex model", "Reasoning effort");
        assertThat(workflow).content(StandardCharsets.UTF_8).doesNotContain("model:", "reasoning_effort:");
    }

    @Test
    void interactiveSetupOmitsCodexModelPromptWhenUnsupportedDiscoveryPreservesExistingOmission() throws Exception {
        // given
        LocalSetup unsupportedModelSetup =
                setupWithCodexDefaults(TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields());
        Path workflow = tempDir.resolve("WORKFLOW.unsupported-existing-omitted.md");
        Path env = tempDir.resolve(".env.unsupported-existing-omitted");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetupWithInput(
                unsupportedModelSetup,
                "\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Unsupported Existing Omitted Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess().stdoutDoesNotContain("Codex model", "Model [", "Reasoning effort");
        assertThat(workflow).content(StandardCharsets.UTF_8).doesNotContain("model:", "reasoning_effort:");
    }

    @Test
    void nonInteractiveSetupOmitsCodexModelFieldsWhenUnsupportedDiscoveryPreservesExistingOmission() throws Exception {
        // given
        LocalSetup unsupportedModelSetup =
                setupWithCodexDefaults(TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields());
        Path workflow = tempDir.resolve("WORKFLOW.unsupported-existing-omitted-noninteractive.md");
        Path env = tempDir.resolve(".env.unsupported-existing-omitted-noninteractive");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                unsupportedModelSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Unsupported Existing Omitted Noninteractive Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow).content(StandardCharsets.UTF_8).doesNotContain("model:", "reasoning_effort:");
    }

    @Test
    void setupPreservesExistingCodexModelValuesWhenDiscoveryDoesNotSupportFirstClassFields() throws Exception {
        // given
        LocalSetup unsupportedModelSetup =
                setupWithCodexDefaults(TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields());
        Path workflow = tempDir.resolve("WORKFLOW.unsupported-existing-configured.md");
        Path env = tempDir.resolve(".env.unsupported-existing-configured");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-old"
                  reasoning_effort: "low"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                unsupportedModelSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Unsupported Existing Configured Queue",
                "--workflow",
                workflow.toString(),
                "--force",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-old\"", "reasoning_effort: \"low\"");
    }

    @Test
    void nonInteractiveSetupReadsCredentialsFromSelectedEnvFile() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.custom-env.md");
        Path env = tempDir.resolve(".env.custom");
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--board-name",
                "Env Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(trello.createdLists())
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
        assertThat(env).content(StandardCharsets.UTF_8).containsOnlyOnce("TRELLO_API_KEY=key");
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedEnvFiles).containsExactly(env.toString());
    }

    @Test
    void setupDoesNotCopyRealEnvironmentCredentialsIntoDotenv() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.real-env.md");
        Path env = tempDir.resolve(".env");
        LocalSetup environmentBackedSetup = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                tempDir.resolve("config").toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                "TRELLO_API_KEY",
                "real-key",
                "TRELLO_API_TOKEN",
                "real-token"));

        // when
        SetupRunResult result = runSetup(
                environmentBackedSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--board-name",
                "Real Env Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("Credentials loaded from environment variables")
                .stdoutDoesNotContain("real-token");
        assertThat(env).doesNotExist();
    }

    @Test
    void setupUsesWorkspaceRootFromEnvironmentWhenOptionIsOmitted() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.workspace-env.md");
        Path env = tempDir.resolve(".env");
        Path workspaceRoot = tempDir.resolve("env-workspaces");
        LocalSetup environmentBackedSetup = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                tempDir.resolve("config").toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                "SYMPHONY_TRELLO_WORKSPACE_ROOT",
                workspaceRoot.toString()));

        // when
        SetupRunResult result = runSetup(
                environmentBackedSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Workspace Env Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(workflow).content(StandardCharsets.UTF_8).contains(workspaceRoot.toString());
        assertThat(tempDir.resolve("config").resolve("connected-boards.json"))
                .content(StandardCharsets.UTF_8)
                .contains(workspaceRoot.toString());
    }

    @Test
    void setupPersistsOnlyPromptedCredentialWhenOtherCredentialComesFromRealEnvironment() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.partial-real-env.md");
        Path env = tempDir.resolve(".env");
        LocalSetup environmentBackedSetup = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                tempDir.resolve("config").toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                "TRELLO_API_KEY",
                "real-key"));

        // when
        SetupRunResult result = runSetupWithInput(
                environmentBackedSetup,
                "prompt-token\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--board-name",
                "Partial Real Env Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_TOKEN=prompt-token")
                .doesNotContain("TRELLO_API_KEY", "real-key");
        assertThatTranscript(result.stdout()).doesNotLeak("real-key").doesNotLeak("prompt-token");
    }

    @MethodSource("dotenvCredentialEscapingScenarios")
    @ParameterizedTest
    void setupEscapesCredentialsWhenWritingDotenv(DotenvCredentialEscapingScenario scenario) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW." + scenario.fileSuffix() + ".md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                scenario.apiKey(),
                "--token",
                scenario.apiToken(),
                "--board-name",
                "Escaped Env Queue " + scenario.fileSuffix(),
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains(scenario.expectedKeyLine(), scenario.expectedTokenLine());
    }

    private static Stream<DotenvCredentialEscapingScenario> dotenvCredentialEscapingScenarios() {
        return Stream.of(
                new DotenvCredentialEscapingScenario(
                        "plain",
                        "plain-key",
                        "plain-token",
                        "TRELLO_API_KEY=plain-key",
                        "TRELLO_API_TOKEN=plain-token"),
                new DotenvCredentialEscapingScenario(
                        "space",
                        "key with space",
                        "token with space",
                        "TRELLO_API_KEY=\"key with space\"",
                        "TRELLO_API_TOKEN=\"token with space\""),
                new DotenvCredentialEscapingScenario(
                        "quote",
                        "key\"quoted",
                        "token\"quoted",
                        "TRELLO_API_KEY=\"key\\\"quoted\"",
                        "TRELLO_API_TOKEN=\"token\\\"quoted\""),
                new DotenvCredentialEscapingScenario(
                        "backslash",
                        "key\\slash",
                        "token\\slash",
                        "TRELLO_API_KEY=\"key\\\\slash\"",
                        "TRELLO_API_TOKEN=\"token\\\\slash\""));
    }

    private record DotenvCredentialEscapingScenario(
            String fileSuffix, String apiKey, String apiToken, String expectedKeyLine, String expectedTokenLine) {
        @Override
        public String toString() {
            return fileSuffix;
        }
    }

    @Test
    void interactiveSetupPromptsForWorkspaceAccessAndSandboxAfterBoardSetup() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.prompt-order.md");
        Path env = tempDir.resolve(".env.prompt-order");

        // when
        SetupRunResult result = runSetupWithInput(
                "\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Prompt Order Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Trello board",
                        "Workspace access",
                        "This controls which files/folders sandboxed Trello card runs may use.",
                        "Default workspace path:",
                        "Allow Codex to run without its command/filesystem sandbox for this workflow (danger-full-access)? [y/N] ");
        result.stdoutDoesNotContain(
                "Added paths grant read/write access.",
                "Directories apply recursively.",
                "Use absolute paths, ~, or paths relative to the current directory.",
                "Local setup relies on Codex sandbox behavior and normal OS permissions, not OS-level filesystem isolation.",
                "Additional paths, comma-separated:");
        assertThatTranscript(result.stdout())
                .containsSectionsInOrder("Trello board", "Workspace access", "Codex execution");
    }

    @Test
    void nonInteractiveDangerFullAccessWritesGeneratedWorkflowPolicy() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.danger.md");
        Path env = tempDir.resolve(".env.danger");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Danger Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--danger-full-access",
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "danger-full-access disables Codex's command/filesystem sandbox", "Codex sandbox disabled")
                .stdoutDoesNotContain("[y/N]");
        assertThatWorkflow(workflow).hasDangerFullAccess();
    }

    @Test
    void setupSkipsDefaultServerPortWhenItIsAlreadyBoundLocally() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.bound-port.md");
        Path env = tempDir.resolve(".env");
        try (ServerSocket occupiedPort = new ServerSocket()) {
            try {
                occupiedPort.bind(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), TrelloBoardSetup.DEFAULT_SERVER_PORT));
            } catch (IOException e) {
                abort("Default setup port is already unavailable before the test can bind it: " + e.getMessage());
            }

            // when
            SetupRunResult result = runSetupWithProductionDefaultPort(
                    "--non-interactive",
                    "--endpoint",
                    endpoint(),
                    "--key",
                    "key",
                    "--token",
                    "token",
                    "--board-name",
                    "Bound Port Queue",
                    "--workflow",
                    workflow.toString(),
                    "--env",
                    env.toString(),
                    "--no-github");

            // then
            result.assertSuccess();
            int selectedPort = portFromSetupResult(result);
            assertThat(selectedPort).isNotEqualTo(TrelloBoardSetup.DEFAULT_SERVER_PORT);
            result.stdoutContains("Local server port selected for \"Bound Port Queue\": " + selectedPort);
            assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: " + selectedPort);
        }
    }

    @Test
    void setupPollsPositiveHttpPortOverrideWhenStartingManagedWorker() throws Exception {
        // given
        int overridePort = availablePort();
        commands.healthPortOverride = overridePort;
        LocalSetup setupWithOverride = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                tempDir.resolve("config").toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                "SYMPHONY_HTTP_PORT",
                String.valueOf(overridePort)));
        Path workflow = tempDir.resolve("WORKFLOW.port-override.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult setupResult = runSetupWithInput(
                setupWithOverride,
                "\n\nn\nn\n",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Port Override Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                "19080",
                "--no-github");
        SetupRunResult checkResult = runSetup(setupWithOverride, "check", "--endpoint", endpoint());

        // then
        setupResult.assertSuccess().stdoutContains("OK  Symphony is connected to");
        checkResult
                .assertSuccess()
                .stdoutContains("OK    \"Port Override Queue\" local server: http://127.0.0.1:" + overridePort
                        + " (already running)")
                .stdoutDoesNotContain("configured port is used by another process");
    }

    @Test
    void setupPollsPositiveHttpPortOverrideFromSelectedEnvFileWhenStartingManagedWorker() throws Exception {
        // given
        int overridePort = availablePort();
        commands.healthPortOverride = overridePort;
        Path workflow = tempDir.resolve("WORKFLOW.env-port-override.md");
        Path env = tempDir.resolve(".env.port-override");
        Files.writeString(
                env,
                """
                TRELLO_API_KEY=key
                TRELLO_API_TOKEN=token
                SYMPHONY_HTTP_PORT=%d
                """
                        .formatted(overridePort),
                StandardCharsets.UTF_8);

        // when
        SetupRunResult setupResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--board-name",
                "Dotenv Port Override Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                "19080",
                "--no-github");
        SetupRunResult checkResult = runSetup("check", "--endpoint", endpoint());

        // then
        setupResult.assertSuccess().stdoutContains("OK  Symphony is connected to");
        checkResult
                .assertSuccess()
                .stdoutContains("OK    \"Dotenv Port Override Queue\" local server: http://127.0.0.1:" + overridePort
                        + " (already running)")
                .stdoutDoesNotContain("configured port is used by another process");
    }

    @Test
    void setupResolvesRelativeUserDataPathsInsideConfigDirectory() throws Exception {
        // given
        Path config = tempDir.resolve("configured-data");
        Path workflow = config.resolve("WORKFLOW.relative.md");
        Path env = config.resolve(".env.relative");
        Path manifest = config.resolve("boards.json");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Relative Paths",
                "--config-dir",
                config.toString(),
                "--workflow",
                "WORKFLOW.relative.md",
                "--manifest",
                "boards.json",
                "--env",
                ".env.relative",
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(env).content(StandardCharsets.UTF_8).contains("TRELLO_API_KEY=key");
        assertThatManifest(manifest)
                .hasBoard("Relative Paths")
                .hasWorkflowPath("Relative Paths", workflow)
                .hasEnvPath("Relative Paths", env);
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedEnvFiles).containsExactly(env.toString());
        assertThat(tempDir.resolve("WORKFLOW.relative.md")).doesNotExist();
        assertThat(tempDir.resolve(".env.relative")).doesNotExist();
        assertThat(tempDir.resolve("boards.json")).doesNotExist();
    }

    @Test
    void nonInteractiveSetupConnectsExistingBoardFromBoardOptionWithoutPrompting() throws Exception {
        // given
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--env",
                env.toString(),
                "--no-github");

        // then
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        result.assertSuccess()
                .stdoutContains("Board connected: \"Imported Queue\"", "Symphony is connected to \"Imported Queue\"");
        assertThat(trello.createdLists()).isEmpty();
        assertThat(trello.boardLookups()).contains("/1/boards/board-1");
        assertThatWorkflow(workflow)
                .hasBoardId("abc123")
                .hasActiveStates("Ready for Codex")
                .hasTerminalStates("Human Review");
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedEnvFiles).containsExactly(env.toString());
    }

    @Test
    void reconnectsExistingBoardByConnectedBoardNameBeforeTrelloLookup() throws Exception {
        // given
        Path existingWorkflow = tempDir.resolve("WORKFLOW.connected-name.md");
        Path existingEnv = tempDir.resolve(".env.connected-name");
        fixture.givenConnectedBoard("Connected Import", existingWorkflow, existingEnv, 18144, false);
        Path env = tempDir.resolve(".env.reconnected-name");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "Connected Import",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess().stdoutContains("Board connected: \"Imported Queue\"");
        assertThat(trello.boardLookups()).contains("/1/boards/board-1");
        assertThat(trello.boardLookups()).noneMatch(path -> path.contains("Connected"));
    }

    @Test
    void existingBoardImportSkipsServerPortsReservedByConnectedBoardManifest() throws Exception {
        // given
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        Path reservedWorkflow = tempDir.resolve("other").resolve("WORKFLOW.reserved-default-port.md");
        Path reservedEnv = tempDir.resolve(".env.reserved-default-port");
        Path workflow = tempDir.resolve("WORKFLOW.import-manifest-reservation.md");
        Path env = tempDir.resolve(".env.import-manifest-reservation");
        writeConnectedBoardManifest(
                manifest, "Reserved Default Port", reservedWorkflow, reservedEnv, ConfigDefaults.DEFAULT_SERVER_PORT);
        // The scan runs in the production 18080+ range where live workers bind and release ports,
        // so the probe fakes every port as free; skipping the default port must come from the
        // manifest reservation alone.
        LocalSetup probedSetup = setupWithPortProbe(port -> false);

        // when
        SetupRunResult result = runSetupWithProductionDefaultPort(
                probedSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-start",
                "--no-github");

        // then
        int expectedPort = ConfigDefaults.DEFAULT_SERVER_PORT + 1;
        result.assertSuccess().stdoutContains("Local server port selected for \"Imported Queue\": " + expectedPort);
        assertThatWorkflow(workflow).hasServerPort(expectedPort);
        assertThatManifest(manifest).hasBoardWithPort("Imported Queue", expectedPort);
    }

    @Test
    void newBoardSetupRejectsManifestReservedRequestedPortBeforeTrelloValidation() throws Exception {
        // given
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        Path reservedWorkflow = tempDir.resolve("WORKFLOW.reserved-new-board.md");
        Path reservedEnv = tempDir.resolve(".env.reserved-new-board");
        Path workflow = tempDir.resolve("WORKFLOW.requested-conflict-new-board.md");
        Path env = tempDir.resolve(".env.requested-conflict-new-board");
        int reservedPort = firstAvailableManagedPort();
        writeConnectedBoardManifest(manifest, "Reserved New Board", reservedWorkflow, reservedEnv, reservedPort);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Requested Conflict Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(reservedPort),
                "--no-start",
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_server_port_conflict",
                        "--server-port %d is already reserved by another connected workflow.".formatted(reservedPort));
        assertThat(trello.memberLookups()).isEmpty();
        assertThat(trello.workspaceLookups()).isEmpty();
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(originalManifest);
    }

    @Test
    void existingBoardImportRejectsListeningRequestedPortBeforeTrelloBoardLookup() throws Exception {
        // given
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        Path workflow = tempDir.resolve("WORKFLOW.requested-listening-import.md");
        Path env = tempDir.resolve(".env.requested-listening-import");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{ \"boards\": [] }\n", StandardCharsets.UTF_8);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);

        // when
        SetupRunResult result;
        try (ServerSocket occupiedPort = new ServerSocket()) {
            occupiedPort.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int port = occupiedPort.getLocalPort();
            result = runSetup(
                    "--non-interactive",
                    "--endpoint",
                    endpoint(),
                    "--key",
                    "key",
                    "--token",
                    "token",
                    "--board",
                    "board-1",
                    "--workflow",
                    workflow.toString(),
                    "--env",
                    env.toString(),
                    "--server-port",
                    String.valueOf(port),
                    "--no-start",
                    "--no-github");

            result.assertFailure(2)
                    .stderrContains(
                            "setup_failed code=setup_server_port_conflict",
                            "--server-port %d is already in use on 127.0.0.1.".formatted(port));
        }

        // then
        assertThat(trello.memberLookups()).isEmpty();
        assertThat(trello.workspaceLookups()).isEmpty();
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(originalManifest);
    }

    @Test
    void existingBoardImportSkipsEnvironmentBackedSiblingWorkflowServerPorts() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path siblingWorkflow = config.resolve("WORKFLOW.sibling-env-port.md");
        Path workflow = config.resolve("WORKFLOW.import-sibling-env-port.md");
        Path env = tempDir.resolve(".env.import-sibling-env-port");
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  board_id: "sibling-board"
                server:
                  port: $SIBLING_WORKFLOW_PORT
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                env,
                """
                TRELLO_API_KEY=key
                TRELLO_API_TOKEN=token
                SIBLING_WORKFLOW_PORT=%d
                """
                        .formatted(ConfigDefaults.DEFAULT_SERVER_PORT),
                StandardCharsets.UTF_8);
        // The scan runs in the production 18080+ range where live workers bind and release ports,
        // so the probe fakes every port as free; skipping the default port must come from the
        // sibling workflow's environment-backed reservation alone.
        LocalSetup probedSetup = setupWithPortProbe(port -> false);

        // when
        SetupRunResult result = runSetupWithProductionDefaultPort(
                probedSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-start",
                "--no-github");

        // then
        int expectedPort = ConfigDefaults.DEFAULT_SERVER_PORT + 1;
        result.assertSuccess().stdoutContains("Local server port selected for \"Imported Queue\": " + expectedPort);
        assertThatWorkflow(workflow).hasServerPort(expectedPort);
        assertThatManifest(config.resolve("connected-boards.json")).hasBoardWithPort("Imported Queue", expectedPort);
    }

    @Test
    void forcedExistingBoardImportDoesNotPreserveEphemeralWorkflowServerPort() throws Exception {
        // given
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        Path workflow = tempDir.resolve("WORKFLOW.import-ephemeral-port.md");
        Path env = tempDir.resolve(".env.import-ephemeral-port");
        // The scan runs in the production 18080+ range where live workers bind and release ports,
        // so the probe fakes every port as free and the re-selected port is pure arithmetic.
        LocalSetup probedSetup = setupWithPortProbe(port -> false);
        int expectedPort = ConfigDefaults.DEFAULT_SERVER_PORT;
        writeWorkflow(workflow, "board-1", 0);

        // when
        SetupRunResult result = runSetupWithProductionDefaultPort(
                probedSetup,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--force",
                "--no-start",
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("Board connected: \"Imported Queue\"")
                .stdoutDoesNotContain("Local server port selected for \"Imported Queue\": 0");
        assertThatWorkflow(workflow).hasServerPort(expectedPort);
        assertThatManifest(manifest).hasBoardWithPort("Imported Queue", expectedPort);
    }

    @Test
    void existingBoardSetupUsesSuffixedWorkflowPathWhenGeneratedWorkflowAlreadyExists() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path existingWorkflow = config.resolve("WORKFLOW.imported-queue.md");
        Path expectedWorkflow = config.resolve("WORKFLOW.imported-queue-2.md");
        Files.writeString(existingWorkflow, "existing", StandardCharsets.UTF_8);
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(existingWorkflow).content(StandardCharsets.UTF_8).isEqualTo("existing");
        assertThat(expectedWorkflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"", "## Local And Non-GitHub Repository Work");
        assertThat(commands.startedWorkflows).containsExactly(expectedWorkflow.toString());
    }

    @Test
    void nonInteractiveSetupConnectsExistingBoardWithExplicitListMappings() throws Exception {
        // given
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Backlog","closed":false,"pos":1},
                  {"id":"list-2","name":"Build Next","closed":false,"pos":2},
                  {"id":"list-3","name":"Doing","closed":false,"pos":3},
                  {"id":"list-4","name":"Needs Help","closed":false,"pos":4},
                  {"id":"list-5","name":"Team Review","closed":false,"pos":5},
                  {"id":"list-6","name":"Shipped","closed":false,"pos":6}
                ]
                """);
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--active",
                "Build Next, Doing",
                "--in-progress",
                "Doing",
                "--terminal",
                "Shipped",
                "--blocked",
                "Needs Help",
                "--env",
                env.toString(),
                "--no-github");

        // then
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        result.assertSuccess();
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "- \"Build Next\"", "in_progress_state: \"Doing\"", "- \"Shipped\"", "list_name \"Needs Help\"")
                .doesNotContain("- \" Doing\"", "- \"\"");
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedEnvFiles).containsExactly(env.toString());
    }

    @Test
    void setupLocalReportsUnknownInProgressListAsInProgressError() {
        // given
        Path env = tempDir.resolve(".env");
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.bad-in-progress.md");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--active",
                "Ready for Codex",
                "--terminal",
                "Done",
                "--in-progress",
                "No Such List 123",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_unknown_in_progress_state",
                        "Unknown in-progress list(s): \"No Such List 123\"")
                .stderrDoesNotContain("setup_unknown_active_state", "Unknown active list(s)");
        assertThat(workflow).doesNotExist();
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void githubExistingBoardSetupCreatesMissingRecommendedLists() throws Exception {
        // given
        commands.githubAuthenticated = true;
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Inbox","closed":false,"pos":1},
                  {"id":"list-2","name":"Ready for Codex","closed":false,"pos":2},
                  {"id":"list-3","name":"In Progress","closed":false,"pos":3},
                  {"id":"list-4","name":"Blocked","closed":false,"pos":4},
                  {"id":"list-5","name":"Human Review","closed":false,"pos":5},
                  {"id":"list-6","name":"Done","closed":false,"pos":6}
                ]
                """);
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--env",
                env.toString(),
                "--github");

        // then
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        result.assertSuccess().stdoutContains("Board connected: \"Imported Queue\"");
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThatWorkflow(workflow).hasGithubFlow().hasMerging();
    }

    @Test
    void githubExistingBoardSetupValidatesListMappingsBeforeCreatingMissingMergingList() {
        // given
        commands.githubAuthenticated = true;
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--active",
                "Buildd",
                "--env",
                env.toString(),
                "--github");

        // then
        result.assertFailure(2).stderrContains("setup_unknown_active_state");
        assertThat(trello.createdLists()).isEmpty();
    }

    @Test
    void interactiveGithubExistingBoardSetupAbortsWhenMissingMergingListCreationIsDeclined() throws Exception {
        // given
        commands.githubAuthenticated = true;
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-2","name":"In Progress","closed":false,"pos":2},
                  {"id":"list-3","name":"Blocked","closed":false,"pos":3},
                  {"id":"list-4","name":"Human Review","closed":false,"pos":4},
                  {"id":"list-5","name":"Done","closed":false,"pos":5}
                ]
                """);
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");

        // when
        SetupRunResult result = runSetupWithInput(
                "n\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--active",
                "Ready for Codex",
                "--terminal",
                "Done",
                "--in-progress",
                "In Progress",
                "--blocked",
                "Blocked",
                "--github");

        // then
        result.assertFailure(2)
                .stdoutContains("GitHub mode will create this missing list: Merging")
                .stderrContains("setup_github_import_list_declined", "needs a Merging list");
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow).doesNotExist();
    }

    @Test
    void interactiveExistingBoardSetupPromptsForListMappings() throws Exception {
        // given
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Queue","closed":false,"pos":1},
                  {"id":"list-2","name":"Working","closed":false,"pos":2},
                  {"id":"list-3","name":"Blocked","closed":false,"pos":3},
                  {"id":"list-4","name":"Human Review","closed":false,"pos":4},
                  {"id":"list-5","name":"Finished","closed":false,"pos":5}
                ]
                """);

        // when
        SetupRunResult result = runSetupWithInput(
                "2\nboard-1\nQueue\nFinished\nWorking\nBlocked\n\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--no-github");

        // then
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        result.assertSuccess()
                .stdoutContains(
                        "Existing board lists",
                        "Queued-work list names",
                        "In-progress list name",
                        "move it to \"Queue\"",
                        "moves it to \"Working\"",
                        "move it to \"Finished\"")
                .stdoutDoesNotContain(
                        "move it to \"Ready for Codex\"", "moves it to \"In Progress\"", "move it to \"Done\"");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Queue\"", "in_progress_state: \"Working\"", "- \"Finished\"", "list_name \"Blocked\"");
    }

    @Test
    void interactiveExistingBoardSetupTreatsDashAsNoBlockedList() throws Exception {
        // given
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Queue","closed":false,"pos":1},
                  {"id":"list-2","name":"Working","closed":false,"pos":2},
                  {"id":"list-3","name":"Human Review","closed":false,"pos":3},
                  {"id":"list-4","name":"Finished","closed":false,"pos":4}
                ]
                """);

        // when
        SetupRunResult result = runSetupWithInput(
                "2\nboard-1\nQueue\nFinished\nWorking\n-\n\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--no-github");

        // then
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Queue\"", "in_progress_state: \"Working\"", "- \"Finished\"")
                .doesNotContain("blocked_state: \"-\"", "list_name \"-\"");
    }

    @Test
    void interactiveExistingBoardSetupCanOptOutOfDetectedOptionalLists() throws Exception {
        // given
        trello.givenRawBoardListsJson(
                """
                [
                  {"id":"list-1","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-2","name":"In Progress","closed":false,"pos":2},
                  {"id":"list-3","name":"Blocked","closed":false,"pos":3},
                  {"id":"list-4","name":"Human Review","closed":false,"pos":4},
                  {"id":"list-5","name":"Done","closed":false,"pos":5}
                ]
                """);

        // when
        SetupRunResult result = runSetupWithInput(
                "2\nboard-1\n-\n-\n\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--no-github");

        // then
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        result.assertSuccess()
                .stdoutContains(
                        "In-progress list name [In Progress, enter - for none]",
                        "Blocked list name [Blocked, enter - for none]");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Ready for Codex\"", "- \"Done\"")
                .doesNotContain("in_progress_state:", "blocked_state:", "list_name \"Blocked\"");
    }

    @Test
    void setupUsesSuffixedWorkflowPathWhenGeneratedWorkflowAlreadyExists() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path existingWorkflow = config.resolve("WORKFLOW.repeat-queue.md");
        Files.writeString(existingWorkflow, "existing", StandardCharsets.UTF_8);
        Path expectedWorkflow = config.resolve("WORKFLOW.repeat-queue-2.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Repeat Queue",
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(existingWorkflow).content(StandardCharsets.UTF_8).isEqualTo("existing");
        assertThat(expectedWorkflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"", "## Local And Non-GitHub Repository Work");
        assertThat(commands.startedWorkflows).containsExactly(expectedWorkflow.toString());
    }

    @Test
    void setupBoundsGeneratedWorkflowPathForLongBoardNames() throws Exception {
        // given
        String boardName = "Project " + "Alpha ".repeat(80);
        String expectedSlugPrefix = TrelloBoardConnector.slug(boardName).substring(0, 100);
        Path config = fixture.configDir();
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                boardName,
                "--env",
                env.toString(),
                "--no-github",
                "--no-start");

        // then
        result.assertSuccess().stdoutContains("Board connected: \"" + boardName + "\"");
        List<Path> workflows;
        try (var stream = Files.list(config)) {
            workflows = stream.filter(path -> path.getFileName().toString().startsWith("WORKFLOW."))
                    .toList();
        }
        assertThat(workflows).singleElement().satisfies(workflow -> {
            String fileName = workflow.getFileName().toString();
            assertThat(fileName)
                    .startsWith("WORKFLOW." + expectedSlugPrefix + "-")
                    .endsWith(".md")
                    .matches("WORKFLOW\\.[a-z0-9-]+-[0-9a-f]{12}\\.md")
                    .hasSizeLessThan(128);
            assertThat(workflow)
                    .content(StandardCharsets.UTF_8)
                    .contains("board_id: \"abc123\"", "## Local And Non-GitHub Repository Work");
        });
        assertThat(trello.createdLists())
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void forcedWorkflowReplacementRemovesStaleManifestEntryWithSameWorkflowPath() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path workflow = config.resolve("WORKFLOW.shared.md");
        Files.writeString(workflow, "existing", StandardCharsets.UTF_8);
        Path manifest = config.resolve("connected-boards.json");
        writeOldBoardManifest(manifest, workflow);
        Path env = tempDir.resolve(".env");
        int updatedPort = availablePort();

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(updatedPort),
                "--force",
                "--no-github");

        // then
        result.assertSuccess();
        assertThatManifest(manifest)
                .hasBoardCount(1)
                .hasBoard("Imported Queue")
                .hasBoardId("Imported Queue", "board-1")
                .hasBoardWithPort("Imported Queue", updatedPort)
                .hasNoBoard("Old Board");
        assertThatWorkflow(workflow).hasServerPort(updatedPort);
        assertThat(commands.commandEvents).containsExactly("stop:" + workflow, "start:" + workflow);
    }

    @Test
    void forcedExistingBoardImportPreservesEnvironmentBackedServerPortFromSelectedEnv() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Path workflow = config.resolve("WORKFLOW.env-backed-import.md");
        Path env = tempDir.resolve(".env.env-backed-import");
        int configuredPort = 19_091;
        fixture.givenConnectedBoard("Env Backed Import", workflow, env, configuredPort, false);
        fixture.givenWorkflow(workflow, "board-1", configuredPort);
        Files.writeString(
                workflow,
                Files.readString(workflow, StandardCharsets.UTF_8)
                        .replace("port: " + configuredPort, "port: $ENV_BACKED_IMPORT_PORT"),
                StandardCharsets.UTF_8);
        Files.writeString(
                env,
                """
                TRELLO_API_KEY=key
                TRELLO_API_TOKEN=token
                ENV_BACKED_IMPORT_PORT=%d
                """
                        .formatted(configuredPort),
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetupWithProductionDefaultPort(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--force",
                "--no-start",
                "--no-github");

        // then
        result.assertSuccess().stdoutContains("Board connected: \"Imported Queue\"");
        assertThatWorkflow(workflow).hasServerPort(configuredPort);
        assertThatManifest(config.resolve("connected-boards.json"))
                .hasBoardWithPort("Imported Queue", configuredPort)
                .hasEnvPath("Imported Queue", env);
        assertThat(commands.commandEvents).containsExactly("stop:" + workflow);
    }

    @Test
    void forcedExistingBoardImportPreservesRunningWorkflowServerPort() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Path workflow = config.resolve("WORKFLOW.running-import.md");
        Path env = tempDir.resolve(".env.running-import");
        int configuredPort = availablePort();
        fixture.givenConnectedBoard("Running Import", workflow, env, configuredPort, false);
        fixture.givenWorkflow(workflow, "board-1", configuredPort);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetupWithProductionDefaultPort(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--force",
                "--no-start",
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("Board connected: \"Imported Queue\"")
                .stdoutDoesNotContain(
                        "Local server port selected for \"Imported Queue\": " + ConfigDefaults.DEFAULT_SERVER_PORT);
        assertThatWorkflow(workflow).hasServerPort(configuredPort);
        assertThatManifest(config.resolve("connected-boards.json")).hasBoardWithPort("Imported Queue", configuredPort);
        assertThat(commands.commandEvents).containsExactly("stop:" + workflow);
    }

    @Test
    void forcedExistingBoardImportAllowsExplicitManagedCurrentServerPort() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Path workflow = config.resolve("WORKFLOW.running-explicit-import.md");
        Path env = tempDir.resolve(".env.running-explicit-import");
        int configuredPort = availablePort();
        fixture.givenConnectedBoard("Running Explicit Import", workflow, env, configuredPort, false);
        fixture.givenWorkflow(workflow, "board-1", configuredPort);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetupWithProductionDefaultPort(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(configuredPort),
                "--force",
                "--no-start",
                "--no-github");

        // then
        result.assertSuccess().stdoutContains("Board connected: \"Imported Queue\"");
        assertThatWorkflow(workflow).hasServerPort(configuredPort);
        assertThatManifest(config.resolve("connected-boards.json")).hasBoardWithPort("Imported Queue", configuredPort);
        assertThat(commands.commandEvents).containsExactly("stop:" + workflow);
    }

    @Test
    void forcedExistingBoardImportRejectsUnmanagedLiveCurrentServerPortBeforeWorkflowRewrite() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Path workflow = config.resolve("WORKFLOW.unmanaged-live-import.md");
        Path env = tempDir.resolve(".env.unmanaged-live-import");
        int configuredPort = availablePort();
        fixture.givenConnectedBoard("Unmanaged Live Import", workflow, env, configuredPort, false);
        fixture.givenWorkflow(workflow, "board-1", configuredPort);
        String originalWorkflow = Files.readString(workflow, StandardCharsets.UTF_8);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        commands.startHealthServer(workflow);
        doReturn(false).when(workerManager).canStopManagedWorker(any(), any());

        // when
        SetupRunResult result = runSetupWithProductionDefaultPort(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--force",
                "--no-start",
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_server_port_conflict",
                        "--server-port %d is already in use on 127.0.0.1.".formatted(configuredPort));
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(originalWorkflow);
        assertThatManifest(config.resolve("connected-boards.json"))
                .hasBoardCount(1)
                .hasBoard("Unmanaged Live Import")
                .hasBoardWithPort("Unmanaged Live Import", configuredPort);
        assertThat(commands.commandEvents).isEmpty();
    }

    @Test
    void forcedWorkflowReplacementStopsStaleWorkerEvenWhenStartupIsSkipped() throws Exception {
        // given
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path workflow = config.resolve("WORKFLOW.shared-no-start.md");
        Files.writeString(workflow, "existing", StandardCharsets.UTF_8);
        Path manifest = config.resolve("connected-boards.json");
        writeOldBoardManifest(manifest, workflow);
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--force",
                "--no-start",
                "--no-github");

        // then
        result.assertSuccess();
        assertThatManifest(manifest)
                .hasBoardCount(1)
                .hasBoard("Imported Queue")
                .hasBoardId("Imported Queue", "board-1")
                .hasNoBoard("Old Board");
        assertThat(commands.commandEvents).containsExactly("stop:" + workflow);
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void setupSurfacesManagedWorkerStartFailure() throws Exception {
        // given
        doThrow(new TrelloBoardSetupException("setup_worker_start_failed", "Unable to start managed worker."))
                .when(workerManager)
                .start(any(), any(), any(), any());
        Path workflow = tempDir.resolve("WORKFLOW.no-command.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "No Command Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2).stderrContains("setup_worker_start_failed", "Unable to start managed worker.");
    }

    @Test
    void setupSurfacesUnhealthyManagedWorkerStart() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.running-queue.md");
        Path env = tempDir.resolve(".env");
        doThrow(
                        new TrelloBoardSetupException(
                                "setup_start_unhealthy",
                                "Symphony start returned successfully, but /api/v1/state did not report the expected workflow."))
                .when(workerManager)
                .start(any(), any(), any(), any());

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Running Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains("setup_start_unhealthy", "/api/v1/state", "Troubleshooting report written:");
    }

    @Test
    void repairPortDoesNotTreatStoppedWorkflowNameContainingRunningAsActive() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.running-queue.md");
        Path env = tempDir.resolve(".env.running-queue");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Running Name Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        commands.commandEvents.clear();
        commands.close();
        commands.statusByWorkflow.put(workflow.toString(), "stopped WORKFLOW.running-queue.md");

        // when
        SetupRunResult result = runSetup("repair-port", "--board", "Running Name Queue");

        // then
        firstResult.assertSuccess();
        result.assertSuccess()
                .stdoutContains("No port repair needed.", "\"Running Name Queue\"")
                .stdoutDoesNotContain("WOULD   restart", "Starting Symphony");
        assertThat(commands.commandEvents).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedEnvFiles).isEmpty();
    }

    @Test
    void interactiveSetupRejectsInvalidChoiceAsExpectedInputError() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.invalid-choice.md");
        Path env = tempDir.resolve(".env.invalid-choice");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Choice Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // when
        SetupRunResult result = runSetupWithInput("x\n", "--endpoint", endpoint());

        // then
        firstResult.assertSuccess();
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_choice", "Choice must be a number between 1 and")
                .stderrDoesNotContain("For input string", "Troubleshooting report written");
    }

    @Test
    void dryRunReportsNoStartWhenNoStartIsSet() throws Exception {
        // given
        String boardName = "No Start Plan Queue";

        // when
        SetupRunResult result = runSetup(
                "--dry-run",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                boardName,
                "--no-start",
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("WOULD NOT start Symphony after setup because --no-start is set")
                .stdoutDoesNotContain("WOULD start Symphony after setup");
    }

    @Test
    void repairPortFailsActionablyWhenNoBoardsAreConnected() throws Exception {
        // given
        Path emptyManifest = tempDir.resolve("empty-connected-boards.json");
        Files.writeString(emptyManifest, "{\"boards\":[]}", StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                "repair-port", "--board", "anything", "--non-interactive", "--manifest", emptyManifest.toString());

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_repair_board_not_found", "No Trello boards are connected to Symphony");
    }

    @Test
    void repairPortAvoidsPortsReservedByLocalWorkflowFiles() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.repair-avoid.md");
        Path env = tempDir.resolve(".env.repair-avoid");
        int boardPort = availablePort();
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Avoid Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(boardPort),
                "--no-start",
                "--no-github");
        firstResult.assertSuccess();
        // Force a repair by occupying the configured port with a foreign listener.
        try (ServerSocket portBlocker = new ServerSocket(boardPort, 1, InetAddress.getLoopbackAddress())) {
            assertThat(portBlocker.isBound()).isTrue();
            int stalePort = boardPort + 1;
            Path staleWorkflow = tempDir.resolve("config").resolve("WORKFLOW.stale-port.md");
            Files.writeString(
                    staleWorkflow,
                    """
                    ---
                    tracker:
                      kind: trello
                      board_id: "stale-board"
                    server:
                      port: %d
                    ---
                    Body
                    """
                            .formatted(stalePort),
                    StandardCharsets.UTF_8);
            commands.startedWorkflows.clear();
            commands.stoppedWorkflows.clear();

            // when
            SetupRunResult result = runSetup("repair-port", "--board", "Avoid Queue");

            // then
            result.assertSuccess();
            assertThat(result.stdout()).doesNotContain("to use http://127.0.0.1:" + stalePort);
        }
    }

    @Test
    void repairPortReportsNoRepairNeededWhenConfiguredPortIsAvailable() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.repair-dry-run.md");
        Path env = tempDir.resolve(".env.repair-dry-run");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Dry Run Repair Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(availablePort()),
                "--no-github");
        firstResult.assertSuccess();
        String originalWorkflow = Files.readString(workflow, StandardCharsets.UTF_8);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        commands.commandEvents.clear();

        // when
        SetupRunResult dryRunResult = runSetup("repair-port", "--dry-run", "--board", "Dry Run Repair Queue");
        SetupRunResult repairResult = runSetup("repair-port", "--board", "Dry Run Repair Queue");

        // then
        dryRunResult
                .assertSuccess()
                .stdoutContains("No port repair needed.", "already configured for an available port")
                .stdoutDoesNotContain("WOULD   update");
        repairResult.assertSuccess().stdoutContains("No port repair needed.").stdoutDoesNotContain("Updated ");
        assertThat(Files.readString(workflow, StandardCharsets.UTF_8)).isEqualTo(originalWorkflow);
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(originalManifest);
        assertThat(commands.commandEvents).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedEnvFiles).isEmpty();
    }

    @Test
    void checkUsesWorkflowServerPortWhenManifestPortIsStale() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.stale-manifest-check.md");
        Path env = tempDir.resolve(".env.stale-manifest-check");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        int workflowPort = availablePort();
        writeWorkflow(workflow, "board-1", workflowPort);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeConnectedBoardManifest(manifest, "Stale Check Queue", workflow, env, ConfigDefaults.DEFAULT_SERVER_PORT);
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint(), "--board", "Stale Check Queue");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "OK      Workflow: " + workflow,
                        "OK    \"Stale Check Queue\" local server: http://127.0.0.1:" + workflowPort
                                + " (already running)")
                .stdoutDoesNotContain(
                        "Workflow server.port does not match the connected board",
                        "expected " + ConfigDefaults.DEFAULT_SERVER_PORT,
                        "Suggested fix:");
    }

    @Test
    void dryRunRejectsDirectoryWorkflowPathAsExpectedInput() throws Exception {
        // given
        Path workflowDirectory = tempDir.resolve("dry-run-workflow-dir");
        Files.createDirectories(workflowDirectory);

        // when
        SetupRunResult result =
                runSetup("--dry-run", "--non-interactive", "--workflow", workflowDirectory.toString(), "--force");

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_path", "directory")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(result.stdout()).doesNotContain("Dry run");
    }

    @Test
    void dryRunRejectsWorkflowUnderFileParentAsExpectedInput() throws Exception {
        // given
        Path plainFile = tempDir.resolve("dry-run-not-a-directory");
        Files.writeString(plainFile, "plain", StandardCharsets.UTF_8);
        Path workflow = plainFile.resolve("child.WORKFLOW.md");

        // when
        SetupRunResult result =
                runSetup("--dry-run", "--non-interactive", "--workflow", workflow.toString(), "--force");

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_path", "not a directory")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(result.stdout()).doesNotContain("Dry run");
    }

    @Test
    void guidedSetupValidatesWorkflowDestinationBeforeTrelloMemberValidation() throws Exception {
        // given
        Path workflowDirectory = tempDir.resolve("guided-workflow-dir");
        Files.createDirectories(workflowDirectory);

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                "http://127.0.0.1:1/",
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Preflight Queue",
                "--workflow",
                workflowDirectory.toString(),
                "--force",
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_invalid_path", "directory")
                .stderrDoesNotContain("trello_api_request", "Troubleshooting report written");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"boards\":null}", "{\"boards\":[{}]}"})
    void repairPortClassifiesInvalidManifestShapesAsExpectedConfigErrors(String manifestContent) throws Exception {
        // given
        Path manifest = tempDir.resolve("invalid-shape-connected-boards.json");
        Files.writeString(manifest, manifestContent, StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                "repair-port", "--dry-run", "--manifest", manifest.toString(), "--board", "Broken Manifest Queue");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_manifest_unavailable",
                        "Repair or remove connected-boards.json, then rerun the command.")
                .stderrDoesNotContain(
                        "NullPointerException",
                        "Cannot invoke",
                        "JsonParseException",
                        "MismatchedInputException",
                        "com.fasterxml",
                        "No Trello boards connected",
                        "Troubleshooting report written");
    }

    @Test
    void guidedSetupRejectsReferenceLookingCredentialFileValuesBeforeTrello() throws Exception {
        // given
        Path env = tempDir.resolve(".env.reference");
        Files.writeString(env, "TRELLO_API_KEY=${REAL_KEY}\nTRELLO_API_TOKEN=real-token\n", StandardCharsets.UTF_8);
        Path workflow = tempDir.resolve("WORKFLOW.reference-creds.md");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                "http://127.0.0.1:1/",
                "--board-name",
                "Reference Credentials Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_credentials_environment_reference",
                        "credential file values are used literally",
                        "export TRELLO_API_KEY in the shell environment")
                .stderrDoesNotContain("trello_auth_failed", "trello_api_request", "Troubleshooting report written");
    }

    @MethodSource("invalidManifestShapes")
    @ParameterizedTest
    void guidedSetupClassifiesInvalidManifestShapesAsExpectedConfigErrors(String name, String manifestContent)
            throws Exception {
        // given
        Files.createDirectories(fixture.configDir());
        Files.writeString(fixture.manifestPath(), manifestContent, StandardCharsets.UTF_8);
        Path workflow = tempDir.resolve("WORKFLOW.invalid-manifest.md");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                "http://127.0.0.1:1/",
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Corrupt Manifest Queue",
                "--workflow",
                workflow.toString(),
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_manifest_unavailable",
                        "Repair or remove connected-boards.json, then rerun the command.")
                .stderrDoesNotContain(
                        "NullPointerException",
                        "Cannot invoke",
                        "JsonParseException",
                        "MismatchedInputException",
                        "com.fasterxml",
                        "trello_api_request",
                        "Troubleshooting report written");
    }

    private static Stream<Arguments> invalidManifestShapes() {
        return Stream.of(
                Arguments.of("top-level null", "null"),
                Arguments.of("null boards", "{\"boards\":null}"),
                Arguments.of("top-level array", "[]"),
                Arguments.of("non-array boards", "{\"boards\":\"not-array\"}"),
                Arguments.of("null board row", "{\"boards\":[null]}"),
                Arguments.of("incomplete row", "{\"boards\":[{}]}"));
    }

    @Test
    void checkWarnsOnMalformedManifestJsonWithoutRawParserInternals() throws Exception {
        // given
        Path manifest = tempDir.resolve("malformed-connected-boards.json");
        Files.writeString(manifest, "not-valid-json", StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup("check", "--non-interactive", "--manifest", manifest.toString());

        // then
        result.assertFailure(2)
                .stdoutContains("WARN", "Connected-board manifest is not valid JSON.")
                .stderrDoesNotContain("JsonParseException", "Cannot invoke", "Troubleshooting report written");
    }

    @Test
    void checkWarnsOnIncompleteManifestRowsWithoutNullPointerInternals() throws Exception {
        // given
        Path manifest = tempDir.resolve("incomplete-connected-boards.json");
        Files.writeString(manifest, "{\"boards\":[{}]}", StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup("check", "--non-interactive", "--manifest", manifest.toString());

        // then
        result.assertFailure(2)
                .stdoutContains("WARN", "must be a non-blank string")
                .stderrDoesNotContain("Cannot invoke", "NullPointerException", "Troubleshooting report written");
    }

    @Test
    void checkReportsOverlappingConnectedWorkflowListRoles() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.overlap-check.md");
        Path env = tempDir.resolve(".env.overlap-check");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        int port = availablePort();
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Ready  for Codex"
                server:
                  port: %d
                codex:
                  command: codex app-server
                ---
                Body
                """
                        .formatted(port),
                StandardCharsets.UTF_8);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeConnectedBoardManifest(manifest, "Overlap Check Queue", workflow, env, port);

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint(), "--board", "Overlap Check Queue");

        // then
        result.assertFailure(2)
                .stdoutContains("WARN", "Workflow tracker list roles overlap", "overlapping tracker list roles")
                .stdoutDoesNotContain("OK      Workflow: " + workflow);
    }

    @Test
    void repairPortDryRunRepairsStaleManifestPortWithoutMovingHealthyWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.stale-manifest-repair.md");
        Path env = tempDir.resolve(".env.stale-manifest-repair");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        int workflowPort = availablePort();
        writeWorkflow(workflow, "board-1", workflowPort);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeConnectedBoardManifest(manifest, "Stale Repair Queue", workflow, env, ConfigDefaults.DEFAULT_SERVER_PORT);
        String originalWorkflow = Files.readString(workflow, StandardCharsets.UTF_8);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetup("repair-port", "--dry-run", "--board", "Stale Repair Queue");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Dry run",
                        "WOULD   update connected-board manifest for \"Stale Repair Queue\" to use http://127.0.0.1:"
                                + workflowPort,
                        "Workflow and running Symphony worker already use this port.")
                .stdoutDoesNotContain(
                        "WOULD   update \"Stale Repair Queue\" to use http://127.0.0.1:"
                                + ConfigDefaults.DEFAULT_SERVER_PORT,
                        "WOULD   restart Symphony");
        assertThat(Files.readString(workflow, StandardCharsets.UTF_8)).isEqualTo(originalWorkflow);
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(originalManifest);
    }

    @Test
    void repairPortUpdatesStaleManifestPortWithoutChangingHealthyWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.stale-manifest-repair-actual.md");
        Path env = tempDir.resolve(".env.stale-manifest-repair-actual");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        int workflowPort = availablePort();
        writeWorkflow(workflow, "board-1", workflowPort);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeConnectedBoardManifest(
                manifest, "Stale Actual Repair Queue", workflow, env, ConfigDefaults.DEFAULT_SERVER_PORT);
        String originalWorkflow = Files.readString(workflow, StandardCharsets.UTF_8);
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetup("repair-port", "--board", "Stale Actual Repair Queue");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "OK      Updated connected-board manifest for \"Stale Actual Repair Queue\" to use http://127.0.0.1:"
                                + workflowPort)
                .stdoutDoesNotContain("Restart:", "Updated \"Stale Actual Repair Queue\" to use");
        assertThat(Files.readString(workflow, StandardCharsets.UTF_8)).isEqualTo(originalWorkflow);
        assertThatManifest(manifest).hasBoardWithPort("Stale Actual Repair Queue", workflowPort);
        assertThat(commands.commandEvents).isEmpty();
    }

    @Test
    void repairPortDoesNotCopyHealthyWorkflowPortReservedByAnotherConnectedBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.conflicting-stale-manifest-repair.md");
        Path otherWorkflow = tempDir.resolve("WORKFLOW.other-port-owner.md");
        Path env = tempDir.resolve(".env.conflicting-stale-manifest-repair");
        Path otherEnv = tempDir.resolve(".env.other-port-owner");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        int conflictingPort = availablePort();
        // The board's own worker stays healthy on conflictingPort (real health server), but that
        // port is also reserved by Other Port Owner in the manifest, so repair must re-scan the
        // production 18080+ range and pick a different port. That scan ran with real loopback
        // probes, so a live worker binding or releasing a port between the scan and the restart
        // raced its arithmetic expectation. The probe now reports every port as in use except the
        // free port the restart will bind, so the re-selected port is deterministic while the
        // restart still binds a real port outside the production range.
        int expectedPort = availablePortOtherThan(conflictingPort);
        LocalSetup probedSetup = setupWithPortProbe(port -> port != expectedPort);
        writeWorkflow(workflow, "board-1", conflictingPort);
        writeWorkflow(otherWorkflow, "board-2", conflictingPort);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        Files.writeString(otherEnv, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeManifest(
                """
                {
                  "boards": [
                    {
                      "boardId": "board-1",
                      "boardKey": "abc123",
                      "boardName": "Conflict Repair Queue",
                      "boardUrl": "https://trello.example/abc123",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    },
                    {
                      "boardId": "board-2",
                      "boardKey": "def456",
                      "boardName": "Other Port Owner",
                      "boardUrl": "https://trello.example/def456",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(
                                json(workflow),
                                json(env),
                                json(tempDir.resolve("workspaces")),
                                ConfigDefaults.DEFAULT_SERVER_PORT,
                                json(otherWorkflow),
                                json(otherEnv),
                                json(tempDir.resolve("workspaces")),
                                conflictingPort));
        commands.startHealthServer(workflow);

        // when
        SetupRunResult result = runSetup(probedSetup, "repair-port", "--board", "Conflict Repair Queue");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "OK      Updated \"Conflict Repair Queue\" to use http://127.0.0.1:" + expectedPort,
                        "Starting Symphony...")
                .stdoutDoesNotContain(
                        "Updated connected-board manifest for \"Conflict Repair Queue\"",
                        "Workflow and running Symphony worker already use this port.");
        assertThatWorkflow(workflow).hasServerPort(expectedPort);
        assertThatManifest(manifest)
                .hasBoardWithPort("Conflict Repair Queue", expectedPort)
                .hasBoardWithPort("Other Port Owner", conflictingPort);
        assertThat(commands.commandEvents).containsExactly("stop:" + workflow, "start:" + workflow);
    }

    @Test
    void repairPortStopFailureEscapesControlCharacterBoardNames() throws Exception {
        // given
        // The manifest is hand-editable, so persisted board names may contain quotes and control
        // characters; the stop-failure message must stay one escaped line, and the failure must
        // happen before the workflow or manifest port is updated.
        Path workflow = tempDir.resolve("WORKFLOW.dirty-name-stop-failure.md");
        Path otherWorkflow = tempDir.resolve("WORKFLOW.dirty-name-port-owner.md");
        Path env = tempDir.resolve(".env.dirty-name-stop-failure");
        Path otherEnv = tempDir.resolve(".env.dirty-name-port-owner");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        int conflictingPort = availablePort();
        writeWorkflow(workflow, "board-1", conflictingPort);
        writeWorkflow(otherWorkflow, "board-2", conflictingPort);
        Files.writeString(env, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        Files.writeString(otherEnv, "TRELLO_API_KEY=key%nTRELLO_API_TOKEN=token%n".formatted(), StandardCharsets.UTF_8);
        writeManifest(
                """
                {
                  "boards": [
                    {
                      "boardId": "board-1",
                      "boardKey": "abc123",
                      "boardName": "Plan \\"B\\"\\nQueue",
                      "boardUrl": "https://trello.example/abc123",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    },
                    {
                      "boardId": "board-2",
                      "boardKey": "def456",
                      "boardName": "Other Port Owner",
                      "boardUrl": "https://trello.example/def456",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(
                                json(workflow),
                                json(env),
                                json(tempDir.resolve("workspaces")),
                                ConfigDefaults.DEFAULT_SERVER_PORT,
                                json(otherWorkflow),
                                json(otherEnv),
                                json(tempDir.resolve("workspaces")),
                                conflictingPort));
        commands.startHealthServer(workflow);
        doThrow(new IOException("simulated stop failure")).when(workerManager).stop(any(), any(), any());

        // when
        SetupRunResult result = runSetup("repair-port", "--board", "board-1");

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_stop_failed",
                        "Could not stop Symphony for \"Plan \\\"B\\\"\\nQueue\": simulated stop failure")
                .stderrDoesNotContain("Plan \"B\"\nQueue");
        assertThatWorkflow(workflow).hasServerPort(conflictingPort);
        assertThatManifest(manifest).hasBoardWithPort("Plan \"B\"\nQueue", ConfigDefaults.DEFAULT_SERVER_PORT);
        assertThat(commands.commandEvents).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Repair Selector Queue", "board-1", "abc123", "https://trello.com/b/abc123/board"})
    void repairPortAcceptsConnectedBoardNameIdKeyOrUrl(String selector) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.repair-selector.md");
        Path env = tempDir.resolve(".env.repair-selector");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Repair Selector Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(availablePort()),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        commands.commandEvents.clear();

        // when
        SetupRunResult result = runSetup("repair-port", "--board", selector);

        // then
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("No port repair needed.", "\"Repair Selector Queue\"");
    }

    @Test
    void repairPortRefusesDotenvHttpPortOverrideWithoutChangingFiles() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.dotenv-repair-override.md");
        Path env = tempDir.resolve(".env.dotenv-repair-override");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Dotenv Repair Override Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        String originalWorkflow = Files.readString(workflow, StandardCharsets.UTF_8);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(env, "SYMPHONY_HTTP_PORT=%d%n".formatted(availablePort()), StandardOpenOption.APPEND);
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        commands.commandEvents.clear();

        // when
        SetupRunResult result = runSetup("repair-port", "--board", "Dotenv Repair Override Queue");

        // then
        firstResult.assertSuccess();
        result.assertFailure(2)
                .stdoutDoesNotContain("Updated \"Dotenv Repair Override Queue\"")
                .stderrContains(
                        "setup_repair_port_http_override",
                        "SYMPHONY_HTTP_PORT in " + env,
                        "Remove or update SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT");
        assertThat(Files.readString(workflow, StandardCharsets.UTF_8)).isEqualTo(originalWorkflow);
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(originalManifest);
        assertThat(commands.commandEvents).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedEnvFiles).isEmpty();
    }

    @Test
    void repairPortRefusesEnvironmentHttpPortOverrideWithoutChangingFiles() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.environment-repair-override.md");
        Path env = tempDir.resolve(".env.environment-repair-override");
        Path manifest = tempDir.resolve("config").resolve("connected-boards.json");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Environment Repair Override Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        String originalWorkflow = Files.readString(workflow, StandardCharsets.UTF_8);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        LocalSetup setupWithOverride = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                tempDir.resolve("config").toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                "QUARKUS_HTTP_PORT",
                String.valueOf(availablePort())));
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        commands.commandEvents.clear();

        // when
        SetupRunResult result =
                runSetup(setupWithOverride, "repair-port", "--board", "Environment Repair Override Queue");

        // then
        firstResult.assertSuccess();
        result.assertFailure(2)
                .stdoutDoesNotContain("Updated \"Environment Repair Override Queue\"")
                .stderrContains(
                        "setup_repair_port_http_override",
                        "QUARKUS_HTTP_PORT environment variable",
                        "Remove or update SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT");
        assertThat(Files.readString(workflow, StandardCharsets.UTF_8)).isEqualTo(originalWorkflow);
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(originalManifest);
        assertThat(commands.commandEvents).isEmpty();
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedEnvFiles).isEmpty();
    }

    @Test
    void setupRestartsExistingBoardWithSavedEnvFile() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.saved-env.md");
        Path env = tempDir.resolve(".env.saved");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Saved Env Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();

        // when
        SetupRunResult secondResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "new-key",
                "--token",
                "new-token",
                "--no-github");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertSuccess()
                .stdoutContains(
                        "Keeping connected Trello boards.",
                        "You're good to go - your Trello board is now a queue for Codex work.");
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedEnvFiles).containsExactly(env.toString());
    }

    @Test
    void setupKeepTutorialEscapesControlCharacterWorkflowListNames() throws Exception {
        // given
        // Workflow YAML can carry Trello list names containing quotes and control characters;
        // the keep-existing tutorial must render them display-escaped on one physical line
        // instead of letting a list name split or garble the tutorial text.
        Path workflow = tempDir.resolve("WORKFLOW.dirty-tutorial.md");
        Path env = tempDir.resolve(".env.dirty-tutorial");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Dirty Tutorial Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        String content = Files.readString(workflow, StandardCharsets.UTF_8);
        content = content.replace("- \"Ready for Codex\"", "- \"Ready\\nCodex\"")
                .replace("in_progress_state: \"In Progress\"", "in_progress_state: \"Doing\\tNow\"")
                .replace("- \"In Progress\"", "- \"Doing\\tNow\"")
                .replace("- \"Done\"", "- \"Done \\\"Q\\\"\"");
        Files.writeString(workflow, content, StandardCharsets.UTF_8);

        // when
        SetupRunResult secondResult = runSetup("--non-interactive", "--no-github");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertSuccess()
                .stdoutContains(
                        "Keeping connected Trello boards.",
                        "Create a Trello card with a clear task and move it to \"Ready\\nCodex\".",
                        "Symphony picks it up, moves it to \"Doing\\tNow\", runs Codex",
                        "If you accept it, move it to \"Done \\\"Q\\\"\".")
                .stdoutDoesNotContain("Ready\nCodex", "Doing\tNow", "Done \"Q\"");
    }

    @Test
    void setupKeepsExistingManifestWithoutFreshTrelloCredentials() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.saved-only.md");
        Path env = tempDir.resolve(".env.saved-only");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Saved Only Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        trello.memberLookups().clear();
        trello.workspaceLookups().clear();

        // when
        SetupRunResult secondResult = runSetup("--non-interactive", "--no-github");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertSuccess()
                .stdoutContains(
                        "Keeping connected Trello boards.",
                        "You're good to go - your Trello board is now a queue for Codex work.");
        assertThat(trello.memberLookups()).isEmpty();
        assertThat(trello.workspaceLookups()).isEmpty();
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedEnvFiles).containsExactly(env.toString());
    }

    @Test
    void existingBoardSetupDoesNotRequireWorkspaceSelection() throws Exception {
        // given
        trello.givenWorkspaces("[]");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "board-1",
                "--env",
                env.toString(),
                "--no-github");

        // then
        Path workflow = tempDir.resolve("config").resolve("WORKFLOW.imported-queue.md");
        result.assertSuccess().stdoutContains("Board connected: \"Imported Queue\"");
        assertThat(trello.workspaceLookups()).isEmpty();
        assertThat(trello.boardLookups()).contains("/1/boards/board-1");
        assertThat(workflow).exists();
    }

    @Test
    void nonInteractiveNewBoardRequiresWorkspaceIdWhenTokenHasMultipleWorkspaces() {
        // given
        trello.givenWorkspaces(
                """
                [
                  {"id":"workspace-1","name":"first","displayName":"First"},
                  {"id":"workspace-2","name":"second","displayName":"Second"}
                ]
        """);
        Path workflow = tempDir.resolve("WORKFLOW.multi-workspace.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Ambiguous Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2).stderrContains("setup_workspace_id_required", "--workspace-id");
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow).doesNotExist();
    }

    @Test
    void interactiveNewBoardWithMultipleWorkspacesRejectsBlankWorkspaceSelection() {
        // given
        trello.givenWorkspaces(
                """
                [
                  {"id":"workspace-1","name":"first","displayName":"First"},
                  {"id":"workspace-2","name":"second","displayName":"Second"}
                ]
        """);
        Path workflow = tempDir.resolve("WORKFLOW.multi-workspace-blank.md");
        Path env = tempDir.resolve(".env.blank-workspace");

        // when
        SetupRunResult result = runSetupWithInput(
                "\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Interactive Workspace Selection",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2)
                .stderrContains("setup_failed code=setup_workspace_id_required", "Workspace selection is required.")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow).doesNotExist();
    }

    @Test
    void setupWithConnectAnotherGithubBoardConnectsNewBoardInsteadOfUpgradingExistingBoard() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.local-first.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = connectLocalBoardWithoutGithub(firstWorkflow, env, "Local First");
        prepareNextSetupRunWithGithubAuth();

        // when
        SetupRunResult secondResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Explicit",
                "--env",
                env.toString(),
                "--github");

        // then
        Path secondWorkflow = tempDir.resolve("config").resolve("WORKFLOW.github-explicit.md");
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("Board connected: \"GitHub Explicit\"");
        assertThatWorkflow(firstWorkflow).hasNoGithubFlow();
        assertThatWorkflow(secondWorkflow).hasGithubFlow();
        assertThat(trello.createdLists())
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThat(commands.stoppedWorkflows).containsExactly(firstWorkflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(secondWorkflow.toString());
    }

    @Test
    void configureGithubAppliesExplicitCodexModelOverrides() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-upgrade-model.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Upgrade Model",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--codex-model",
                "gpt-old",
                "--codex-reasoning-effort",
                "low",
                "--no-github");
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--codex-model",
                "gpt-new",
                "--codex-reasoning-effort",
                "high");

        // then
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("GitHub workflow enabled for \"GitHub Upgrade Model\"");
        assertThatWorkflow(workflow).hasGithubFlow();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-new\"", "reasoning_effort: \"high\"")
                .doesNotContain("gpt-old", "reasoning_effort: \"low\"");
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void configureGithubAppliesExplicitMaxAgents() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-upgrade-max-agents.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Upgrade Max Agents",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--max-agents",
                "3");

        // then
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("GitHub workflow enabled for \"GitHub Upgrade Max Agents\"");
        assertThatWorkflow(workflow).hasGithubFlow().hasMaxAgents(3);
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void configureGithubPreservesEnvironmentBackedServerPort() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-upgrade-env-port.md");
        Path env = tempDir.resolve(".env");
        int configuredPort = firstAvailableManagedPort();
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Upgrade Env Port",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(configuredPort),
                "--no-github");
        firstResult.assertSuccess();
        Files.writeString(
                workflow,
                Files.readString(workflow, StandardCharsets.UTF_8)
                        .replace("port: " + configuredPort, "port: $GITHUB_UPGRADE_PORT"),
                StandardCharsets.UTF_8);
        Files.writeString(env, "GITHUB_UPGRADE_PORT=" + configuredPort + "\n", StandardOpenOption.APPEND);
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        commands.stoppedWorkflows.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github", "--non-interactive", "--endpoint", endpoint(), "--key", "key", "--token", "token");

        // then
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("GitHub workflow enabled for \"GitHub Upgrade Env Port\"");
        assertThatWorkflow(workflow).hasGithubFlow().hasServerPort(configuredPort);
        assertThat(trello.createdLists()).containsExactly("Merging");
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void configureGithubRejectsWorkflowOptionsItCannotApply() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-upgrade-ignored-option.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Upgrade Ignored Option",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--server-port",
                "19000",
                "--codex-model",
                "gpt-new");

        // then
        firstResult.assertSuccess();
        secondResult.assertFailure(2).stderrContains("setup_invalid_arguments", "--server-port");
        assertThatWorkflow(workflow).doesNotHaveServerPort(19000);
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void configureGithubRejectsWorkflowOptionsBeforeCodexAuth() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-upgrade-before-auth.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Upgrade Before Auth",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.codexAuthenticated = false;
        commands.githubAuthenticated = true;
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--server-port",
                "19000");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertFailure(2)
                .stderrContains("setup_invalid_arguments", "--server-port")
                .stderrDoesNotContain("setup_codex_auth_required");
        assertThatWorkflow(workflow).hasNoGithubFlow().doesNotHaveServerPort(19000);
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void configureGithubRejectsUnknownBoardSelectorWithActionableMessage() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-upgrade-unknown-selector.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Upgrade Unknown Selector",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "definitely-not-a-board",
                "--no-start");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertFailure(2)
                .stderrContains(
                        "setup_github_upgrade_not_found",
                        "No connected non-GitHub board matches \"definitely-not-a-board\"")
                .stderrDoesNotContain("setup-local configure-github can apply", "Troubleshooting report written");
        assertThatWorkflow(workflow).hasNoGithubFlow();
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void configureGithubRejectsWorkflowSelectorWithActionableMessage() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-upgrade-workflow-selector.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Upgrade Workflow Selector",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--workflow",
                tempDir.resolve("definitely-not-a-workflow.md").toString(),
                "--no-start");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertFailure(2)
                .stderrContains("setup_invalid_arguments", "setup-local configure-github does not support --workflow")
                .stderrDoesNotContain("setup-local configure-github can apply", "Troubleshooting report written");
        assertThatWorkflow(workflow).hasNoGithubFlow();
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void configureGithubRejectsWorkflowOptionsWhenNoUpgradeWillRun() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-already-enabled-model.md");
        Path env = tempDir.resolve(".env");
        commands.githubAuthenticated = true;
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Already Enabled Model",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--codex-model",
                "gpt-new");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertFailure(2)
                .stderrContains("setup_github_upgrade_not_found", "non-GitHub connected Trello board");
        assertThat(workflow).content(StandardCharsets.UTF_8).doesNotContain("gpt-new");
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void configureGithubRejectsUnsupportedWorkflowOptionsWhenNoUpgradeWillRun() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-already-enabled-server-port.md");
        Path env = tempDir.resolve(".env");
        commands.githubAuthenticated = true;
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Already Enabled Server Port",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");
        commands.startedWorkflows.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult secondResult = runSetup(
                "configure-github",
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--server-port",
                "19000");

        // then
        firstResult.assertSuccess();
        secondResult.assertFailure(2).stderrContains("setup_invalid_arguments", "--server-port");
        assertThatWorkflow(workflow).doesNotHaveServerPort(19000);
        assertThat(commands.stoppedWorkflows).isEmpty();
        assertThat(commands.startedWorkflows).isEmpty();
    }

    @Test
    void nonInteractiveSetupWithExistingManifestConnectsExplicitBoardRequest() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "First Queue",
                "--workflow",
                firstWorkflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult secondResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Second Queue",
                "--env",
                env.toString(),
                "--no-github");

        // then
        Path secondWorkflow = tempDir.resolve("config").resolve("WORKFLOW.second-queue.md");
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("Board connected: \"Second Queue\"");
        assertThat(trello.createdLists())
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
        assertThat(secondWorkflow).content(StandardCharsets.UTF_8).contains("## Local And Non-GitHub Repository Work");
        assertThat(commands.startedWorkflows).containsExactly(secondWorkflow.toString());
    }

    @Test
    void disconnectBoardCancelsWhenBoardNumberIsBlank() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.disconnect.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Disconnect Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        commands.startedEnvFiles.clear();

        // when
        SetupRunResult secondResult = runSetupWithInput(
                "3\n\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--env",
                env.toString(),
                "--no-github");

        // then
        SetupRunResult checkResult = runSetup("check", "--endpoint", endpoint(), "--no-github");
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("Disconnect cancelled.");
        checkResult.assertSuccess().stdoutContains("local server: http://127.0.0.1:", "(already running)");
    }

    @Test
    void disconnectBoardStopsSelectedManagedWorkflowAndRemovesManifestEntry() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.disconnect-stop.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Disconnect Stop Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult secondResult = runSetupWithInput(
                "3\n1\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--env",
                env.toString(),
                "--no-github");

        // then
        SetupRunResult checkResult = runSetup("check", "--endpoint", endpoint(), "--no-github");
        firstResult.assertSuccess();
        secondResult.assertSuccess().stdoutContains("Symphony will stop managing \"Disconnect Stop Queue\"");
        checkResult.assertSuccess().stdoutContains("No Trello boards connected to Symphony");
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void disconnectBoardDoesNotRequireCodexAuthentication() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.disconnect-without-codex.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Disconnect Without Codex Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.codexAuthenticated = false;
        commands.codexLoginCommands.clear();
        commands.stoppedWorkflows.clear();

        // when
        SetupRunResult result = runSetupWithInput(
                "3\n1\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--env",
                env.toString(),
                "--no-github");

        // then
        firstResult.assertSuccess();
        result.assertSuccess().stdoutContains("Symphony will stop managing \"Disconnect Without Codex Queue\"");
        assertThatManifest(tempDir.resolve("config").resolve("connected-boards.json"))
                .hasBoardCount(0);
        assertThat(commands.codexLoginCommands).isEmpty();
        assertThat(commands.stoppedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void setupKeepsWorkflowFrontMatterReadableWhenCodexAccessIsCustomized() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.codex-access.md");
        Path env = tempDir.resolve(".env");
        Path allowedPath = tempDir.resolve("absolute-allowed-path");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Codex Access Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--add-path",
                allowedPath.toString(),
                "--no-github");

        // then
        var loadedWorkflow = new WorkflowLoader().load(workflow);
        result.assertSuccess();
        assertThat(loadedWorkflow.config())
                .containsKeys("tracker", "codex")
                .extractingByKey("codex")
                .asString()
                .contains(allowedPath.toString());
        assertThat(Files.readString(workflow, StandardCharsets.UTF_8)).doesNotStartWith("---\n---\n");
    }

    @Test
    void rerunWithNameAndExistingBoardConnectsAnotherBoardWithoutKeepMenu() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first-name.md");
        Path env = tempDir.resolve(".env.name-rerun");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "First Name Queue",
                "--workflow",
                firstWorkflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();
        trello.createdLists().clear();

        // when
        SetupRunResult result = runSetupWithInput(
                "n\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Docs Queue",
                "--env",
                env.toString(),
                "--no-github");

        // then
        Path secondWorkflow = tempDir.resolve("config").resolve("WORKFLOW.docs-queue.md");
        firstResult.assertSuccess();
        result.assertSuccess()
                .stdoutContains("Board connected: \"Docs Queue\"")
                .stdoutDoesNotContain(
                        "Keeping connected Trello boards", "What do you want to do?", "Choose board setup");
        assertThat(secondWorkflow).exists();
        assertThat(trello.createdLists())
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
    }

    @Test
    void rerunWithBoardAndExistingBoardImportsThatBoardWithoutKeepMenu() throws Exception {
        // given
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first-board.md");
        Path env = tempDir.resolve(".env.board-rerun");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "First Board Queue",
                "--workflow",
                firstWorkflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        commands.startedWorkflows.clear();

        // when
        SetupRunResult result = runSetupWithInput(
                "\n\n\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/abc123/imported",
                "--env",
                env.toString(),
                "--no-github");

        // then
        firstResult.assertSuccess();
        result.assertSuccess()
                .stdoutContains("Board connected: \"Imported Queue\"")
                .stdoutDoesNotContain(
                        "Keeping connected Trello boards", "What do you want to do?", "Choose board setup");
        assertThat(trello.boardLookups()).anySatisfy(path -> assertThat(path).contains("/1/boards/abc123"));
    }

    @MethodSource("broadWorkspacePathScenarios")
    @ParameterizedTest
    void setupHandlesBroadWorkspacePathConfirmation(BroadWorkspacePathScenario scenario) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW." + scenario.fileSuffix() + ".md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = scenario.input() == null
                ? runSetup(broadWorkspacePathArgs(workflow, env, scenario))
                : runSetupWithInput(scenario.input(), broadWorkspacePathArgs(workflow, env, scenario));

        // then
        if (scenario.expectedExitCode() == 0) {
            result.assertSuccess();
            if (!scenario.allowAllPaths()) {
                result.stdoutContains("Adding / grants broad recursive read/write access");
            }
            if (scenario.expectedRootPersisted()) {
                assertThatWorkflow(workflow).hasAdditionalWritableRoot(Path.of("/"));
            } else {
                assertThat(Files.readString(workflow, StandardCharsets.UTF_8))
                        .doesNotContain("additional_writable_roots");
            }
        } else {
            result.assertFailure(scenario.expectedExitCode()).stderrContains("setup_broad_path_requires_confirmation");
            assertThat(workflow).doesNotExist();
            assertThat(trello.createdLists()).isEmpty();
        }
    }

    @MethodSource("unsupportedLifecycleOptionScenarios")
    @ParameterizedTest
    void lifecycleSubcommandsRejectUnsupportedInheritedSetupOptions(UnsupportedLifecycleOptionScenario scenario) {
        // given

        // when
        SetupRunResult result = runSetup(scenario.commandArray());

        // then
        result.assertFailure(2)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "setup-local " + scenario.subcommand() + " does not support " + scenario.optionName())
                .stderrDoesNotContain("Troubleshooting report written");
        result.stdoutDoesNotContain("Dry run", "Symphony setup check");
        assertThat(trello.createdLists()).isEmpty();
    }

    private static Stream<InvalidSetupPathOptionScenario> blankSetupLocalPathOptionScenarios() {
        return Stream.of(
                setupLocalPathScenario(
                        "setup blank config dir",
                        "--config-dir must not be empty.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--config-dir",
                        ""),
                setupLocalPathScenario(
                        "setup whitespace config dir",
                        "--config-dir must not be empty.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--config-dir",
                        "   "),
                setupLocalPathScenario(
                        "setup blank manifest",
                        "--manifest must not be empty.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--manifest",
                        ""),
                setupLocalPathScenario(
                        "setup whitespace manifest",
                        "--manifest must not be empty.",
                        "--dry-run",
                        "--non-interactive",
                        "--board",
                        "https://trello.com/b/input/queue",
                        "--manifest",
                        "   "),
                setupLocalPathScenario(
                        "check blank config dir",
                        "--config-dir must not be empty.",
                        "check",
                        "--dry-run",
                        "--board",
                        "Test Board 4",
                        "--config-dir",
                        ""),
                setupLocalPathScenario(
                        "configure github blank config dir",
                        "--config-dir must not be empty.",
                        "configure-github",
                        "--dry-run",
                        "--board",
                        "Test Board 4",
                        "--config-dir",
                        ""),
                setupLocalPathScenario(
                        "configure github blank manifest",
                        "--manifest must not be empty.",
                        "configure-github",
                        "--dry-run",
                        "--board",
                        "Test Board 4",
                        "--manifest",
                        ""));
    }

    private static InvalidSetupPathOptionScenario setupLocalPathScenario(
            String name, String expectedMessage, String... command) {
        return new InvalidSetupPathOptionScenario(name, expectedMessage, List.of(command));
    }

    private static Stream<UnsupportedLifecycleOptionScenario> unsupportedLifecycleOptionScenarios() {
        return Stream.of(
                unsupportedLifecycleOption("check", "--server-port", "check", "--server-port", "1"),
                unsupportedLifecycleOption("check", "--server-port", "check", "--server-port", "0"),
                unsupportedLifecycleOption("check", "--active", "check", "--active", "Inbox"),
                unsupportedLifecycleOption("check", "--workspace-root", "check", "--workspace-root", "."),
                unsupportedLifecycleOption("check", "--workflow", "check", "--workflow", ""),
                unsupportedLifecycleOption(
                        "check", "--workflow", "check", "--board", "Board", "--workflow", "WORKFLOW.md"),
                unsupportedLifecycleOption("check", "--codex-model", "--codex-model", "", "check"),
                unsupportedLifecycleOption("check", "--codex-model", "check", "--codex-model", ""),
                unsupportedLifecycleOption(
                        "repair-port", "--server-port", "repair-port", "--board", "Board", "--server-port", "1"),
                unsupportedLifecycleOption(
                        "repair-port", "--active", "repair-port", "--board", "Board", "--active", ""),
                unsupportedLifecycleOption(
                        "repair-port", "--active", "repair-port", "--board", "Board", "--active", "Inbox"),
                unsupportedLifecycleOption(
                        "repair-port",
                        "--workspace-root",
                        "repair-port",
                        "--board",
                        "Board",
                        "--workspace-root",
                        "/tmp/workspaces"),
                unsupportedLifecycleOption("repair-port", "--workflow", "repair-port", "--workflow", "WORKFLOW.md"),
                unsupportedLifecycleOption(
                        "repair-port", "--workflow", "repair-port", "--board", "Board", "--workflow", "WORKFLOW.md"),
                unsupportedLifecycleOption(
                        "configure-github", "--dry-run", "configure-github", "--board", "Board", "--dry-run"),
                unsupportedLifecycleOption(
                        "configure-github",
                        "--server-port",
                        "configure-github",
                        "--board",
                        "Board",
                        "--server-port",
                        "1"),
                unsupportedLifecycleOption(
                        "configure-github", "--active", "configure-github", "--board", "Board", "--active", "Inbox"),
                unsupportedLifecycleOption(
                        "configure-github",
                        "--workflow",
                        "configure-github",
                        "--board",
                        "Board",
                        "--workflow",
                        "WORKFLOW.md"),
                unsupportedLifecycleOption(
                        "configure-github", "--env", "configure-github", "--board", "Board", "--env", ""),
                unsupportedLifecycleOption(
                        "configure-github", "--env", "configure-github", "--board", "Board", "--env", ".env.other"));
    }

    private static UnsupportedLifecycleOptionScenario unsupportedLifecycleOption(
            String subcommand, String optionName, String... command) {
        return new UnsupportedLifecycleOptionScenario(subcommand, optionName, command);
    }

    private static Stream<BroadWorkspacePathScenario> broadWorkspacePathScenarios() {
        return Stream.of(
                new BroadWorkspacePathScenario("interactive-declined", false, false, "\nn\nn\nn\n", 0, false),
                new BroadWorkspacePathScenario("non-interactive-rejected", true, false, null, 2, false),
                new BroadWorkspacePathScenario("non-interactive-allowed", true, true, null, 0, true));
    }

    private String[] broadWorkspacePathArgs(Path workflow, Path env, BroadWorkspacePathScenario scenario) {
        List<String> args = new ArrayList<>();
        if (scenario.nonInteractive()) {
            args.add("--non-interactive");
        }
        args.addAll(List.of(
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Broad Path Queue " + scenario.fileSuffix(),
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--add-path",
                "/"));
        if (scenario.allowAllPaths()) {
            args.add("--allow-all-paths");
        }
        args.add("--no-github");
        return args.toArray(String[]::new);
    }

    private record BroadWorkspacePathScenario(
            String fileSuffix,
            boolean nonInteractive,
            boolean allowAllPaths,
            String input,
            int expectedExitCode,
            boolean expectedRootPersisted) {
        @Override
        public String toString() {
            return fileSuffix;
        }
    }

    @Test
    void setupReplacesExistingExportedCredentialsInSelectedEnvFile() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.replaced-env.md");
        Path env = tempDir.resolve(".env.custom");
        Files.writeString(
                env,
                """
                export TRELLO_API_KEY=old-key
                TRELLO_API_TOKEN = old-token
                """,
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "new-key",
                "--token",
                "new-token",
                "--board-name",
                "Replaced Env Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_KEY=new-key", "TRELLO_API_TOKEN=new-token")
                .doesNotContain("old-key", "old-token", "export TRELLO_API_KEY", "TRELLO_API_TOKEN =");
    }

    @MethodSource("unsafeEnvPathScenarios")
    @ParameterizedTest
    void setupRejectsEnvPathsThatWouldPersistSecrets(UnsafeEnvPathScenario scenario) {
        // given
        Path workflow = tempDir.resolve("WORKFLOW." + scenario.fileSuffix() + ".md");
        Path env = tempDir.resolve(scenario.envFileName());

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Unsafe Env " + scenario.fileSuffix(),
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2).stderrContains(scenario.expectedErrorFragments());
        assertThat(trello.createdLists()).isEmpty();
        assertThat(env).doesNotExist();
    }

    private static Stream<UnsafeEnvPathScenario> unsafeEnvPathScenarios() {
        return Stream.of(
                new UnsafeEnvPathScenario("trackable", "trello.env", "setup_env_path_not_ignored"),
                new UnsafeEnvPathScenario(
                        "template", ".env.example", "setup_env_path_not_ignored", "tracked template"));
    }

    private record UnsafeEnvPathScenario(
            String fileSuffix, String envFileName, List<String> expectedErrorFragmentList) {
        private UnsafeEnvPathScenario(String fileSuffix, String envFileName, String... expectedErrorFragments) {
            this(fileSuffix, envFileName, List.of(expectedErrorFragments));
        }

        String[] expectedErrorFragments() {
            return expectedErrorFragmentList.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return fileSuffix;
        }
    }

    @Test
    void explicitWorkflowPathFailsWhenFileExistsWithoutForce() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing.md");
        Files.writeString(workflow, "existing", StandardCharsets.UTF_8);
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Explicit Workflow",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertFailure(2).stderrContains("setup_workflow_exists");
        assertThat(trello.memberLookups()).isEmpty();
        assertThat(trello.workspaceLookups()).isEmpty();
        assertThat(trello.boardLookups()).isEmpty();
        assertThat(trello.createdLists()).isEmpty();
        assertThat(env).doesNotExist();
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("existing");
    }

    @Test
    void setupWithGithubCreatesMergingWorkflowWhenGithubIsAuthenticated() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github.md");
        Path env = tempDir.resolve(".env");
        commands.githubAuthenticated = true;

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess()
                .stdoutContains(
                        "GitHub CLI authenticated",
                        "You're good to go - your Trello board is now a queue for Codex work.",
                        "Symphony picks it up, moves it to \"In Progress\", runs Codex, and opens or updates a pull request.",
                        "Review the PR. If you want changes, comment on the PR or Trello card, then move the Trello card back to \"Ready for Codex\".",
                        "When the PR is ready to merge, move the Trello card to `Merging`; Symphony will re-check it, merge it, and move the Trello card to \"Done\".")
                .stdoutDoesNotContain("workflow's Trello handoff lists", "\u2019", "\u2014");
        assertThat(trello.createdLists())
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThatWorkflow(workflow).hasGithubFlow().hasMerging();
        assertThat(commands.startedWorkflows).containsExactly(workflow.toString());
    }

    @Test
    void interactiveSetupWithGithubRunsInlineLoginWhenGithubCliIsInstalledButNotAuthenticated() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.github-login.md");
        Path env = tempDir.resolve(".env");
        commands.githubAuthenticated = false;

        // when
        SetupRunResult result = runSetupWithInput(
                "",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Login Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");

        // then
        result.assertSuccess().stdoutContains("Starting GitHub login:", "OK  GitHub CLI authenticated as alex-example");
        assertThat(commands.githubLoginCommands).containsExactly("gh auth login");
    }

    @Test
    void interactiveSetupWithGithubStopsWhenMissingGithubCliInstallIsDeclined() throws Exception {
        // given
        commands.githubCliAvailable = false;
        Path workflow = tempDir.resolve("WORKFLOW.github-cli-declined.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetupWithInput(
                "n\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub CLI Declined Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");

        // then
        result.assertFailure(2)
                .stdoutContains("GitHub CLI is missing.", "Proposed install command:")
                .stderrContains("setup_github_cli_declined");
        assertThat(commands.githubLoginCommands).isEmpty();
    }

    @Test
    void interactiveSetupWithGithubInstallsMissingGithubCliThenRunsInlineLogin() throws Exception {
        // given
        commands.githubCliAvailable = false;
        Path workflow = tempDir.resolve("WORKFLOW.github-cli-installed.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetupWithInput(
                "y\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub CLI Install Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");

        // then
        result.assertSuccess()
                .stdoutContains("GitHub CLI is missing.", "Starting GitHub login:", "OK  GitHub CLI authenticated");
        assertThat(commands.commandEvents).contains("install-gh");
        assertThat(commands.githubLoginCommands).containsExactly("gh auth login");
    }

    @Test
    void interactiveSetupWithGithubInstallsMissingGithubCliWithWingetOnWindows() throws Exception {
        // given
        commands.githubCliAvailable = false;
        commands.wingetAvailable = true;
        LocalSetup windowsSetup = setupWithOperatingSystem("Windows 11");
        Path workflow = tempDir.resolve("WORKFLOW.github-cli-winget.md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetupWithInput(
                windowsSetup,
                "y\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub CLI Winget Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");

        // then
        result.assertSuccess()
                .stdoutContains("winget install --id GitHub.cli --source winget", "OK  GitHub CLI authenticated");
        assertThat(commands.commandEvents).contains("install-gh-winget");
        assertThat(commands.githubLoginCommands).containsExactly("gh auth login");
    }

    @Test
    void setupRejectsJavaRuntimeWithoutJavacForSourceInstall() {
        // given
        commands.javacAvailable = false;

        // when
        SetupRunResult result = runSetup("check", "--no-github");

        // then
        result.assertFailure(2).stderrEmpty().stdoutContains("NEEDED  Java 25+ JDK");
    }

    @Test
    void interactiveSetupUsesCodexDeviceLoginWhenAuthIsMissing() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.codex-login.md");
        Path env = tempDir.resolve(".env");
        commands.codexAuthenticated = false;

        // when
        SetupRunResult result = runSetupWithInput(
                "n\n\nn\nn\n",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Codex Login Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess()
                .stdoutContains("Can this machine open a browser for Codex login? [Y/n]")
                .stdoutDoesNotContain("Device auth");
        assertThat(commands.codexLoginCommands).containsExactly("codex login --device-auth");
    }

    @MethodSource("nonInteractiveGithubFailureScenarios")
    @ParameterizedTest
    void setupWithGithubFailsBeforeCreatingBoardWhenCliOrAuthIsMissing(NonInteractiveGithubFailureScenario scenario) {
        // given
        commands.githubCliAvailable = scenario.githubCliAvailable();
        Path workflow = tempDir.resolve("WORKFLOW." + scenario.fileSuffix() + ".md");
        Path env = tempDir.resolve(".env");

        // when
        SetupRunResult result = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "GitHub Failure " + scenario.fileSuffix(),
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--github");

        // then
        result.assertFailure(2)
                .stderrContains(scenario.expectedErrorFragments())
                .stderrDoesNotContain(scenario.forbiddenErrorFragments());
        assertThat(trello.createdLists()).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(commands.githubLoginCommands).isEmpty();
    }

    private static Stream<NonInteractiveGithubFailureScenario> nonInteractiveGithubFailureScenarios() {
        return Stream.of(
                new NonInteractiveGithubFailureScenario(
                        "unauthenticated-gh",
                        true,
                        new String[] {"setup_github_auth_required", "gh auth login"},
                        new String[] {"setup_github_cli_required"}),
                new NonInteractiveGithubFailureScenario(
                        "missing-gh",
                        false,
                        new String[] {"setup_github_cli_required", "Install the GitHub CLI"},
                        new String[] {"setup_github_auth_required"}));
    }

    private static Stream<String> invalidEndpointValues() {
        return Stream.of(
                "https://api.trello.com/1/members/me",
                "https://api.trello.com/2",
                "http://api.trello.com/1",
                "http://api.trello.com./1",
                "https://api.trello.com/foo/1",
                "https://api.trello.com/1?x=y",
                "https://api.trello.com/1#frag");
    }

    private void writeOldBoardManifest(Path manifest, Path workflow) throws IOException {
        Files.writeString(
                manifest,
                """
                {
                  "boards": [
                    {
                      "boardId": "old-board",
                      "boardKey": "old",
                      "boardName": "Old Board",
                      "boardUrl": "https://trello.example/old",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(
                                workflow,
                                tempDir.resolve(".env.old"),
                                tempDir.resolve("workspaces"),
                                ConfigDefaults.DEFAULT_SERVER_PORT),
                StandardCharsets.UTF_8);
    }

    private void writeConnectedBoardManifest(Path manifest, String boardName, Path workflow, Path env, int serverPort)
            throws IOException {
        Files.createDirectories(manifest.getParent());
        Files.writeString(
                manifest,
                """
                {
                  "boards": [
                    {
                      "boardId": "board-1",
                      "boardKey": "abc123",
                      "boardName": "%s",
                      "boardUrl": "https://trello.example/abc123",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": %d,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(boardName, workflow, env, tempDir.resolve("workspaces"), serverPort),
                StandardCharsets.UTF_8);
    }

    private static int firstAvailableManagedPort(int... reservedPorts) {
        for (int port = ConfigDefaults.DEFAULT_SERVER_PORT; port <= LocalPort.MAX; port++) {
            if (!contains(reservedPorts, port) && !LocalHealthChecker.portAcceptsConnections(port)) {
                return port;
            }
        }
        throw new AssertionError("No free managed test port found.");
    }

    private static int availablePortOtherThan(int excludedPort) {
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = availablePort();
            // The selected port must sit inside the repair-port scan window - the scan runs from
            // TrelloBoardSetup.DEFAULT_SERVER_PORT (18080) up to 65535 - yet above the live production
            // worker range 18080-18090. A port below 18080 would never be reached by the scan (the
            // probe would mark every scanned port in use), and a port in 18080-18090 could collide
            // with a real worker, since repair-port binds the selected port.
            if (port != excludedPort && port > 18090) {
                return port;
            }
        }
        throw new AssertionError("Could not allocate a free test port distinct from " + excludedPort);
    }

    private static int portFromSetupResult(SetupRunResult result) {
        String marker = "Local server port selected for \"";
        return result.stdoutLines().stream()
                .filter(line -> line.contains(marker))
                .findFirst()
                .map(line -> line.substring(line.lastIndexOf(':') + 1).trim())
                .map(Integer::parseInt)
                .orElseThrow(() -> new AssertionError("Expected server port selection line in setup output."));
    }

    private static boolean contains(int[] ports, int candidate) {
        for (int port : ports) {
            if (port == candidate) {
                return true;
            }
        }
        return false;
    }

    private void writeDuplicateConnectedBoardsManifest(
            Path firstWorkflow, Path firstEnv, Path secondWorkflow, Path secondEnv) throws IOException {
        writeManifest(
                """
                {
                  "boards": [
                    {
                      "boardId": "private-board-id-one",
                      "boardKey": "private-key-one",
                      "boardName": "Duplicate Private Board",
                      "boardUrl": "https://trello.example/private-one",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": 19101,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    },
                    {
                      "boardId": "private-board-id-two",
                      "boardKey": "private-key-two",
                      "boardName": "Duplicate Private Board",
                      "boardUrl": "https://trello.example/private-two",
                      "workflowPath": "%s",
                      "envPath": "%s",
                      "workspaceRoot": "%s",
                      "serverPort": 19102,
                      "githubEnabled": false,
                      "additionalWritableRoots": [],
                      "dangerFullAccess": false
                    }
                  ]
                }
                """
                        .formatted(
                                json(firstWorkflow),
                                json(firstEnv),
                                json(tempDir.resolve("workspaces-one")),
                                json(secondWorkflow),
                                json(secondEnv),
                                json(tempDir.resolve("workspaces-two"))));
    }

    private DuplicateConnectedBoardsFixture duplicateConnectedBoards() throws IOException {
        Path firstWorkflow = tempDir.resolve("private-workflows").resolve("WORKFLOW.duplicate-a.md");
        Path secondWorkflow = tempDir.resolve("private-workflows").resolve("WORKFLOW.duplicate-b.md");
        Path firstEnv = tempDir.resolve("private-env").resolve(".env.duplicate-a");
        Path secondEnv = tempDir.resolve("private-env").resolve(".env.duplicate-b");
        Files.createDirectories(firstEnv.getParent());
        Files.writeString(firstEnv, "TRELLO_API_KEY=key\nTRELLO_API_TOKEN=token\n", StandardCharsets.UTF_8);
        Files.writeString(secondEnv, "TRELLO_API_KEY=key\nTRELLO_API_TOKEN=token\n", StandardCharsets.UTF_8);
        writeWorkflow(firstWorkflow, "private-board-id-one", 19101);
        writeWorkflow(secondWorkflow, "private-board-id-two", 19102);
        writeDuplicateConnectedBoardsManifest(firstWorkflow, firstEnv, secondWorkflow, secondEnv);
        return new DuplicateConnectedBoardsFixture(firstWorkflow, secondWorkflow);
    }

    private record NonInteractiveGithubFailureScenario(
            String fileSuffix,
            boolean githubCliAvailable,
            List<String> expectedErrorFragmentList,
            List<String> forbiddenErrorFragmentList) {
        private NonInteractiveGithubFailureScenario(
                String fileSuffix,
                boolean githubCliAvailable,
                String[] expectedErrorFragments,
                String[] forbiddenErrorFragments) {
            this(fileSuffix, githubCliAvailable, List.of(expectedErrorFragments), List.of(forbiddenErrorFragments));
        }

        String[] expectedErrorFragments() {
            return expectedErrorFragmentList.toArray(String[]::new);
        }

        String[] forbiddenErrorFragments() {
            return forbiddenErrorFragmentList.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return fileSuffix;
        }
    }
}
