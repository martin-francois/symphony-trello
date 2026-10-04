---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #542](https://github.com/martin-francois/symphony-trello/issues/542)"
  - "[GitHub CLI `gh issue edit` manual](https://cli.github.com/manual/gh_issue_edit)"
  - "[GitHub REST API: get an issue](https://docs.github.com/en/rest/issues/issues#get-an-issue)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors]
---

# Assign Linked GitHub Issues Through Generated Workflow Guidance

## Context and Problem Statement

When a Trello card implements a GitHub issue, nothing in a generated workflow told Codex to claim
that issue. Other contributors could not see that the issue was taken until a branch or pull request
appeared. [GitHub issue #542](https://github.com/martin-francois/symphony-trello/issues/542) asks
GitHub-enabled workflows to assign those issues to the authenticated GitHub account before
implementation starts, without failing the card when assignment is impossible.

Where should that behavior live, and what should happen when assignment fails?

## Decision Drivers

* Only GitHub-enabled workflows may mention GitHub issue assignment; non-GitHub workflows must not
  require GitHub auth.
* Reuse the GitHub CLI authentication that pull request publication already relies on. Add no new
  credentials and no Java GitHub client.
* Deciding which issue a card "implements" needs judgment over free-form card text. A regex over
  card text cannot tell an implemented issue from one mentioned as context.
* A missing permission or a missing login must not stop the actual work.
* Keep the change small enough to merge without touching the scheduler.

## Considered Options

* Add a `GitHub Issue Assignment` section to GitHub-enabled generated workflows.
* Add the step to the shipped `symphony-trello-trello-handoff` skill.
* Assign issues from Java before dispatching Codex.
* Block the card when assignment fails.

## Decision Outcome

Chosen option: add a `GitHub Issue Assignment` section to GitHub-enabled generated workflows, placed
right after `Execution Flow`, and make assignment best effort.

The section runs after the initial stale-blocker `checking` call succeeds and before implementation,
so a card that stays blocked claims nothing. It tells Codex to assign only issues the card clearly
asks it to implement in the repository selected under Repository Source Precedence, to skip pull
requests by checking the `pull_request` field of `gh api repos/<owner>/<repository>/issues/<number>`,
to assign with `gh issue edit <number> --repo <owner>/<repository> --add-assignee @me`, and to read
the assignees back, because GitHub can drop an assignee without an error when the account lacks
permission. When auth, permission, or issue resolution fails, Codex continues and records which
issue was not assigned and why in the workpad, or in the final response when Trello workpad tools
are disabled.

### Consequences

* Good, because the instruction appears only where GitHub integration is configured, so non-GitHub
  workflows stay free of GitHub auth requirements.
* Good, because Codex already has the card text and the selected repository, so it can judge which
  issue the card implements.
* Good, because `--add-assignee @me` is idempotent, so rework runs can repeat the step safely.
* Bad, because assignment depends on the agent following the prompt; nothing in the runtime checks
  that it happened.
* Bad, because existing workflow files keep the old prompt until the operator regenerates them with
  setup `--force` or copies the section from `WORKFLOW.example.md`.

### Confirmation

`TrelloBoardSetupTest` asserts that GitHub-enabled generated workflows contain the section, that the
workflow without Trello handoff tools records skipped assignments in the final response, that
non-GitHub workflows do not contain it, and that the section in `WORKFLOW.example.md` matches the
generated text. `SPEC.md` Section 19.1 lists the GitHub issue assignment extension.

## Pros and Cons of the Options

### Add a section to GitHub-enabled generated workflows

Setup writes a `GitHub Issue Assignment` section after `Execution Flow` only when GitHub integration
is enabled.

* Good, because `TrelloBoardSetup` already switches every GitHub-specific section on
  `githubEnabled`, so the new section follows the same rule.
* Good, because a test on the generated file proves both the presence and the absence.
* Neutral, because the prompt grows by three short paragraphs.

### Add the step to the shipped `trello-handoff` skill

The pickup procedure in `.codex/skills/trello-handoff/SKILL.md` gains an assignment step.

* Good, because the skill already describes pickup.
* Bad, because Symphony installs the same skill for GitHub and non-GitHub workflows, so the step
  would leak into workflows without GitHub integration.

### Assign issues from Java before dispatching Codex

The orchestrator parses card text for issue references and assigns them before it starts Codex.

* Good, because the runtime could guarantee the attempt and log the result.
* Bad, because Java would need a GitHub client or a `gh` subprocess with its own credential handling,
  which the issue rules out.
* Bad, because Java would have to guess which referenced issue the card implements from free-form
  text.

### Block the card when assignment fails

Any assignment failure moves the card to the blocked list instead of continuing.

* Good, because a failed claim would be impossible to miss.
* Bad, because contributors without triage permission on the target repository could never get a
  card implemented, which the issue explicitly rejects.

## More Information

[ADR 0058](0058-generated-workflow-repository-source-precedence.md) defines how a generated workflow
selects the repository a card changes. The assignment section relies on that selection to decide
whether an issue belongs to the same repository.
