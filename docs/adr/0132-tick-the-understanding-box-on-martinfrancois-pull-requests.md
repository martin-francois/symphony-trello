---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - .github/pull_request_template.md
  - .coderabbit.yaml
  - AI_CONTRIBUTION_POLICY.md
  - docs/adr/0085-enforce-pull-request-template-with-coderabbit.md
informed: [Contributors, Reviewers, Future maintainers]
---

# Tick the understanding box on martinfrancois's pull requests

## Context and Problem Statement

The AI Assistance section of the pull request template has the box
`I confirm I understand what the code does`. The CodeRabbit check "AI assistance disclosed" from
[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) fails while `AI-assisted PR` is
ticked and this box is not. While a check fails, CodeRabbit keeps a changes-requested review on the
pull request, and that review blocks the merge.

Until now the agent docs told every agent to leave the box for a human. Most pull requests here are
authored by martinfrancois and opened or finished by agents he directs. Each of them stopped at
handoff until he ticked the box by hand. His own pull requests have no requested reviewer who could
override the failed check, so the only other way to merge was the ruleset bypass.

The box exists so that an outside contributor confirms that a person owns the code they submit, as
`AI_CONTRIBUTION_POLICY.md` asks. Who should tick it, and how does an agent know which case it is
in?

## Decision Drivers

* An outside contributor's pull request must carry that contributor's own confirmation.
* martinfrancois's pull requests should not wait for a manual tick that adds no information to work
  he directs and his agents review before merging.
* An outside contributor's agent reads the same docs and must not tick the box on its own pull
  request. A fork copies the docs and must not inherit the rule either.
* The CodeRabbit gate from [ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) stays
  unchanged for every author.

## Considered Options

* The agent ticks the box on martinfrancois's pull requests, and on any other pull request the box
  stays with its author.
* Every agent leaves the box for a human to tick.
* Exempt martinfrancois's pull requests in the CodeRabbit check.

## Decision Outcome

Chosen option: the agent ticks the box on martinfrancois's pull requests, and on any other pull
request the box stays with its author.

The rule depends on who authored the pull request, and it holds only in
`martin-francois/symphony-trello`. An agent ticks the box only when it works under martinfrancois's
GitHub account, which `gh api user --jq .login` shows, and martinfrancois authored the pull request.
That includes pull requests an agent opened or finished for him. Renovate and Release Please pull
requests he lands count as his, though their generated bodies have no box today. On a pull request
authored by anyone else, neither martinfrancois nor his agents tick the box, even when they help
finish it. An agent working for that author leaves it for its human and reports the failing check at
handoff.

The `review-sweep` skill that Symphony installs in every workspace also runs in other people's
repositories. It tells the agent to follow the target repository's own agent docs on who ticks the
box, and to leave the box for the human when those docs say nothing.

When the AI assistance check had already failed on the empty box, the agent comments
`@coderabbitai run pre-merge checks` after ticking it so CodeRabbit clears its changes-requested
review.

`.coderabbit.yaml` does not change. Its failure message says that a person has to tick the
confirmation, which is still true for every outside contributor, and the check cannot see who
ticked the box.

### Consequences

* Good, because martinfrancois's pull requests no longer stop at handoff only for the box, and no
  longer need the ruleset bypass for it.
* Good, because an outside contributor's pull request still merges only after someone ticked the
  box, and the docs forbid martinfrancois and his agents from ticking it.
* Good, because the rule names a command an agent can run to tell the two cases apart.
* Bad, because no automation tells who ticked the box. Pull-request review has to check the body
  as first posted and its edit history.
* Bad, because on martinfrancois's pull requests the box now records his agent's statement for him
  rather than a click by him.

### Confirmation

[Default workflow](../agents/default-workflow.md#committing-and-pull-requests) states the
author-based rule, and [ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) links
here. `.codex/skills/review-sweep/SKILL.md` defers to the target repository's agent docs. `.coderabbit.yaml` still requires the box when `AI-assisted PR` is ticked;
`scripts/coderabbit-config.test.ts` fails when the template no longer contains the box text the
check quotes.

On an outside contributor's pull request, the body as first posted or GitHub's edit history of it
must show the contributor ticking the box. On martinfrancois's pull request, a ticked box and a passing AI
assistance check are enough.

## Pros and Cons of the Options

### The agent ticks the box on martinfrancois's pull requests

On a pull request authored by martinfrancois in this repository, the agent working under his
account ticks the box itself. On any other pull request, only its author ticks it.

* Good, because the box keeps its meaning where it matters, on outside contributions.
* Good, because the CodeRabbit gate stays the same for everyone.
* Bad, because the rule relies on agents reading the docs and on review, not on a check.

### Every agent leaves the box for a human

Every agent leaves the box empty and reports the failing check at handoff, whoever authored the
pull request.

* Good, because only a person's click ticks the box.
* Bad, because every pull request martinfrancois has an agent finish waits for him to open it and
  tick one box, even after his agents reviewed it.
* Bad, because on his own pull requests there is no requested reviewer who could override the
  failed check.

### Exempt martinfrancois's pull requests in the CodeRabbit check

Change the "AI assistance disclosed" instructions so the check passes without the box when
martinfrancois authored the pull request.

* Good, because no one has to tick anything on his pull requests.
* Bad, because the check would have to read the pull request author, which none of the custom
  checks do today, and its instructions are language-model judgments.
* Bad, because his pull request bodies would then claim `AI-assisted PR` with no confirmation, which
  reads like an unfinished template.

## More Information

martinfrancois stated this rule on 2026-10-29 and 2026-10-30.
[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) keeps the gate itself and is
still accepted.
