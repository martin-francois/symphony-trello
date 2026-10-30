---
status: accepted
date: 2026-10-30
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #34](https://github.com/martin-francois/symphony-trello/issues/34)"
  - "[GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31)"
  - "[GitHub PR #844](https://github.com/martin-francois/symphony-trello/pull/844)"
  - "[GitHub issue #1025](https://github.com/martin-francois/symphony-trello/issues/1025)"
  - "[GitHub issue #1124](https://github.com/martin-francois/symphony-trello/issues/1124)"
informed: [Future maintainers, Contributors, Operators]
---

# Pre-Handoff Codex Review Loop Through A Runtime Prompt Section

## Context and Problem Statement

[GitHub issue #34](https://github.com/martin-francois/symphony-trello/issues/34) asks for a workflow
setting that makes Codex run review rounds before it hands off repository changes. Today a card has
to ask for a review in its own text, which is easy to forget. The loop has to run before the final
local checks, so those checks validate the reviewed candidate. That includes the CI-equivalent
local checks used when CI is unavailable, and the branch-only handoff without a pull request from
[GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31).

Repository work is agent-owned in this project: Codex commits, interprets findings, runs checks,
pushes, and opens pull requests. Java schedules runs and gives Codex its prompt and tools. Where
should the setting live, what is its default, how does it reach Codex, and how does Codex run the
review?

[GitHub issue #34](https://github.com/martin-francois/symphony-trello/issues/34) first asked for an opt-in switch that keeps the old behavior by default. The maintainer
decided on 2026-10-30 in
[GitHub issue #1025](https://github.com/martin-francois/symphony-trello/issues/1025) that the loop
runs by default instead, and that a round limit of `0` turns it off. The reason: a change should
already be reviewed when a person looks at it, and people should not have to remember to ask for a
review. The loop stays optional, and turning it off stays one key.

The first version ran one round over the full diff with a per-run timeout. Before release, the
maintainer redesigned it into a multi-round loop with severities, a findings ledger, narrowed later
rounds, a time budget for the whole loop, and an optional reviewer model. This ADR describes the
redesigned loop; the first version never shipped in a release.

## Decision Drivers

* Every repository-changing card should be reviewed before handoff without asking for it.
* One edit per board must be enough to change or turn off the loop. Cards must not repeat the
  request.
* The setting should work for workflows that were generated before this change and for hand-written
  workflows, without regeneration.
* The loop must be bounded and must not widen card scope or rewrite pushed history.
* Java must stay out of repository actions such as running reviews or blocking pushes.
* The nested review must not get more sandbox access than the Codex run that starts it.
* Later rounds should spend review time on the fixes, not on code an earlier round already
  reviewed.
* The reviewer should work on every Codex account without a model name that goes stale.

## Considered Options

* Runtime-appended prompt section driven by `repository` front-matter fields
* Text baked into the generated workflow body at setup time
* A prompt template variable rendered by Java and referenced by the generated body
* Java-enforced pre-push hook or Git wrapper
* A scoped Symphony tool that runs the app-server `review/start` request for the agent
* Review the full diff in every round (round scope)
* Hard-code the reviewer model (reviewer choice)
* Ask for the reviewer model during setup (reviewer choice)
* Checkpoint and rerun the review in the next run when the budget runs out (unfinished review)

## Decision Outcome

Chosen option: "Runtime-appended prompt section driven by `repository` front-matter fields".

### Fields and defaults

The workflow front matter gets these Java extension fields:

```yaml
repository:
  # Codex reviews repository changes before handoff. codex_review_max_rounds: 0 turns the review off.
  codex_review_max_rounds: 3
  codex_review_timeout_ms: 3600000
  # Hard round limit while a finding about security, authentication, permissions, data loss, or concurrency stays open.
  # Unset, it is 5 or codex_review_max_rounds if higher.
  # codex_review_extended_max_rounds: 5
  # Reviewer model and effort. Unset, the review uses codex.model and codex.reasoning_effort.
  # codex_review_model: gpt-5.5
  # codex_review_reasoning_effort: high
```

`codex_review_max_rounds` is the usual number of review rounds before one handoff. The default is
`3`, and `0` turns the loop off. A single number replaces the earlier draft's pair of a boolean
`codex_review_before_handoff` and a `codex_review_max_cycles` limit, which never shipped. With one
number there is no state where the flag and the limit disagree, such as an enabled loop with no
rounds. No alias or migration exists for the draft names because no release accepted them.

`codex_review_extended_max_rounds` is the hard limit, `5` by default, or the round limit when an
operator sets only that one higher. Rounds beyond `codex_review_max_rounds` are allowed only while a
`blocker` or `should` finding about security, authentication, permissions, data loss, or
concurrency stays open with evidence. These findings cost the most when they reach a person unfixed,
so they get two more rounds; other findings do not. A value below `codex_review_max_rounds` fails
configuration, because the hard limit would then be the smaller number and the usual limit would
mean nothing.

`codex_review_timeout_ms` is the time budget for the whole loop, one hour by default. The agent
records the loop start time and gives each separate `codex review` run the time left, in whole
seconds rounded up. The first version limited each run separately, so three rounds could take three
times the configured value. A budget for the loop bounds the total time a card spends in review. No
round starts when no whole second is left, because `timeout 0s` would run without a limit. The
loop runs inside one Codex turn. While the agent waits on a review run it waits at most 2 minutes
per poll, or half of `codex.stall_timeout_ms` (5 minutes without events by default) when that half
is shorter, so the stall check and a turn timeout that counts silence never see the wait as a stuck
turn.

`codex_review_model` and `codex_review_reasoning_effort` are optional. Unset or blank, the reviewer
uses the workflow's `codex.model` and `codex.reasoning_effort`. Model availability differs per Codex
account, and a model name pinned in Java or in setup goes stale. Following the workflow model works
on every account that can run the workflow at all. The values reach the reviewer as
`-c 'model="<model>"'` and `-c 'model_reasoning_effort="<effort>"'`, the Codex CLI config keys. They
must be one word of letters, digits, and `. _ : / @ + -`, because they appear in a shell command in
the prompt. A workflow `codex.model` that is not such a word is left out of the command instead of
failing configuration, since `codex.model` existed before this feature and goes to the app server as
JSON.

The names follow the existing key style. `max_rounds` matches `agent.max_turns` and
`trello_tools.follow_up_cards.max_cards_per_hour`, and `_timeout_ms` matches `codex.turn_timeout_ms`
and `hooks.timeout_ms`. The fields sit under `repository` because the loop only applies to
repository-changing work. They are not GitHub specific, so they do not belong to the `github`
section from [GitHub PR #844](https://github.com/martin-francois/symphony-trello/pull/844). A
nested `repository.codex_review` object was also possible, but the flat keys match the fields that
shipped in the first version. `codex_review_rounds` was rejected because it reads as an exact count,
while the loop stops early after a clean round. "Round" replaces the draft's "cycle" because the
maintainer's decision uses that word. The timeout keeps its `_timeout_ms` name even though it now
covers the whole loop, because `timeout` is the command that enforces it.

The round limits accept `0` and positive integers. The budget must be a positive integer. Other
values fail configuration with `config_value_error`, the same way other integer fields do. The key
names are defined once, in `EffectiveConfig.CodexReviewConfig`, and config parsing, setup, and both
prompt texts read them from there.

### Runtime section

While the round limit is above `0`, `LocalAgentRunner` appends a final `Codex Review Before
Handoff` section after the repository source context. The section is authoritative for the loop. It
fixes the order (commit the candidate, review without pushing, run local checks once, then push and
hand off) and holds the whole procedure:

* Before round 1 the agent records the start time, the base commit, the diff commands (the full
  change for round 1, the change since the previous round's reviewed commit for later rounds, and
  always the full diff for an uncommitted candidate), and a findings ledger in the workpad. Each finding gets an id that never changes, a
  severity (`blocker`, `should`, or `nit`), and an outcome: fixed, disagree with evidence, or
  declined.
* Round 1 reviews the full change against the base. Later rounds review only the change since the
  previous round and say whether each open ledger item still holds.
* The agent makes the smallest fix that solves a justified in-scope finding, prefers deleting or
  narrowing code, answers a finding it disagrees with with evidence, never accepts a finding only to
  end the loop, and cuts the diff back if review fixes grew it.
* The loop stops when a round returns no `blocker` or `should` finding, when the round limits or the
  budget are reached, and does not start a new round for declined nits or comment, documentation,
  or formatting fixes. Reaching a limit is not approval: open `blocker` and `should` findings go
  into the handoff as caveats.
* When the round limit or the budget ends the loop, the agent still hands off the normal way: push,
  pull request or branch-only artifact, and the card moves to review. The handoff comment and the
  workpad record the rounds, the ledger outcomes, and the open findings as caveats. When the budget
  ended it, they say so plainly and suggest raising `repository.codex_review_timeout_ms`, naming its
  current value. Handing off is the default for an unfinished review, rendered by its own method in
  `CodexReviewPrompt`. A later setting that moves the card to the blocked list instead would be a
  compatible addition with this default, so this decision adds no key for it now.
* An unattended agent cannot stop and ask. A finding still disputed after two replies stays
  unresolved, the handoff comment shows both positions in two or three lines, and the card still
  moves to review, where a person decides.
* One reviewer runs per round, without subagents, and nothing is pushed during the loop.
* A failed final validation whose repair changes code, or a sync after a rejected push that changes
  the candidate, gets one more round over only that change when the limits allow.

Every time-dependent part of the section, from the budget and the `timeout` wrapper to the rule
for a stopped run and the budget note in the handoff, comes from `CodexReviewTiming`, so a different
timing design changes that one class.

The procedure lives in the runtime section, not in a shipped skill, so the text that orders the
steps and the procedure itself cannot drift apart, and no skill installation depends on the prompt.
With a limit of `0`, nothing is appended.

### Separate review, sandbox, and fallback

Codex should prefer a separate `codex review -` run wrapped in `timeout` with the time left in the
budget. The `-` makes Codex read a review-only brief from stdin. The brief names the base commit,
the diff command, the card's intent, what is out of scope, the checks that ran, and in later rounds
the ledger and the change since the previous round. `codex review --base <branch>` cannot carry the
brief: Codex CLI 0.164.0 rejects it with
`error: the argument '--base <BRANCH>' cannot be used with '[PROMPT]'`. A check on 2026-10-30 with
Codex CLI 0.164.0 ran `timeout 600 codex review -` with such a brief and no scope flag on a scratch
repository with one planted bug. Round 1 reported the bug as a `blocker` with the given id. After
the fix, round 2 got the ledger and the change since round 1, reported the finding as resolved, and
found nothing else. The same check showed that `-c model_reasoning_effort="low"` reaches the
reviewer.

A bounded real-Codex check on 2026-10-04 with Codex CLI
0.160.0 showed that a nested `codex review` started from a `workspaceWrite` session fails with
`Read-only file system (os error 30)` because the Codex home directory is not writable there.
Adding the Codex home directory as a writable root did not help in that check: the nested sandbox
then failed to start with a bubblewrap mount error. In that case the section tells Codex to review
the same diff in its own session, with the same brief and ledger, and to say which method it used.

A `codex review` run often prints little for many minutes. In Codex session logs from July to
mid-August 2026, agents stopped 22 of 145 runs themselves with Ctrl-C after polling for between 20
seconds and 6 minutes, and some had started fixing from partial output first. The Codex shell tool
ended none of them. So the section tells Codex never to cancel, interrupt, or kill a running review,
not with Ctrl-C, not by ending its session, and not by starting another review, even when it looks
stuck. Codex keeps polling, at most 2 minutes apart and at most half the stall timeout, until the
run exits on its own or the `timeout` wrapper stops it, and acts on findings only after that. A run
the wrapper stopped counts as the budget running out. Only a startup failure sends Codex to the
in-session review.

The nested command gets `--dangerously-bypass-approvals-and-sandbox` only when the workflow's own
Codex turns already run without the sandbox. Then the nested review gets no more access than the
session that starts it, and it can run the same local commands. Otherwise the section forbids
bypass flags, because target repositories may be untrusted. `CodexSandboxPolicy.bypassesSandbox`
reads the turn policy that `CodexSandboxPolicy.effectivePolicy` builds for every turn, and the
thread sandbox when there is no turn policy. That covers a forced
`SYMPHONY_CODEX_DANGER_FULL_ACCESS`, a configured `dangerFullAccess` turn policy, and a
`danger-full-access` `codex.thread_sandbox` that no turn policy overrides. There is no separate
workflow key for the nested flag, and the check reuses the policy Codex receives, so the two cannot
drift apart. The flag goes before the
subcommand, `codex --dangerously-bypass-approvals-and-sandbox review`, the form the repository's own
review loop uses. Codex CLI 0.164.0 rejects the flag after `review`.

### Setup and existing boards

Generated workflows from `setup-local`, `new-board`, and `import-board` write the round limit and
the budget with their defaults and a comment that `codex_review_max_rounds: 0` turns the review off,
so the setting is visible. The extended round limit, the reviewer model, and the effort are written
as commented-out lines, each with a one-line explanation. A written extended limit would stay at `5`
when an operator raises only the round limit above it and would then fail configuration; left
unset, it follows the round limit the same way it does in a hand-written workflow. Regeneration
keeps an extended limit someone set explicitly. The body includes a short "Review Loop Setting" section that names the runtime
section. Setup regeneration preserves the values the same way it preserves `repository.default_url`
and `repository.default_path`. The `commit`, `push-pr`, and `trello-handoff` skills defer to the
runtime section when it is present, including a push retry whose sync changed the candidate.

Guided `setup-local` asks one yes/no question, "Review changes before handing them to you?", after
the follow-up card question and with the same pattern: the default follows the current setting,
Enter keeps the workflow unchanged, Yes on a workflow with the loop off writes `3` (and raises a
lower `codex_review_extended_max_rounds` to `3`, so the workflow stays valid), and No writes `0`.
Non-interactive setup does not ask. `--codex-review-model` and `--codex-review-reasoning-effort`
follow the existing `--codex-model` and `--codex-reasoning-effort` options and write the reviewer
keys uncommented. Setup does not ask for a reviewer model, because
the workflow model is the right default on every account and a second model prompt would add a
question most operators do not need. Editing a workflow through setup rewrites its front matter
without comments, so the commented-out reviewer lines disappear after such an edit; the README and
SPEC document the keys.

A workflow that omits the fields uses the defaults, so existing boards start running the loop on
every repository-changing card after the update. That is the intended effect of the decision.
Operators who want the old behavior set `codex_review_max_rounds: 0`. Workflow body migration from
[ADR 0095](0095-generated-workflow-body-migration.md) only changes the body, so it does not add the
fields to existing front matter.

### Consequences

* Good, because every repository-changing card is reviewed before handoff without card text.
* Good, because an operator changes or turns off the loop with one edit, and the next dispatch picks
  it up through normal workflow reload.
* Good, because older generated workflows and hand-written workflows get the loop without
  regeneration, since the runtime appends the section.
* Good, because the procedure text exists once, in `CodexReviewPrompt`, and skills only refer to it.
* Good, because the nested review never gets more sandbox access than the Codex run.
* Good, because later rounds review only the fixes, so three rounds cost little more than one on a
  change that needs few fixes.
* Good, because the budget bounds the total review time per card, whatever the round count.
* Good, because an unfinished review still reaches a person with its open findings, and a setting
  that blocks the card instead can be added later without changing the default.
* Good, because the reviewer follows the workflow model, so no model name goes stale in Java or as
  an active setting in generated workflows; the commented-out example line has no effect.
* Good, because Java still does not run reviews, inspect findings, or block pushes.
* Bad, because existing boards change behavior after the update: cards take longer and use more
  Codex time and tokens until the operator sets the limit to `0`. The release marks this as a
  breaking change, with a `BREAKING CHANGE:` footer, so the 2.0.0 release notes name it and the
  key that turns the loop off.
* Bad, because the agent has to keep the ledger, the budget, and the round count itself. Java
  cannot check them.
* Neutral, because the review runs inside a Codex turn.
  [ADR 0136](0136-apply-the-codex-turn-timeout-to-silence-like-upstream.md) applies
  `codex.turn_timeout_ms` to silence, so a review that keeps producing output does not end the turn.
* Bad, because the guarantee still depends on Codex following its prompt. Java cannot prove that a
  review ran.
* Bad, because under the default sandbox the review usually runs in the same Codex session that
  wrote the change, which is less independent than a separate reviewer.

### Confirmation

* `ConfigResolverTest` checks the defaults, configured values, blank reviewer values, a limit of
  `0`, the rejected values including an extended limit below the round limit, and which sandbox
  settings count as a bypass.
* `LocalAgentRunnerTest` checks that a limit of `0` adds no review section, that the default
  section comes after the repository source context with the default limits and a 3600 second
  budget, the `codex review -` command without scope flags, the ledger, the severities, the narrowed
  later rounds, the stop rules, the rule never to stop a running review, the handoff with caveats
  and the budget suggestion when the budget runs out, the reviewer model and effort overrides and
  their fallback to the workflow values, that the bypass flag appears only for a workflow without
  the sandbox, and that the section orders the loop before local checks and every publication path.
* `TrelloBoardSetupTest` checks that GitHub, non-GitHub, and imported workflows write the defaults
  with the commented-out reviewer lines and the "Review Loop Setting" section.
  `TrelloBoardSetupMainTest` checks the reviewer options of `new-board` and `import-board`, and
  `LocalSetupTest` checks the `setup-local` options and that `setup-local configure-github` keeps
  edited values.
* `LocalSetupCodexReviewTest` checks the guided question for new, imported, and kept boards.
* `CodexSkillStructureTest` checks that `commit`, `push-pr`, and `trello-handoff` defer to the
  runtime section.
* `SPEC.md` Section 19.7 holds the contract.

## Pros and Cons of the Options

### Runtime-appended prompt section driven by `repository` front-matter fields

Java reads the fields and, while the round limit is above `0`, appends a fixed
`Codex Review Before Handoff` section to the prompt at the agent boundary, after the repository
source context.

* Good, because it works for every workflow body, including ones generated before this change.
* Good, because a front-matter edit applies on the next dispatch without regeneration.
* Neutral, because it reuses the existing runtime-context pattern of the repository source section.
* Bad, because the runtime adds text the workflow file does not show. The generated body and README
  point at it to reduce surprise.

### Text baked into the generated workflow body at setup time

Setup writes the review procedure into the workflow body.

* Good, because the whole instruction is visible in the workflow file.
* Bad, because turning the loop on or off needs regeneration or hand edits to the body.
* Bad, because older and hand-written workflows never get it.

### A prompt template variable rendered by Java and referenced by the generated body

Java renders a variable such as `codex_review` and the generated body includes it, like the
`pull_request_handoff` variable in
[GitHub PR #844](https://github.com/martin-francois/symphony-trello/pull/844).

* Good, because the body shows where the text goes.
* Bad, because only bodies that reference the variable get the loop, so older workflows need
  regeneration or migration.
* Bad, because a strict template engine fails rendering when an older Symphony runs a newer body.

### Java-enforced pre-push hook or Git wrapper

Symphony installs a hook or wrapper in the task checkout that runs a review before a push succeeds.

* Good, because it enforces the review instead of asking for it.
* Bad, because Java would start governing repository actions, against the project's boundary.
* Bad, because it does not cover the local-check ordering, local-only handoffs, or handoffs without a
  push, and it fights normal Git use by the agent.

### A scoped Symphony tool that runs the app-server `review/start` request for the agent

Symphony offers a tool that Codex calls when the candidate is ready. Java starts a review through
the Codex app-server protocol in a separate thread and returns the findings.

* Good, because it would give a separate reviewer without nested sandbox problems.
* Bad, because it needs a second concurrent app-server thread while the agent's turn waits on the
  tool call, which this project has not built or tested.
* Bad, because it is much larger than this issue and changes the Java and Codex protocol boundary.

### Review the full diff in every round

Every round reviews the whole change against the base, as the first version did.

* Good, because each round sees the whole change, so a fix that breaks distant code is in view.
* Bad, because later rounds re-review code an earlier round already reviewed, which costs time and
  tends to produce new nits on unchanged code instead of checking the fixes.
* Bad, because a stable ledger is harder to keep when every round reports the same code again.

The chosen loop keeps the full diff command in every brief, so the reviewer can still read context,
but asks later rounds to review only the change since the previous round and the open findings.

### Hard-code the reviewer model

Java or setup names one fixed reviewer model, and the section always passes it.

* Good, because every review would use the same model, independent of the workflow.
* Bad, because model availability differs per Codex account, so a pinned model fails on accounts
  without it.
* Bad, because a pinned model name goes stale and needs a release to change.

The chosen design uses the workflow's own model and effort by default and lets an operator name a
reviewer in two optional keys.

### Ask for the reviewer model during setup

Guided setup asks which model and effort the review should use, like it asks for the workflow model.

* Good, because the choice would be visible during setup.
* Bad, because it adds a question whose right answer is almost always "the same as the workflow".
* Bad, because the answer would pin a model name in the workflow, with the same staleness problem.

The chosen design writes the keys as commented-out lines and offers `--codex-review-model` and
`--codex-review-reasoning-effort` for operators who want a different reviewer.

### Checkpoint and rerun the review in the next run when the budget runs out

Some review tools record where an unfinished review stopped and run it again in the next run before
they hand off, instead of handing off with caveats.

* Good, because no change reaches a person with an unfinished review.
* Bad, because the card waits for another dispatch, and a change that keeps running out of budget
  never reaches a person.
* Bad, because the checkpoint would need state across runs that the workpad does not keep reliably
  today.

The chosen design hands off with the open findings as caveats and suggests raising
`repository.codex_review_timeout_ms`. A setting that checkpoints instead, like one that moves the
card to the blocked list, could be added later as a compatible option with this default.

## More Information

* `SPEC.md` Section 19.7 defines the fields and the runtime section contract.
* The runtime text lives in `src/main/java/ch/fmartin/symphony/trello/repository/CodexReviewPrompt.java`.
* The README section "Codex Review Before Handoff" explains the keys, the default change for
  existing boards, and how to turn the loop off.
