package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.CodexInvestigationFinding.Classification;
import ch.fmartin.symphony.trello.setup.CodexInvestigationFinding.ValidationRun;
import ch.fmartin.symphony.trello.setup.CodexInvestigationRunner.CodexCommand;
import ch.fmartin.symphony.trello.setup.CodexInvestigationRunner.Outcome;
import ch.fmartin.symphony.trello.setup.SetupFailureFollowUp.Investigation.Answered;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/// Interactive follow-up after an unexpected setup failure wrote a sanitized troubleshooting
/// report: an opt-in local Codex investigation, then the optional GitHub issue.
///
/// The prompt sent to Codex and every GitHub issue draft contain only sanitized text: the report,
/// and Codex answers passed through the report's sanitizer. Codex itself runs locally and sees local
/// paths through its working directory and writable roots, like any local Codex session. ADR 0104
/// (`docs/adr/0104-codex-investigation-before-setup-failure-issues.md`) records the decision.
final class SetupFailureFollowUp {
    static final Duration CODEX_INVESTIGATION_TIMEOUT = Duration.ofMinutes(15);
    static final String ISSUE_REPOSITORY = "martin-francois/symphony-trello";
    static final String ISSUE_TITLE = "Local setup failed";
    private static final String TOKEN_PLACEHOLDER = "<token>";
    static final int ANSWER_TEXT_LIMIT = 2_000;
    private static final int ANSWER_LIST_LIMIT = 50;
    static final String ISSUE_DRAFT_SUFFIX = "-github-issue.md";
    static final String REPORT_SUFFIX = ".md";
    private static final Investigation NO_ANSWER = new Investigation.NoAnswer();

    private final CommandRunner commands;
    private final CodexInvestigationRunner codexRunner;
    private final BooleanSupplier consoleAttached;
    private final Map<String, String> environment;
    private final Duration codexTimeout;

    SetupFailureFollowUp(
            CommandRunner commands,
            CodexInvestigationRunner codexRunner,
            BooleanSupplier consoleAttached,
            Map<String, String> environment,
            Duration codexTimeout) {
        this.commands = commands;
        this.codexRunner = codexRunner;
        this.consoleAttached = consoleAttached;
        this.environment = Map.copyOf(environment);
        this.codexTimeout = codexTimeout;
    }

    static SetupFailureFollowUp forConsole(CommandRunner commands, Map<String, String> environment) {
        return new SetupFailureFollowUp(
                commands,
                new ProcessCodexInvestigationRunner(),
                () -> SystemConsole.current() != null,
                environment,
                CODEX_INVESTIGATION_TIMEOUT);
    }

    /// Offers the follow-up steps. Callers invoke this only for interactive commands; the console
    /// check additionally keeps piped or scripted runs from waiting on prompts.
    void offer(FailedSetup failure, Terminal terminal) {
        if (!consoleAttached.getAsBoolean()) {
            return;
        }
        try {
            Investigation investigation = investigateWithCodex(failure, terminal);
            if (investigation instanceof Answered answered && isLocalConfiguration(answered.finding())) {
                terminal.err().println(localConfigurationClosing(answered.finding()));
            }
            offerGithubIssue(failure, investigation, terminal);
        } catch (IOException e) {
            terminal.err()
                    .println(failure.sanitizer()
                            .apply(SetupFailureCauses.withCause("Setup failure follow-up failed", e)));
        }
    }

    private static boolean isLocalConfiguration(CodexInvestigationFinding finding) {
        return finding.classification() == Classification.LOCAL_CONFIGURATION;
    }

    /// Gives the user an action after a local configuration finding, even when Codex left
    /// `next_step` blank.
    private static String localConfigurationClosing(CodexInvestigationFinding finding) {
        String action;
        if (finding.verifiedFix()) {
            action = "Codex fixed it locally. Rerun the command.";
        } else if (finding.nextStep().isBlank()) {
            action = "Codex did not name a next step. Fix the cause described above, then rerun the command.";
        } else {
            action = "Follow the next step above, then rerun the command.";
        }
        return "Codex found a local configuration problem. " + action;
    }

    private Investigation investigateWithCodex(FailedSetup failure, Terminal terminal) throws IOException {
        if (environment.containsKey(CodexInvestigationRunner.INVESTIGATION_MARKER_ENV)) {
            return NO_ANSWER;
        }
        return switch (failure.codex()) {
            case CodexCli.OnPath onPath
            when codexAuthenticated(onPath.command()) -> offerInvestigation(onPath.command(), failure, terminal);
            case CodexCli _ -> NO_ANSWER;
        };
    }

    private Investigation offerInvestigation(CodexCommand codex, FailedSetup failure, Terminal terminal)
            throws IOException {
        List<Path> additionalWritableRoots = sourceCheckout(failure.paths())
                .filter(root -> !root.equals(failure.paths().configDir()))
                .map(List::of)
                .orElseGet(List::of);
        terminal.err().println();
        terminal.err()
                .println("Codex CLI is installed and signed in. Codex can investigate this failure on this machine"
                        + " and may fix it before you open a GitHub issue.");
        terminal.err()
                .println("Codex receives the Symphony for Trello version, the command name and how it was started,"
                        + " the error code, and the sanitized troubleshooting report with its location."
                        + " It runs locally, can read your files, and sees local paths while it works.");
        terminal.err()
                .println("Apart from temporary files, it can change files only in the Symphony config directory"
                        + (additionalWritableRoots.isEmpty() ? "" : " and the Symphony source checkout")
                        + ". It does not commit, push, or post anything, and only sanitized text goes into a"
                        + " GitHub issue draft.");
        String answer = terminal.readLine("Ask Codex to investigate this failure? [y/N] ");
        if (!confirmed(answer)) {
            return NO_ANSWER;
        }
        Path configDir = failure.paths().configDir();
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            terminal.err()
                    .println(failure.sanitizer()
                            .apply(SetupFailureCauses.withCause(
                                    "cannot prepare Codex configuration directory: create or repair it before retrying",
                                    e)));
            return NO_ANSWER;
        }
        terminal.err()
                .println("Codex is investigating. This can take up to "
                        + CodexInvestigationRunner.describe(codexTimeout) + ". Press Ctrl+C to stop.");
        Outcome outcome = codexRunner.investigate(new CodexInvestigationRunner.Request(
                codex, prompt(failure), configDir, additionalWritableRoots, codexTimeout));
        return switch (outcome) {
            case Outcome.Completed completed -> {
                CodexInvestigationFinding finding = sanitized(completed.finding(), failure.sanitizer());
                printFinding(finding, terminal.err());
                yield new Answered(finding);
            }
            case Outcome.Failed failed -> {
                terminal.err()
                        .println("Codex investigation did not complete: "
                                + failure.sanitizer().apply(failed.reason()) + ".");
                yield NO_ANSWER;
            }
        };
    }

    private boolean codexAuthenticated(CodexCommand codex) {
        return commands.run(codex.command("login", "status")).success();
    }

    /// Only a Git checkout from a source install can take a code fix; a release archive cannot.
    private static Optional<Path> sourceCheckout(LocalWorkerPaths paths) {
        Path appHome = paths.appHome();
        return Files.exists(appHome.resolve(".git")) ? Optional.of(appHome) : Optional.empty();
    }

    private String prompt(FailedSetup failure) throws IOException {
        UnaryOperator<String> sanitizer = failure.sanitizer();
        String report = Files.readString(failure.reportPath());
        String fence = SetupReportMarkdown.markdownFence(report);
        return """
                You are investigating an unexpected Symphony for Trello setup failure on this machine. The user \
                asked for this investigation after the failure.

                Failure:
                - symphony_trello_version: %s
                - command: %s
                - started_by: %s
                - error_code: %s
                - troubleshooting_report: `%s`

                Tasks:
                1. Find the cause. Classify it as one of:
                %s
                2. Change files only when you can make a safe fix that stays scoped to this failure. Never \
                change or print credential values, .env secrets, Codex auth or session files, or GitHub \
                credentials. Do not commit, push, open pull requests, or post issues.
                3. After any change, run the closest local validation, such as `%s setup-local \
                check`, the failed command with --dry-run when it supports it, or the focused Maven test in \
                a source checkout. Report every validation command and whether it passed.
                4. When the user has to act, give one direct next step.

                Inspect local files directly when you need more detail. Your working directory is the \
                Symphony config directory. The troubleshooting_report path starts with the token of the Symphony \
                state directory. An additional writable directory, when present, is the Symphony for \
                Trello source checkout of a source install; `git log -1` there shows the installed commit.

                The troubleshooting report below replaces private values with tokens: `<path:...>` for local \
                paths, `<id:...>`, `board_hash`, and `key_hash` for Trello identifiers, `<value:...>` for \
                other private values, and `<redacted>` for secrets. To map one token to its local value, run \
                `%s`. Use mapped values only for your local work.

                Your final answer may be shown in a public GitHub issue. It must not contain credentials, \
                tokens, Codex auth or session data, Trello URLs, board names, board or card ids, account \
                names, or absolute paths. Name files by their role or by a path relative to the Symphony \
                config directory or source checkout. Answer with JSON that matches the provided schema.

                Sanitized troubleshooting report:

                %s
                %s
                %s
                """.formatted(
                        new TrelloBoardSetupMain.ProjectVersion().getVersion()[0],
                        sanitizer.apply(failure.commandName()),
                        startedBy(),
                        sanitizer.apply(failure.errorCode()),
                        reportLocation(failure),
                        classificationDescriptions(),
                        LocalSetup.DEFAULT_COMMAND,
                        PrivateContextTokens.lookupCommand(TOKEN_PLACEHOLDER),
                        fence + "markdown",
                        report.stripTrailing(),
                        fence);
    }

    private static String classificationDescriptions() {
        return Arrays.stream(Classification.values())
                .map(classification -> "   - " + classification.jsonValue() + ": " + classification.description())
                .collect(Collectors.joining("\n"));
    }

    /// Names the report by the state directory token plus its fixed relative path, so Codex can open
    /// it after one token lookup while the prompt keeps no raw local path.
    private static String reportLocation(FailedSetup failure) {
        Path stateHome = failure.paths().stateHome();
        Path report = failure.reportPath();
        if (!report.startsWith(stateHome)) {
            return failure.sanitizer().apply(report.toString());
        }
        return failure.sanitizer().apply(stateHome.toString())
                + stateHome.getFileSystem().getSeparator()
                + stateHome.relativize(report);
    }

    private String startedBy() {
        if (environment.containsKey(LocalSetup.INSTALLER_COMPLETION_ENV)) {
            return "installer onboarding";
        }
        if (environment.containsKey(LocalSetup.COMMAND_ENV)) {
            return "installed symphony-trello command";
        }
        return "direct Java command";
    }

    /// Applies the report sanitizer and the text length limit to every text Codex returned, once, so
    /// the console output and the issue draft can only show sanitized text. List limits apply when
    /// rendering, so [CodexInvestigationFinding#verifiedFix()] still sees every validation run.
    private static CodexInvestigationFinding sanitized(
            CodexInvestigationFinding finding, UnaryOperator<String> sanitizer) {
        return new CodexInvestigationFinding(
                finding.classification(),
                sanitizedText(finding.diagnosis(), sanitizer),
                finding.fixApplied(),
                sanitizedText(finding.fixSummary(), sanitizer),
                finding.changedFiles().stream()
                        .map(value -> sanitizedText(value, sanitizer))
                        .toList(),
                finding.validation().stream()
                        .map(run -> new ValidationRun(sanitizedText(run.command(), sanitizer), run.passed()))
                        .toList(),
                sanitizedText(finding.nextStep(), sanitizer));
    }

    private static void printFinding(CodexInvestigationFinding finding, PrintStream err) {
        err.println();
        err.println("Codex investigation");
        err.println("  Finding: " + finding.classification().label());
        printField(err, "Diagnosis", finding.diagnosis());
        err.println("  Changed files: " + changedFiles(finding, UnaryOperator.identity()));
        if (finding.fixApplied()) {
            printField(err, "Fix", finding.fixSummary());
        }
        err.println("  Validation: " + validationSummary(finding));
        printField(err, "Next step", finding.nextStep());
        if (finding.classification() == Classification.EXTERNAL_TOOL_OR_SERVICE) {
            err.println("  The failure comes from an external tool or service. A GitHub issue still helps when"
                    + " Symphony for Trello should detect or explain this failure better.");
        }
    }

    private static void printField(PrintStream err, String label, String value) {
        if (!value.isBlank()) {
            err.println("  " + label + ": " + value);
        }
    }

    private static String changedFiles(CodexInvestigationFinding finding, UnaryOperator<String> format) {
        List<String> files = finding.changedFiles().stream()
                .limit(ANSWER_LIST_LIMIT)
                .filter(file -> !file.isBlank())
                .map(format)
                .toList();
        return files.isEmpty() ? "none" : String.join(", ", files);
    }

    private static String validationSummary(CodexInvestigationFinding finding) {
        if (finding.validation().isEmpty()) {
            return "none run";
        }
        return finding.validation().stream()
                .limit(ANSWER_LIST_LIMIT)
                .map(run -> (run.passed() ? "passed" : "failed") + " `" + run.command() + "`")
                .collect(Collectors.joining("; "));
    }

    private void offerGithubIssue(FailedSetup failure, Investigation investigation, Terminal terminal)
            throws IOException {
        if (!commands.run("gh", "auth", "status").success()) {
            return;
        }
        String question = switch (investigation) {
            case Answered answered
            when isLocalConfiguration(answered.finding()) -> "Post a GitHub issue anyway? [y/N] ";
            case Answered answered
            when answered.finding().verifiedSymphonyFix() ->
                "Codex changed files locally and validation passed. Open a GitHub issue that describes the"
                        + " failure and the local fix? [y/N] ";
            case Investigation _ -> "GitHub CLI is authenticated. Open a GitHub issue with this report? [y/N] ";
        };
        if (!confirmed(terminal.readLine(question))) {
            return;
        }
        Path bodyFile = switch (investigation) {
            case Answered answered -> writeIssueDraft(failure, answered.finding());
            case Investigation.NoAnswer _ -> failure.reportPath();
        };
        printIssueDraft(terminal.out(), Files.readString(bodyFile));
        if (!confirmed(terminal.readLine("Post this GitHub issue now? [y/N] "))) {
            return;
        }
        CommandResult result = commands.run(
                "gh",
                "issue",
                "create",
                "--repo",
                ISSUE_REPOSITORY,
                "--title",
                ISSUE_TITLE,
                "--body-file",
                bodyFile.toString());
        if (result.success()) {
            terminal.out().println(SetupReportMarkdown.firstLine(result.output()));
        } else {
            terminal.err()
                    .println("GitHub issue creation failed: "
                            + failure.sanitizer().apply(SetupReportMarkdown.firstLine(result.output())));
        }
    }

    private static void printIssueDraft(PrintStream out, String body) {
        out.println();
        out.println("Issue title:");
        out.println(ISSUE_TITLE);
        out.println();
        out.println("Issue body:");
        out.println(body);
    }

    /// Writes the public issue draft next to the troubleshooting report: the report itself plus the
    /// sanitized Codex answer. The local file stays available for review after the prompt.
    private static Path writeIssueDraft(FailedSetup failure, CodexInvestigationFinding finding) throws IOException {
        Path report = failure.reportPath();
        Path reportFileName = report.getFileName();
        String reportName = reportFileName == null ? "setup-failure" + REPORT_SUFFIX : reportFileName.toString();
        String draftName = (reportName.endsWith(REPORT_SUFFIX)
                        ? reportName.substring(0, reportName.length() - REPORT_SUFFIX.length())
                        : reportName)
                + ISSUE_DRAFT_SUFFIX;
        Path draft = report.resolveSibling(draftName);
        Files.writeString(draft, issueBody(Files.readString(report), finding));
        return draft;
    }

    /// Appends the already sanitized Codex finding to the sanitized report.
    private static String issueBody(String report, CodexInvestigationFinding finding) {
        var body = new StringBuilder(report.stripTrailing()).append("\n\n## Local Codex Investigation\n\n");
        body.append("Codex ran on the reporter's machine. Its changes were not committed or pushed.\n\n");
        SetupReportMarkdown.line(
                body, "classification", finding.classification().jsonValue());
        SetupReportMarkdown.line(body, "diagnosis", finding.diagnosis());
        SetupReportMarkdown.line(body, "fix_applied", finding.fixApplied());
        if (finding.fixApplied()) {
            SetupReportMarkdown.line(body, "fix_summary", finding.fixSummary());
        }
        SetupReportMarkdown.line(body, "changed_files", changedFiles(finding, file -> "`" + file + "`"));
        SetupReportMarkdown.line(body, "validation", validationSummary(finding));
        SetupReportMarkdown.line(body, "next_step", finding.nextStep());
        return body.toString();
    }

    private static String sanitizedText(String value, UnaryOperator<String> sanitizer) {
        String sanitized = sanitizer.apply(value).strip();
        if (sanitized.length() <= ANSWER_TEXT_LIMIT) {
            return sanitized;
        }
        int end = ANSWER_TEXT_LIMIT;
        // Keep the UTF-16 limit without splitting a supplementary character before UTF-8 output.
        if (Character.isHighSurrogate(sanitized.charAt(end - 1)) && Character.isLowSurrogate(sanitized.charAt(end))) {
            end--;
        }
        return sanitized.substring(0, end) + " ...";
    }

    private static boolean confirmed(String answer) {
        return answer != null && answer.strip().toLowerCase(Locale.ROOT).startsWith("y");
    }

    /// Everything the follow-up needs about one failure.
    ///
    /// @param codex whether a Codex executable is on `PATH`
    /// @param sanitizer the report's sanitizer; it applies the same redaction to Codex answers
    record FailedSetup(
            Path reportPath,
            String errorCode,
            String commandName,
            LocalWorkerPaths paths,
            CodexCli codex,
            UnaryOperator<String> sanitizer) {}

    /// Whether the diagnostics tool probe found Codex CLI on `PATH`.
    sealed interface CodexCli {
        record NotOnPath() implements CodexCli {}

        /// Codex CLI was found on `PATH`.
        ///
        /// @param command builds the process command for the resolved executable
        record OnPath(CodexCommand command) implements CodexCli {}
    }

    /// What the optional Codex step leaves for the closing message and the issue draft.
    sealed interface Investigation {
        /// Codex was not offered, was declined, or returned no usable answer.
        record NoAnswer() implements Investigation {}

        /// Codex returned a usable answer for the follow-up.
        ///
        /// @param finding the Codex answer after sanitizing
        record Answered(CodexInvestigationFinding finding) implements Investigation {}
    }
}
