package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.testsupport.TestRepositoryUrls.HTTPS;
import static ch.fmartin.symphony.trello.testsupport.TestRepositoryUrls.SSH;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.abort;

import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import ch.fmartin.symphony.trello.testsupport.TestWorkflows;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class SetupDiagnosticReporterTest {
    private static final String SYNTHETIC_BOARD_ID = "000000000000000000000001";

    @TempDir
    Path tempDir;

    @Test
    void reportsOnlyUnexpectedSetupFailures() {
        // given
        List<String> expectedFailureCodes = List.of(
                "setup_ambiguous_active_state",
                "setup_invalid_arguments",
                "setup_invalid_repository_url",
                "setup_missing_api_key",
                "setup_missing_api_token",
                "setup_overlapping_list_roles",
                "setup_prerequisite_missing",
                "setup_codex_auth_required",
                "setup_codex_login_failed",
                "setup_github_cli_required",
                "setup_github_auth_required",
                "setup_github_cli_install_failed",
                "setup_workspace_id_required",
                "setup_worker_board_ambiguous",
                "setup_worker_board_required",
                "setup_worker_workflow_ambiguous",
                "setup_workflow_unresolved_environment",
                "trello_auth_failed",
                "trello_api_request",
                "trello_permission_denied",
                "trello_write_outcome_unknown");
        List<String> unexpectedFailureCodes = List.of(
                "setup_workflow_write_failed",
                "setup_workflow_scan_failed",
                "setup_start_unhealthy",
                "setup_start_failed",
                "setup_stop_failed",
                "trello_api_status",
                "trello_unknown_payload");

        // when
        boolean reportsIoFailure = SetupDiagnosticReporter.shouldReport(new IOException("boom"));

        // then
        assertThat(expectedFailureCodes).allSatisfy(code -> assertThat(
                        SetupDiagnosticReporter.shouldReport(new TrelloBoardSetupException(code, "expected failure")))
                .as(code)
                .isFalse());
        assertThat(unexpectedFailureCodes).allSatisfy(code -> assertThat(
                        SetupDiagnosticReporter.shouldReport(new TrelloBoardSetupException(code, "unexpected failure")))
                .as(code)
                .isTrue());
        assertThat(reportsIoFailure).isTrue();
    }

    @Test
    void givesActionableHintForTrelloRequestFailures() {
        // given
        var unreachableEndpoint = new TrelloBoardSetupException("trello_api_request", "Trello request failed");

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(unreachableEndpoint);

        // then
        assertThat(hint)
                .contains(
                        "Check the Trello API endpoint URL and network connection, or remove the custom --endpoint value, then rerun the command.");
    }

    @Test
    void givesActionableHintForUnknownTrelloWriteOutcome() {
        // given
        var unknownWrite =
                new TrelloBoardSetupException("trello_write_outcome_unknown", "Trello write outcome is unknown.");

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(unknownWrite);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains("Check Trello for any board or list that may already have been created"));
    }

    @Test
    void givesActionableHintForMissingTrelloCredentials() {
        // given
        var missingApiToken = new TrelloBoardSetupException("setup_missing_api_token", "Missing Trello API token");

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(missingApiToken);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains("Provide Trello credentials with --key and --token, set TRELLO_API_KEY and TRELLO_API_TOKEN")
                .contains("this .env credential file:\n  ")
                .contains(".env")
                .doesNotContain("local .env file", "usually"));
    }

    @Test
    void givesActionableHintForAmbiguousWorkerWorkflowRows() {
        // given
        var duplicateWorkflowRows = new TrelloBoardSetupException(
                "setup_worker_workflow_ambiguous", "Multiple connected-board rows reference --workflow.");

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(duplicateWorkflowRows);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains("Remove duplicate rows for the same workflow from " + ConnectedBoardManifest.FILE_NAME
                        + " in the active Symphony config directory")
                .doesNotContain(tempDir.toString(), "WORKFLOW", "board-1", "board-2"));
    }

    @Test
    void givesActionableHintWithExactWorkerCredentialNames() {
        // given
        var missingWorkerCredentials = new TrelloBoardSetupException(
                        "setup_worker_missing_trello_credentials", "Missing Trello credentials for worker start.")
                .withTrelloCredentialEnvironmentNames("CUSTOM_TRELLO_API_KEY", "CUSTOM_TRELLO_API_TOKEN");

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(missingWorkerCredentials);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains(
                        "Set these Trello credential variables",
                        "CUSTOM_TRELLO_API_KEY=<your Trello API key>",
                        "CUSTOM_TRELLO_API_TOKEN=<your Trello token>",
                        "File:\n  ")
                .doesNotContain("referenced by the workflow", "local .env file"));
    }

    @Test
    void givesActionableHintForWorkerAuthFailureFromShellCredentials() {
        // given
        var authFailure = new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed while starting Symphony.")
                .withDotenvPath(tempDir.resolve(".env"))
                .withTrelloCredentialEnvironmentNames("TRELLO_API_KEY", "TRELLO_API_TOKEN")
                .withTrelloCredentialSources(
                        TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT,
                        TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT);

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(authFailure);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains(
                        "Check TRELLO_API_KEY and TRELLO_API_TOKEN from the shell environment.",
                        "Shell variables take precedence over the .env file passed with --env.")
                .doesNotContain("shell-key", "shell-token", tempDir.toString()));
    }

    @CsvSource({"-1", "99999"})
    @ParameterizedTest
    void selectedWorkflowDiagnosticsSkipsOutOfRangeFrontMatterPorts(int port) throws Exception {
        // given
        DiagnosticsFixture fixture =
                DiagnosticsFixture.create(tempDir, "front-matter-port-" + (port < 0 ? "negative" : port));
        Path workflow =
                fixture.workflow("WORKFLOW.front-matter-port.md", TestWorkflows.diagnosticsWorkflowWithPort(port));

        // when
        String report = fixture.renderWorkflow(workflow);

        // then
        assertThat(report)
                .contains("Configured port " + port + " is outside the valid TCP port range; health probes skipped.")
                .doesNotContain("IllegalArgumentException", "port out of range", "http://127.0.0.1:" + port + "/");
    }

    @Test
    void symlinkedWorkflowSelectorReportsOneSelectedWorkflowIdentity() throws Exception {
        // given
        Path configDir = tempDir.resolve("symlink-selector-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path workflow = configDir.resolve("WORKFLOW.symlink-target.md");
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(20992), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "000000000000000000000001",
                        "SYNTH001",
                        "Symlink Board",
                        "https://trello.com/b/SYNTH001/symlink-board",
                        workflow,
                        configDir.resolve(".env"),
                        tempDir.resolve("workspaces"),
                        20992,
                        false,
                        List.of(),
                        false))));
        Path link = tempDir.resolve("symlink-selector-link.md");
        Files.createSymbolicLink(link, workflow);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(reporter, configDir, stateHome, link);

        // then
        assertThat(report)
                .contains(
                        "- **selected_manifest_board_count:** 1",
                        "- **selected_workflow_in_manifest:** true",
                        "- **selected_workflow_file_count:** 1");
    }

    @Test
    void diagnosticsSkipsHealthProbesForOutOfRangePorts() throws Exception {
        // given
        DiagnosticsFixture fixture = DiagnosticsFixture.create(tempDir, "bad-port");
        Path workflow = fixture.workflow(
                "WORKFLOW.badport.md",
                """
                ---
                tracker:
                  kind: trello
                  board_id: "synthetic-board"
                ---
                Body
                """);
        fixture.saveSyntheticBoard(workflow, 99999);

        // when
        String report = fixture.renderGlobal();

        // then
        assertThat(report)
                .contains("Configured port 99999 is outside the valid TCP port range; health probes skipped.")
                .doesNotContain("IllegalArgumentException", "port out of range", "http://127.0.0.1:99999");
    }

    @CsvSource({"-1", "99999"})
    @ParameterizedTest
    void diagnosticsSkipDeclaredOutOfRangeWorkflowPortWithoutManifestFallback(int port) throws Exception {
        // given
        DiagnosticsFixture fixture =
                DiagnosticsFixture.create(tempDir, "declared-bad-port-" + (port < 0 ? "negative" : port));
        Path workflow =
                fixture.workflow("WORKFLOW.declared-bad-port.md", TestWorkflows.diagnosticsWorkflowWithPort(port));
        fixture.saveSyntheticBoard(workflow, 20990);

        // when
        String report = fixture.renderGlobal();

        // then
        assertThat(report)
                .contains("Configured port " + port + " is outside the valid TCP port range; health probes skipped.")
                .doesNotContain("http://127.0.0.1:20990/", "IllegalArgumentException");
    }

    @Test
    void diagnosticsDoNotMarkManifestWorkflowPortMismatchAsInvalidWorkflow() throws Exception {
        // given
        DiagnosticsFixture fixture = DiagnosticsFixture.create(tempDir, "manifest-workflow-port-mismatch");
        Path workflow = fixture.workflow(
                "WORKFLOW.port-mismatch.md", TestWorkflows.workflowWithBoardAndPort(SYNTHETIC_BOARD_ID, 18211));
        fixture.saveSyntheticBoard(workflow, 18210);

        // when
        String report = fixture.renderGlobal();

        // then
        assertThat(report)
                .contains(
                        "## Connected Board Manifest",
                        " | 18210 | ",
                        "## Workflow Summary",
                        " | 18211 | ",
                        "http://127.0.0.1:18211/api/v1/local-status")
                .doesNotContain(
                        "## Invalid Connected Board Workflows",
                        "unusable workflow configuration",
                        "http://127.0.0.1:18210/");
    }

    @Test
    void diagnosticsStillMarkConnectedBoardIdMismatchAsInvalidWorkflow() throws Exception {
        // given
        DiagnosticsFixture fixture = DiagnosticsFixture.create(tempDir, "connected-board-id-mismatch");
        Path workflow = fixture.workflow(
                "WORKFLOW.board-id-mismatch.md", TestWorkflows.workflowWithBoardAndPort("other-board-id", 18212));
        fixture.saveSyntheticBoard(workflow, 18212);

        // when
        String report = fixture.renderGlobal();

        // then
        assertThat(report)
                .contains(
                        "## Invalid Connected Board Workflows",
                        "- **invalid_connected_board_workflow_count:** 1",
                        "unusable workflow configuration")
                .doesNotContain("other-board-id", SYNTHETIC_BOARD_ID, tempDir.toString());
    }

    @Test
    void diagnosticsDoNotProbeManifestPortWhenWorkflowDeclaresNonNumericPort() throws Exception {
        // given
        DiagnosticsFixture fixture = DiagnosticsFixture.create(tempDir, "non-numeric-port");
        Path workflow = fixture.workflow(
                "WORKFLOW.non-numeric-port.md",
                """
                ---
                tracker:
                  kind: trello
                  board_id: "synthetic-board"
                server:
                  port: "not-a-port"
                ---
                Body
                """);
        fixture.saveSyntheticBoard(workflow, 20990);

        // when
        String report = fixture.renderGlobal();

        // then
        assertThat(report).contains("invalid").doesNotContain("http://127.0.0.1:20990/", "IllegalArgumentException");
    }

    @Test
    void diagnosticsProbeManifestPortWhenWorkflowOmitsServerPort() throws Exception {
        // given
        DiagnosticsFixture fixture = DiagnosticsFixture.create(tempDir, "omitted-port");
        Path workflow = fixture.workflow(
                "WORKFLOW.omitted-port.md",
                """
                ---
                tracker:
                  kind: trello
                  board_id: "synthetic-board"
                ---
                Body
                """);
        fixture.saveSyntheticBoard(workflow, 20726);

        // when
        String report = fixture.renderGlobal();

        // then
        assertThat(report).contains("http://127.0.0.1:20726/api/v1/local-status");
    }

    @MethodSource("environmentBackedPortCases")
    @ParameterizedTest(name = "{0}")
    void diagnosticsHandlesEnvironmentBackedWorkflowPorts(EnvironmentBackedPortCase testCase) throws Exception {
        // given
        EnvironmentBackedPortScenario scenario =
                environmentBackedPortScenario(testCase.name(), testCase.manifestPort(), testCase.statusPortLine());

        // when
        String report = renderGlobalDiagnostics(scenario.reporter(), scenario.configDir(), scenario.stateHome());

        // then
        assertThat(report)
                .contains(testCase.expectedFragments().toArray(String[]::new))
                .doesNotContain(testCase.forbiddenFragments().toArray(String[]::new));
    }

    @Test
    void selectedUnconnectedWorkflowDiagnosticsProbeEnvironmentResolvedPort() throws Exception {
        // given
        Path configDir = tempDir.resolve("selected-env-port-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path workflow = configDir.resolve("WORKFLOW.selected-env-port.md");
        Files.writeString(
                workflow, TestWorkflows.diagnosticsWorkflowWithEnvironmentBackedPort(), StandardCharsets.UTF_8);
        Files.writeString(
                configDir.resolve(".env"),
                """
                BOARD_ID_REF=000000000000000000000001
                BOARD_STATUS_PORT=20728
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(reporter, configDir, stateHome, workflow);

        // then
        assertThat(report)
                .contains("http://127.0.0.1:20728/api/v1/local-status")
                .doesNotContain("$BOARD_STATUS_PORT");
    }

    private record EnvironmentBackedPortScenario(SetupDiagnosticReporter reporter, Path configDir, Path stateHome) {}

    private record EnvironmentBackedPortCase(
            String name,
            int manifestPort,
            String statusPortLine,
            List<String> expectedFragments,
            List<String> forbiddenFragments) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Stream<EnvironmentBackedPortCase> environmentBackedPortCases() {
        return Stream.of(
                new EnvironmentBackedPortCase(
                        "resolved environment port",
                        20990,
                        "BOARD_STATUS_PORT=20991",
                        List.of("http://127.0.0.1:20991/api/v1/local-status"),
                        List.of("http://127.0.0.1:20990/")),
                new EnvironmentBackedPortCase(
                        "out-of-range environment port",
                        20990,
                        "BOARD_STATUS_PORT=99999",
                        List.of("Configured port 99999 is outside the valid TCP port range; health probes skipped."),
                        List.of("http://127.0.0.1:20990/")),
                new EnvironmentBackedPortCase(
                        "non-numeric environment port",
                        20990,
                        "BOARD_STATUS_PORT=not-a-port",
                        List.of("invalid"),
                        List.of("http://127.0.0.1:20990/", "not-a-port", "IllegalArgumentException")),
                // Compatibility contract: if the board environment does not define the reference,
                // diagnostics cannot know the effective port, so it keeps the manifest fallback.
                new EnvironmentBackedPortCase(
                        "unresolved environment port reference",
                        20727,
                        "",
                        List.of("http://127.0.0.1:20727/api/v1/local-status"),
                        List.of("$BOARD_STATUS_PORT")));
    }

    private EnvironmentBackedPortScenario environmentBackedPortScenario(
            String prefix, int manifestPort, String statusPortLine) throws IOException {
        Path configDir = tempDir.resolve(prefix + "-config");
        Path stateHome = tempDir.resolve(prefix + "-state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path workflow = configDir.resolve("WORKFLOW.env-port.md");
        Files.writeString(
                workflow, TestWorkflows.diagnosticsWorkflowWithEnvironmentBackedPort(), StandardCharsets.UTF_8);
        Files.writeString(
                configDir.resolve(".env"),
                "BOARD_ID_REF=000000000000000000000001\n" + statusPortLine + "\n",
                StandardCharsets.UTF_8);
        saveSyntheticBoard(configDir, workflow, manifestPort);
        return new EnvironmentBackedPortScenario(
                new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner()), configDir, stateHome);
    }

    private static void saveSyntheticBoard(Path configDir, Path workflow, int serverPort) throws IOException {
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "000000000000000000000001",
                        "SYNTH001",
                        "Synthetic Port Board",
                        "https://trello.com/b/SYNTH001/synthetic-port-board",
                        workflow,
                        configDir.resolve(".env"),
                        configDir.resolveSibling("workspaces"),
                        serverPort,
                        false,
                        List.of(),
                        false))));
    }

    @Test
    void sanitizesPrivatePathsAsSingleTokensWithoutAdjacentValueAndPathFragments() throws Exception {
        // given
        Path configDir = tempDir.resolve("single-token-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path workflow = configDir.resolve("WORKFLOW.minimal.md");
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(20986), StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.of(stateHome),
                Optional.empty(),
                Optional.of(workflow)));

        // then
        assertThat(report).doesNotContain("><path:").doesNotContain(workflow.toString());
    }

    @Test
    void broadDiagnosticsSeparatesUnconnectedWorkflowFilesAndSkipsTheirProbes() throws Exception {
        // given
        Path configDir = tempDir.resolve("broad-split-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path connectedWorkflow = configDir.resolve("WORKFLOW.connected.md");
        Files.writeString(connectedWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(20987), StandardCharsets.UTF_8);
        Path staleWorkflow = configDir.resolve("WORKFLOW.stale.md");
        Files.writeString(staleWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(20988), StandardCharsets.UTF_8);
        saveSyntheticBoard(configDir, connectedWorkflow, 20987);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report)
                .contains(
                        "- **selected_workflow_file_count:** 1",
                        "## Unconnected Workflow Files",
                        "- **unconnected_workflow_file_count:** 1",
                        "Their ports are not",
                        "http://127.0.0.1:20987/api/v1/local-status")
                .doesNotContain("http://127.0.0.1:20988/api/v1/local-status");
    }

    @Test
    void diagnosticsToolProbeDoesNotNeedHelperShell() throws Exception {
        // given
        Path configDir = tempDir.resolve("no-shell-tool-probe-config");
        Path stateHome = tempDir.resolve("no-shell-tool-probe-state");
        Path toolDirectory = tempDir.resolve("posix-tools");
        Path java = toolDirectory.resolve("java");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(java, "", StandardCharsets.UTF_8);
        java.toFile().setExecutable(true);
        CommandRunner commands = command -> {
            if ("sh".equals(command[0]) || "cmd".equals(command[0])) {
                throw new AssertionError("diagnostics tool probe must not call a helper shell");
            }
            if (List.of(command).equals(List.of(java.toString(), "-version"))) {
                return new CommandResult(0, "openjdk version \"25\"\n");
            }
            return new CommandResult(CommandResult.COMMAND_NOT_FOUND_EXIT_CODE, "");
        };
        var reporter = new SetupDiagnosticReporter(Map.of("PATH", toolDirectory.toString()), commands);

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report)
                .contains("## Tool Availability", "| java | available | openjdk version \"25\" |")
                .doesNotContain("helper shell");
    }

    @Test
    void diagnosticsToolVersionUsesFirstOutputLine() throws Exception {
        // given
        Path configDir = tempDir.resolve("first-tool-version-line-config");
        Path stateHome = tempDir.resolve("first-tool-version-line-state");
        Path toolDirectory = tempDir.resolve("first-tool-version-line-tools");
        Path java = toolDirectory.resolve("java");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(java, "", StandardCharsets.UTF_8);
        java.toFile().setExecutable(true);
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "openjdk version \"25\"\nopenjdk version \"26\"\n", java.toString(), "-version");
        var reporter = new SetupDiagnosticReporter(Map.of("PATH", toolDirectory.toString()), commands);

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report)
                .contains("| java | available | openjdk version \"25\" |")
                .doesNotContain("openjdk version \"26\"");
    }

    @Test
    void diagnosticsToolProbeUsesFirstExecutableInPathOrder() throws Exception {
        // given
        if (System.getProperty("os.name").startsWith("Windows")) {
            abort("POSIX execute-bit behavior is not portable on Windows");
        }
        Path configDir = tempDir.resolve("path-order-config");
        Path stateHome = tempDir.resolve("path-order-state");
        Path firstDirectory = tempDir.resolve("path-order-first");
        Path secondDirectory = tempDir.resolve("path-order-second");
        Path firstCodex = firstDirectory.resolve("codex");
        Path secondCodex = secondDirectory.resolve("codex");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(firstDirectory);
        Files.createDirectories(secondDirectory);
        Files.writeString(firstCodex, "", StandardCharsets.UTF_8);
        Files.writeString(secondCodex, "", StandardCharsets.UTF_8);
        if (!firstCodex.toFile().setExecutable(true) || !secondCodex.toFile().setExecutable(true)) {
            abort("test filesystem does not support executable tool fixtures");
        }
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "codex-first\n", firstCodex.toString(), "--version")
                .returns(0, "codex-second\n", secondCodex.toString(), "--version");
        var reporter = new SetupDiagnosticReporter(
                Map.of("PATH", firstDirectory + File.pathSeparator + secondDirectory),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Linux");

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | available | codex-first; login=not-probed |");
    }

    @Test
    void diagnosticsMarksMissingPosixToolWithoutLaunchingIt() throws Exception {
        // given
        Path configDir = tempDir.resolve("missing-posix-tool-config");
        Path stateHome = tempDir.resolve("missing-posix-tool-state");
        Path toolDirectory = tempDir.resolve("empty-posix-tools");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        CommandRunner commands = command -> {
            throw new AssertionError("missing tools must not be launched: " + List.of(command));
        };
        var reporter = new SetupDiagnosticReporter(Map.of("PATH", toolDirectory.toString()), commands);

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | missing |  |", "| gh | missing |  |");
    }

    @Test
    void diagnosticsMarksPosixFoundToolWithUnavailableVersionAsAvailable() throws Exception {
        // given
        Path configDir = tempDir.resolve("posix-version-unavailable-config");
        Path stateHome = tempDir.resolve("posix-version-unavailable-state");
        Path toolDirectory = tempDir.resolve("posix-version-unavailable-tools");
        Path codex = toolDirectory.resolve("codex");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(codex, "", StandardCharsets.UTF_8);
        codex.toFile().setExecutable(true);
        FakeCommandRunner commands = new FakeCommandRunner().returns(2, "unsupported\n", codex.toString(), "--version");
        var reporter = new SetupDiagnosticReporter(Map.of("PATH", toolDirectory.toString()), commands);

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | available | version unavailable |");
    }

    @Test
    void diagnosticsSkipsNonExecutablePosixPathEntries() throws Exception {
        // given
        if (System.getProperty("os.name").startsWith("Windows")) {
            abort("POSIX execute-bit behavior is not portable on Windows");
        }
        Path configDir = tempDir.resolve("posix-shadowed-tool-config");
        Path stateHome = tempDir.resolve("posix-shadowed-tool-state");
        Path shadowDirectory = tempDir.resolve("posix-shadowed-tools");
        Path executableDirectory = tempDir.resolve("posix-real-tools");
        Path shadowCodex = shadowDirectory.resolve("codex");
        Path realCodex = executableDirectory.resolve("codex");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(shadowDirectory);
        Files.createDirectories(executableDirectory);
        Files.writeString(shadowCodex, "", StandardCharsets.UTF_8);
        Files.writeString(realCodex, "", StandardCharsets.UTF_8);
        shadowCodex.toFile().setExecutable(false);
        if (Files.isExecutable(shadowCodex)) {
            abort("test filesystem does not support non-executable tool fixtures");
        }
        if (!realCodex.toFile().setExecutable(true)) {
            abort("test filesystem does not support executable tool fixtures");
        }
        FakeCommandRunner commands =
                new FakeCommandRunner().returns(0, "codex-cli 9.9\n", realCodex.toString(), "--version");
        var reporter = new SetupDiagnosticReporter(
                Map.of("PATH", shadowDirectory + File.pathSeparator + executableDirectory),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Linux");

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | available | codex-cli 9.9; login=not-probed |");
    }

    @Test
    void diagnosticsToolProbeUsesWindowsPathextWithoutHelperShell() throws Exception {
        // given
        Path configDir = tempDir.resolve("windows-pathext-tool-probe-config");
        Path stateHome = tempDir.resolve("windows-pathext-tool-probe-state");
        Path toolDirectory = tempDir.resolve("windows-tools");
        Path codexShim = toolDirectory.resolve("codex.CMD");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(codexShim, "", StandardCharsets.UTF_8);
        CommandRunner commands = command -> {
            if ("sh".equals(command[0]) || "cmd".equals(command[0])) {
                throw new AssertionError("diagnostics tool probe must not call a helper shell");
            }
            if (List.of(command).equals(List.of(windowsBatchCommand(codexShim, "--version")))) {
                return new CommandResult(0, "codex-cli 9.9\n");
            }
            return new CommandResult(CommandResult.COMMAND_NOT_FOUND_EXIT_CODE, "");
        };
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", '"' + toolDirectory.toString() + '"', "PATHEXT", ".CMD;.EXE"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report)
                .contains("## Tool Availability", "| codex | available | codex-cli 9.9; login=not-probed |")
                .doesNotContain("helper shell");
    }

    @Test
    void deepDiagnosticsUsesWindowsPathextCodexExecutableForLoginStatus() throws Exception {
        // given
        Path configDir = tempDir.resolve("windows-pathext-codex-deep-config");
        Path stateHome = tempDir.resolve("windows-pathext-codex-deep-state");
        Path toolDirectory = tempDir.resolve("windows-deep-tools");
        Path codexShim = toolDirectory.resolve("codex.CMD");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(codexShim, "", StandardCharsets.UTF_8);
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "codex-cli 9.9\n", windowsBatchCommand(codexShim, "--version"))
                .returns(0, "Logged in\n", windowsBatchCommand(codexShim, "login", "status"));
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", '"' + toolDirectory.toString() + '"', "PATHEXT", ".CMD;.EXE"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderDeepDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | available | codex-cli 9.9; login=ok |");
    }

    @Test
    void deepDiagnosticsKeepsWindowsPathextCodexAvailableWhenLoginStatusFails() throws Exception {
        // given
        Path configDir = tempDir.resolve("windows-pathext-codex-deep-failure-config");
        Path stateHome = tempDir.resolve("windows-pathext-codex-deep-failure-state");
        Path toolDirectory = tempDir.resolve("windows-deep-failure-tools");
        Path codexShim = toolDirectory.resolve("codex.CMD");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(codexShim, "", StandardCharsets.UTF_8);
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "codex-cli 9.9\n", windowsBatchCommand(codexShim, "--version"))
                .returns(1, "not logged in\n", windowsBatchCommand(codexShim, "login", "status"));
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", '"' + toolDirectory.toString() + '"', "PATHEXT", ".CMD;.EXE"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderDeepDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | available | codex-cli 9.9; login=not-ok |");
    }

    @Test
    void deepDiagnosticsUsesWindowsPathextGithubExecutableForAuthStatus() throws Exception {
        // given
        Path configDir = tempDir.resolve("windows-pathext-gh-deep-config");
        Path stateHome = tempDir.resolve("windows-pathext-gh-deep-state");
        Path toolDirectory = tempDir.resolve("windows-gh-tools");
        Path ghShim = toolDirectory.resolve("gh.CMD");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(ghShim, "", StandardCharsets.UTF_8);
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "gh version 2.70.0\n", windowsBatchCommand(ghShim, "--version"))
                .returns(0, "github.com\n", windowsBatchCommand(ghShim, "auth", "status"));
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", '"' + toolDirectory.toString() + '"', "PATHEXT", ".CMD;.EXE"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderDeepDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| gh | available | gh version 2.70.0; auth=ok |");
    }

    @Test
    void deepDiagnosticsKeepsWindowsPathextGithubAvailableWhenAuthStatusFails() throws Exception {
        // given
        Path configDir = tempDir.resolve("windows-pathext-gh-deep-failure-config");
        Path stateHome = tempDir.resolve("windows-pathext-gh-deep-failure-state");
        Path toolDirectory = tempDir.resolve("windows-gh-failure-tools");
        Path ghShim = toolDirectory.resolve("gh.CMD");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(ghShim, "", StandardCharsets.UTF_8);
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "gh version 2.70.0\n", windowsBatchCommand(ghShim, "--version"))
                .returns(1, "not authenticated\n", windowsBatchCommand(ghShim, "auth", "status"));
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", '"' + toolDirectory.toString() + '"', "PATHEXT", ".CMD;.EXE"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderDeepDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| gh | available | gh version 2.70.0; auth=not-ok |");
    }

    @Test
    void diagnosticsRunsNativeWindowsPathextExecutableDirectly() throws Exception {
        // given
        Path configDir = tempDir.resolve("windows-native-tool-config");
        Path stateHome = tempDir.resolve("windows-native-tool-state");
        Path toolDirectory = tempDir.resolve("windows-native-tools");
        Path codexExe = toolDirectory.resolve("codex.EXE");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(codexExe, "", StandardCharsets.UTF_8);
        FakeCommandRunner commands =
                new FakeCommandRunner().returns(0, "codex-cli 9.9\n", codexExe.toString(), "--version");
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", toolDirectory.toString(), "PATHEXT", ".EXE;.CMD"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | available | codex-cli 9.9; login=not-probed |");
    }

    @Test
    void diagnosticsMarksFoundToolWithUnavailableVersionAsAvailable() throws Exception {
        // given
        Path configDir = tempDir.resolve("version-unavailable-tool-config");
        Path stateHome = tempDir.resolve("version-unavailable-tool-state");
        Path toolDirectory = tempDir.resolve("version-unavailable-tools");
        Path codexExe = toolDirectory.resolve("codex.EXE");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(codexExe, "", StandardCharsets.UTF_8);
        FakeCommandRunner commands =
                new FakeCommandRunner().returns(2, "unsupported\n", codexExe.toString(), "--version");
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", toolDirectory.toString(), "PATHEXT", ".EXE"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report).contains("| codex | available | version unavailable |");
    }

    @Test
    void diagnosticsMarksFoundButUnlaunchableToolSeparately() throws Exception {
        // given
        Path configDir = tempDir.resolve("unlaunchable-tool-config");
        Path stateHome = tempDir.resolve("unlaunchable-tool-state");
        Path toolDirectory = tempDir.resolve("unlaunchable-tools");
        Path codexExe = toolDirectory.resolve("codex.EXE");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(toolDirectory);
        Files.writeString(codexExe, "", StandardCharsets.UTF_8);
        CommandRunner commands = command -> {
            if (List.of(command).equals(List.of(codexExe.toString(), "--version"))) {
                return CommandResult.launchFailed("permission denied");
            }
            return new CommandResult(CommandResult.COMMAND_NOT_FOUND_EXIT_CODE, "");
        };
        var reporter = new SetupDiagnosticReporter(
                Map.of("Path", toolDirectory.toString(), "PATHEXT", ".EXE"),
                commands,
                Files::list,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "Windows 11");

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report)
                .contains("| codex | unlaunchable | could not launch |")
                .doesNotContain(codexExe.toString(), "permission denied");
    }

    @Test
    void diagnosticsRendersIncompleteManifestRowsSafely() throws Exception {
        // given
        Path configDir = tempDir.resolve("incomplete-manifest-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME),
                """
                {"boards":[{},{"boardName":"Private Board","boardId":"abc","boardKey":"def","serverPort":18199}]}
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, stateHome);

        // then
        assertThat(report)
                .contains("- **board_count:** 2", "unavailable")
                .doesNotContain("Cannot invoke", "NullPointerException", "Private Board");
    }

    @Test
    void selectedWorkflowDiagnosticsTolerateEmptyManifestBoardRow() throws Exception {
        // given
        Path configDir = tempDir.resolve("empty-row-config");
        Path workspaceRoot = tempDir.resolve("empty-row-workspaces");
        Path stateHome = tempDir.resolve("empty-row-state");
        Path workflow = configDir.resolve("WORKFLOW.empty-row.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(20723), StandardCharsets.UTF_8);
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME),
                """
                {"boards":[{}]}
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, workflow);

        // then
        assertThat(report)
                .contains("selector:** workflow", "selected_workflow_in_manifest:** false")
                .doesNotContain("Cannot invoke", "NullPointerException", tempDir.toString());
    }

    @Test
    void selectedWorkflowDiagnosticsKeepPartialManifestRowFieldsPrivate() throws Exception {
        // given
        Path configDir = tempDir.resolve("partial-row-config");
        Path workspaceRoot = tempDir.resolve("partial-row-workspaces");
        Path stateHome = tempDir.resolve("partial-row-state");
        Path workflow = configDir.resolve("WORKFLOW.partial-row.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(20724), StandardCharsets.UTF_8);
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME),
                """
                {"boards":[{"boardName":"Private Board","boardId":"abc","boardKey":"def","serverPort":18199}]}
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, workflow);

        // then
        assertThat(report)
                .contains("selector:** workflow", "selected_workflow_in_manifest:** false")
                .doesNotContain("Cannot invoke", "NullPointerException", "Private Board", "18199", tempDir.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{\"boards\":null}"})
    void diagnosticsReportsStructurallyInvalidManifestContentAsInvalid(String manifestContent) throws Exception {
        // given
        Path configDir = tempDir.resolve("invalid-content-config-" + manifestContent.hashCode());
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path manifest = configDir.resolve(ConnectedBoardManifest.FILE_NAME);
        Files.writeString(manifest, manifestContent, StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.of(stateHome),
                Optional.of(manifest),
                Optional.empty()));

        // then
        assertThat(report)
                .contains(
                        "- **manifest_status:** invalid",
                        "- **board_count:** 0",
                        "The connected-board manifest is not valid connected-board JSON.")
                .doesNotContain("Cannot invoke", "NullPointerException", "manifest_status:** loaded");
    }

    @Test
    void diagnosticsReportsDirectoryManifestPathAsNotAFile() throws Exception {
        // given
        Path configDir = tempDir.resolve("manifest-dir-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.of(stateHome),
                Optional.of(configDir),
                Optional.empty()));

        // then
        assertThat(report)
                .contains(
                        "- **manifest_status:** not_a_file",
                        "The connected-board manifest path exists but is not a regular file.")
                .doesNotContain("No connected-board manifest was found.");
    }

    @Test
    void diagnosticsRejectsFileValuedStateHomeAsExpectedInput() throws Exception {
        // given
        Path configDir = tempDir.resolve("state-file-config");
        Path stateHomeFile = tempDir.resolve("state-file");
        Files.createDirectories(configDir);
        Files.writeString(stateHomeFile, "plain", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Throwable thrown = catchThrowable(() -> renderGlobalDiagnostics(reporter, configDir, stateHomeFile));

        // then
        assertThat(thrown)
                .isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.getMessage())
                        .contains("--state-home must be a directory."));
    }

    @Test
    void writesDistinctReportsForFailuresInTheSameSecond() throws Exception {
        // given
        SameSecondReportScenario scenario = sameSecondReportScenario("collision");

        // when
        Optional<Path> first = scenario.reportFailure();
        Optional<Path> second = scenario.reportFailure();

        // then
        assertThat(first).hasValueSatisfying(path -> assertThat(path).exists());
        assertThat(second).hasValueSatisfying(path -> assertThat(path).exists());
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void givesUpOnReportNamesAfterBoundedSameSecondCollisions() throws Exception {
        // given
        SameSecondReportScenario scenario = sameSecondReportScenario("exhausted-collision");
        Path reportDir = scenario.configDir().resolveSibling("state").resolve("troubleshooting");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("setup-failure-20260502-030405.md"), "taken", StandardCharsets.UTF_8);
        for (int suffix = 2; suffix <= 100; suffix++) {
            Files.writeString(
                    reportDir.resolve("setup-failure-20260502-030405-" + suffix + ".md"),
                    "taken",
                    StandardCharsets.UTF_8);
        }

        // when
        Optional<Path> report = scenario.reportFailure();

        // then
        assertThat(report)
                .as("exhausted same-second names degrade to no report instead of looping")
                .isEmpty();
    }

    private record SameSecondReportScenario(
            Path configDir, SetupDiagnosticReporter reporter, LocalSetupRequest request, RecordingTerminal terminal) {

        Optional<Path> reportFailure() throws IOException {
            return reporter.reportFailure(
                    new TrelloBoardSetupException("setup_workflow_write_failed", "Workflow write failed"),
                    request,
                    terminal);
        }
    }

    /** The fixed clock matches the setup-failure-20260502-030405 report names asserted by callers. */
    private SameSecondReportScenario sameSecondReportScenario(String prefix) throws IOException {
        Path configDir = tempDir.resolve(prefix + "-config");
        Path workspaceRoot = tempDir.resolve(prefix + "-workspaces");
        Path manifest = configDir.resolve(ConnectedBoardManifest.FILE_NAME);
        Path workflow = configDir.resolve("WORKFLOW.report.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Instant now = Instant.parse("2026-05-02T03:04:05Z");
        var reporter = new SetupDiagnosticReporter(
                Map.of(), new FakeCommandRunner(), Files::list, Clock.fixed(now, ZoneOffset.UTC));
        return new SameSecondReportScenario(
                configDir,
                reporter,
                request(configDir, workspaceRoot, manifest, workflow, env),
                new RecordingTerminal());
    }

    @Test
    void selectedDiagnosticsProbesOnlyCurrentWorkflowPortAfterPortChange() throws Exception {
        // given
        Path configDir = tempDir.resolve("reused-port-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path workflow = configDir.resolve("WORKFLOW.reused.md");
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(20991), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "000000000000000000000001",
                        "SYNTH001",
                        "Reused Board",
                        "https://trello.com/b/SYNTH001/reused-board",
                        workflow,
                        configDir.resolve(".env"),
                        tempDir.resolve("workspaces"),
                        20990,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderBoardDiagnostics(reporter, "Reused Board", configDir, stateHome);

        // then
        assertThat(report)
                .contains("http://127.0.0.1:20991/api/v1/local-status")
                .doesNotContain("http://127.0.0.1:20990/");
    }

    @Test
    void diagnosticsRejectsMissingWorkflowSelectorAsExpectedInput() throws Exception {
        // given
        Path configDir = tempDir.resolve("missing-workflow-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path missingWorkflow = configDir.resolve("WORKFLOW.definitely-missing.md");
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Throwable thrown =
                catchThrowable(() -> renderWorkflowDiagnostics(reporter, configDir, stateHome, missingWorkflow));

        // then
        assertThat(thrown)
                .isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.getMessage())
                        .contains("--workflow must reference a readable workflow file"));
    }

    @Test
    void diagnosticsSelectsBoardForBareSelectorWithAccidentalQueryOrFragment() throws Exception {
        // given
        Path configDir = tempDir.resolve("decorated-selector-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "000000000000000000000001",
                        "SYNTH001",
                        "Synthetic Board",
                        "https://trello.com/b/SYNTH001/synthetic-board",
                        configDir.resolve("WORKFLOW.md"),
                        configDir.resolve(".env"),
                        tempDir.resolve("workspaces"),
                        20992,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String byQuery = renderBoardDiagnostics(reporter, "SYNTH001?utm=test", configDir, stateHome);
        String byFragment = renderBoardDiagnostics(reporter, "SYNTH001#fragment", configDir, stateHome);

        // then
        assertThat(byQuery).contains("selected_board_matched:** true", "selected_manifest_board_count:** 1");
        assertThat(byFragment).contains("selected_board_matched:** true", "selected_manifest_board_count:** 1");
    }

    @Test
    void diagnosticsDoesNotCountMissingManifestWorkflowFilesAsIncluded() throws Exception {
        // given
        Path configDir = tempDir.resolve("missing-manifest-workflow-config");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path missingWorkflow = configDir.resolve("WORKFLOW.deleted.md");
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "000000000000000000000001",
                        "SYNTH001",
                        "Deleted Workflow Board",
                        "https://trello.com/b/SYNTH001/deleted-workflow-board",
                        missingWorkflow,
                        configDir.resolve(".env"),
                        tempDir.resolve("workspaces"),
                        20991,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderBoardDiagnostics(reporter, "SYNTH001", configDir, stateHome);

        // then
        assertThat(report)
                .contains("- **selected_workflow_file_count:** 0", "- **selected_workflow_missing_count:** 1");
    }

    @Test
    void displayPathShowsResolvedPathForFilesUnderUserHome() {
        // given
        Path underHome = Path.of(System.getProperty("user.home"), ".local", "share", "symphony-trello", ".env");

        // when
        String displayed = SetupDiagnosticReporter.displayPath(underHome);

        // then
        assertThat(displayed)
                .isEqualTo(underHome.toAbsolutePath().normalize().toString())
                .doesNotContain("$HOME");
    }

    @Test
    void givesActionableHintForWorkerAuthFailureFromDotenvCredentials() {
        // given
        Path env = tempDir.resolve(".env");
        var authFailure = new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed while starting Symphony.")
                .withDotenvPath(env)
                .withTrelloCredentialEnvironmentNames("TRELLO_API_KEY", "TRELLO_API_TOKEN")
                .withTrelloCredentialSources(
                        TrelloBoardSetupException.TrelloCredentialSource.DOTENV_FILE,
                        TrelloBoardSetupException.TrelloCredentialSource.DOTENV_FILE);

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(authFailure);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains(
                        "Check TRELLO_API_KEY and TRELLO_API_TOKEN in this .env credential file:",
                        SetupDiagnosticReporter.displayPath(env))
                .doesNotContain("dotenv-key", "dotenv-token", "shell environment"));
    }

    @Test
    void givesActionableHintForWorkerAuthFailureFromMixedCredentialSources() {
        // given
        Path env = tempDir.resolve(".env");
        var authFailure = new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed while starting Symphony.")
                .withDotenvPath(env)
                .withTrelloCredentialEnvironmentNames("TRELLO_API_KEY", "TRELLO_API_TOKEN")
                .withTrelloCredentialSources(
                        TrelloBoardSetupException.TrelloCredentialSource.DOTENV_FILE,
                        TrelloBoardSetupException.TrelloCredentialSource.SHELL_ENVIRONMENT);

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(authFailure);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains(
                        "Check these Trello credential sources:",
                        "TRELLO_API_KEY: .env credential file\n    " + SetupDiagnosticReporter.displayPath(env),
                        "TRELLO_API_TOKEN: shell environment",
                        "Shell variables take precedence over the .env file passed with --env.")
                .doesNotContain("dotenv-key", "shell-token"));
    }

    @Test
    void givesActionableHintForWorkerAuthFailureFromWorkflowCredentials() {
        // given
        var authFailure = new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed while starting Symphony.")
                .withTrelloCredentialSources(
                        TrelloBoardSetupException.TrelloCredentialSource.WORKFLOW_CONFIG,
                        TrelloBoardSetupException.TrelloCredentialSource.WORKFLOW_CONFIG);

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(authFailure);

        // then
        assertThat(hint).hasValueSatisfying(value -> assertThat(value)
                .contains(
                        "Check these Trello credential sources:",
                        "tracker.api_key: workflow configuration",
                        "tracker.api_token: workflow configuration")
                .doesNotContain("TRELLO_API_KEY", "TRELLO_API_TOKEN"));
    }

    @Test
    void doesNotAddGenericHintWhenCodexAuthMessageAlreadyNamesRetryCommand() {
        // given
        var codexLoginFailed = new TrelloBoardSetupException(
                "setup_codex_login_failed",
                "Codex login did not complete successfully. Run `codex login --device-auth`, then rerun setup-local.");

        // when
        Optional<String> hint = SetupDiagnosticReporter.userActionHint(codexLoginFailed);

        // then
        assertThat(hint).isEmpty();
    }

    @Test
    void rendersDiagnosticsWithInjectedClock() throws Exception {
        // given
        Path configDir = tempDir.resolve("clock-config");
        Files.createDirectories(configDir);
        Instant now = Instant.parse("2026-05-11T12:34:56Z");
        var reporter = new SetupDiagnosticReporter(
                Map.of(), new FakeCommandRunner(), Files::list, Clock.fixed(now, ZoneOffset.UTC));

        // when
        String report = reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        // then
        assertThat(report).contains("time_utc:** 2026-05-11T12:34:56Z");
    }

    @Test
    void rendersSanitizedDiagnosticsForSelectedBoardWithoutAuthProbe() throws Exception {
        // given
        Path configDir = tempDir.resolve("config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stateHome = tempDir.resolve("state");
        Path privateWorkflow = configDir.resolve("WORKFLOW.private.md");
        Path otherWorkflow = configDir.resolve("WORKFLOW.other.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(privateWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19181), StandardCharsets.UTF_8);
        Files.writeString(otherWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19182), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "private-board-id",
                                "SYNTH901",
                                "Sensitive Board Name",
                                "https://trello.com/b/SYNTH901/sensitive-board",
                                privateWorkflow,
                                env,
                                workspaceRoot,
                                19181,
                                true,
                                List.of(tempDir.resolve("client repo")),
                                true),
                        new ConnectedBoard(
                                "other-board-id",
                                "other-key",
                                "Other Board",
                                "https://trello.com/b/other-key/other-board",
                                otherWorkflow,
                                env,
                                workspaceRoot,
                                19182,
                                false,
                                List.of(),
                                false))));
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        Files.writeString(
                store.files(privateWorkflow).stderrLog(),
                """
                TRELLO_API_TOKEN=trello-secret
                board https://trello.com/b/SYNTH901/sensitive-board
                repo /Users/Jane Doe/client/private-repo
                selected-tail-line
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                store.files(otherWorkflow).stderrLog(),
                """
                other-board-secret
                other-tail-line
                """,
                StandardCharsets.UTF_8);
        Path toolDirectory = fakeToolDirectory(tempDir, "codex", "gh");
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "codex-cli 1.2.3\n", toolDirectory.resolve("codex").toString(), "--version")
                .returns(0, "gh version 2.70.0\n", toolDirectory.resolve("gh").toString(), "--version");
        var reporter = new SetupDiagnosticReporter(
                Map.of(
                        "PATH", toolDirectory.toString(),
                        "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString(),
                        "SYMPHONY_TRELLO_WORKSPACE_ROOT", workspaceRoot.toString(),
                        "SYMPHONY_TRELLO_STATE_HOME", stateHome.toString(),
                        "SYMPHONY_TRELLO_COMMAND",
                                tempDir.resolve("bin/symphony-trello").toString(),
                        "SHELL", "/bin/zsh"),
                commands);

        // when
        String report = renderBoardDiagnostics(
                reporter, "https://trello.com/b/SYNTH901/sensitive-board", configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "# Symphony for Trello Diagnostics",
                        "selector:** board",
                        "selected_manifest_board_count:** 1",
                        "selected_board_matched:** true",
                        "command_context:** effective command after installer wrapper defaults",
                        "deep:** disabled",
                        "os_distribution:**",
                        "board_count:** 1",
                        "danger_full_access",
                        "true",
                        "codex-cli 1.2.3; login=not-probed",
                        "gh version 2.70.0; auth=not-probed",
                        "19181",
                        "selected-tail-line")
                .doesNotContain(
                        "Sensitive Board Name",
                        "private-board-id",
                        "SYNTH901",
                        "trello-secret",
                        "https://trello.com/b/SYNTH901/sensitive-board",
                        "Other Board",
                        "other-board-id",
                        "19182",
                        "other-board-secret",
                        "other-tail-line",
                        "/Users/Jane Doe",
                        "Jane Doe",
                        "private-repo",
                        tempDir.toString());
    }

    @Test
    void rendersDiagnosticsForSelectedBoardShortLinkFromBoardUrlWhenBoardKeyIsFullBoardId() throws Exception {
        // given
        Path configDir = tempDir.resolve("url-short-link-config");
        Path workspaceRoot = tempDir.resolve("url-short-link-workspaces");
        Path stateHome = tempDir.resolve("url-short-link-state");
        Path selectedWorkflow = configDir.resolve("WORKFLOW.selected.md");
        Path otherWorkflow = configDir.resolve("WORKFLOW.other.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(selectedWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19191), StandardCharsets.UTF_8);
        Files.writeString(otherWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19192), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "000000000000000000000001",
                                "000000000000000000000001",
                                "Selected Private Board",
                                "https://trello.com/b/SYNTH003/selected-private-board",
                                selectedWorkflow,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19191,
                                false,
                                List.of(),
                                false),
                        new ConnectedBoard(
                                "other-board-id",
                                "other-key",
                                "Other Private Board",
                                "https://trello.com/b/other-key/other-private-board",
                                otherWorkflow,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19192,
                                false,
                                List.of(),
                                false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderBoardDiagnostics(reporter, "SYNTH003", configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "selector:** board",
                        "selected_manifest_board_count:** 1",
                        "selected_board_matched:** true",
                        "board_count:** 1",
                        "19191")
                .doesNotContain(
                        "Selected Private Board",
                        "000000000000000000000001",
                        "SYNTH003",
                        "https://trello.com/b/SYNTH003/selected-private-board",
                        "Other Private Board",
                        "other-board-id",
                        "other-key",
                        "19192",
                        tempDir.toString());
    }

    @Test
    void workflowSummaryResolvesConnectedBoardEnvironmentReferences() throws Exception {
        // given
        Path configDir = tempDir.resolve("env-backed-workflow-config");
        Path workspaceRoot = tempDir.resolve("env-backed-workspaces");
        Path stateHome = tempDir.resolve("env-backed-state");
        Path workflow = configDir.resolve("WORKFLOW.env-backed.md");
        Path env = configDir.resolve(".env.env-backed");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: $SYNTHETIC_BOARD_ID
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                server:
                  port: $SYNTHETIC_WORKER_PORT
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                env,
                """
                SYNTHETIC_BOARD_ID=resolved-env-board-id
                SYNTHETIC_WORKER_PORT=19301
                """,
                StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "resolved-env-board-id",
                        "env-key",
                        "Private Env Board",
                        "https://trello.com/b/env-key/private-env-board",
                        workflow,
                        env,
                        workspaceRoot,
                        19301,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        String boardHash = token(diagnosticsKey(configDir), "resolved-env-board-id");
        assertThat(report)
                .contains(
                        "## Workflow Summary",
                        "| workflow | board_hash | port | max_agents | active | terminal | in_progress | blocked |",
                        boardHash,
                        " | 19301 | ")
                .doesNotContain(
                        "SYNTHETIC_BOARD_ID",
                        "SYNTHETIC_WORKER_PORT",
                        "$SYNTHETIC_BOARD_ID",
                        "$SYNTHETIC_WORKER_PORT",
                        "resolved-env-board-id",
                        "Private Env Board",
                        "env-key",
                        "https://trello.com",
                        "## Invalid Workflow Files",
                        tempDir.toString());

        String selectedReport = renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, workflow);
        assertThat(selectedReport)
                .contains(
                        "selector:** workflow",
                        "selected_workflow_in_manifest:** true",
                        "selected_manifest_board_count:** 1",
                        boardHash,
                        " | 19301 | ")
                .doesNotContain(
                        "SYNTHETIC_BOARD_ID",
                        "SYNTHETIC_WORKER_PORT",
                        "$SYNTHETIC_BOARD_ID",
                        "$SYNTHETIC_WORKER_PORT",
                        "resolved-env-board-id",
                        "Private Env Board",
                        "env-key",
                        "https://trello.com",
                        "## Invalid Workflow Files",
                        tempDir.toString());
    }

    @Test
    void redactsRelativeStateHomeArgumentFromRenderedCommand() throws IOException {
        // given
        Path configDir = tempDir.resolve("relative-state-config");
        Files.createDirectories(configDir);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, Path.of("Jane Doe/state"));

        // then
        assertThat(report).contains("--state-home <redacted>").doesNotContain("Jane Doe", "Jane Doe/state");
    }

    @Test
    void deepDiagnosticsRunsAuthStatusProbes() throws IOException {
        // given
        Path configDir = tempDir.resolve("deep-config");
        Files.createDirectories(configDir);
        Path toolDirectory = fakeToolDirectory(tempDir, "codex", "gh");
        FakeCommandRunner commands = authProbeCommandRunner(toolDirectory);
        var reporter = new SetupDiagnosticReporter(Map.of("PATH", toolDirectory.toString()), commands);

        // when
        String report = reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                true,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        // then
        assertThat(report)
                .contains(
                        "command:** diagnostics --config-dir <redacted> --deep",
                        "deep:** enabled",
                        "codex-cli 1.2.3; login=ok",
                        "gh version 2.70.0; auth=ok")
                .doesNotContain("--probe-auth", "auth_probe");
    }

    @Test
    void deepPrivateContextIncludesDeepDiagnosticsAndPrivateContext() throws IOException {
        // given
        Path configDir = tempDir.resolve("deep-private-config");
        Files.createDirectories(configDir);
        Path toolDirectory = fakeToolDirectory(tempDir, "codex", "gh");
        FakeCommandRunner commands = authProbeCommandRunner(toolDirectory);
        var reporter = new SetupDiagnosticReporter(Map.of("PATH", toolDirectory.toString()), commands);
        var request = new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                true,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        // when
        String report = reporter.renderReport(request, true);

        // then
        assertThat(report)
                .contains(
                        "# Symphony for Trello Private Context",
                        "Private diagnostics context. Do not paste this output into public issues.",
                        "command:** diagnostics --show-private-context --config-dir "
                                + configDir.normalize()
                                + " --deep")
                .doesNotContain(
                        "# Symphony for Trello Diagnostics",
                        "deep:** enabled",
                        "codex-cli 1.2.3; login=ok",
                        "gh version 2.70.0; auth=ok");
    }

    @Test
    void privateContextMapsDiagnosticsHashesAndPathTokensWithoutReadingSecrets() throws Exception {
        // given
        Path configDir = tempDir.resolve("private-context-config");
        Path workspaceRoot = tempDir.resolve("private-context-workspaces");
        Path stateHome = tempDir.resolve("private-context-state");
        Path workflow = configDir.resolve("WORKFLOW.private.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19199), StandardCharsets.UTF_8);
        Files.writeString(env, "TRELLO_API_TOKEN=secret-token\n", StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "private-board-id",
                        "private-key",
                        "Private Board",
                        "https://trello.com/b/private-key/private-board",
                        workflow,
                        env,
                        workspaceRoot,
                        19199,
                        false,
                        List.of(tempDir.resolve("private checkout")),
                        false))));
        ManagedProcessStore.ManagedProcessFiles logs = new ManagedProcessStore(stateHome).files(workflow);
        Files.writeString(logs.stdoutLog(), "secret log content\n", StandardCharsets.UTF_8);
        DiagnosticsTokenHasher.load(configDir);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderPrivateContext(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.of("Private Board"),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.of(workspaceRoot),
                Optional.of(stateHome),
                Optional.empty(),
                Optional.empty()));
        byte[] diagnosticsKey = diagnosticsKey(configDir);
        String diagnosticsKeyHex = HexFormat.of().formatHex(diagnosticsKey);

        // then
        assertThat(report)
                .contains(
                        "# Symphony for Trello Private Context",
                        "Do not paste this output into public issues",
                        "selector:** board",
                        "selected_manifest_board_count:** 1",
                        token(diagnosticsKey, "private-board-id"),
                        token(diagnosticsKey, "private-key"),
                        pathToken(diagnosticsKey, workflow),
                        pathToken(diagnosticsKey, logs.stdoutLog()),
                        "Private Board",
                        "private-board-id",
                        "private-key",
                        "https://trello.com/b/private-key/private-board",
                        workflow.toString(),
                        env.toString(),
                        workspaceRoot.toString(),
                        logs.stdoutLog().toString(),
                        "19199",
                        "stdout")
                .doesNotContain("secret-token", "secret log content", diagnosticsKeyHex);
    }

    @Test
    void privateContextLookupResolvesOneDiagnosticsToken() throws Exception {
        // given
        Path configDir = tempDir.resolve("lookup-config");
        Path workspaceRoot = tempDir.resolve("lookup-workspaces");
        Path stateHome = tempDir.resolve("lookup-state");
        Path workflow = configDir.resolve("WORKFLOW.lookup.md");
        Path env = configDir.resolve(".env.lookup");
        Files.createDirectories(configDir);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19201), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "lookup-board-id",
                        "lookup-key",
                        "Lookup Board",
                        "https://trello.com/b/lookup-key/lookup-board",
                        workflow,
                        env,
                        workspaceRoot,
                        19201,
                        false,
                        List.of(),
                        false))));
        DiagnosticsTokenHasher.load(configDir);
        String lookupToken = pathToken(diagnosticsKey(configDir), workflow);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderReport(
                new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.of("Lookup Board"),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.of(workspaceRoot),
                        Optional.of(stateHome),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(lookupToken)),
                true);

        // then
        assertThat(report)
                .contains(
                        "# Symphony for Trello Private Context",
                        "## Lookup",
                        "- **lookup_token:** " + lookupToken,
                        "- **lookup_status:** found",
                        "| connected_board | workflow_path | " + lookupToken + " | " + workflow + " |",
                        "| workflow | workflow_path | " + lookupToken + " | " + workflow + " |")
                .doesNotContain("lookup-key", "https://trello.com/b/lookup-key/lookup-board");
    }

    @Test
    void privateContextLookupResolvesPathTokensEmittedFromSystemAndInstallerContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("lookup-installer-context-config");
        Path workspaceRoot = tempDir.resolve("lookup-installer-context-workspaces");
        Path stateHome = tempDir.resolve("lookup-installer-context-state");
        Path shell = tempDir.resolve("tools").resolve("bash");
        Path wrapperShell = tempDir.resolve("wrapper").resolve("bash");
        Path binDir = tempDir.resolve("installed").resolve("bin");
        Path codexNpmPrefix = tempDir.resolve("installed").resolve("codex-npm");
        Path installedWorkspaceRoot = tempDir.resolve("installed").resolve("workspaces");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                stateHome.resolve("install-context.properties"),
                """
                bin_dir=%s
                codex_npm_prefix=%s
                workspace_root=%s
                """
                        .formatted(binDir, codexNpmPrefix, installedWorkspaceRoot),
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(
                Map.of("SHELL", shell.toString(), "SYMPHONY_TRELLO_WRAPPER_SHELL", wrapperShell.toString()),
                new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);
        byte[] diagnosticsKey = diagnosticsKey(configDir);
        Map<String, Path> emittedTokens = Map.of(
                pathToken(diagnosticsKey, shell), shell,
                pathToken(diagnosticsKey, wrapperShell), wrapperShell,
                pathToken(diagnosticsKey, binDir), binDir,
                pathToken(diagnosticsKey, codexNpmPrefix), codexNpmPrefix,
                pathToken(diagnosticsKey, installedWorkspaceRoot), installedWorkspaceRoot);

        // then
        assertThat(report)
                .contains(
                        "- **shell:** " + pathToken(diagnosticsKey, shell),
                        "- **wrapper_shell:** " + pathToken(diagnosticsKey, wrapperShell),
                        "bin_dir=" + pathToken(diagnosticsKey, binDir),
                        "codex_npm_prefix=" + pathToken(diagnosticsKey, codexNpmPrefix),
                        "workspace_root=" + pathToken(diagnosticsKey, installedWorkspaceRoot))
                .doesNotContain(
                        shell.toString(),
                        wrapperShell.toString(),
                        binDir.toString(),
                        codexNpmPrefix.toString(),
                        installedWorkspaceRoot.toString());
        for (Map.Entry<String, Path> emittedToken : emittedTokens.entrySet()) {
            String lookup = reporter.renderReport(
                    new SetupDiagnosticReporter.DiagnosticsRequest(
                            Optional.empty(),
                            Optional.empty(),
                            false,
                            false,
                            Optional.empty(),
                            Optional.of(configDir),
                            Optional.of(workspaceRoot),
                            Optional.of(stateHome),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(emittedToken.getKey())),
                    true);

            assertThat(lookup)
                    .contains(
                            "## Lookup",
                            "- **lookup_token:** " + emittedToken.getKey(),
                            "- **lookup_status:** found",
                            "| " + emittedToken.getKey() + " | " + emittedToken.getValue() + " |")
                    .doesNotContain("lookup_status:** not_found");
        }
    }

    @Test
    void privateContextLookupResolvesManagedPidToken() throws Exception {
        // given
        Path configDir = tempDir.resolve("lookup-pid-config");
        Path workspaceRoot = tempDir.resolve("lookup-pid-workspaces");
        Path stateHome = tempDir.resolve("lookup-pid-state");
        Path workflow = configDir.resolve("WORKFLOW.lookup-pid.md");
        Files.createDirectories(configDir);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19203), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "lookup-pid-board-id",
                        "pid-key",
                        "Lookup Pid Board",
                        "https://trello.com/b/pid-key/lookup-pid-board",
                        workflow,
                        configDir.resolve(".env.pid"),
                        workspaceRoot,
                        19203,
                        false,
                        List.of(),
                        false))));
        DiagnosticsTokenHasher.load(configDir);
        Path pidFile = new ManagedProcessStore(stateHome).files(workflow).pidFile();
        String lookupToken = pathToken(diagnosticsKey(configDir), pidFile);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderReport(
                new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.of("Lookup Pid Board"),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.of(workspaceRoot),
                        Optional.of(stateHome),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(lookupToken)),
                true);

        // then
        assertThat(report)
                .contains(
                        "## Lookup",
                        "- **lookup_token:** " + lookupToken,
                        "- **lookup_status:** found",
                        "| process_state | pid_file | " + lookupToken + " | " + pidFile + " |")
                .doesNotContain("pid-key", "https://trello.com/b/pid-key/lookup-pid-board");
    }

    @Test
    void privateContextLookupResolvesFileBackedSecretTokenWithoutReadingSecretValue() throws Exception {
        // given
        Path configDir = tempDir.resolve("lookup-secret-config");
        Path workspaceRoot = tempDir.resolve("lookup-secret-workspaces");
        Path stateHome = tempDir.resolve("lookup-secret-state");
        Path workflow = configDir.resolve("WORKFLOW.lookup-secret.md");
        Path secretFile = configDir.resolve("secrets").resolve("trello-api-key");
        Files.createDirectories(configDir);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: lookup-secret-board-id
                  api_key: file:secrets/trello-api-key
                  api_token: literal-secret-token
                server:
                  port: 19204
                ---
                # Lookup Secret
                """,
                StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "lookup-secret-board-id",
                        "lookup-secret-key",
                        "Lookup Secret Board",
                        "https://trello.com/b/lookup-secret-key/lookup-secret-board",
                        workflow,
                        configDir.resolve(".env.secret"),
                        workspaceRoot,
                        19204,
                        false,
                        List.of(),
                        false))));
        DiagnosticsTokenHasher.load(configDir);
        String lookupToken = pathToken(diagnosticsKey(configDir), secretFile);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderReport(
                new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.of("Lookup Secret Board"),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.of(workspaceRoot),
                        Optional.of(stateHome),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(lookupToken)),
                true);

        // then
        assertThat(report)
                .contains(
                        "## Lookup",
                        "- **lookup_token:** " + lookupToken,
                        "- **lookup_status:** found",
                        "| secret_file | tracker.api_key | " + lookupToken + " | " + secretFile + " |")
                .doesNotContain(
                        "literal-secret-token",
                        "lookup-secret-key",
                        "https://trello.com/b/lookup-secret-key/lookup-secret-board");
    }

    @Test
    void privateContextLookupReportsMissingTokenWithoutDumpingFullPrivateContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("lookup-missing-config");
        Files.createDirectories(configDir);
        DiagnosticsTokenHasher.load(configDir);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderReport(
                new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("<path:000000000000>")),
                true);

        // then
        assertThat(report)
                .contains(
                        "## Lookup",
                        "- **lookup_token:** <path:000000000000>",
                        "- **lookup_status:** not_found",
                        "No private-context mapping matched this token")
                .doesNotContain("## Local Paths", "## Connected Board Identifiers");
    }

    @Test
    void privateContextLookupRejectsMalformedTokenWithoutDumpingFullPrivateContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("lookup-malformed-config");
        Path workflow = configDir.resolve("WORKFLOW.private.md");
        Files.createDirectories(configDir);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19202), StandardCharsets.UTF_8);
        DiagnosticsTokenHasher.load(configDir);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderReport(
                new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("not-a-token")),
                true);

        // then
        assertThat(report)
                .contains(
                        "## Lookup",
                        "- **lookup_token:** not-a-token",
                        "- **lookup_status:** invalid_token",
                        "Lookup accepts public diagnostics tokens")
                .doesNotContain("## Local Paths", "## Workflow Identifiers", workflow.toString());
    }

    @Test
    void diagnosticsReportsTemporaryTokenKeyFallbackWithoutLeakingKeyFilePath() throws Exception {
        // given
        Path configDir = tempDir.resolve("Jane Doe").resolve("temporary-key-config");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME), "invalid-local-key", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        // then
        assertThat(report)
                .contains(
                        "diagnostics_token_key:** temporary",
                        "Diagnostics tokens are stable only for this run because the local diagnostics key could not be read or written.")
                .doesNotContain(
                        configDir.toString(), DiagnosticsTokenHasher.KEY_FILE_NAME, "Jane Doe", "invalid-local-key");
    }

    @Test
    void diagnosticsDoesNotCreateMissingConfigDirectoryForTokenKey() throws Exception {
        // given
        Path configDir = tempDir.resolve("missing-config");
        Path workspaceRoot = tempDir.resolve("workspace");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "# Symphony for Trello Diagnostics",
                        "diagnostics_token_key:** temporary",
                        "Diagnostics tokens are stable only for this run because the local diagnostics key could not be read or written.")
                .doesNotContain(configDir.toString(), DiagnosticsTokenHasher.KEY_FILE_NAME);
        assertThat(configDir).doesNotExist();
    }

    @Test
    void diagnosticsCreatesTokenKeyForExplicitExistingConfigDirectory() throws Exception {
        // given
        Path configDir = tempDir.resolve("existing-config");
        Files.createDirectories(configDir);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

        // then
        assertThat(report).contains("diagnostics_token_key:** local");
        assertThat(configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).isRegularFile();
    }

    @Test
    void deepPrivateContextReusesTemporaryTokenKeyAcrossPrivateContextReport() throws Exception {
        // given
        Path configDir = tempDir.resolve("deep-private-temporary-key-config");
        Path workspaceRoot = tempDir.resolve("deep-private-temporary-key-workspaces");
        Path stateHome = tempDir.resolve("deep-private-temporary-key-state");
        Path workflow = configDir.resolve("WORKFLOW.private.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME), "invalid-local-key", StandardCharsets.UTF_8);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19198), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "private-board-id",
                        "private-key",
                        "Private Board",
                        "https://trello.com/b/private-key/private-board",
                        workflow,
                        configDir.resolve(".env"),
                        workspaceRoot,
                        19198,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = reporter.renderReport(
                new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        true,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.of(workspaceRoot),
                        Optional.of(stateHome),
                        Optional.empty(),
                        Optional.empty()),
                true);

        // then
        String workflowToken = firstMatch(
                report,
                Pattern.compile("\\| (<path:[0-9a-f]{12}>) \\| " + Pattern.quote(workflow.toString()) + " \\|"));
        assertThat(report)
                .contains(
                        "# Symphony for Trello Private Context",
                        "Private diagnostics context. Do not paste this output into public issues.",
                        "diagnostics_token_key:** temporary",
                        "Diagnostics tokens are stable only for this run because the local diagnostics key could not be read or written.",
                        workflow.toString())
                .contains(workflowToken)
                .contains("| " + workflowToken + " | " + workflow + " |")
                .doesNotContain("# Symphony for Trello Diagnostics");
    }

    @Test
    void setupFailureReportIncludesTemporaryTokenKeyFallback() throws Exception {
        // given
        Path configDir = tempDir.resolve("setup-failure-temporary-key-config");
        Path stateHome = tempDir.resolve("setup-failure-temporary-key-state");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME), "invalid-local-key", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Path report = reporter.write(
                        new RuntimeException("unexpected failure"),
                        List.of(
                                "setup-local",
                                "--config-dir",
                                configDir.toString(),
                                "--state-home",
                                stateHome.toString()))
                .orElseThrow();

        // then
        assertThat(report)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "diagnostics_token_key:** temporary",
                        "Diagnostics tokens are stable only for this run because the local diagnostics key could not be read or written.")
                .doesNotContain(DiagnosticsTokenHasher.KEY_FILE_NAME, "invalid-local-key");
    }

    @Test
    void renderDiagnosticsContinuesWhenRecentLogsCannotBeListedWithoutLeakingPath() throws Exception {
        // given
        Path configDir = tempDir.resolve("safe-log-config");
        Path workspaceRoot = tempDir.resolve("safe-log-workspaces");
        Path stateHome = tempDir.resolve("Jane Doe").resolve("private-state");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner(), path -> {
            throw new IOException("Access denied for " + path.resolve("secret-log.err"));
        });

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains("## Recent Logs", "Could not list recent worker logs.")
                .doesNotContain(
                        tempDir.toString(),
                        stateHome.toString(),
                        "Jane Doe",
                        "private-state",
                        "secret-log.err",
                        "Access denied",
                        "AccessDeniedException",
                        "IOException",
                        "Troubleshooting report written");
    }

    @Test
    void diagnosticsRedactsTokenShapedSecretsInRecentLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("token-log-config");
        Path workspaceRoot = tempDir.resolve("token-log-workspaces");
        Path stateHome = tempDir.resolve("token-log-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME), "{\"boards\":[]}", StandardCharsets.UTF_8);
        String githubToken = "ghp_" + "abcdefghijklmnopqrstuvwxyz1234567890abcd";
        String trelloToken = "ATTA" + "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
        String openAiToken = "sk-" + "proj-abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
        Files.writeString(
                stateHome.resolve("fake-secret.log"),
                """
                github token %s
                trello key 1234567890abcdef1234567890abcdef
                trello token %s
                openai %s
                password=super-secret-value
                ordinary 1234567890abcdef1234567890abcdef
                """
                        .formatted(githubToken, trelloToken, openAiToken),
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "github token <redacted>",
                        "trello key <redacted>",
                        "trello token <redacted>",
                        "openai <redacted>",
                        "password=<redacted>",
                        "ordinary 1234567890abcdef1234567890abcdef")
                .doesNotContain(
                        githubToken,
                        "trello key 1234567890abcdef1234567890abcdef",
                        trelloToken,
                        openAiToken,
                        "super-secret-value");
    }

    @Test
    void rendersEachPrivatePathOccurrenceInRecentLogsAsOneSinglePathToken() throws Exception {
        // given
        Path configDir = tempDir.resolve("single-token-log-config");
        Path workspaceRoot = tempDir.resolve("single-token-log-workspaces");
        Path stateHome = tempDir.resolve("single-token-log-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.writeString(
                stateHome.resolve("single-token.log"),
                """
                exact %s end
                nested %s/nested/output.txt end
                ref private-ref-value end
                """
                        .formatted(configDir, configDir),
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(
                Map.of("SYMPHONY_TRELLO_REF", "private-ref-value"), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        byte[] key = diagnosticsKey(configDir);
        assertThat(report)
                .contains(
                        "exact <path:" + token(key, configDir.toString()) + "> end",
                        "nested <path:" + token(key, configDir + "/nested/output.txt") + "> end",
                        "ref <value:" + token(key, "private-ref-value") + "> end")
                .doesNotContain(
                        "><path:",
                        "<value:" + token(key, configDir.toString()) + ">",
                        tempDir.toString(),
                        "private-ref-value");
    }

    @Test
    void preservesCommonInstallerRefWordsInRecentLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("common-ref-config");
        Path workspaceRoot = tempDir.resolve("common-ref-workspaces");
        Path stateHome = tempDir.resolve("common-ref-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.writeString(stateHome.resolve("install-context.properties"), "ref=main\n", StandardCharsets.UTF_8);
        Files.writeString(
                stateHome.resolve("worker.log"),
                "INFO  [io.quarkus] (main) symphony-trello started\n",
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of("SYMPHONY_TRELLO_REF", "main"), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        byte[] key = diagnosticsKey(configDir);
        assertThat(report)
                .contains("ref=<value:" + token(key, "main") + ">", "INFO  [io.quarkus] (main)")
                .doesNotContain("(<value:", "ref=main");
    }

    @Test
    void redactsShortPrivateInstallerRefsInRecentLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("short-private-ref-config");
        Path workspaceRoot = tempDir.resolve("short-private-ref-workspaces");
        Path stateHome = tempDir.resolve("short-private-ref-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.writeString(stateHome.resolve("install-context.properties"), "ref=prod\n", StandardCharsets.UTF_8);
        Files.writeString(stateHome.resolve("worker.log"), "branch prod failed\n", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of("SYMPHONY_TRELLO_REF", "prod"), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        byte[] key = diagnosticsKey(configDir);
        assertThat(report)
                .contains("ref=<value:" + token(key, "prod") + ">", "branch <value:" + token(key, "prod") + "> failed")
                .doesNotContain("ref=prod", "branch prod failed");
    }

    @Test
    void redactsShortInstallerRepoUrlsInRecentLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("short-repo-url-config");
        Path workspaceRoot = tempDir.resolve("short-repo-url-workspaces");
        Path stateHome = tempDir.resolve("short-repo-url-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        String repoUrl = "git@h:r";
        Files.writeString(
                stateHome.resolve("install-context.properties"), "repo_url=" + repoUrl + "\n", StandardCharsets.UTF_8);
        Files.writeString(stateHome.resolve("worker.log"), "repo " + repoUrl + " failed\n", StandardCharsets.UTF_8);
        var reporter =
                new SetupDiagnosticReporter(Map.of("SYMPHONY_TRELLO_REPO_URL", repoUrl), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        byte[] key = diagnosticsKey(configDir);
        assertThat(report)
                .contains(
                        "repo_url=<value:" + token(key, repoUrl) + ">",
                        "repo <value:" + token(key, repoUrl) + "> failed")
                .doesNotContain("repo_url=" + repoUrl, "repo " + repoUrl + " failed");
    }

    @Test
    void redactsInstallerSourceCommitsInRecentLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("source-commit-config");
        Path workspaceRoot = tempDir.resolve("source-commit-workspaces");
        Path stateHome = tempDir.resolve("source-commit-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        String sourceCommit = "0123456789abcdef0123456789abcdef01234567";
        Files.writeString(
                stateHome.resolve("install-context.properties"),
                "source_commit=" + sourceCommit + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                stateHome.resolve("worker.log"), "commit " + sourceCommit + " failed\n", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        byte[] key = diagnosticsKey(configDir);
        assertThat(report)
                .contains(
                        "source_commit=<value:" + token(key, sourceCommit) + ">",
                        "commit <value:" + token(key, sourceCommit) + "> failed")
                .doesNotContain("source_commit=" + sourceCommit, "commit " + sourceCommit + " failed");
    }

    @Test
    void doesNotUseMalformedInstallerSourceCommitAsRecentLogRedactionTerm() throws Exception {
        // given
        Path configDir = tempDir.resolve("malformed-source-commit-config");
        Path workspaceRoot = tempDir.resolve("malformed-source-commit-workspaces");
        Path stateHome = tempDir.resolve("malformed-source-commit-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        String malformedSourceCommit = "not-a-commit-value";
        Files.writeString(
                stateHome.resolve("install-context.properties"),
                "source_commit=" + malformedSourceCommit + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                stateHome.resolve("worker.log"),
                "ordinary " + malformedSourceCommit + " text\n",
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        byte[] key = diagnosticsKey(configDir);
        assertThat(report)
                .contains(
                        "source_commit=<value:" + token(key, malformedSourceCommit) + ">",
                        "ordinary " + malformedSourceCommit + " text")
                .doesNotContain("ordinary <value:" + token(key, malformedSourceCommit) + "> text");
    }

    @Test
    void diagnosticsRedactsYamlParserSecretContinuationLinesInRecentLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("yaml-parser-log-config");
        Path workspaceRoot = tempDir.resolve("yaml-parser-log-workspaces");
        Path stateHome = tempDir.resolve("yaml-parser-log-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME), "{\"boards\":[]}", StandardCharsets.UTF_8);
        String trelloToken = "ATTA" + "thisLooksLikeASecretTokenValue1234567890";
        Files.writeString(
                stateHome.resolve("parser-secret.log"),
                """
                Failed to parse workflow front matter
                 api_token: "%s
                 ... sLikeASecretTokenValue1234567890
                line: 4, column: 14
                """
                        .formatted(trelloToken),
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "Failed to parse workflow front matter",
                        "api_token: \"<redacted>",
                        "... <redacted>",
                        "line: 4, column: 14")
                .doesNotContain(
                        trelloToken, "thisLooksLikeASecretTokenValue1234567890", "sLikeASecretTokenValue1234567890");
    }

    @Test
    void diagnosticsUsesLongerLogFenceWhenLogContainsMarkdownFence() throws Exception {
        // given
        Path configDir = tempDir.resolve("fenced-log-config");
        Path workspaceRoot = tempDir.resolve("fenced-log-workspaces");
        Path stateHome = tempDir.resolve("fenced-log-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME), "{\"boards\":[]}", StandardCharsets.UTF_8);
        Files.writeString(
                stateHome.resolve("fake-fence.log"),
                """
                before fence
                ```
                # injected markdown heading
                [link](https://example.com)
                ```
                after fence
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        """
                        ````text
                        before fence
                        ```
                        # injected markdown heading
                        [link](https://example.com)
                        ```
                        after fence
                        ````
                        """)
                .doesNotContain("\n```text\nbefore fence\n```\n# injected markdown heading\n");
    }

    @Test
    void diagnosticsEscapesTerminalControlCharactersInRecentLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("control-log-config");
        Path workspaceRoot = tempDir.resolve("control-log-workspaces");
        Path stateHome = tempDir.resolve("control-log-state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        Files.writeString(
                configDir.resolve(ConnectedBoardManifest.FILE_NAME), "{\"boards\":[]}", StandardCharsets.UTF_8);
        Files.writeString(
                stateHome.resolve("fake-control.log"),
                """
                normal line
                ansi \u001B[31mred\u001B[0m line
                osc \u001B]8;;https://example.com\u0007Link\u001B]8;;\u0007 line
                backspace abc\b\bxy
                formfeed before\fafter
                carriage before\rafter
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "normal line",
                        "ansi \\u001B[31mred\\u001B[0m line",
                        "osc \\u001B]8;;https://example.com\\u0007Link\\u001B]8;;\\u0007 line",
                        "backspace abc\\b\\bxy",
                        "formfeed before\\fafter",
                        "carriage before",
                        "after")
                .doesNotContain("\u001B", "\u0007", "\b", "\f", "\r");
    }

    @Test
    void diagnosticsExplainsMissingManifestWhileStillSummarizingLocalWorkflows() throws Exception {
        // given
        Path configDir = tempDir.resolve("workflow-only-config");
        Path workspaceRoot = tempDir.resolve("workflow-only-workspaces");
        Path stateHome = tempDir.resolve("workflow-only-state");
        Path firstWorkflow = configDir.resolve("WORKFLOW.first.md");
        Path secondWorkflow = configDir.resolve("WORKFLOW.second.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(firstWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19194), StandardCharsets.UTF_8);
        Files.writeString(secondWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19195), StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "selected_manifest_board_count:** 0",
                        "selected_workflow_file_count:** 2",
                        "manifest_status:** missing",
                        "board_count:** 0",
                        "No connected-board manifest was found.",
                        "http://127.0.0.1:19194/api/v1/local-status",
                        "http://127.0.0.1:19195/api/v1/local-status")
                .doesNotContain(tempDir.toString());
    }

    @Test
    void diagnosticsOmitsEmptyLogsAndStripsStartupBannerFromLogTail() throws Exception {
        // given
        Path configDir = tempDir.resolve("log-trim-config");
        Path workspaceRoot = tempDir.resolve("log-trim-workspaces");
        Path stateHome = tempDir.resolve("log-trim-state");
        Path workflow = configDir.resolve("WORKFLOW.logs.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19196), StandardCharsets.UTF_8);
        ManagedProcessStore.ManagedProcessFiles logs = new ManagedProcessStore(stateHome).files(workflow);
        Files.writeString(
                logs.stdoutLog(),
                """

                2026-05-29 08:02:26,698 INFO  [app] previous worker event

                __  ____  __  _____   ___  __ ____  ______
                 --/ __ \\/ / / / _ | / _ \\/ //_/ / / / __/
                 -/ /_/ / /_/ / __ |/ , _/ ,< / /_/ /\\ \\
                --\\___\\_\\____/_/ |_/_/|_/_/|_|\\____/___/
                2026-05-29 08:02:27,698 INFO  [app] worker started
                 --/ legitimate command output
                2026-05-29 08:03:29,223 INFO  [app] useful worker event
                """,
                StandardCharsets.UTF_8);
        Files.writeString(logs.stderrLog(), "", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "previous worker event",
                        "worker started",
                        " --/ legitimate command output",
                        "useful worker event")
                .doesNotContain("__  ____", "--/ __ \\", "-/ /_/", "--\\___");
    }

    @Test
    void diagnosticsDoesNotReadSymlinkedWorkerLogs() throws Exception {
        // given
        Path configDir = tempDir.resolve("symlink-log-config");
        Path workspaceRoot = tempDir.resolve("symlink-log-workspaces");
        Path stateHome = tempDir.resolve("symlink-log-state");
        Path workflow = configDir.resolve("WORKFLOW.symlink-log.md");
        Path privateHostFile = tempDir.resolve("private-host-file.txt");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19197), StandardCharsets.UTF_8);
        Files.writeString(privateHostFile, "PRIVATE_HOST_FILE_MARKER_SHOULD_NOT_APPEAR\n", StandardCharsets.UTF_8);
        ManagedProcessStore.ManagedProcessFiles logs = new ManagedProcessStore(stateHome).files(workflow);
        createSymbolicLinkOrSkip(logs.stdoutLog(), privateHostFile);
        Files.writeString(logs.stderrLog(), "", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains("## Recent Logs", "No worker logs found.")
                .doesNotContain(
                        "PRIVATE_HOST_FILE_MARKER_SHOULD_NOT_APPEAR",
                        privateHostFile.toString(),
                        logs.stdoutLog().toString(),
                        tempDir.toString());
    }

    @Test
    void diagnosticsPreservesPreTimestampLogOutputThatIsNotStartupBanner() throws Exception {
        // given
        Path configDir = tempDir.resolve("pre-timestamp-log-config");
        Path workspaceRoot = tempDir.resolve("pre-timestamp-log-workspaces");
        Path stateHome = tempDir.resolve("pre-timestamp-log-state");
        Path workflow = configDir.resolve("WORKFLOW.pre-timestamp.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19197), StandardCharsets.UTF_8);
        ManagedProcessStore.ManagedProcessFiles logs = new ManagedProcessStore(stateHome).files(workflow);
        Files.writeString(
                logs.stdoutLog(),
                """
                JVM startup failure before logging initialized
                java.lang.IllegalStateException: useful pre-log failure
                2026-05-29 08:02:27,698 INFO  [app] worker started
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "JVM startup failure before logging initialized",
                        "java.lang.IllegalStateException: useful pre-log failure",
                        "worker started");
    }

    @Test
    void diagnosticsStripsPartialStartupBannerAtStartOfLogTail() throws Exception {
        // given
        Path configDir = tempDir.resolve("partial-banner-log-config");
        Path workspaceRoot = tempDir.resolve("partial-banner-log-workspaces");
        Path stateHome = tempDir.resolve("partial-banner-log-state");
        Path workflow = configDir.resolve("WORKFLOW.partial-banner.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19198), StandardCharsets.UTF_8);
        ManagedProcessStore.ManagedProcessFiles logs = new ManagedProcessStore(stateHome).files(workflow);
        Files.writeString(
                logs.stdoutLog(),
                """

                 --/ __ \\/ / / / _ | / _ \\/ //_/ / / / __/
                 -/ /_/ / /_/ / __ |/ , _/ ,< / /_/ /\\ \\
                --\\___\\_\\____/_/ |_/_/|_/_/|_|\\____/___/
                 --/ legitimate command output
                2026-05-29 08:03:29,223 INFO  [app] useful worker event
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(" --/ legitimate command output", "useful worker event")
                .doesNotContain("--/ __ \\", "-/ /_/", "--\\___");
    }

    @Test
    void diagnosticsSelectsManifestBoardForRelativeWorkflowFromConfigDirectory() throws Exception {
        // given
        Path configDir = tempDir.resolve("relative-config");
        Path workspaceRoot = tempDir.resolve("relative-workspaces");
        Path stateHome = tempDir.resolve("relative-state");
        Path workflow = configDir.resolve("WORKFLOW.relative.md");
        Path otherWorkflow = configDir.resolve("WORKFLOW.other-relative.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19184), StandardCharsets.UTF_8);
        Files.writeString(otherWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19185), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "relative-board-id",
                                "SYNTH101",
                                "Relative Board",
                                "https://trello.com/b/SYNTH101/relative-board",
                                workflow,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19184,
                                false,
                                List.of(),
                                false),
                        new ConnectedBoard(
                                "other-board-id",
                                "SYNTH102",
                                "Other Relative Board",
                                "https://trello.com/b/SYNTH102/other-relative-board",
                                otherWorkflow,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19185,
                                false,
                                List.of(),
                                false))));
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        Files.writeString(store.files(workflow).stdoutLog(), "selected workflow log\n", StandardCharsets.UTF_8);
        Files.writeString(store.files(otherWorkflow).stdoutLog(), "other workflow log\n", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(
                reporter, configDir, workspaceRoot, stateHome, Path.of("WORKFLOW.relative.md"));

        // then
        assertThat(report)
                .contains(
                        "selector:** workflow",
                        "selected_workflow_in_manifest:** true",
                        "selected_manifest_board_count:** 1",
                        "board_count:** 1",
                        "19184",
                        "selected workflow log")
                .doesNotContain(
                        "Relative Board",
                        "relative-board-id",
                        "SYNTH101",
                        "https://trello.com/b/SYNTH101/relative-board",
                        "Other Relative Board",
                        "other-board-id",
                        "SYNTH102",
                        "https://trello.com/b/SYNTH102/other-relative-board",
                        "19185",
                        "other workflow log",
                        tempDir.toString());
    }

    /**
     * The manifest is hand-editable, so a board row may miss envPath; diagnostics load leniently
     * and resolve workflow environment references from the config-directory .env default.
     */
    @Test
    void diagnosticsResolveWorkflowEnvironmentFromConfigDirectoryDotenvWhenManifestEnvPathIsMissing() throws Exception {
        // given
        Path configDir = tempDir.resolve("missing-env-path-config");
        Path workspaceRoot = tempDir.resolve("missing-env-path-workspaces");
        Path stateHome = tempDir.resolve("missing-env-path-state");
        Path workflow = configDir.resolve("WORKFLOW.missing-env-path.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflow, TestWorkflows.diagnosticsWorkflowWithEnvironmentBackedPort(), StandardCharsets.UTF_8);
        Files.writeString(
                configDir.resolve(".env"),
                """
                BOARD_ID_REF=000000000000000000000002
                BOARD_STATUS_PORT=19421
                """,
                StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "000000000000000000000002",
                        "SYNTH002",
                        "Synthetic Env Board",
                        "https://trello.com/b/SYNTH002/synthetic-env-board",
                        workflow,
                        null,
                        workspaceRoot,
                        19421,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains("## Workflow Summary", " | 19421 | ")
                .doesNotContain(
                        "## Invalid Workflow Files",
                        "## Invalid Connected Board Workflows",
                        "$BOARD_STATUS_PORT",
                        "$BOARD_ID_REF",
                        "Synthetic Env Board",
                        "000000000000000000000002",
                        "SYNTH002",
                        "https://trello.com",
                        tempDir.toString());
    }

    @Test
    void diagnosticsIncludesWorkflowOutsideManifestWithoutUnrelatedBoardContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("external-workflow-config");
        Path workspaceRoot = tempDir.resolve("external-workspaces");
        Path stateHome = tempDir.resolve("external-state");
        Path manifestWorkflow = configDir.resolve("WORKFLOW.manifest.md");
        Path requestedWorkflow = tempDir.resolve("external").resolve("WORKFLOW.requested.md");
        Files.createDirectories(configDir);
        Files.createDirectories(requestedWorkflow.getParent());
        Files.createDirectories(stateHome);
        Files.writeString(manifestWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19186), StandardCharsets.UTF_8);
        Files.writeString(requestedWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19187), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "manifest-board-id",
                        "SYNTH201",
                        "Manifest Board",
                        "https://trello.com/b/SYNTH201/manifest-board",
                        manifestWorkflow,
                        configDir.resolve(".env"),
                        workspaceRoot,
                        19186,
                        false,
                        List.of(),
                        false))));
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        Files.writeString(store.files(manifestWorkflow).stdoutLog(), "manifest workflow log\n", StandardCharsets.UTF_8);
        Files.writeString(
                store.files(requestedWorkflow).stdoutLog(), "requested workflow log\n", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, requestedWorkflow);

        // then
        assertThat(report)
                .contains(
                        "selector:** workflow",
                        "selected_workflow_in_manifest:** false",
                        "selected_manifest_board_count:** 0",
                        "board_count:** 0",
                        "19187",
                        "requested workflow log")
                .doesNotContain(
                        "Manifest Board",
                        "manifest-board-id",
                        "SYNTH201",
                        "https://trello.com/b/SYNTH201/manifest-board",
                        "19186",
                        "manifest workflow log",
                        tempDir.toString());
    }

    @Test
    void diagnosticsRejectsAmbiguousBoardSelectorWithoutLeakingPrivateContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("ambiguous-board-config");
        Path workspaceRoot = tempDir.resolve("ambiguous-workspaces");
        Path stateHome = tempDir.resolve("ambiguous-state");
        Path workflowA = configDir.resolve("WORKFLOW.private-a.md");
        Path workflowB = configDir.resolve("WORKFLOW.private-b.md");
        String privateBoardName = "Private Duplicate Board";
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflowA, TestWorkflows.diagnosticsWorkflowWithPort(19188), StandardCharsets.UTF_8);
        Files.writeString(workflowB, TestWorkflows.diagnosticsWorkflowWithPort(19189), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "private-board-a-id",
                                "SYNTH301",
                                privateBoardName,
                                "https://trello.com/b/SYNTH301/private-a",
                                workflowA,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19188,
                                false,
                                List.of(),
                                false),
                        new ConnectedBoard(
                                "private-board-b-id",
                                "SYNTH302",
                                privateBoardName,
                                "https://trello.com/b/SYNTH302/private-b",
                                workflowB,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19189,
                                false,
                                List.of(),
                                false))));
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        Files.writeString(store.files(workflowA).stdoutLog(), "private board A log\n", StandardCharsets.UTF_8);
        Files.writeString(store.files(workflowB).stdoutLog(), "private board B log\n", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Throwable thrown = catchThrowable(
                () -> renderBoardDiagnostics(reporter, privateBoardName, configDir, workspaceRoot, stateHome));

        // then
        assertThat(thrown)
                .isInstanceOfSatisfying(TrelloBoardSetupException.class, exception -> assertThat(exception)
                        .extracting(TrelloBoardSetupException::code, Throwable::getMessage)
                        .containsExactly(
                                "setup_invalid_arguments",
                                "Multiple connected boards match --board. Re-run with a board id or short link."))
                .hasMessageContaining("Multiple connected boards match --board")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(
                                privateBoardName,
                                "private-board-a-id",
                                "private-board-b-id",
                                "SYNTH301",
                                "SYNTH302",
                                "https://trello.com/b/SYNTH301/private-a",
                                "https://trello.com/b/SYNTH302/private-b",
                                workflowA.toString(),
                                workflowB.toString(),
                                "private board A log",
                                "private board B log",
                                tempDir.toString()));
        assertThat(configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).doesNotExist();
    }

    @Test
    void diagnosticsRejectsAmbiguousWorkflowSelectorWithoutLeakingPrivateContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("ambiguous-workflow-config");
        Path workspaceRoot = tempDir.resolve("ambiguous-workflow-workspaces");
        Path stateHome = tempDir.resolve("ambiguous-workflow-state");
        Path workflow = configDir.resolve("WORKFLOW.private-shared.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19193), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "private-workflow-board-a-id",
                                "SYNTH401",
                                "Private Workflow Board A",
                                "https://trello.com/b/SYNTH401/private-workflow-a",
                                workflow,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19193,
                                false,
                                List.of(),
                                false),
                        new ConnectedBoard(
                                "private-workflow-board-b-id",
                                "SYNTH402",
                                "Private Workflow Board B",
                                "https://trello.com/b/SYNTH402/private-workflow-b",
                                workflow,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19194,
                                false,
                                List.of(),
                                false))));
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        Files.writeString(store.files(workflow).stdoutLog(), "private workflow log\n", StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Throwable thrown = catchThrowable(
                () -> renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, workflow));

        // then
        assertThat(thrown)
                .isInstanceOfSatisfying(TrelloBoardSetupException.class, exception -> assertThat(exception)
                        .extracting(TrelloBoardSetupException::code, Throwable::getMessage)
                        .containsExactly(
                                "setup_invalid_arguments",
                                "Multiple connected-board rows reference --workflow. Repair "
                                        + ConnectedBoardManifest.FILE_NAME + ", then rerun the command."))
                .hasMessageContaining("Multiple connected-board rows reference --workflow")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain(
                                "Private Workflow Board A",
                                "Private Workflow Board B",
                                "private-workflow-board-a-id",
                                "private-workflow-board-b-id",
                                "SYNTH401",
                                "SYNTH402",
                                "https://trello.com/b/SYNTH401/private-workflow-a",
                                "https://trello.com/b/SYNTH402/private-workflow-b",
                                workflow.toString(),
                                "19193",
                                "19194",
                                "private workflow log",
                                tempDir.toString()));
        assertThat(configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).doesNotExist();
    }

    @Test
    void diagnosticsRejectsBothBoardAndWorkflowSelectorsAtReporterBoundary() throws Exception {
        // given
        Path configDir = tempDir.resolve("conflicting-selector-config");
        Path workflow = configDir.resolve("WORKFLOW.private.md");
        Files.createDirectories(configDir);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19190), StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Throwable thrown =
                catchThrowable(() -> reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.of("Private Board"),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(workflow))));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, exception -> assertThat(exception)
                .extracting(TrelloBoardSetupException::code, Throwable::getMessage)
                .containsExactly("setup_invalid_arguments", "--board and --workflow cannot be used together."));
        assertThat(configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).doesNotExist();
    }

    @Test
    void diagnosticsRejectsBlankBoardSelectorAtReporterBoundary() throws Exception {
        // given
        Path configDir = tempDir.resolve("blank-board-selector-config");
        Files.createDirectories(configDir);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Throwable thrown =
                catchThrowable(() -> reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.of(" "),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())));

        // then
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        TrelloBoardSetupException.class,
                        exception -> assertThat(exception)
                                .extracting(TrelloBoardSetupException::code, Throwable::getMessage)
                                .containsExactly(
                                        "setup_invalid_arguments",
                                        "--board must not be empty. Provide a Trello board name, id, or short link, or omit --board to use the command's default scope."));
        assertThat(configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).doesNotExist();
    }

    @Test
    void diagnosticsRejectsBlankWorkflowSelectorAtReporterBoundary() throws Exception {
        // given
        Path configDir = tempDir.resolve("blank-workflow-selector-config");
        Files.createDirectories(configDir);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Throwable thrown =
                catchThrowable(() -> reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(configDir),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(Path.of("")))));

        // then
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        TrelloBoardSetupException.class,
                        exception -> assertThat(exception)
                                .extracting(TrelloBoardSetupException::code, Throwable::getMessage)
                                .containsExactly(
                                        "setup_invalid_arguments",
                                        "--workflow must not be empty. Provide a workflow path, or omit --workflow to use the command's default scope."));
        assertThat(configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME)).doesNotExist();
    }

    @Test
    void diagnosticsRejectsUnusableWorkflowSelectorsAtReporterBoundary() throws Exception {
        // given
        Path configDir = tempDir.resolve("unusable-workflow-selector-config");
        Path workspaceRoot = tempDir.resolve("unusable-workflow-selector-workspaces");
        Path stateHome = tempDir.resolve("unusable-workflow-selector-state");
        Path directory = configDir.resolve("WORKFLOW.directory.md");
        Path missing = configDir.resolve("WORKFLOW.missing.md");
        Path empty = configDir.resolve("WORKFLOW.empty.md");
        Path noFrontMatter = configDir.resolve("WORKFLOW.no-frontmatter.md");
        Path invalidPort = configDir.resolve("WORKFLOW.invalid-port.md");
        Files.createDirectories(directory);
        Files.createDirectories(stateHome);
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        Files.writeString(noFrontMatter, "Body only\n", StandardCharsets.UTF_8);
        Files.writeString(
                invalidPort,
                """
                ---
                tracker:
                  board_id: "private-board-id"
                server:
                  port: "not-a-port"
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        // Invalid server.port values stay selectable: diagnostics is the inspection tool for
        // such workflows and reports the port problem inside the rendered report instead.
        List<Path> unusableSelectors = List.of(directory, missing, empty, noFrontMatter);

        // when
        List<Throwable> thrown = unusableSelectors.stream()
                .map(workflow -> catchThrowable(
                        () -> renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, workflow)))
                .toList();

        // then
        assertThat(thrown).allSatisfy(exception -> assertThat(exception)
                .isInstanceOfSatisfying(
                        TrelloBoardSetupException.class,
                        setupException -> assertThat(setupException)
                                .extracting(TrelloBoardSetupException::code, Throwable::getMessage)
                                .containsExactly(
                                        "setup_invalid_arguments",
                                        "--workflow must reference a readable workflow file with usable workflow front matter."))
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain(
                                tempDir.toString(),
                                configDir.toString(),
                                directory.toString(),
                                missing.toString(),
                                empty.toString(),
                                noFrontMatter.toString(),
                                "private-board-id")));
        String invalidPortReport =
                renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, invalidPort);
        assertThat(invalidPortReport).contains("invalid server.port");
    }

    @Test
    void writesSanitizedReportWithInstallerToolWorkflowAndLogContext() throws Exception {
        // given
        Path appHome = tempDir.resolve("app");
        Path configDir = tempDir.resolve("config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stateHome = tempDir.resolve("state");
        int port = freePort();
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Path workflow = configDir.resolve("WORKFLOW.private-board.md");
        Path env = configDir.resolve(".env");
        String workflowContent =
                """
                ---
                tracker:
                  board_id: "private-board-id"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                  in_progress_state: "In Progress"
                server:
                  port: %d
                agent:
                  max_concurrent_agents: 2
                codex:
                  additional_writable_roots:
                    - "/home/alice/private-project"
                ---
                Body
                """
                        .formatted(port);
        String privateSourceCommit = "abcdef0123456789abcdef0123456789abcdef01";
        Files.writeString(workflow, workflowContent, StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "private-board-id",
                        "private-key",
                        "Sensitive Board Name",
                        "https://trello.com/b/private-key/sensitive-board",
                        workflow,
                        env,
                        workspaceRoot,
                        port,
                        true,
                        List.of(tempDir.resolve("private-project")),
                        false))));
        Files.writeString(
                stateHome.resolve("WORKFLOW.private-board.md.abc.err"),
                """
                token=secret-value
                "api_token":"json-secret"
                Authorization: Bearer bearer-secret
                Authorization: Basic basic-secret
                Authorization: token ghp-secret
                Authorization: OAuth oauth_consumer_key="oauth-key-secret", oauth_token="oauth-token-secret"
                path=/home/alice/private-project
                trailing slash path /home/alice/private-project/: denied
                backtick path /mnt/client`name/repo failed
                non-ascii path é/home/alice/nonascii-path failed
                path=/Volumes/Client Work/repo
                unquoted workflow /Volumes/Client Work/repo/WORKFLOW.md failed
                quoted="/Users/Jane Doe/project"
                windows="C:\\Users\\Jane Doe\\repo"
                pr url https://github.com/private-org/client-repo/pull/123
                https remote https://github.com/private-org/client-repo.git
                repository git@github.com:private/repo.git
                prefixed remote repo:git@github.com:private/punctuated.git
                non-ascii remote égit@github.com:private/nonascii.git
                bare remote host git@github.com
                ssh remote ssh://git@github.com/private-org/client-repo.git
                branch feature/private-ref
                card_id=private-log-card-id
                card_identifier=TRELLO-private-short
                {"cardId":"private-json-card-id","cardIdentifier":"TRELLO-private-json"}
                nested parent path %s
                """
                        .formatted(appHome.resolve("Secret Client").resolve("repo")),
                StandardCharsets.UTF_8);
        Files.writeString(
                stateHome.resolve("install-context.properties"),
                """
                repo_url=git@github.com:private/repo.git
                ref=feature/private-ref
                source_commit=%s
                TRELLO_TOKEN=secret-value
                """
                        .formatted(privateSourceCommit),
                StandardCharsets.UTF_8);
        Files.writeString(stateHome.resolve("large.log"), largeLog(), StandardCharsets.UTF_8);
        Path toolDirectory = fakeToolDirectory(tempDir, "git", "codex");
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(0, "git version 2.45.0\n", toolDirectory.resolve("git").toString(), "--version")
                .returns(0, "codex-cli 1.2.3\n", toolDirectory.resolve("codex").toString(), "--version")
                .returns(1, "not logged in\n", toolDirectory.resolve("codex").toString(), "login", "status");
        var reporter = new SetupDiagnosticReporter(
                Map.of(
                        "PATH", toolDirectory.toString(),
                        "SYMPHONY_TRELLO_APP_HOME", appHome.toString(),
                        "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString(),
                        "SYMPHONY_TRELLO_WORKSPACE_ROOT", workspaceRoot.toString(),
                        "SYMPHONY_TRELLO_STATE_HOME", stateHome.toString(),
                        "SYMPHONY_TRELLO_DOTENV", env.toString(),
                        "SYMPHONY_TRELLO_REPO_URL", "git@github.com:private/repo.git",
                        "SYMPHONY_TRELLO_REF", "feature/private-ref",
                        "SHELL", "/bin/bash"),
                commands);

        Path report;
        HttpServer server = fakeLocalServer(port);
        try {

            // when
            report = reporter.write(
                            new TrelloBoardSetupException(
                                    "setup_start_unhealthy",
                                    "No connected Trello board matched \"Top Secret Board\". "
                                            + "No active list was provided. "
                                            + "Open lists: Secret Queue v2.1, Internal Backlog v3.2"),
                            List.of(
                                    "setup-local",
                                    "--non-interactive",
                                    "--name",
                                    "Top Secret Board",
                                    "--token",
                                    "split-secret",
                                    "--key=inline-secret",
                                    "--board-name",
                                    "Private Launch Board",
                                    "--board=private-short-link",
                                    "--repository-url",
                                    HTTPS,
                                    "--repository-url=" + SSH,
                                    "--active",
                                    "Secret Queue"))
                    .orElseThrow();
        } finally {
            server.stop(0);
        }

        // then
        assertThat(report).exists();
        assertThat(report)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "## Failure",
                        "setup_start_unhealthy",
                        "## Installer Context",
                        "## Tool Availability",
                        "codex-cli 1.2.3; login=not-ok",
                        "## Workflow Summary",
                        String.valueOf(port),
                        "## Local Health Probes",
                        "\"resolved_board_hash\"",
                        "\"configured_board_input_hash\"",
                        "\"running_count\":1",
                        "\"retrying_count\":1",
                        "## Recent Logs",
                        "source_commit=<value:",
                        "tail-line-149")
                .doesNotContain(
                        "prefix-line-0",
                        "private-board-id",
                        "older-private-short-link",
                        "\"configured_board_hash\"",
                        "private-key",
                        "Sensitive Board Name",
                        "secret-value",
                        "json-secret",
                        "bearer-secret",
                        "basic-secret",
                        "ghp-secret",
                        "oauth-key-secret",
                        "oauth-token-secret",
                        "git@github.com:private/repo.git",
                        "feature/private-ref",
                        privateSourceCommit,
                        "Top Secret Board",
                        "split-secret",
                        "inline-secret",
                        "Private Launch Board",
                        "private-short-link",
                        HTTPS,
                        SSH,
                        "Secret Queue",
                        "Secret Queue v2.1",
                        "Internal Backlog v3.2",
                        "private-card-id",
                        "TRELLO-private",
                        "private-log-card-id",
                        "TRELLO-private-short",
                        "private-json-card-id",
                        "TRELLO-private-json",
                        "private runtime message",
                        "/home/alice/private-project",
                        "/mnt/client`name/repo",
                        "client`name",
                        "/home/alice/nonascii-path",
                        "nonascii-path",
                        "/Volumes/Client Work/repo",
                        "/Users/Jane Doe/project",
                        "C:\\Users\\Jane Doe\\repo",
                        "Client Work",
                        "Jane Doe",
                        "private-org",
                        "client-repo",
                        "punctuated",
                        "nonascii",
                        "Secret Client",
                        tempDir.toString());
    }

    @Test
    void diagnosticsIgnoresSymlinkedInstallerContext() throws Exception {
        // given
        Path appHome = tempDir.resolve("app");
        Path configDir = tempDir.resolve("config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stateHome = tempDir.resolve("state");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(stateHome);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of()));
        Path privateContext = tempDir.resolve("private-context.txt");
        Files.writeString(
                privateContext,
                """
                PRIVATE_INSTALL_CONTEXT_MARKER_SHOULD_NOT_APPEAR
                secret_token=abc123
                """,
                StandardCharsets.UTF_8);
        createSymbolicLinkOrSkip(stateHome.resolve("install-context.properties"), privateContext);
        var reporter = new SetupDiagnosticReporter(
                Map.of(
                        "SYMPHONY_TRELLO_APP_HOME", appHome.toString(),
                        "SYMPHONY_TRELLO_CONFIG_DIR", configDir.toString(),
                        "SYMPHONY_TRELLO_WORKSPACE_ROOT", workspaceRoot.toString(),
                        "SYMPHONY_TRELLO_STATE_HOME", stateHome.toString()),
                new FakeCommandRunner());

        // when
        String report = renderDefaultDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains("## Installer Context")
                .doesNotContain("PRIVATE_INSTALL_CONTEXT_MARKER_SHOULD_NOT_APPEAR", "secret_token", "abc123");
    }

    @Test
    void handledSetupFailureUsesRequestPathsForReportContext() throws Exception {
        // given
        Path configDir = tempDir.resolve("custom-config");
        Path workspaceRoot = tempDir.resolve("custom-workspaces");
        Path manifest = Path.of("relative-connected-boards.json");
        Path resolvedManifest = configDir.resolve(manifest);
        Path workflow = configDir.resolve("WORKFLOW.request.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: "request-board-id"
                server:
                  port: 19090
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        new ConnectedBoardRepository(resolvedManifest)
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "request-board-id",
                        "request-key",
                        "Request Board",
                        "https://trello.com/b/request-key/request-board",
                        workflow,
                        env,
                        workspaceRoot,
                        19090,
                        false,
                        List.of(),
                        false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        var terminal = new RecordingTerminal();

        // when
        Optional<Path> report = reporter.reportFailure(
                new TrelloBoardSetupException(
                        "setup_request_failed",
                        "Setup failed for Private Queue, Private Done, Private Progress, and Private Blocked. "
                                + "This token can access multiple Trello Workspaces. "
                                + "Available Workspaces: abc (Secret Workspace), def (Client Space)"),
                request(configDir, workspaceRoot, manifest, workflow, env),
                terminal);

        // then
        assertThat(report).hasValueSatisfying(path -> {
            assertThat(path).startsWith(tempDir.resolve("state").resolve("troubleshooting"));
            assertThat(path)
                    .content(StandardCharsets.UTF_8)
                    .contains("board_count:** 1", "19090")
                    .doesNotContain(
                            "Request Board",
                            "request-board-id",
                            "Private Queue",
                            "Private Done",
                            "Private Progress",
                            "Private Blocked",
                            "Secret Workspace",
                            "Client Space",
                            tempDir.toString());
        });
        assertThat(terminal.stderr()).contains("Troubleshooting report written:", "private context");
    }

    @Test
    void dryRunFailureDoesNotWriteTroubleshootingReport() {
        // given
        Path configDir = tempDir.resolve("dry-run-config");
        Path workspaceRoot = tempDir.resolve("dry-run-workspaces");
        Path manifest = Path.of(ConnectedBoardManifest.FILE_NAME);
        Path workflow = configDir.resolve("WORKFLOW.dry-run.md");
        Path env = configDir.resolve(".env");
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        var terminal = new RecordingTerminal();

        // when
        Optional<Path> report = reporter.reportFailure(
                new TrelloBoardSetupException("setup_dry_run_failed", "dry run failed"),
                request(configDir, workspaceRoot, manifest, workflow, env, true),
                terminal);

        // then
        assertThat(report).isEmpty();
        assertThat(tempDir.resolve("state")).doesNotExist();
        assertThat(terminal.stderr()).doesNotContain("Troubleshooting report written:");
    }

    @Test
    void missingConfigDirectoryStillWritesTroubleshootingReport() throws Exception {
        // given
        Path configDir = tempDir.resolve("missing-config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path workflow = configDir.resolve("WORKFLOW.missing.md");
        Path env = configDir.resolve(".env");
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        var terminal = new RecordingTerminal();

        // when
        Optional<Path> report = reporter.reportFailure(
                new TrelloBoardSetupException("setup_missing_config", "setup failed"),
                request(configDir, workspaceRoot, Path.of(ConnectedBoardManifest.FILE_NAME), workflow, env),
                terminal);

        // then
        assertThat(report).hasValueSatisfying(path -> assertThat(path)
                .content(StandardCharsets.UTF_8)
                .contains("## Workflow Summary", "## Local Health Probes", "No configured local ports found."));
        assertThat(terminal.stderr()).contains("Troubleshooting report written:");
    }

    @Test
    void healthProbesIncludeCustomMarkdownWorkflowWhenManifestIsMissing() throws Exception {
        // given
        Path configDir = tempDir.resolve("custom-workflow-config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path workflow = tempDir.resolve("custom-board.md");
        Path env = configDir.resolve(".env");
        int port = freePort();
        Files.createDirectories(configDir);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: "custom-board-id"
                server:
                  port: %d
                ---
                Body
                """
                        .formatted(port),
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        var terminal = new RecordingTerminal();

        // when
        Optional<Path> report = reporter.reportFailure(
                new TrelloBoardSetupException("setup_custom_workflow_failed", "setup failed"),
                request(configDir, workspaceRoot, Path.of("missing-manifest.json"), workflow, env),
                terminal);

        // then
        assertThat(report).hasValueSatisfying(path -> assertThat(path)
                .content(StandardCharsets.UTF_8)
                .contains("http://127.0.0.1:" + port + "/api/v1/local-status")
                .doesNotContain("No configured local ports found."));
    }

    @Test
    void lifecycleReportResolvesRelativeWorkflowFromCallerDirectory() throws Exception {
        // given
        Path configDir = tempDir.resolve("lifecycle-config");
        Path relativeWorkflow = Path.of("target", "diagnostic-workflow-" + System.nanoTime() + ".md");
        Files.createDirectories(relativeWorkflow.toAbsolutePath().normalize().getParent());
        Files.writeString(relativeWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(19192), StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        try {

            // when
            Optional<Path> report = reporter.write(
                    new TrelloBoardSetupException("setup_lifecycle_failed", "setup failed"),
                    List.of(
                            "start",
                            "--board",
                            "setup-local",
                            "--config-dir",
                            configDir.toString(),
                            "--workflow",
                            relativeWorkflow.toString()));

            // then
            assertThat(report).hasValueSatisfying(path -> assertThat(path)
                    .content(StandardCharsets.UTF_8)
                    .contains("http://127.0.0.1:19192/api/v1/local-status")
                    .doesNotContain("No configured local ports found."));
        } finally {
            Files.deleteIfExists(relativeWorkflow);
        }
    }

    @Test
    void setupLocalReportResolvesRelativeWorkflowFromConfigDirectory() throws Exception {
        // given
        Path configDir = tempDir.resolve("setup-config");
        Path workflow = configDir.resolve("custom.md");
        Files.createDirectories(configDir);
        Files.writeString(workflow, TestWorkflows.diagnosticsWorkflowWithPort(19193), StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        Optional<Path> report = reporter.write(
                new TrelloBoardSetupException("setup_local_failed", "setup failed"),
                List.of("setup-local", "--config-dir", configDir.toString(), "--workflow", "custom.md"));

        // then
        assertThat(report).hasValueSatisfying(path -> assertThat(path)
                .content(StandardCharsets.UTF_8)
                .contains("http://127.0.0.1:19193/api/v1/local-status")
                .doesNotContain("No configured local ports found."));
    }

    @Test
    void healthProbesIgnoreUnrelatedMarkdownWithServerPort() throws Exception {
        // given
        Path configDir = tempDir.resolve("markdown-config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path workflow = configDir.resolve("WORKFLOW.missing.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve("notes.md"),
                """
                ---
                server:
                  port: 19091
                ---
                Notes, not a Symphony workflow.
                """,
                StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());
        var terminal = new RecordingTerminal();

        // when
        Optional<Path> report = reporter.reportFailure(
                new TrelloBoardSetupException("setup_notes_failed", "setup failed"),
                request(configDir, workspaceRoot, Path.of("missing-manifest.json"), workflow, env),
                terminal);

        // then
        assertThat(report).hasValueSatisfying(path -> assertThat(path)
                .content(StandardCharsets.UTF_8)
                .contains("No configured local ports found.")
                .doesNotContain("19091"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"many\"", "0", "-2", "1.5", "null"})
    void diagnosticsFlagsInvalidWorkflowMaxAgentsValues(String maxAgentsValue) throws Exception {
        // given
        Path configDir = tempDir.resolve("invalid-max-agents-config-" + maxAgentsValue.hashCode());
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stateHome = tempDir.resolve("state");
        Path workflow = configDir.resolve("WORKFLOW.invalid-max-agents.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflow, TestWorkflows.diagnosticsWorkflowWithMaxAgents(maxAgentsValue), StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, workflow);

        // then
        assertThat(report)
                .contains(
                        "## Workflow Summary",
                        "| workflow | board_hash | port | max_agents | active | terminal | in_progress | blocked |",
                        "<path:",
                        " | 20731 | invalid | 1 | 1 | false | false |")
                .doesNotContain(tempDir.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"not-a-port\"", "-1", "70000", "18080.5"})
    void diagnosticsSurfacesInvalidWorkflowFilesDuringBroadConfigScan(String invalidPortValue) throws Exception {
        // given
        Path configDir = tempDir.resolve("invalid-workflow-scan-config");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stateHome = tempDir.resolve("state");
        Path invalidWorkflow = configDir.resolve("WORKFLOW.invalid-port.md");
        Path validWorkflow = configDir.resolve("WORKFLOW.no-tracker.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                invalidWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "custom-board-id"
                server:
                  port: %s
                ---
                Body
                """
                        .formatted(invalidPortValue),
                StandardCharsets.UTF_8);
        Files.writeString(validWorkflow, TestWorkflows.diagnosticsWorkflowWithPort(20999), StandardCharsets.UTF_8);
        // The fixed clock keeps the rendered time_utc line free of every invalidPortValue substring;
        // the system clock makes "-1" match dates such as 2026-06-10.
        var reporter = new SetupDiagnosticReporter(
                Map.of(),
                new FakeCommandRunner(),
                Files::list,
                Clock.fixed(Instant.parse("2026-05-02T03:04:05Z"), ZoneOffset.UTC));

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "## Workflow Summary",
                        "## Invalid Workflow Files",
                        "- **invalid_workflow_count:** 1",
                        "| workflow | problem |",
                        "| --- | --- |",
                        "<path:",
                        "invalid server.port",
                        " | 20999 | ")
                .doesNotContain(tempDir.toString(), "custom-board-id");
        if (invalidPortValue.equals("-1") || invalidPortValue.equals("70000")) {
            assertThat(report)
                    .contains("Configured port " + invalidPortValue
                            + " is outside the valid TCP port range; health probes skipped.");
        } else {
            assertThat(report).doesNotContain(invalidPortValue.replace("\"", ""));
        }
    }

    @Test
    void diagnosticsSurfacesInvalidConnectedBoardWorkflowFiles() throws Exception {
        // given
        Path configDir = tempDir.resolve("invalid-connected-workflow-config");
        Path workspaceRoot = tempDir.resolve("private-workspaces");
        Path stateHome = tempDir.resolve("state");
        Path plainWorkflow = configDir.resolve("WORKFLOW.private-plain.md");
        Path missingWorkflow = configDir.resolve("WORKFLOW.private-missing.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(plainWorkflow, "plain body with client notes\n", StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "private-board-id",
                                "private-key",
                                "Sensitive Board Name",
                                "https://trello.com/b/private-key/sensitive-board",
                                plainWorkflow,
                                env,
                                workspaceRoot,
                                19201,
                                true,
                                List.of(),
                                false),
                        new ConnectedBoard(
                                "missing-board-id",
                                "missing-key",
                                "Missing Board Name",
                                "https://trello.com/b/missing-key/missing-board",
                                missingWorkflow,
                                env,
                                workspaceRoot,
                                19202,
                                false,
                                List.of(),
                                false))));
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderGlobalDiagnostics(reporter, configDir, workspaceRoot, stateHome);

        // then
        assertThat(report)
                .contains(
                        "- **manifest_status:** loaded",
                        "## Invalid Connected Board Workflows",
                        "- **invalid_connected_board_workflow_count:** 2",
                        "| board_hash | workflow | problem |",
                        "unusable workflow configuration",
                        "missing workflow file")
                .doesNotContain(
                        tempDir.toString(),
                        "Sensitive Board Name",
                        "Missing Board Name",
                        "private-board-id",
                        "private-key",
                        "missing-board-id",
                        "missing-key",
                        "https://trello.com",
                        "plain body with client notes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"Ready for Codex\"", "null", "[]"})
    void diagnosticsFlagsInvalidWorkflowRoutingListValues(String routingListValue) throws Exception {
        // given
        Path configDir = tempDir.resolve("invalid-routing-config-" + routingListValue.hashCode());
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stateHome = tempDir.resolve("state");
        Path workflow = configDir.resolve("WORKFLOW.invalid-routing.md");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflow, TestWorkflows.diagnosticsWorkflowWithRoutingLists(routingListValue), StandardCharsets.UTF_8);
        var reporter = new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner());

        // when
        String report = renderWorkflowDiagnostics(reporter, configDir, workspaceRoot, stateHome, workflow);

        // then
        assertThat(report)
                .contains(
                        "## Workflow Summary",
                        "| workflow | board_hash | port | max_agents | active | terminal | in_progress | blocked |",
                        "<path:",
                        " | 20722 |  | invalid | invalid | false | false |")
                .doesNotContain(tempDir.toString(), "Ready for Codex");
    }

    private static String renderGlobalDiagnostics(SetupDiagnosticReporter reporter, Path configDir, Path stateHome)
            throws IOException {
        return renderDiagnostics(reporter, Optional.empty(), configDir, Optional.empty(), stateHome, Optional.empty());
    }

    private static String renderDeepDiagnostics(SetupDiagnosticReporter reporter, Path configDir, Path stateHome)
            throws IOException {
        return reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                Optional.empty(),
                Optional.empty(),
                false,
                true,
                Optional.empty(),
                Optional.of(configDir),
                Optional.empty(),
                Optional.of(stateHome),
                Optional.empty(),
                Optional.empty()));
    }

    private static String renderGlobalDiagnostics(
            SetupDiagnosticReporter reporter, Path configDir, Path workspaceRoot, Path stateHome) throws IOException {
        return renderDiagnostics(
                reporter, Optional.empty(), configDir, Optional.of(workspaceRoot), stateHome, Optional.empty());
    }

    private static String renderBoardDiagnostics(
            SetupDiagnosticReporter reporter, String board, Path configDir, Path stateHome) throws IOException {
        return renderDiagnostics(
                reporter, Optional.of(board), configDir, Optional.empty(), stateHome, Optional.empty());
    }

    private static String renderBoardDiagnostics(
            SetupDiagnosticReporter reporter, String board, Path configDir, Path workspaceRoot, Path stateHome)
            throws IOException {
        return renderDiagnostics(
                reporter, Optional.of(board), configDir, Optional.of(workspaceRoot), stateHome, Optional.empty());
    }

    private static String renderWorkflowDiagnostics(
            SetupDiagnosticReporter reporter, Path configDir, Path stateHome, Path workflow) throws IOException {
        return renderDiagnostics(
                reporter, Optional.empty(), configDir, Optional.empty(), stateHome, Optional.of(workflow));
    }

    private static String renderWorkflowDiagnostics(
            SetupDiagnosticReporter reporter, Path configDir, Path workspaceRoot, Path stateHome, Path workflow)
            throws IOException {
        return renderDiagnostics(
                reporter, Optional.empty(), configDir, Optional.of(workspaceRoot), stateHome, Optional.of(workflow));
    }

    private static String renderDiagnostics(
            SetupDiagnosticReporter reporter,
            Optional<String> board,
            Path configDir,
            Optional<Path> workspaceRoot,
            Path stateHome,
            Optional<Path> workflow)
            throws IOException {
        return reporter.renderDiagnostics(new SetupDiagnosticReporter.DiagnosticsRequest(
                board,
                Optional.empty(),
                false,
                false,
                Optional.empty(),
                Optional.of(configDir),
                workspaceRoot,
                Optional.of(stateHome),
                Optional.empty(),
                workflow));
    }

    private record DiagnosticsFixture(Path configDir, Path stateHome, SetupDiagnosticReporter reporter) {
        private static DiagnosticsFixture create(Path tempDir, String prefix) throws IOException {
            Path configDir = tempDir.resolve(prefix + "-config");
            Path stateHome = tempDir.resolve(prefix + "-state");
            Files.createDirectories(configDir);
            Files.createDirectories(stateHome);
            return new DiagnosticsFixture(
                    configDir, stateHome, new SetupDiagnosticReporter(Map.of(), new FakeCommandRunner()));
        }

        private Path workflow(String fileName, String content) throws IOException {
            Path workflow = configDir.resolve(fileName);
            Files.writeString(workflow, content, StandardCharsets.UTF_8);
            return workflow;
        }

        private void saveSyntheticBoard(Path workflow, int serverPort) throws IOException {
            SetupDiagnosticReporterTest.saveSyntheticBoard(configDir, workflow, serverPort);
        }

        private String renderGlobal() throws IOException {
            return renderGlobalDiagnostics(reporter, configDir, stateHome);
        }

        private String renderWorkflow(Path workflow) throws IOException {
            return renderWorkflowDiagnostics(reporter, configDir, stateHome, workflow);
        }
    }

    private static HttpServer fakeLocalServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext(
                "/api/v1/local-status",
                exchange -> writeJson(
                        exchange,
                        """
                {"boardId":"private-board-id","configuredBoardId":"older-private-short-link","workflowPath":"/home/alice/private-project/WORKFLOW.md"}
                """));
        server.createContext(
                "/api/v1/state",
                exchange -> writeJson(
                        exchange,
                        """
                {
                  "generated_at":"2026-05-11T00:00:00Z",
                  "counts":{"running":1,"retrying":1},
                  "running":[{"card_id":"private-card-id","card_identifier":"TRELLO-private","last_message":"private runtime message"}],
                  "retrying":[{"card_id":"private-card-id-2","card_identifier":"TRELLO-private-2","error":"private retry message"}],
                  "routing":{"active_lists":["Ready for Codex"],"terminal_lists":["Done"],"handoff_lists":[]}
                }
                """));
        server.start();
        return server;
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(body);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            abort("Symbolic links are not available in this test environment: "
                    + e.getClass().getSimpleName());
        }
    }

    private static String renderDefaultDiagnostics(
            SetupDiagnosticReporter reporter, Path configDir, Path workspaceRoot, Path stateHome) throws IOException {
        return renderGlobalDiagnostics(reporter, configDir, workspaceRoot, stateHome);
    }

    private static FakeCommandRunner authProbeCommandRunner(Path toolDirectory) {
        return new FakeCommandRunner()
                .returns(0, "codex-cli 1.2.3\n", toolDirectory.resolve("codex").toString(), "--version")
                .returns(
                        0,
                        "Logged in using ChatGPT\n",
                        toolDirectory.resolve("codex").toString(),
                        "login",
                        "status")
                .returns(0, "gh version 2.70.0\n", toolDirectory.resolve("gh").toString(), "--version")
                .returns(0, "github.com\n", toolDirectory.resolve("gh").toString(), "auth", "status");
    }

    private static Path fakeToolDirectory(Path parent, String... tools) throws IOException {
        Path toolDirectory = parent.resolve("fake-tools-" + String.join("-", tools));
        Files.createDirectories(toolDirectory);
        for (String tool : tools) {
            Path executable = toolDirectory.resolve(tool);
            Files.writeString(executable, "", StandardCharsets.UTF_8);
            executable.toFile().setExecutable(true);
        }
        return toolDirectory;
    }

    private static String[] windowsBatchCommand(Path executable, String... arguments) {
        List<String> parts = new ArrayList<>();
        parts.add(executable.toString());
        parts.addAll(List.of(arguments));
        return new String[] {
            "cmd.exe",
            "/d",
            "/s",
            "/c",
            "\"" + parts.stream().map(SetupDiagnosticReporterTest::quoteForCmd).collect(joining(" ")) + "\""
        };
    }

    private static String quoteForCmd(String value) {
        return "\"" + value.replace("%", "%%").replace("\"", "\\\"") + "\"";
    }

    private static String pathToken(byte[] key, Path path) {
        return "<path:" + token(key, path.toString()) + ">";
    }

    private static byte[] diagnosticsKey(Path configDir) throws IOException {
        return HexFormat.of()
                .parseHex(Files.readString(
                                configDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME), StandardCharsets.UTF_8)
                        .strip());
    }

    private static String token(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA3-256");
            mac.init(new SecretKeySpec(key, "HmacSHA3-256"));
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA3-256 is unavailable", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Diagnostics token key is invalid", e);
        }
    }

    private static String firstMatch(String value, Pattern pattern) {
        java.util.regex.Matcher matcher = pattern.matcher(value);
        assertThat(matcher.find()).as("expected pattern %s to match", pattern).isTrue();
        return matcher.group(1);
    }

    private static String largeLog() {
        StringBuilder log = new StringBuilder();
        for (int index = 0; index < 5_000; index++) {
            log.append("prefix-line-").append(index).append(" secret=old\n");
        }
        for (int index = 0; index < 150; index++) {
            log.append("tail-line-").append(index).append('\n');
        }
        return log.toString();
    }

    private static LocalSetupRequest request(
            Path configDir, Path workspaceRoot, Path manifest, Path workflow, Path env) {
        return request(configDir, workspaceRoot, manifest, workflow, env, false);
    }

    private static LocalSetupRequest request(
            Path configDir, Path workspaceRoot, Path manifest, Path workflow, Path env, boolean dryRun) {
        return new LocalSetupRequest(
                LocalSetupRequest.Action.SETUP,
                dryRun,
                true,
                false,
                false,
                Optional.empty(),
                Optional.of("key"),
                Optional.of("token"),
                Optional.of("Request Board"),
                Optional.empty(),
                Optional.empty(),
                List.of("Private Queue"),
                List.of("Private Done"),
                "Private Progress",
                false,
                "Private Blocked",
                Optional.of(workflow),
                Optional.of(workspaceRoot),
                Optional.of(configDir),
                Optional.of(manifest),
                Optional.empty(),
                1,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.of(env),
                List.of(),
                false,
                true,
                false,
                URI.create("https://api.trello.com/1"));
    }
}
