---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #44](https://github.com/martin-francois/symphony-trello/issues/44)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors, Operators]
---

# Guided Tutorial Plays Symphony's Part On A Temporary Trello Board

## Context and Problem Statement

[GitHub issue #44](https://github.com/martin-francois/symphony-trello/issues/44) asks for an
optional guided tutorial that lets a first-time user move real Trello cards, see Symphony react,
and learn the review, rework, and `Merging` flow without touching their real boards or
repositories. The tutorial must verify the user's moves through Trello, poll gently with manual
controls, and clean up its temporary board.

Several choices shape how that works: who produces Symphony's reactions on the tutorial board,
where setup offers the tutorial, how the terminal waits for input while it polls Trello, and how
the temporary board is cleaned up when the user stops, the tutorial fails, or the user presses
Ctrl+C.

## Decision Drivers

* The tutorial must not change connected boards, workflow files, credentials, or repositories.
* It must not spend Codex quota or create pull requests without explicit opt-in.
* A user who moves the card in Trello should see the reaction without returning to the terminal.
* Every prompt goes through the shared `Terminal.readLine` layer.
* The installer's final handoff stays the last completion block, as `SPEC.md` Section 19.4 requires.
* A stopped, failed, or interrupted tutorial must not leave a temporary board behind by default.
* Cleanup must never reach a board the tutorial did not create in the same run.

## Considered Options

* Tutorial plays Symphony's part on an unconnected temporary board.
* Connect the temporary board to a real managed worker that runs Codex.
* Ask the user to press Enter after each move and check only then.
* Offer the tutorial prompt from the installer after its final handoff.
* Delete the temporary board at the end instead of archiving it.

## Decision Outcome

Chosen option: "Tutorial plays Symphony's part on an unconnected temporary board".

`symphony-trello tutorial` creates a board named `Symphony for Trello tutorial (temporary)` with
the recommended lists for the selected path and one sample card. When the user's move is verified,
the tutorial itself moves the card to `In Progress` and `Human Review` and writes one
`## Codex Workpad` comment that says it is a tutorial demo. No worker manages the board, so no
Codex work runs and no pull request is created. The GitHub path shows where a pull request line
appears in the workpad and treats `Merging` the same way.

Each step checks the card every 5 seconds for up to 3 minutes and then checks only when the user
presses Enter. The user can also type `s` to let the tutorial perform the step or `q` to stop. A
terminal read cannot time out, so one background thread owns the pending `Terminal.readLine` call
and the tutorial thread waits for it with a timeout between Trello checks. A pending read is reused
by the next prompt when the user types the answer after that prompt appeared. A line that arrived
before the next prompt appeared was typed for the old prompt and is dropped, so it cannot answer a
question such as the archive prompt that the user has not seen.

At the end the tutorial asks `Archive the temporary tutorial board now? [Y/n]`. It archives instead
of deleting because archiving can be undone in Trello and matches the wording users see. After a
failure, and from a JVM shutdown hook after Ctrl+C, it archives without asking. The tutorial
thread and the hook claim the board through one atomic settlement before they call Trello, so the
board is archived at most once and a "keep" answer is never overridden. `--no-cleanup` keeps the
board on every path.

Interactive `setup-local` asks
`Want to try a guided walkthrough on a temporary Trello board? [y/N]` after its final handoff. The
installer runs setup in deferred-completion mode, so setup does not ask there. Instead, the final
handoff names the `tutorial` command before the lifecycle commands, and non-interactive setup does
the same.

### Consequences

* Good, because the tutorial is safe: it writes only to its own board and card, and it costs no
  Codex quota.
* Good, because the reactions are deterministic and fast, so the walkthrough works the same on every
  machine and is fully testable against a fake Trello API.
* Good, because a card move is noticed within seconds without pressing Enter, and polling stays
  bounded.
* Good, because Ctrl+C and failures leave no open temporary board unless the user asked to keep it.
* Bad, because the user sees a demo of Symphony's reactions, not a real Codex run.
* Bad, because installer users get the tutorial command in the handoff rather than a yes/no prompt.
* Neutral, because archived tutorial boards stay in the user's archived boards until the user
  deletes them in Trello.

### Confirmation

This decision remains implemented when:

* `GuidedTutorialTest` drives both paths against `StatefulFakeTrello` and shows that only the
  tutorial board is archived;
* `TutorialInputTest` shows that waits share one pending terminal read;
* `TutorialBoardCleanupTest` shows that a concurrent archive request sends one Trello archive call
  and that a kept board is never archived later;
* `TutorialCommandProcessTest` sends SIGINT to a real `tutorial` process and sees the board archived;
* `LocalSetupTutorialOfferTest` shows the offer after interactive setup and the command in the
  installer and non-interactive handoffs; and
* `SPEC.md` Section 19.4 and the README describe the same behavior.

## Pros and Cons of the Options

### Tutorial Plays Symphony's Part On An Unconnected Temporary Board

The tutorial command performs the card moves and workpad updates that Symphony would perform, on a
board that no worker manages.

* Good, because no workflow file, worker, Codex session, or repository is involved.
* Good, because the result does not depend on Codex availability, model speed, or quota.
* Bad, because the reactions are scripted, so the walkthrough cannot show real Codex output.

### Connect The Temporary Board To A Real Managed Worker That Runs Codex

Setup would generate a workflow for the temporary board, start a worker, and let Codex handle the
sample card.

* Good, because the user would see a real Codex run.
* Bad, because it spends Codex quota, which the issue keeps out of scope without explicit
  confirmation.
* Bad, because cleanup would also have to remove a workflow file, a manifest row, a worker, and a
  workspace, and a crash could leave any of them behind.
* Bad, because a real run takes minutes and its result varies, so the steps could not verify a
  predictable state.

### Ask The User To Press Enter After Each Move And Check Only Then

Each step would block on `Terminal.readLine` and read Trello only after the answer.

* Good, because it needs no background thread.
* Bad, because the user must return to the terminal after every move and does not see Symphony
  react on its own.
* Bad, because the issue asks for gentle polling in addition to an explicit check.

### Offer The Tutorial Prompt From The Installer After Its Final Handoff

`install.sh` and `install.ps1` would call the tutorial offer after printing the handoff. Asking
inside the installer's deferred setup run instead was also rejected, because Ctrl+C in the tutorial
would then stop the installer before it registers autostart.

* Good, because installer users would get the same yes/no prompt as `setup-local` users.
* Bad, because the handoff would no longer be the last completion block, which `SPEC.md` requires.
* Bad, because it needs matching Bash and PowerShell changes and installer lifecycle test updates
  for a step that Java already owns.

### Delete The Temporary Board At The End Instead Of Archiving It

The tutorial would permanently delete its board through the Trello API instead of archiving it.

* Good, because no archived tutorial boards pile up in the user's account.
* Bad, because a deletion cannot be undone, even when the user added something to the board that
  they want to keep.
* Bad, because the issue and the Trello UI talk about archiving, so a deletion would surprise users.

## More Information

The tutorial reuses the setup command's Trello credential resolution and error codes through
`TrelloSetupApi`. A card that the user deletes during the tutorial ends it with the expected
failure code `setup_tutorial_card_missing`, which does not create a troubleshooting report.
