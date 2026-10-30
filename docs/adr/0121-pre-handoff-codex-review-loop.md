---
status: accepted
date: 2026-10-30
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #34](https://github.com/martin-francois/symphony-trello/issues/34)"
  - "[GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31)"
  - "[GitHub PR #844](https://github.com/martin-francois/symphony-trello/pull/844)"
  - "[GitHub issue #1025](https://github.com/martin-francois/symphony-trello/issues/1025)"
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
runs by default instead, with one round, and that a round limit of `0` turns it off.

## Decision Drivers

* Every repository-changing card should get one review before handoff without asking for it.
* One edit per board must be enough to change or turn off the loop. Cards must not repeat the
  request.
* The setting should work for workflows that were generated before this change and for hand-written
  workflows, without regeneration.
* The loop must be bounded and must not widen card scope or rewrite pushed history.
* Java must stay out of repository actions such as running reviews or blocking pushes.
* The nested review must not get more sandbox access than the Codex run that starts it.

## Considered Options

* Runtime-appended prompt section driven by `repository` front-matter fields
* Text baked into the generated workflow body at setup time
* A prompt template variable rendered by Java and referenced by the generated body
* Java-enforced pre-push hook or Git wrapper
* A scoped Symphony tool that runs the app-server `review/start` request for the agent

## Decision Outcome

Chosen option: "Runtime-appended prompt section driven by `repository` front-matter fields".

### Fields and defaults

The workflow front matter gets two Java extension fields:

```yaml
repository:
  codex_review_max_rounds: 1
  codex_review_timeout_ms: 3600000
```

`codex_review_max_rounds` is the most review rounds Codex runs before one handoff. The default is
`1`, and `0` turns the loop off. A single number replaces the earlier draft's pair of a boolean
`codex_review_before_handoff` and a `codex_review_max_cycles` limit, which never shipped. With one
number there is no state where the flag and the limit disagree, such as an enabled loop with no
rounds. No alias or migration exists for the draft names because no release accepted them.

`codex_review_timeout_ms` limits one separate `codex review` run. The default is one hour. The
earlier draft hard-coded 30 minutes; the maintainer chose a longer default and made it
configurable, because a review of a large change can need more time.

The names follow the existing key style. `max_rounds` matches `agent.max_turns` and
`trello_tools.follow_up_cards.max_cards_per_hour`, and `_timeout_ms` matches `codex.turn_timeout_ms`
and `hooks.timeout_ms`. The fields sit under `repository` because the loop only applies to
repository-changing work. They are not GitHub specific, so they do not belong to the `github`
section from [GitHub PR #844](https://github.com/martin-francois/symphony-trello/pull/844). A
nested `repository.codex_review` object was also possible, but it adds a level for two values.
`codex_review_rounds` was rejected because it reads as an exact count, while the loop stops early
after a clean round. "Round" replaces the draft's "cycle" because the maintainer's decision uses
that word.

The round limit accepts `0` and positive integers. The timeout must be a positive integer. Other
values fail configuration with `config_value_error`, the same way other integer fields do. The key
names are defined once, in `EffectiveConfig.CodexReviewConfig`, and config parsing, setup, and both
prompt texts read them from there.

### Runtime section

While the round limit is above `0`, `LocalAgentRunner` appends a final `Codex Review Before
Handoff` section after the repository source context. The section is authoritative for the loop. It
fixes the order (commit the candidate, review, run local checks once, then push and hand off),
explains how to evaluate and place fixes, sets the round limit, and lists what the workpad and
handoff comment must record. With a limit of `0`, nothing is appended.

### Separate review, sandbox, and fallback

Codex should prefer a separate scoped `codex review --base <branch>` run wrapped in `timeout` with
the configured limit in whole seconds. A bounded real-Codex check on 2026-10-04 with Codex CLI
0.160.0 showed that a nested `codex review` started from a `workspaceWrite` session fails with
`Read-only file system (os error 30)` because the Codex home directory is not writable there.
Adding the Codex home directory as a writable root did not help in that check: the nested sandbox
then failed to start with a bubblewrap mount error. In that case the section tells Codex to review
the same diff in its own session and to say which method it used.

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

Generated workflows from `setup-local`, `new-board`, and `import-board` write both fields with
their defaults, so the setting is visible, and include a short "Review Loop Setting" section that
names the runtime section. Setup regeneration preserves the two values the same way it preserves
`repository.default_url` and `repository.default_path`. The `commit`, `push-pr`, and
`trello-handoff` skills defer to the runtime section when it is present, including a push retry
whose sync changed the candidate.

A workflow that omits the fields uses the defaults, so existing boards start running one review
round per repository-changing card after the update. That is the intended effect of the decision.
Operators who want the old behavior set `codex_review_max_rounds: 0`. Workflow body migration from
[ADR 0095](0095-generated-workflow-body-migration.md) only changes the body, so it does not add the
two fields to existing front matter.

Setup gets no new command-line option or question. Editing one front-matter value changes the
loop. A setup option would add another prompt or flag to `setup-local`, `new-board`, and
`import-board` for a setting most operators change once, if at all.

### Consequences

* Good, because every repository-changing card gets one review before handoff without card text.
* Good, because an operator changes or turns off the loop with one edit, and the next dispatch picks
  it up through normal workflow reload.
* Good, because older generated workflows and hand-written workflows get the loop without
  regeneration, since the runtime appends the section.
* Good, because the procedure text exists once, in `CodexReviewPrompt`, and skills only refer to it.
* Good, because the nested review never gets more sandbox access than the Codex run.
* Good, because Java still does not run reviews, inspect findings, or block pushes.
* Bad, because existing boards change behavior after the update: cards take longer and use more
  Codex time until the operator sets the limit to `0`.
* Bad, because the review runs inside a Codex turn, so `codex.turn_timeout_ms` still bounds it. With
  both defaults at one hour, a review that reaches its own timeout has already used the turn's
  time.
* Bad, because the guarantee still depends on Codex following its prompt. Java cannot prove that a
  review ran.
* Bad, because under the default sandbox the review usually runs in the same Codex session that
  wrote the change, which is less independent than a separate reviewer.

### Confirmation

* `ConfigResolverTest` checks the defaults, configured values, a limit of `0`, the rejected values,
  and which sandbox settings count as a bypass.
* `LocalAgentRunnerTest` checks that a limit of `0` adds no review section, that the default
  section comes after the repository source context with one round and a 3600 second timeout, that
  configured values reach the text, that the bypass flag appears only for a workflow without the
  sandbox, and that the section orders the loop before local checks and every publication path.
* `TrelloBoardSetupTest` checks that GitHub, non-GitHub, and imported workflows write the defaults
  and the "Review Loop Setting" section, and `LocalSetupTest` checks that
  `setup-local configure-github` keeps edited values.
* `CodexSkillStructureTest` checks that `commit`, `push-pr`, and `trello-handoff` defer to the
  runtime section.
* `SPEC.md` Section 19.7 holds the contract.

## Pros and Cons of the Options

### Runtime-appended prompt section driven by `repository` front-matter fields

Java reads the two fields and, while the round limit is above `0`, appends a fixed
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

## More Information

* `SPEC.md` Section 19.7 defines the fields and the runtime section contract.
* The runtime text lives in `src/main/java/ch/fmartin/symphony/trello/repository/CodexReviewPrompt.java`.
* The README section "Codex Review Before Handoff" explains the two keys, the default change for
  existing boards, and how to turn the loop off.
