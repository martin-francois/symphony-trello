package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.CodexInvestigationFinding.Classification;
import ch.fmartin.symphony.trello.setup.CodexInvestigationFinding.ValidationRun;
import ch.fmartin.symphony.trello.setup.CodexInvestigationRunner.Outcome;
import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Covers the interactive follow-up after an unexpected setup failure: the opt-in Codex
/// investigation and the GitHub issue prompt, driven through the real troubleshooting report and
/// sanitizer with a fake Codex runner and a fake `gh`.
final class SetupFailureFollowUpTest {
    private static final String TRELLO_API_KEY = "0123456789abcdef0123456789abcdef";
    // Split so the private-context scanner does not read the synthetic token as a real one.
    private static final String TRELLO_API_TOKEN = "ATTA" + "syntheticTrelloToken0123456789abcdefXYZ";
    private static final String BOARD_NAME = "Synthetic Private Board";
    private static final String TRELLO_BOARD_URL = "https://trello.com/b/SYNTH001/synthetic-board";
    private static final String UNEXPECTED_CODE = "setup_workflow_write_failed";
    private static final String CODEX_QUESTION = "Ask Codex to investigate this failure? [y/N] ";
    private static final String REPORT_ISSUE_QUESTION =
            "GitHub CLI is authenticated. Open a GitHub issue with this report?";
    private static final String FIX_ISSUE_QUESTION =
            "Codex changed files locally and validation passed. Open a GitHub issue that describes the failure and"
                    + " the local fix?";
    private static final String POSTED_ISSUE_URL = "https://github.com/martin-francois/symphony-trello/issues/1";
    private static final Instant NOW = Instant.parse("2026-05-02T03:04:05Z");

    @TempDir
    Path tempDir;

    @Test
    void expectedFailureOffersNeitherCodexNorGithubIssue() throws IOException {
        // given
        Scenario scenario = scenario(ScenarioOptions.signedInCodex(), "y", "y", "y");

        // when
        Optional<Path> report = scenario.fail(new TrelloBoardSetupException("setup_missing_api_key", "missing"));

        // then
        assertThat(report)
                .as("expected failures write no troubleshooting report")
                .isEmpty();
        assertThat(scenario.codex().requests()).isEmpty();
        assertThat(scenario.terminal().stdout()).doesNotContain(CODEX_QUESTION, REPORT_ISSUE_QUESTION);
        assertThat(scenario.commands().commands()).doesNotContain(List.of("gh", "auth", "status"));
    }

    @MethodSource("unavailableCodex")
    @ParameterizedTest(name = "{0}")
    void unavailableCodexFallsBackToGithubIssuePrompt(String scenarioName, ScenarioOptions options) throws IOException {
        // given
        Scenario scenario = scenario(options, "n");

        // when
        scenario.failUnexpectedly();

        // then
        assertThat(scenario.terminal().stdout()).contains(REPORT_ISSUE_QUESTION).doesNotContain(CODEX_QUESTION);
        assertThat(scenario.codex().requests()).isEmpty();
    }

    static Stream<Arguments> unavailableCodex() {
        return Stream.of(
                Arguments.of(
                        "codex not on PATH", ScenarioOptions.signedInCodex().with(Condition.NO_CODEX_ON_PATH)),
                Arguments.of("codex signed out", ScenarioOptions.signedInCodex().with(Condition.CODEX_SIGNED_OUT)));
    }

    @Test
    void signedInCodexIsOfferedBeforeTheIssuePromptAndCanBeDeclined() throws IOException {
        // given
        Scenario scenario = scenario(ScenarioOptions.signedInCodex(), "n", "n");

        // when
        scenario.failUnexpectedly();

        // then
        assertThat(scenario.terminal().stdout()).containsSubsequence(CODEX_QUESTION, REPORT_ISSUE_QUESTION);
        assertThat(scenario.terminal().stderr())
                .contains(
                        "Codex receives the Symphony for Trello version, the command name and how it was started,"
                                + " the error code, and the sanitized troubleshooting report.",
                        "sees local paths while it works",
                        "only sanitized text goes into a GitHub issue draft");
        assertThat(scenario.codex().requests())
                .as("declining must not start Codex")
                .isEmpty();
    }

    @Test
    void investigationWithoutChangesExplainsTheDiagnosisAndKeepsTheIssuePrompt() throws IOException {
        // given
        Scenario scenario = scenario(
                ScenarioOptions.signedInCodex()
                        .withCodexOutcome(completed(new CodexInvestigationFinding(
                                Classification.ENVIRONMENT,
                                "Java 21 is first on PATH, but Symphony for Trello needs Java 25.",
                                false,
                                "",
                                List.of(),
                                List.of(),
                                "Put a Java 25 JDK first on PATH, then rerun setup-local."))),
                "y",
                "n");

        // when
        scenario.failUnexpectedly();

        // then
        assertThat(scenario.terminal().stderr())
                .contains(
                        "Finding: problem in this machine's environment",
                        "Diagnosis: Java 21 is first on PATH, but Symphony for Trello needs Java 25.",
                        "Changed files: none",
                        "Validation: none run",
                        "Next step: Put a Java 25 JDK first on PATH, then rerun setup-local.");
        assertThat(scenario.terminal().stdout()).containsSubsequence(CODEX_QUESTION, REPORT_ISSUE_QUESTION);
        assertThat(scenario.codex().requests()).singleElement().satisfies(request -> {
            assertThat(request.workingRoot()).isEqualTo(scenario.configDir());
            assertThat(request.additionalWritableRoots()).isEmpty();
            assertThat(request.timeout()).isEqualTo(SetupFailureFollowUp.CODEX_INVESTIGATION_TIMEOUT);
            assertThat(request.prompt())
                    .contains(
                            "- command: setup-local",
                            "- error_code: " + UNEXPECTED_CODE,
                            "# Symphony for Trello Setup Failure",
                            "symphony-trello diagnostics --show-private-context --lookup '<token>'",
                            "Inspect local files directly when you need more detail.");
        });
    }

    @Test
    void verifiedLocalFixAsksToPostAnIssueWithTheSolutionSummary() throws IOException {
        // given
        Scenario scenario = scenario(
                ScenarioOptions.signedInCodex()
                        .with(Condition.SOURCE_CHECKOUT)
                        .withCodexOutcome(completed(new CodexInvestigationFinding(
                                Classification.SYMPHONY_BUG,
                                "The workflow writer did not create the missing parent directory.",
                                true,
                                "Create the parent directory before writing the workflow.",
                                List.of("src/main/java/ch/fmartin/symphony/trello/setup/WorkflowConfigEditor.java"),
                                List.of(new ValidationRun("./mvnw -q -Dtest=WorkflowConfigEditorTest test", true)),
                                "Rerun setup-local."))),
                "y",
                "y",
                "y");

        // when
        Optional<Path> report = scenario.failUnexpectedly();

        // then
        assertThat(scenario.codex().requests()).singleElement().satisfies(request -> assertThat(
                        request.additionalWritableRoots())
                .containsExactly(scenario.appHome()));
        assertThat(scenario.terminal().stderr())
                .contains(
                        "and the Symphony source checkout",
                        "Fix: Create the parent directory before writing the workflow.",
                        "Changed files: src/main/java/ch/fmartin/symphony/trello/setup/WorkflowConfigEditor.java",
                        "Validation: passed `./mvnw -q -Dtest=WorkflowConfigEditorTest test`");
        assertThat(scenario.terminal().stdout())
                .contains(FIX_ISSUE_QUESTION, POSTED_ISSUE_URL)
                .doesNotContain(REPORT_ISSUE_QUESTION);
        Path draft = issueDraft(report);
        assertThat(scenario.commands().commands())
                .contains(List.of(
                        "gh",
                        "issue",
                        "create",
                        "--repo",
                        SetupFailureFollowUp.ISSUE_REPOSITORY,
                        "--title",
                        SetupFailureFollowUp.ISSUE_TITLE,
                        "--body-file",
                        draft.toString()));
        assertThat(draft)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "# Symphony for Trello Setup Failure",
                        "## Local Codex Investigation",
                        "- **classification:** symphony_bug",
                        "- **fix_applied:** true",
                        "- **fix_summary:** Create the parent directory before writing the workflow.",
                        "- **changed_files:** `src/main/java/ch/fmartin/symphony/trello/setup/WorkflowConfigEditor.java`",
                        "- **validation:** passed `./mvnw -q -Dtest=WorkflowConfigEditorTest test`");
    }

    @Test
    void localConfigurationFindingGivesTheNextStepInsteadOfAnIssuePrompt() throws IOException {
        // given
        Scenario scenario = scenario(
                ScenarioOptions.signedInCodex()
                        .withCodexOutcome(completed(new CodexInvestigationFinding(
                                Classification.LOCAL_CONFIGURATION,
                                "The workflow sets server.port to a port another program uses.",
                                false,
                                "",
                                List.of(),
                                List.of(),
                                "Run setup-local repair-port for this board."))),
                "y",
                "y");

        // when
        scenario.failUnexpectedly();

        // then
        assertThat(scenario.terminal().stderr())
                .contains(
                        "Next step: Run setup-local repair-port for this board.",
                        "Codex found a local configuration problem, so no GitHub issue is suggested.");
        assertThat(scenario.terminal().stdout()).doesNotContain(REPORT_ISSUE_QUESTION, FIX_ISSUE_QUESTION);
    }

    @Test
    void fixedLocalConfigurationAsksForARerunInsteadOfAnIssue() throws IOException {
        // given
        Scenario scenario = scenario(
                ScenarioOptions.signedInCodex()
                        .withCodexOutcome(completed(new CodexInvestigationFinding(
                                Classification.LOCAL_CONFIGURATION,
                                "The workflow sets server.port to a port another program uses.",
                                true,
                                "Moved the workflow to the next free port.",
                                List.of("WORKFLOW.md"),
                                List.of(new ValidationRun("symphony-trello setup-local check", true)),
                                "Rerun setup-local."))),
                "y",
                "y");

        // when
        scenario.failUnexpectedly();

        // then
        assertThat(scenario.terminal().stderr())
                .contains(
                        "Changed files: WORKFLOW.md",
                        "no GitHub issue is suggested. Codex fixed it locally. Rerun the command.");
        assertThat(scenario.terminal().stdout()).doesNotContain(REPORT_ISSUE_QUESTION, FIX_ISSUE_QUESTION);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "Codex did not finish within 15 minutes",
                "Codex exited with code 1",
                "Codex returned an answer in an unexpected format"
            })
    void failedInvestigationStillOffersTheExistingIssuePrompt(String reason) throws IOException {
        // given
        Scenario scenario =
                scenario(ScenarioOptions.signedInCodex().withCodexOutcome(new Outcome.Failed(reason)), "y", "n");

        // when
        scenario.failUnexpectedly();

        // then
        assertThat(scenario.terminal().stderr()).contains("Codex investigation did not complete: " + reason + ".");
        assertThat(scenario.terminal().stdout()).containsSubsequence(CODEX_QUESTION, REPORT_ISSUE_QUESTION);
    }

    @MethodSource("unverifiedFixes")
    @ParameterizedTest(name = "{0}")
    void unverifiedFixKeepsTheExistingIssuePromptAndAddsTheFindingToTheDraft(
            String scenarioName, List<String> changedFiles, ValidationRun validation, String draftLine)
            throws IOException {
        // given
        Scenario scenario = scenario(
                ScenarioOptions.signedInCodex()
                        .withCodexOutcome(completed(new CodexInvestigationFinding(
                                Classification.SYMPHONY_BUG,
                                "The workflow writer ignores a read-only parent directory.",
                                true,
                                "Check the parent directory before writing.",
                                changedFiles,
                                List.of(validation),
                                ""))),
                "y",
                "y",
                "n");

        // when
        Optional<Path> report = scenario.failUnexpectedly();

        // then
        assertThat(scenario.terminal().stdout()).contains(REPORT_ISSUE_QUESTION).doesNotContain(FIX_ISSUE_QUESTION);
        assertThat(issueDraft(report)).content(StandardCharsets.UTF_8).contains(draftLine);
        assertThat(scenario.commands().commands())
                .noneSatisfy(command -> assertThat(command).startsWith("gh", "issue", "create"));
    }

    static Stream<Arguments> unverifiedFixes() {
        return Stream.of(
                Arguments.of(
                        "validation failed",
                        List.of("WORKFLOW.md"),
                        new ValidationRun("symphony-trello setup-local check", false),
                        "- **validation:** failed `symphony-trello setup-local check`"),
                Arguments.of(
                        "no changed files reported",
                        List.of(),
                        new ValidationRun("symphony-trello setup-local check", true),
                        "- **changed_files:** none"));
    }

    @MethodSource("codexNotOffered")
    @ParameterizedTest(name = "{0}")
    void codexIsNotOfferedOutsideAnInteractiveFirstFailure(String scenarioName, ScenarioOptions options)
            throws IOException {
        // given
        Scenario scenario = scenario(options, "y", "y", "y");

        // when
        scenario.failUnexpectedly();

        // then
        assertThat(scenario.codex().requests()).isEmpty();
        assertThat(scenario.terminal().stdout()).doesNotContain(CODEX_QUESTION);
    }

    static Stream<Arguments> codexNotOffered() {
        return Stream.of(
                Arguments.of(
                        "--non-interactive", ScenarioOptions.signedInCodex().with(Condition.NON_INTERACTIVE)),
                Arguments.of(
                        "no console attached", ScenarioOptions.signedInCodex().with(Condition.NO_CONSOLE)),
                Arguments.of(
                        "command started by a Codex investigation",
                        ScenarioOptions.signedInCodex().with(Condition.STARTED_BY_CODEX_INVESTIGATION)));
    }

    @Test
    void codexPromptAndPublicIssueTextStaySanitized() throws IOException {
        // given
        Scenario scenario = scenario(ScenarioOptions.signedInCodex(), "y", "y", "y");
        Path privateWorkflow = scenario.configDir().resolve("WORKFLOW.private.md");
        scenario.codex()
                .answer(completed(new CodexInvestigationFinding(
                        Classification.EXTERNAL_TOOL_OR_SERVICE,
                        "Trello rejected TRELLO_API_TOKEN=" + TRELLO_API_TOKEN + " with key " + TRELLO_API_KEY + " for "
                                + BOARD_NAME + " at " + TRELLO_BOARD_URL,
                        false,
                        "",
                        List.of(privateWorkflow.toString()),
                        List.of(),
                        "Check " + privateWorkflow)));

        // when
        Optional<Path> report = scenario.fail(new TrelloBoardSetupException(
                UNEXPECTED_CODE, "Workflow write failed for " + BOARD_NAME + " in " + privateWorkflow));

        // then
        String[] privateValues = {TRELLO_API_KEY, TRELLO_API_TOKEN, BOARD_NAME, TRELLO_BOARD_URL, tempDir.toString()};
        assertThat(scenario.codex().requests()).singleElement().satisfies(request -> assertThat(request.prompt())
                .contains("- error_code: " + UNEXPECTED_CODE)
                .doesNotContain(privateValues));
        assertThat(issueDraft(report))
                .content(StandardCharsets.UTF_8)
                .contains("## Local Codex Investigation", "<redacted>", "<path:")
                .doesNotContain(privateValues);
        assertThat(scenario.terminal().stdout()).contains(POSTED_ISSUE_URL).doesNotContain(privateValues);
        assertThat(scenario.terminal().stderr())
                .contains("A GitHub issue still helps")
                .doesNotContain(TRELLO_API_KEY, TRELLO_API_TOKEN, BOARD_NAME, TRELLO_BOARD_URL);
    }

    private static Path issueDraft(Optional<Path> report) {
        assertThat(report)
                .as("unexpected failures write a troubleshooting report")
                .isPresent();
        Path reportPath = report.orElseThrow();
        String reportName = reportPath.getFileName().toString();
        return reportPath.resolveSibling(
                reportName.substring(0, reportName.length() - SetupFailureFollowUp.REPORT_SUFFIX.length())
                        + SetupFailureFollowUp.ISSUE_DRAFT_SUFFIX);
    }

    private static Outcome completed(CodexInvestigationFinding finding) {
        return new Outcome.Completed(finding);
    }

    private Scenario scenario(ScenarioOptions options, String... answers) throws IOException {
        Path root = Files.createTempDirectory(tempDir, "follow-up-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        Path appHome = Files.createDirectories(root.resolve("app"));
        if (options.has(Condition.SOURCE_CHECKOUT)) {
            Files.createDirectories(appHome.resolve(".git"));
        }
        Path tools = Files.createDirectories(root.resolve("tools"));
        Path codexExecutable = tools.resolve("codex");
        if (!options.has(Condition.NO_CODEX_ON_PATH)) {
            Files.writeString(codexExecutable, "");
            assertThat(codexExecutable.toFile().setExecutable(true))
                    .as("the fake codex executable must be executable so PATH lookup finds it")
                    .isTrue();
        }
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", tools.toString());
        environment.put("SYMPHONY_TRELLO_APP_HOME", appHome.toString());
        if (options.has(Condition.STARTED_BY_CODEX_INVESTIGATION)) {
            environment.put(CodexInvestigationRunner.INVESTIGATION_MARKER_ENV, "1");
        }
        FakeCommandRunner commands = new FakeCommandRunner()
                .returns(
                        options.has(Condition.CODEX_SIGNED_OUT) ? 1 : 0,
                        "",
                        codexExecutable.toString(),
                        "login",
                        "status")
                .returns(0, "github.com\n", "gh", "auth", "status")
                .returnsForPrefix(0, POSTED_ISSUE_URL + "\n", "gh", "issue", "create");
        var codex = new RecordingCodex(options.codexOutcome());
        var followUp = new SetupFailureFollowUp(
                commands,
                codex,
                () -> !options.has(Condition.NO_CONSOLE),
                environment,
                SetupFailureFollowUp.CODEX_INVESTIGATION_TIMEOUT);
        var reporter = new SetupDiagnosticReporter(
                environment, commands, Files::list, Clock.fixed(NOW, ZoneOffset.UTC), "Linux", followUp);
        return new Scenario(
                configDir,
                appHome,
                commands,
                codex,
                reporter,
                new RecordingTerminal(answers),
                options.has(Condition.NON_INTERACTIVE));
    }

    private record Scenario(
            Path configDir,
            Path appHome,
            FakeCommandRunner commands,
            RecordingCodex codex,
            SetupDiagnosticReporter reporter,
            RecordingTerminal terminal,
            boolean nonInteractive) {

        Optional<Path> failUnexpectedly() {
            return fail(new TrelloBoardSetupException(UNEXPECTED_CODE, "Workflow write failed"));
        }

        Optional<Path> fail(TrelloBoardSetupException exception) {
            return reporter.reportFailure(exception, request(), terminal);
        }

        private LocalSetupRequest request() {
            return new LocalSetupRequest(
                    LocalSetupRequest.Action.SETUP,
                    false,
                    nonInteractive,
                    false,
                    false,
                    Optional.empty(),
                    Optional.of(TRELLO_API_KEY),
                    Optional.of(TRELLO_API_TOKEN),
                    Optional.of(BOARD_NAME),
                    Optional.empty(),
                    Optional.empty(),
                    List.of("Ready"),
                    List.of("Done"),
                    "In Progress",
                    false,
                    "Blocked",
                    Optional.of(configDir.resolve("WORKFLOW.private.md")),
                    Optional.of(configDir.resolveSibling("workspaces")),
                    Optional.of(configDir),
                    Optional.empty(),
                    Optional.empty(),
                    1,
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(configDir.resolve(".env")),
                    List.of(),
                    false,
                    false,
                    true,
                    URI.create("https://api.trello.com/1"));
        }
    }

    /// Ways a scenario departs from the default: Codex on PATH and signed in, a console attached,
    /// an interactive command, a release install, and a command the user started directly.
    private enum Condition {
        NO_CODEX_ON_PATH,
        CODEX_SIGNED_OUT,
        NO_CONSOLE,
        NON_INTERACTIVE,
        SOURCE_CHECKOUT,
        STARTED_BY_CODEX_INVESTIGATION
    }

    private record ScenarioOptions(Set<Condition> conditions, Outcome codexOutcome) {
        ScenarioOptions {
            conditions = Set.copyOf(conditions);
        }

        static ScenarioOptions signedInCodex() {
            return new ScenarioOptions(Set.of(), new Outcome.Failed("no Codex answer configured"));
        }

        ScenarioOptions with(Condition condition) {
            Set<Condition> combined = EnumSet.of(condition);
            combined.addAll(conditions);
            return new ScenarioOptions(combined, codexOutcome);
        }

        ScenarioOptions withCodexOutcome(Outcome outcome) {
            return new ScenarioOptions(conditions, outcome);
        }

        boolean has(Condition condition) {
            return conditions.contains(condition);
        }
    }

    /// Records each investigation request and answers with the configured outcome, so tests can
    /// check exactly what Symphony would send to Codex.
    private static final class RecordingCodex implements CodexInvestigationRunner {
        private final List<Request> requests = new ArrayList<>();
        private Outcome outcome;

        RecordingCodex(Outcome outcome) {
            this.outcome = outcome;
        }

        void answer(Outcome configuredOutcome) {
            this.outcome = configuredOutcome;
        }

        List<Request> requests() {
            return requests;
        }

        @Override
        public Outcome investigate(Request request) {
            requests.add(request);
            return outcome;
        }
    }
}
