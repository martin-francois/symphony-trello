package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE;
import static ch.fmartin.symphony.trello.setup.TrelloBoardSetup.RECOMMENDED_BLOCKED_STATE;
import static ch.fmartin.symphony.trello.setup.TrelloBoardSetup.RECOMMENDED_DONE_STATE;
import static ch.fmartin.symphony.trello.setup.TrelloBoardSetup.RECOMMENDED_INBOX_LIST;
import static ch.fmartin.symphony.trello.setup.TrelloBoardSetup.RECOMMENDED_IN_PROGRESS_STATE;
import static ch.fmartin.symphony.trello.setup.TrelloBoardSetup.RECOMMENDED_MERGING_STATE;
import static ch.fmartin.symphony.trello.setup.TrelloBoardSetup.RECOMMENDED_REVIEW_STATE;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.WorkspaceInfo;
import ch.fmartin.symphony.trello.setup.TutorialInput.Answer;
import ch.fmartin.symphony.trello.setup.TutorialTrello.Board;
import ch.fmartin.symphony.trello.setup.TutorialTrello.CardSnapshot;
import ch.fmartin.symphony.trello.setup.TutorialTrello.Comment;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Walks a user through the Symphony for Trello card flow on a temporary Trello board.
///
/// The tutorial creates its own board, asks the user to move one sample card, verifies each move
/// through the Trello API, and plays Symphony's part itself by moving the card and writing a demo
/// workpad comment. It never connects the board to a worker, so no Codex work runs. It archives
/// the board at the end unless the user keeps it. See ADR 0110.
final class GuidedTutorial {
    static final String OFFER_PROMPT = "Want to try a guided walkthrough on a temporary Trello board? [y/N] ";
    static final String COMMAND = "tutorial";
    static final String BOARD_NAME = "Symphony for Trello tutorial (temporary)";
    static final String CARD_NAME = "Tutorial: add a hello message to the README";
    static final String SAMPLE_CHANGE_REQUEST = "Please also add a short goodbye message";
    static final String CONCURRENCY_NOTE =
            "By default, Symphony handles one card per board at a time; later you can raise"
                    + " `agent.max_concurrent_agents` in `WORKFLOW.md` when you are comfortable running more in"
                    + " parallel.";
    static final String MERGING_EXAMPLE = "After you reviewed the PR and it looks good, move the Trello card to `"
            + RECOMMENDED_MERGING_STATE
            + "`. Symphony will do the final checks, make any safe final fixups, resolve addressed review threads"
            + " where possible, merge the PR if checks and repo policy allow, and move the card to `"
            + RECOMMENDED_DONE_STATE + "`.";
    static final String CLEANUP_PROMPT = "Archive the temporary tutorial board now? [Y/n] ";
    static final String CHECK_PROMPT = "Press Enter to check now, s to let the tutorial do it, or q to stop: ";
    static final String CONTINUE_PROMPT = "Press Enter to continue, or q to stop: ";

    private static final String CARD_DESCRIPTION =
            """
            This card belongs to the Symphony for Trello guided tutorial. The board is not connected to a \
            Symphony worker, so no Codex work runs. Follow the steps in your terminal.""";
    private static final String DEMO_NOTICE =
            "Tutorial demo: the guided tutorial wrote this comment to show what Symphony and Codex write"
                    + " during a real run. No Codex work ran.";
    private static final int TOTAL_STEPS = 3;
    private static final String PICKUP_PLAN =
            """
            Plan:
            - Read the card and find the README
            - Add a short hello message
            - Check the Markdown formatting""";
    private static final String HANDOFF_SUMMARY =
            """
            Summary: Added a short hello message to the README.
            Validation: Checked the Markdown formatting.""";
    private static final String DEMO_PULL_REQUEST_LINE =
            "Pull request: demo only, no pull request was created. In a real run, the pull request URL is on this line.";
    private static final String MERGE_NOTES =
            """
            Final checks: passed. Resolved addressed review threads. Merged the pull request because checks and \
            repository policy allowed it.
            Demo only: no pull request exists, so nothing was merged.""";
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    /// Keeps the quoted rework comment in the demo workpad short and on one line.
    private static final int QUOTED_COMMENT_LIMIT = 200;

    private static final Map<String, String> LIST_PURPOSES = Map.of(
            RECOMMENDED_INBOX_LIST,
            "Ideas and rough tasks. Symphony leaves them alone.",
            RECOMMENDED_ACTIVE_STATE,
            "Cards that are ready for Codex. Symphony picks up work from here.",
            RECOMMENDED_IN_PROGRESS_STATE,
            "Symphony moves a card here while Codex works on it.",
            RECOMMENDED_BLOCKED_STATE,
            "Codex moves a card here when it cannot continue without your help.",
            RECOMMENDED_REVIEW_STATE,
            "Codex moves finished work here so a person can review it.",
            RECOMMENDED_MERGING_STATE,
            "Move a reviewed card here so Symphony does final checks and merges the PR.",
            RECOMMENDED_DONE_STATE,
            "Finished work. Symphony leaves these cards alone.");

    private final TutorialTrello trello;
    private final Terminal terminal;
    private final ShutdownHooks shutdownHooks;
    private final Pacing pacing;

    GuidedTutorial(TutorialTrello trello, Terminal terminal, ShutdownHooks shutdownHooks, Pacing pacing) {
        this.trello = Objects.requireNonNull(trello, "trello");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.shutdownHooks = Objects.requireNonNull(shutdownHooks, "shutdownHooks");
        this.pacing = Objects.requireNonNull(pacing, "pacing");
    }

    /// Creates the tutorial that a user starts from the CLI: real Trello, the JVM's Ctrl+C hooks, and
    /// the default check pacing.
    static GuidedTutorial forCli(URI endpoint, TrelloCredentials credentials, Terminal terminal) {
        return new GuidedTutorial(
                new TutorialTrello(endpoint, credentials), terminal, ShutdownHooks.runtime(), Pacing.DEFAULT);
    }

    /// Whether a board in the connected-board manifest uses GitHub integration, which selects the
    /// GitHub path when the user passed neither `--github` nor `--no-github`.
    static boolean connectedBoardUsesGithub(Path manifestPath) throws IOException {
        return new ConnectedBoardRepository(manifestPath)
                .load().boards().stream().anyMatch(ConnectedBoard::githubEnabled);
    }

    /// The command line that starts the tutorial, for hints that tell the user how to run it again.
    static String commandLine(String cliCommand) {
        return cliCommand + " " + COMMAND;
    }

    /// Runs the whole walkthrough. Trello failures still settle the temporary board before they are
    /// rethrown.
    Outcome run(Options options) throws IOException {
        PrintStream out = terminal.out(); // NOPMD - Terminal owns the stream.
        printIntro(out);
        String workspaceId = chooseWorkspace(options);
        var cleanup = new TutorialBoardCleanup(trello::archiveBoard);
        var session = new Session(options, cleanup);
        Runnable unregisterHook = shutdownHooks.register(() -> settleWithoutAsking(session, "Tutorial interrupted."));
        try (var input = new TutorialInput(terminal)) {
            createBoard(session, workspaceId);
            Outcome outcome = walkThrough(session, input);
            settleAtEnd(session, input, outcome);
            return outcome;
        } catch (IOException | RuntimeException e) {
            settleWithoutAsking(session, "The tutorial stopped because of an error.");
            throw e;
        } finally {
            unregisterHook.run();
        }
    }

    private void printIntro(PrintStream out) {
        out.println();
        out.println("Guided tutorial");
        out.println("This walkthrough creates a temporary Trello board, asks you to move one sample card through the");
        out.println("workflow, and shows how Symphony reacts. It does not change your connected boards, your");
        out.println("repositories, or your workflow files. The tutorial plays Symphony's part itself, so no Codex");
        out.println("work runs and no pull request is created.");
    }

    private String chooseWorkspace(Options options) throws IOException {
        if (options.workspaceId().isPresent()) {
            return options.workspaceId().get();
        }
        List<WorkspaceInfo> workspaces = trello.workspaces();
        if (workspaces.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_workspace_required",
                    "No Trello Workspace was found for this token. Create a Workspace in Trello, then start the"
                            + " tutorial again.");
        }
        if (workspaces.size() == 1) {
            return workspaces.getFirst().id();
        }
        terminal.info("");
        terminal.info("Choose the Trello Workspace for the temporary tutorial board:");
        for (int i = 0; i < workspaces.size(); i++) {
            terminal.info("  " + (i + 1) + ". "
                    + DisplayNames.quotedName(workspaces.get(i).displayName()));
        }
        int selected = PromptSupport.requiredChoice(
                terminal.readLine("Workspace: "),
                workspaces.size(),
                "setup_workspace_id_required",
                "Workspace selection is required. Pass --workspace-id to choose it without a prompt.");
        return workspaces.get(selected - 1).id();
    }

    private void createBoard(Session session, String workspaceId) {
        terminal.info("");
        terminal.info("Creating the temporary tutorial board...");
        session.creatingBoard = true;
        Board board = trello.createBoard(BOARD_NAME, workspaceId);
        session.cleanup.track(board);
        session.board = board;
        terminal.info("  OK  Board created: " + DisplayNames.quotedName(BOARD_NAME));
        for (String listName : session.listNames()) {
            session.listIds.put(listName, trello.createList(board, listName));
        }
        terminal.info("  OK  Lists: " + DisplayNames.quotedList(session.listNames()));
        session.cardId = trello.createCard(session.listId(RECOMMENDED_INBOX_LIST), CARD_NAME, CARD_DESCRIPTION);
        terminal.info(
                "  OK  Sample card in " + quoted(RECOMMENDED_INBOX_LIST) + ": " + DisplayNames.quotedName(CARD_NAME));
        terminal.info("");
        terminal.info("Open the tutorial board in your browser:");
        terminal.info("  " + board.url());
        terminal.info("");
        terminal.info("What each Trello list means:");
        int width = session.listNames().stream()
                .mapToInt(name -> quoted(name).length())
                .max()
                .orElse(0);
        for (String listName : session.listNames()) {
            terminal.info("  " + padded(quoted(listName), width) + "  " + LIST_PURPOSES.get(listName));
        }
    }

    private Outcome walkThrough(Session session, TutorialInput input) throws IOException {
        terminal.info("");
        terminal.info("Step 1 of " + TOTAL_STEPS + ": queue the card");
        terminal.info("In Trello, move the card " + DisplayNames.quotedName(CARD_NAME));
        terminal.info("from " + quoted(RECOMMENDED_INBOX_LIST) + " to " + quoted(RECOMMENDED_ACTIVE_STATE) + ".");
        if (await(session, input, new ListExpectation(session, RECOMMENDED_INBOX_LIST, RECOMMENDED_ACTIVE_STATE))
                == StepResult.QUIT) {
            return Outcome.STOPPED;
        }
        if (!simulatePickup(session, input) || !simulateHandoff(session, input)) {
            return Outcome.STOPPED;
        }

        terminal.info("");
        terminal.info("Step 2 of " + TOTAL_STEPS + ": ask for a change");
        terminal.info("Pretend the result needs one more change. Add a Trello comment to the card, for example:");
        terminal.info("  " + SAMPLE_CHANGE_REQUEST);
        terminal.info(
                "Then move the card back to " + quoted(RECOMMENDED_ACTIVE_STATE) + " so Symphony can address it.");
        if (session.options.github()) {
            terminal.info("In a real run you can also write review comments on the pull request in GitHub.");
            terminal.info("Symphony reads both.");
        }
        var rework = new ReworkExpectation(session, card(session));
        if (await(session, input, rework) == StepResult.QUIT) {
            return Outcome.STOPPED;
        }
        if (!simulateRework(session, input, rework)) {
            return Outcome.STOPPED;
        }

        terminal.info("");
        terminal.info(CONCURRENCY_NOTE);
        terminal.info("The README workflow contract has the details:");
        terminal.info("  " + ProjectDocs.WORKFLOW_CONTRACT_URL);

        boolean finished = session.options.github() ? mergingStep(session, input) : doneStep(session, input);
        if (!finished) {
            return Outcome.STOPPED;
        }
        if (!session.options.github()) {
            printWhatGitHubAdds(session.options);
        }
        terminal.info("");
        terminal.info("You finished the tutorial. Use the same flow on your connected board.");
        return Outcome.COMPLETED;
    }

    private boolean simulatePickup(Session session, TutorialInput input) throws IOException {
        terminal.info("");
        terminal.info("Symphony picks up the card");
        terminal.info("Symphony dispatches cards from " + quoted(RECOMMENDED_ACTIVE_STATE)
                + ". When it starts one, it moves the card to");
        terminal.info(quoted(RECOMMENDED_IN_PROGRESS_STATE) + " and adds one " + quoted(TrelloClient.WORKPAD_MARKER)
                + " Trello comment. The tutorial does the same now.");
        moveCard(session, RECOMMENDED_IN_PROGRESS_STATE);
        session.workpadCommentId = trello.addComment(session.cardId, workpad("In progress", PICKUP_PLAN));
        session.tutorialCommentIds.add(session.workpadCommentId);
        terminal.info("  OK  Added the Codex Workpad comment");
        terminal.info("Open the card and read the workpad comment. Symphony records progress and handoff notes");
        terminal.info("there, and it updates this one comment instead of adding new ones.");
        return pause(input);
    }

    private boolean simulateHandoff(Session session, TutorialInput input) throws IOException {
        terminal.info("");
        terminal.info("Codex hands the card over for review");
        terminal.info("When Codex finishes, it updates the workpad with a summary and moves the card to "
                + quoted(RECOMMENDED_REVIEW_STATE) + ".");
        trello.updateComment(session.workpadCommentId, workpad("Ready for human review", handoffNotes(session)));
        terminal.info("  OK  Updated the workpad with handoff notes");
        moveCard(session, RECOMMENDED_REVIEW_STATE);
        if (session.options.github()) {
            terminal.info("With GitHub integration, Codex creates or updates a pull request and links it on the card.");
            terminal.info(
                    "Look for the \"Pull request\" line in the workpad. In a real run it shows the pull request URL.");
            terminal.info("You can review and comment on either the pull request in GitHub or the Trello card.");
        } else {
            terminal.info("Review the result: read the workpad notes on the card.");
        }
        return pause(input);
    }

    private boolean simulateRework(Session session, TutorialInput input, ReworkExpectation rework) throws IOException {
        String request = rework.newestRequest(card(session)).orElse(SAMPLE_CHANGE_REQUEST);
        terminal.info("");
        terminal.info("Symphony treats this as rework");
        terminal.info("Symphony picks the card up again. The card already has a workpad, so Codex treats this as");
        terminal.info(
                "rework: it rereads the card, your new Trello comments, and the workpad before it changes anything.");
        if (session.options.github()) {
            terminal.info("With GitHub integration, it normally updates the existing pull request.");
        }
        moveCard(session, RECOMMENDED_IN_PROGRESS_STATE);
        String reworkNotes = "Rework input: your Trello comment \"" + oneLine(request) + "\"\n"
                + "Summary: Read the new Trello comment, made the requested change, and checked it again.\n"
                + nextStepNote(session);
        trello.updateComment(session.workpadCommentId, workpad("Ready for human review (rework done)", reworkNotes));
        terminal.info("  OK  Updated the workpad with your comment as rework input");
        moveCard(session, RECOMMENDED_REVIEW_STATE);
        return pause(input);
    }

    private boolean doneStep(Session session, TutorialInput input) throws IOException {
        terminal.info("");
        terminal.info("Step 3 of " + TOTAL_STEPS + ": accept the result");
        terminal.info("The result looks good now. Move the card to " + quoted(RECOMMENDED_DONE_STATE) + ".");
        if (await(session, input, new ListExpectation(session, RECOMMENDED_REVIEW_STATE, RECOMMENDED_DONE_STATE))
                == StepResult.QUIT) {
            return false;
        }
        terminal.info(quoted(RECOMMENDED_DONE_STATE) + " is a terminal list. Symphony leaves cards there alone.");
        return true;
    }

    private boolean mergingStep(Session session, TutorialInput input) throws IOException {
        terminal.info("");
        terminal.info(
                "Step 3 of " + TOTAL_STEPS + ": approve the pull request with " + quoted(RECOMMENDED_MERGING_STATE));
        terminal.info(MERGING_EXAMPLE);
        terminal.info("Move the card to " + quoted(RECOMMENDED_MERGING_STATE) + " now.");
        if (await(session, input, new ListExpectation(session, RECOMMENDED_REVIEW_STATE, RECOMMENDED_MERGING_STATE))
                == StepResult.QUIT) {
            return false;
        }
        trello.updateComment(session.workpadCommentId, workpad("Merged", MERGE_NOTES));
        terminal.info("  OK  Updated the workpad with the final checks and merge result (demo only)");
        moveCard(session, RECOMMENDED_DONE_STATE);
        terminal.info("If you merge a pull request yourself instead, move the card to " + quoted(RECOMMENDED_DONE_STATE)
                + " yourself.");
        return true;
    }

    private void printWhatGitHubAdds(Options options) {
        terminal.info("");
        terminal.info("What GitHub pull request integration adds");
        terminal.info("Without GitHub integration, Symphony still runs the Trello and Codex loop for card-based work.");
        terminal.info("Setup adds GitHub-specific lists such as " + quoted(RECOMMENDED_MERGING_STATE)
                + " only when GitHub integration is configured.");
        terminal.info("With it, Symphony can create and update pull requests, link them on Trello cards, and process");
        terminal.info("feedback from pull requests and Trello comments. " + quoted(RECOMMENDED_MERGING_STATE)
                + " then means \"do final checks and merge");
        terminal.info(
                "this PR if safe\" before Symphony moves the finished card to " + quoted(RECOMMENDED_DONE_STATE) + ".");
        terminal.info("To add GitHub integration later, run:");
        terminal.info("  " + options.cliCommand() + " setup-local configure-github");
    }

    private StepResult await(Session session, TutorialInput input, Expectation expectation) throws IOException {
        terminal.info("The tutorial checks the board every " + describe(pacing.interval()) + " for up to "
                + describe(pacing.automaticWindow()) + ".");
        input.showPromptAgain();
        int automaticChecks = 0;
        boolean explicitCheck = false;
        Observation reported = null;
        while (true) {
            Observation observation = expectation.observe(card(session));
            if (observation.satisfied()) {
                terminal.out().println();
                terminal.info("  OK  " + capitalized(observation.description()));
                return StepResult.DONE;
            }
            if (explicitCheck || (observation.changed() && !observation.equals(reported))) {
                printCorrection(session, observation, expectation);
                input.showPromptAgain();
                reported = observation;
            }
            explicitCheck = false;
            Answer answer;
            if (automaticChecks < pacing.automaticChecks()) {
                Optional<Answer> polled = input.poll(CHECK_PROMPT, pacing.interval());
                if (polled.isEmpty()) {
                    automaticChecks++;
                    if (automaticChecks == pacing.automaticChecks()) {
                        terminal.out().println();
                        terminal.info(
                                "  Stopped checking automatically after " + describe(pacing.automaticWindow()) + ".");
                        explicitCheck = true;
                    }
                    continue;
                }
                answer = polled.get();
            } else {
                answer = input.read(CHECK_PROMPT);
            }
            switch (command(answer)) {
                case QUIT -> {
                    return StepResult.QUIT;
                }
                case SKIP -> {
                    terminal.info("  OK  " + expectation.doForUser());
                    return StepResult.SKIPPED;
                }
                case CHECK -> {
                    explicitCheck = true;
                }
            }
        }
    }

    private void printCorrection(Session session, Observation observation, Expectation expectation) {
        terminal.out().println();
        terminal.info("  Not yet: " + observation.description() + ".");
        terminal.info("  Expected: " + expectation.expected() + ".");
        terminal.info("  Board:");
        terminal.info("    " + session.board.url());
    }

    private boolean pause(TutorialInput input) throws IOException {
        return command(input.read(CONTINUE_PROMPT)) != Command.QUIT;
    }

    private void settleAtEnd(Session session, TutorialInput input, Outcome outcome) throws IOException {
        if (outcome == Outcome.STOPPED) {
            terminal.info("");
            terminal.info("Stopped the tutorial.");
        }
        if (!session.options.cleanup()) {
            session.cleanup.keep();
            printKeptBoard(session);
            return;
        }
        terminal.info("");
        String answer = input.read(CLEANUP_PROMPT).line();
        boolean keep = answer != null && answer.strip().toLowerCase(Locale.ROOT).startsWith("n");
        if (keep) {
            session.cleanup.keep();
            printKeptBoard(session);
            return;
        }
        archive(session);
    }

    /// Settles the board after a failure or an interrupt, when there is no one to ask.
    private void settleWithoutAsking(Session session, String reason) {
        if (session.board == null) {
            if (session.creatingBoard) {
                // The board request was sent, but no answer arrived, so Trello may have created it.
                terminal.info("");
                terminal.info(reason);
                terminal.info("If Trello created the board " + DisplayNames.quotedName(BOARD_NAME)
                        + ", archive it in Trello.");
            }
            return;
        }
        terminal.info("");
        terminal.info(reason);
        if (session.options.cleanup()) {
            archive(session);
        } else if (session.cleanup.keep()) {
            printKeptBoard(session);
        }
    }

    private void archive(Session session) {
        try {
            if (session.cleanup.archive()) {
                terminal.info("  OK  Archived the temporary tutorial board");
            }
        } catch (RuntimeException e) {
            terminal.info("  WARN  Could not archive the temporary tutorial board. Archive it in Trello:");
            terminal.info("    " + session.board.url());
        }
    }

    private void printKeptBoard(Session session) {
        terminal.info("");
        terminal.info("The temporary tutorial board stays in Trello:");
        terminal.info("  " + session.board.url());
        terminal.info("It is not connected to Symphony, so Symphony does not manage it.");
        terminal.info("Archive it in Trello when you no longer need it.");
    }

    private CardSnapshot card(Session session) {
        try {
            return trello.card(session.cardId);
        } catch (TrelloBoardSetupException e) {
            if ("trello_resource_not_found".equals(e.code())) {
                throw new TrelloBoardSetupException(
                        "setup_tutorial_card_missing",
                        "The tutorial card is no longer in Trello. Start the tutorial again with: "
                                + commandLine(session.options.cliCommand()),
                        e);
            }
            throw e;
        }
    }

    private void moveCard(Session session, String listName) {
        trello.moveCard(session.cardId, session.listId(listName));
        terminal.info("  OK  Moved the card to " + quoted(listName));
    }

    private String handoffNotes(Session session) {
        String pullRequestLine = session.options.github() ? DEMO_PULL_REQUEST_LINE + "\n" : "";
        return HANDOFF_SUMMARY + "\n" + pullRequestLine + nextStepNote(session);
    }

    private static String nextStepNote(Session session) {
        String accept = session.options.github()
                ? "move the card to " + quoted(RECOMMENDED_MERGING_STATE) + " when the pull request is good"
                : "move the card to " + quoted(RECOMMENDED_DONE_STATE) + " when it is good";
        return "Next: Review the result. " + capitalized(accept) + ", or add a Trello comment and move the card"
                + " back to " + quoted(RECOMMENDED_ACTIVE_STATE) + " when it needs more work.";
    }

    private static String workpad(String status, String details) {
        return TrelloClient.WORKPAD_MARKER + "\n\n" + DEMO_NOTICE + "\n\nStatus: " + status + "\n" + details;
    }

    private static String oneLine(String text) {
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= QUOTED_COMMENT_LIMIT
                ? collapsed
                : collapsed.substring(0, QUOTED_COMMENT_LIMIT) + "...";
    }

    private static Command command(Answer answer) {
        String line = answer.line();
        if (line == null) {
            return Command.QUIT;
        }
        return switch (line.strip().toLowerCase(Locale.ROOT)) {
            case "q", "quit", "stop" -> Command.QUIT;
            case "s", "skip" -> Command.SKIP;
            default -> Command.CHECK;
        };
    }

    static String describe(Duration duration) {
        if (duration.compareTo(ONE_MINUTE) >= 0 && duration.toSecondsPart() == 0) {
            return plural(duration.toMinutes(), "minute");
        }
        if (duration.compareTo(ONE_SECOND) >= 0) {
            return plural(duration.toSeconds(), "second");
        }
        return plural(duration.toMillis(), "millisecond");
    }

    private static String plural(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String padded(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private static String quoted(String listName) {
        return DisplayNames.quotedName(listName);
    }

    /// Settings for one tutorial run.
    record Options(boolean github, boolean cleanup, Optional<String> workspaceId, String cliCommand) {
        Options {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(cliCommand, "cliCommand");
        }
    }

    /// How often and how long a step checks Trello by itself before it only checks on request.
    record Pacing(Duration interval, int automaticChecks) {
        /// Five-second checks stay far below Trello's rate limit while still reacting quickly.
        private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
        /// Long enough to find the board in a browser and move a card, short enough not to poll an
        /// abandoned tutorial for long.
        private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(3);
        static final Pacing DEFAULT =
                new Pacing(DEFAULT_INTERVAL, Math.toIntExact(DEFAULT_WINDOW.dividedBy(DEFAULT_INTERVAL)));

        Duration automaticWindow() {
            return interval.multipliedBy(automaticChecks);
        }
    }

    /// Registers work to run when the JVM shuts down, for example after Ctrl+C. Returns an action
    /// that removes the registration again.
    @FunctionalInterface
    interface ShutdownHooks {
        Runnable register(Runnable action);

        static ShutdownHooks runtime() {
            return action -> {
                var hook = new Thread(action, "tutorial-cleanup");
                Runtime.getRuntime().addShutdownHook(hook);
                return () -> removeUnlessShuttingDown(hook);
            };
        }

        /// Returns whether the hook was removed. During JVM shutdown the hook is already running, which
        /// is safe because the cleanup claims the board at most once.
        private static boolean removeUnlessShuttingDown(Thread hook) {
            try {
                return Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                return false;
            }
        }
    }

    enum Outcome {
        COMPLETED,
        STOPPED
    }

    private enum StepResult {
        DONE,
        SKIPPED,
        QUIT
    }

    private enum Command {
        CHECK,
        SKIP,
        QUIT
    }

    private record Observation(boolean satisfied, boolean changed, String description) {}

    private interface Expectation {
        Observation observe(CardSnapshot card);

        String expected();

        /// Performs the step for the user and returns what it did.
        String doForUser();
    }

    /// Expects the card to move from one Trello list to another.
    private final class ListExpectation implements Expectation {
        private final Session session;
        private final String from;
        private final String to;

        private ListExpectation(Session session, String from, String to) {
            this.session = session;
            this.from = from;
            this.to = to;
        }

        @Override
        public Observation observe(CardSnapshot card) {
            if (card.archived()) {
                return new Observation(false, true, "the card is archived");
            }
            Optional<String> listName = session.listName(card.listId());
            if (listName.filter(to::equals).isPresent()) {
                return new Observation(true, true, "the card is in " + quoted(to));
            }
            if (listName.filter(from::equals).isPresent()) {
                return new Observation(false, false, "the card is still in " + quoted(from));
            }
            return new Observation(false, true, session.whereIs(card));
        }

        @Override
        public String expected() {
            return "the card is in " + quoted(to);
        }

        @Override
        public String doForUser() {
            trello.moveCard(session.cardId, session.listId(to));
            return "The tutorial moved the card to " + quoted(to) + " for you";
        }
    }

    /// Expects a new Trello comment on the card and the card back in the queue list.
    private final class ReworkExpectation implements Expectation {
        private final Session session;
        private final Set<String> knownCommentIds = new HashSet<>();

        private ReworkExpectation(Session session, CardSnapshot before) {
            this.session = session;
            before.comments().forEach(comment -> knownCommentIds.add(comment.id()));
            knownCommentIds.addAll(session.tutorialCommentIds);
        }

        @Override
        public Observation observe(CardSnapshot card) {
            boolean commented = newestRequest(card).isPresent();
            if (card.archived()) {
                return new Observation(false, true, "the card is archived");
            }
            Optional<String> listName = session.listName(card.listId());
            String queue = quoted(RECOMMENDED_ACTIVE_STATE);
            String review = quoted(RECOMMENDED_REVIEW_STATE);
            if (listName.filter(RECOMMENDED_ACTIVE_STATE::equals).isPresent()) {
                return commented
                        ? new Observation(true, true, "the card is back in " + queue + " with your new Trello comment")
                        : new Observation(
                                false, true, "the card is in " + queue + ", but it has no new Trello comment");
            }
            if (listName.filter(RECOMMENDED_REVIEW_STATE::equals).isPresent()) {
                return commented
                        ? new Observation(false, true, "you added a Trello comment, but the card is still in " + review)
                        : new Observation(
                                false, false, "the card is still in " + review + " and has no new Trello comment");
            }
            return new Observation(false, true, session.whereIs(card));
        }

        /// Trello returns comments newest first, so the first new one is the latest request.
        Optional<String> newestRequest(CardSnapshot card) {
            return card.comments().stream()
                    .filter(comment -> !knownCommentIds.contains(comment.id()))
                    .map(Comment::text)
                    .filter(text -> !text.isBlank())
                    .findFirst();
        }

        @Override
        public String expected() {
            return "the card is in " + quoted(RECOMMENDED_ACTIVE_STATE)
                    + " and has a new Trello comment that asks for a change";
        }

        @Override
        public String doForUser() {
            trello.addComment(session.cardId, SAMPLE_CHANGE_REQUEST);
            trello.moveCard(session.cardId, session.listId(RECOMMENDED_ACTIVE_STATE));
            return "The tutorial added the comment " + DisplayNames.quotedName(SAMPLE_CHANGE_REQUEST)
                    + " and moved the card to " + quoted(RECOMMENDED_ACTIVE_STATE) + " for you";
        }
    }

    /// State of one tutorial run.
    private static final class Session {
        private final Options options;
        private final TutorialBoardCleanup cleanup;
        private final Map<String, String> listIds = new HashMap<>();
        private final Set<String> tutorialCommentIds = new HashSet<>();
        // Read by the Ctrl+C hook thread.
        private volatile Board board;
        private volatile boolean creatingBoard;
        private String cardId;
        private String workpadCommentId;

        private Session(Options options, TutorialBoardCleanup cleanup) {
            this.options = options;
            this.cleanup = cleanup;
        }

        private List<String> listNames() {
            return options.github()
                    ? TrelloBoardSetup.RECOMMENDED_LISTS
                    : TrelloBoardSetup.RECOMMENDED_NON_GITHUB_LISTS;
        }

        private String listId(String listName) {
            return listIds.get(listName);
        }

        /// List ids are unique, so any matching entry is the card's list.
        private Optional<String> listName(String listId) {
            return listIds.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(listId))
                    .map(Map.Entry::getKey)
                    .findAny();
        }

        private String whereIs(CardSnapshot card) {
            return listName(card.listId())
                    .map(name -> "the card is in " + DisplayNames.quotedName(name))
                    .orElse("the card is no longer on the tutorial board");
        }
    }
}
