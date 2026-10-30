---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1124](https://github.com/martin-francois/symphony-trello/issues/1124)"
  - "[GitHub PR #1151](https://github.com/martin-francois/symphony-trello/pull/1151)"
  - "[ADR 0121](0121-pre-handoff-codex-review-loop.md)"
  - "[Upstream Symphony commit 3c372fa1](https://github.com/openai/symphony/commit/3c372fa1f32a4d573a7bb9fa0cc101e16add63c3)"
informed: [Future maintainers, Contributors, Operators]
---

# Apply The Codex Turn Timeout To Silence, Like Upstream Symphony

## Context and Problem Statement

[ADR 0121](0121-pre-handoff-codex-review-loop.md) makes Codex run a review before it hands off
repository changes. The review runs inside the Codex turn, and one separate `codex review` run may
take up to `repository.codex_review_timeout_ms`, one hour by default. The Java runtime applied
`codex.turn_timeout_ms`, also one hour by default, as a cap on the whole turn: `awaitTurnCompleted`
waited on the turn's completion with one `future.get(turnTimeout)`. A review that used its full hour
therefore ended the turn, and the prompt's fallback, an in-session review after a timed-out command,
never ran at the defaults.
[GitHub issue #1124](https://github.com/martin-francois/symphony-trello/issues/1124) asks how the two
timeouts should relate.

Upstream Symphony answers this differently.
[Commit 3c372fa1](https://github.com/openai/symphony/commit/3c372fa1f32a4d573a7bb9fa0cc101e16add63c3)
documents `codex.turn_timeout_ms` as "maximum silence interval while a turn stream is active; each
app-server output resets it, so it is not a total turn runtime cap". The Elixir runtime already
worked that way: its turn loop is a `receive ... after timeout_ms`, which starts the wait again after
every line the app-server writes, with stderr merged into the same stream. The commit rejects a
total-runtime deadline because "healthy long turns should stay alive while updates continue".

Should this project keep the total cap and make room for the review in it, or apply the timeout to
silence like upstream?

## Decision Drivers

* A turn that is still working, including a long review, should not be cut off.
* A turn that has stopped producing output should still end.
* Existing workflows should keep working without an edit or a migration.
* The project adapts the upstream specification and should not diverge from it without a reason.

## Considered Options

* Apply `codex.turn_timeout_ms` to silence, like upstream
* Keep the total cap and raise its default to two hours, with a migration for generated workflows
* Keep the total cap and reserve part of it for the review
* Run the review in a separate turn

## Decision Outcome

Chosen option: "Apply `codex.turn_timeout_ms` to silence, like upstream", because it keeps busy
turns alive, still ends silent ones, needs no new default or migration, and removes a divergence
from upstream instead of adding one.

`TurnSilenceTimeout` holds the time of the last app-server output in one atomic value that only
moves forward. The stdout and stderr reader threads update it for every line. `awaitTurnCompleted`
starts the interval when it starts waiting, waits for the turn's completion for the remaining
interval, and measures the silence again whenever that wait runs out. It fails with `turn_timeout`
only once a full `codex.turn_timeout_ms` has passed without output. The default stays `3600000`.

With this meaning, a check that the review timeout is below the turn timeout says nothing useful: a
review that streams output never reaches the turn timeout, however long it runs. No such check is
added.

A cap on the total turn runtime is not part of this decision. If one is needed later, it can be a
separate optional key that is off by default. Adding it would not change existing workflows, so it
would not be a breaking change.

### Consequences

* Good, because a turn that keeps streaming updates, such as a long review with command output,
  runs until it completes.
* Good, because a silent turn still ends with `turn_timeout`, and the orchestrator's
  `codex.stall_timeout_ms` still stops a session without Codex events.
* Good, because the timeout means the same here as in upstream Symphony.
* Good, because no workflow has to change, so no migration is needed.
* Bad, because nothing limits the total time of a turn that keeps producing output. A turn that
  loops while it streams Codex events runs until the operator stops it; `stall_timeout_ms` does not
  fire while events keep coming.
* Bad, because a review command that prints nothing for the whole interval ends the turn when the
  app-server writes nothing else in that time. The app-server streams command output as
  `item/commandExecution/outputDelta` notifications, so this only happens when the review run itself
  stays silent.

### Confirmation

* `TurnSilenceTimeoutTest` checks with a fake clock that the wait fails after a silent interval,
  that output during the wait moves the deadline, that a turn with steady output runs ten times
  past the limit, and that the interval starts when the wait starts. Two reader threads in a forced
  order check that a reader that read the clock earlier but writes later cannot move the deadline
  back.
* `CodexAppServerClientTest` runs a fake app-server that streams updates for longer than the turn
  timeout, once on stdout and once on stderr, and expects success, and one that stays silent and
  expects `turn_timeout`.
* `SPEC.md` Sections 5.3.6, 6.4, 10.3, 10.6, 14, 17 and 19.7 hold the contract.

## Pros and Cons of the Options

### Apply `codex.turn_timeout_ms` to silence, like upstream

The timeout ends a turn only after `codex.turn_timeout_ms` without app-server output.

* Good, because it fixes the review case without a new default.
* Good, because it matches upstream.
* Bad, because there is no total limit for a busy turn.

### Keep the total cap and raise its default to two hours, with a migration for generated workflows

[GitHub PR #1151](https://github.com/martin-francois/symphony-trello/pull/1151) implemented this.
The default `codex.turn_timeout_ms` went to `7200000`, dispatch validation rejected a review timeout
that was not below the turn timeout, and `migrate-workflows` changed the `3600000` that every 1.x
setup wrote to `7200000`.

* Good, because the review and its fallback fit inside the default turn.
* Bad, because it is a breaking change: workflows with a short turn timeout fail dispatch.
* Bad, because it needs a temporary front matter migration with its own cleanup issue.
* Bad, because a longer review budget would need the next raise and the next migration.
* Bad, because it diverges further from upstream.

### Keep the total cap and reserve part of it for the review

Symphony would keep the review timeout below the turn timeout by a fixed reserve, for example by
deriving one from the other.

* Good, because the fallback always has time left.
* Bad, because the implementation part of the turn gets shorter by the reserve, and a long change
  still hits the cap.
* Bad, because it couples two settings that operators set separately.

### Run the review in a separate turn

Symphony would start the review as its own turn after the implementation turn, each with its own
limit.

* Good, because each turn gets the full limit.
* Bad, because [ADR 0121](0121-pre-handoff-codex-review-loop.md) keeps the review loop in the
  prompt and keeps Java out of repository actions such as running reviews. A separate review turn
  would make Java start and sequence the review.
* Bad, because it is a much larger change for a problem the silence timeout already solves.

## More Information

* The change lives in `src/main/java/ch/fmartin/symphony/trello/agent/TurnSilenceTimeout.java` and
  `CodexAppServerClient`.
* README sections "Codex Command" and "Codex Review Before Handoff" explain the timeout.
