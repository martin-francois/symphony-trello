---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - .github/pull_request_template.md
  - .coderabbit.yaml
  - scripts/check-pr-compatibility-metadata.ts
  - AI_CONTRIBUTION_POLICY.md
  - "[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md)"
  - "[ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)"
  - "[ADR 0141](0141-check-the-ai-assistance-disclosure-deterministically.md)"
informed: [Contributors, Reviewers, Future maintainers]
---

# Require the understanding box on every pull request

## Context and Problem Statement

The pull request template had `I confirm I understand what the code does` as the third checkbox
of the AI Assistance section. [ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md)
and [ADR 0141](0141-check-the-ai-assistance-disclosure-deterministically.md) required it only
when `AI-assisted PR` was ticked: the box backed the AI disclosure, and an author who ticked the
no-assistance choice never saw a reason to tick it.

The box says something that holds for every change. Whoever opens a pull request should
understand what it does, whether a person wrote every line or an agent did. A contributor who
copies a fix from elsewhere, or who delegates to a tool and then ticks the no-assistance choice,
is not covered by a box that only the AI-assisted choice unlocks. On 2026-10-31 martinfrancois
decided that the box is required on every pull request. Where should the box live, and which
checks read it?

## Decision Drivers

* Every author confirms understanding, with no way to skip it by picking the other AI choice.
* The box keeps its exact wording, so the CodeRabbit check, the metadata script, the generated
  OpenRewrite body and the docs keep matching the same text.
* The AI Assistance section keeps its two disclosure choices and its comment, so the disclosure
  rule from ADR 0085 does not change.
* The deterministic check and the CodeRabbit check stay in agreement, with CodeRabbit a subset
  of the script as before.
* The box stays the author's statement
  ([ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)): his agent on
  his pull requests, the author on anyone else's, with one new allowance for the author's agent.

## Considered Options

* Move the box to the first line of the Review Checklist and require it on every pull request.
* Keep the box in the AI Assistance section and require it there on every pull request.
* Keep the rule as it was.

## Decision Outcome

Chosen option: "Move the box to the first line of the Review Checklist and require it on every
pull request".

`.github/pull_request_template.md` lists `- [ ] I confirm I understand what the code does` as
the first item of `## Review Checklist`. The AI Assistance section keeps its heading, its comment
and its two choices. `scripts/check-pr-compatibility-metadata.ts` splits the old check in two:
`evaluateAiAssistanceDisclosure` requires the section once and exactly one of the two choices;
`evaluateUnderstandingConfirmation` requires the Review Checklist section once, the box line
once, and the box ticked, and reports `Tick \`I confirm I understand what the code does\`.` when
it is not. Both run in the Commitlint workflow's `Check compatibility metadata` step on
`pull_request`, with the bot and Release Please skips unchanged. The "AI assistance disclosed"
check in `.coderabbit.yaml` reads the box from its new place and fails when it is not ticked,
whether or not AI helped; it stays a subset of the script.

The generated OpenRewrite pull request body moves the box to the checklist, unticked. That pull
request is opened by the automation App and reviewed by martinfrancois, so it counts as his
([ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)) and his agent
ticks the box when it lands the pull request, as for Renovate and Release Please bodies.

Open outside pull requests keep the body they were opened with. Until such a body is updated to
the new template, the script reports the box line as missing from the Review Checklist and the
CodeRabbit check fails the same body, so it cannot merge until the author moves the line and
ticks it. The maintainer asks for that update in review.

Who ticks the box changes in one way. On martinfrancois's pull requests his agent still ticks it
([ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)). On anyone
else's, the box is the author's statement, and where ADR 0132 told their agent to leave it alone,
an agent working for that author may now tick it, but only
after it asked the author whether they read the code changes and understand them, got an explicit
yes in that conversation, and was told it may tick the box; otherwise it leaves the box unticked
and tells the author to review the change. The rule is stated where the box is: in the template
comment directly above it, in the issue forms' confirmation description, and in `CONTRIBUTING.md`.
No check can tell whether a person or their agent ticked the box, because both act as the same
account; the evidence is those three texts and the maintainer's review of the pull request, as
[Maintaining agent docs](../agents/maintaining-agent-docs.md) requires for a rule no automation
can prove.

The same rule holds for `I confirm I understand and reviewed this ...` in the issue forms. That
box is required on every issue an agent created or an AI helped write, and the agent may tick it
only under the same three conditions, for martinfrancois as for anyone else. A form cannot
require one box only when another is ticked, so the rule stands in the field's description and in
[GitHub issue triage](../agents/github-issue-triage.md). No automation checks issue bodies; the
read-back that page requires after each create or edit is the evidence. Issues filed before this
decision keep their bodies.

### Consequences

* Good, because a pull request opened without AI help now carries the same confirmation as one
  opened with it.
* Good, because the wording did not change, so every check and page that quotes it keeps
  working after the move.
* Good, because the AI section now asks one question, which choice applies, and the checklist
  holds the attestation with the other review items.
* Bad, because a pull request opened from the old template fails the script's Review Checklist
  rule until its body is updated.
* Bad, because the rule now lives in four places: the template, the script, the CodeRabbit
  check and the generated OpenRewrite body. The script tests pin the template lines and the
  CodeRabbit quotes; the rest is review.

### Confirmation

Run the script tests:

```bash
corepack pnpm run verify:scripts
```

The Review Checklist tests in `scripts/check-pr-compatibility-metadata.test.ts` must pass, and
removing the ticked check, the one-line check or the section check from
`evaluateUnderstandingConfirmation`, or the one-choice check or the two-lines check from
`evaluateAiAssistanceDisclosure`, must fail at least one of them.
`scripts/coderabbit-config.test.ts` fails when the CodeRabbit check stops quoting the box or the
"whether or not AI helped" rule. `scripts/openrewrite-renovate.test.ts` checks the generated body.
On a pull request with the no-assistance choice ticked and the box empty, the Commitlint check
and the CodeRabbit check both fail; ticking the box clears both.

## Pros and Cons of the Options

### Move the box to the first line of the Review Checklist and require it on every pull request

The box becomes the first review item; the AI section keeps the two choices.

* Good, because the checklist is where every author already ticks things that apply to every
  pull request.
* Good, because the AI section no longer carries a box whose rule depended on another box.
* Bad, because open pull requests from the old template need a body update.

### Keep the box in the AI Assistance section and require it there on every pull request

Leave the three lines where they are and change only the rule.

* Good, because no open pull request body needs an update.
* Bad, because a box under "AI Assistance (if used)" reads as part of the AI disclosure, and an
  author who ticked the no-assistance choice would read the section as not applying.

### Keep the rule as it was

The box stays in the AI Assistance section and is required only with `AI-assisted PR`.

* Good, because nothing changes.
* Bad, because an author who ticks the no-assistance choice never confirms understanding, which
  is the gap martinfrancois closed.

## More Information

[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) keeps the CodeRabbit gate,
[ADR 0141](0141-check-the-ai-assistance-disclosure-deterministically.md) the deterministic
check, and [ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md) who
ticks the box; each links here for the rule change.
