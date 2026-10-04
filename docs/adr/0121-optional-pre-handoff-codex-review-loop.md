---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #34](https://github.com/martin-francois/symphony-trello/issues/34)"
  - "[GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31)"
  - "[GitHub PR #844](https://github.com/martin-francois/symphony-trello/pull/844)"
informed: [Future maintainers, Contributors, Operators]
---

# Optional Pre-Handoff Codex Review Loop Through A Runtime Prompt Section

## Context and Problem Statement

[GitHub issue #34](https://github.com/martin-francois/symphony-trello/issues/34) asks for an
operator switch that makes Codex run review cycles before it hands off repository changes. Today a
card has to ask for a review in its own text, which is easy to forget. The loop has to run before
the final local checks, so those checks validate the reviewed candidate. That includes the
CI-equivalent local checks used when CI is unavailable, and the branch-only handoff without a pull
request from [GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31).

Repository work is agent-owned in this project: Codex commits, interprets findings, runs checks,
pushes, and opens pull requests. Java schedules runs and gives Codex its prompt and tools. Where
should the switch live, how should it reach Codex, and how should Codex run the review?

## Decision Drivers

* Default behavior must not change. A workflow that does not opt in sends the same prompt as before.
* One edit per board must be enough. Cards must not repeat the request.
* The switch should work for workflows that were generated before this change and for hand-written
  workflows, without regeneration. Workflow body migration is not available yet.
* The loop must be bounded and must not widen card scope or rewrite pushed history.
* Java must stay out of repository actions such as running reviews or blocking pushes.
* The review must not need sandbox bypass flags, because target repositories may be untrusted.

## Considered Options

* Runtime-appended prompt section driven by `repository` front-matter fields
* Text baked into the generated workflow body at setup time
* A prompt template variable rendered by Java and referenced by the generated body
* Java-enforced pre-push hook or Git wrapper
* A scoped Symphony tool that runs the app-server `review/start` request for the agent

## Decision Outcome

Chosen option: "Runtime-appended prompt section driven by `repository` front-matter fields".

The workflow front matter gets two Java extension fields:

```yaml
repository:
  codex_review_before_handoff: false
  codex_review_max_cycles: 3
```

The names follow the issue's proposal. The trigger is handoff readiness, not only a push, so the
name covers pull request, branch-only, and local handoffs. The fields sit under `repository`
because the loop only applies to repository-changing work, and they are not GitHub specific, so
they do not belong to the `github` section proposed in
[GitHub PR #844](https://github.com/martin-francois/symphony-trello/pull/844). A nested
`repository.codex_review` object was also possible, but it adds a level for two values and differs
from the names in the issue.

The flag accepts only booleans, so a typo fails configuration instead of silently reading as
`false` the way the lenient `trello_tools` flags do. The cycle limit must be a positive integer. The
default of three cycles is small enough to stop a reviewer that keeps disputing the same findings.

When the flag is `true`, `LocalAgentRunner` appends a final `Codex Review Before Handoff` section
after the repository source context. The section is authoritative for the loop. It fixes the order
(commit the candidate, review, run local checks once, then push and hand off), explains how to
evaluate and place fixes, sets the cycle limit, and lists what the workpad and handoff comment must
record. When the flag is `false`, nothing is appended.

Codex should prefer a separate scoped `codex review --base <branch>` run with a timeout. A bounded
real-Codex check on 2026-10-04 with Codex CLI 0.160.0 showed that a nested `codex review` started
from a `workspaceWrite` session fails with `Read-only file system (os error 30)` because the Codex
home directory is not writable there. Adding the Codex home directory as a writable root did not
help in that check: the nested sandbox then failed to start with a bubblewrap mount error. So the
section tells Codex not to bypass the sandbox and to review
the same diff in its own session when the separate command cannot run, and to say which method it
used.

Generated workflows write both fields with their defaults and include a short "Review Loop
Setting" section that names the runtime section. Setup regeneration preserves the two values the
same way it preserves `repository.default_url` and `repository.default_path`. The `push-pr` and
`trello-handoff` skills defer to the runtime section when it is present.

Setup gets no new command-line option. Editing one front-matter value is the documented way to turn
the loop on. A setup option would add another prompt or flag to `setup-local`, `new-board`, and
`import-board` for a setting most operators change once.

### Consequences

* Good, because the default prompt is unchanged byte for byte.
* Good, because an operator turns the loop on with one edit, and the next dispatch picks it up
  through normal workflow reload.
* Good, because older generated workflows and hand-written workflows get the loop without
  regeneration, since the runtime appends the section.
* Good, because the procedure text exists once, in `CodexReviewPrompt`, and skills only refer to it.
* Good, because Java still does not run reviews, inspect findings, or block pushes.
* Bad, because the guarantee still depends on Codex following its prompt. Java cannot prove that a
  review ran.
* Bad, because under the default sandbox the review usually runs in the same Codex session that
  wrote the change, which is less independent than a separate reviewer.
* Bad, because hand-written workflows that already use `repository.codex_review_before_handoff` for
  something else now fail configuration if the value is not a boolean.

### Confirmation

* `ConfigResolverTest` checks the defaults, an enabled configuration, and the rejected values.
* `LocalAgentRunnerTest` checks that the default prompt has no review section, that the enabled
  section comes after the repository source context, and that it orders the loop before local
  checks and every publication path.
* `TrelloBoardSetupTest` checks that GitHub and non-GitHub generated workflows write the disabled
  defaults and the "Review Loop Setting" section, and `LocalSetupTest` checks that
  `setup-local configure-github` keeps edited values.
* `CodexSkillStructureTest` checks that `push-pr` and `trello-handoff` defer to the runtime section.
* `SPEC.md` Section 19.6 holds the contract.

## Pros and Cons of the Options

### Runtime-appended prompt section driven by `repository` front-matter fields

Java reads the two fields and, when the flag is on, appends a fixed `Codex Review Before Handoff`
section to the prompt at the agent boundary, after the repository source context.

* Good, because it works for every workflow body, including ones generated before this change.
* Good, because a front-matter edit applies on the next dispatch without regeneration.
* Good, because the default prompt does not change.
* Neutral, because it reuses the existing runtime-context pattern of the repository source section.
* Bad, because the runtime adds text the workflow file does not show. The generated body and README
  point at it to reduce surprise.

### Text baked into the generated workflow body at setup time

Setup writes the review procedure into the workflow body when the operator asks for it.

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

* `SPEC.md` Section 19.6 defines the fields and the runtime section contract.
* The runtime text lives in `src/main/java/ch/fmartin/symphony/trello/repository/CodexReviewPrompt.java`.
* The README section "Codex Review Before Handoff" explains how operators turn the loop on and off.
