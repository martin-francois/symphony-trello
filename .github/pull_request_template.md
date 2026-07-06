## Summary

Describe the change in 2-5 bullet points.

- Problem:
- Why it matters:
- What changed:
- What did not change:

## Change Type

Choose all that apply.

- [ ] Bug fix
- [ ] Feature
- [ ] Documentation
- [ ] Refactor required for this change
- [ ] Chore / infrastructure

## Linked Issue

- Fixes #
- Related #

## User-Visible Behavior

Describe what a user, operator, or contributor can observe after this PR. If there is no
user-visible change, write `None`.

## Compatibility Decision

### What changes for existing users?
- [ ] No visible change
- [ ] Could break existing usage
- [ ] Unsure

If this may break existing usage (or if you are unsure), fill these fields:

What breaks:
`Breaks: ...`

Migration path:
`Migration: ...`

Alternative:
`Alternative: ...`

If this is not a breaking change, you can leave all three fields blank.

## Root Cause And Guardrail

For bug fixes or regressions, explain why the issue happened and what now prevents it from coming
back. For non-bug changes, write `N/A`.

- Root cause:
- Test or guardrail added:
- If no test was added, why not:

## Validation

List the commands, manual checks, or live checks you ran. Include relevant failures that were fixed
during the PR.

- [ ] `./mvnw -q spotless:check verify`
- [ ] Installer or script checks, if touched
- [ ] Documentation lint, if Markdown changed
- [ ] Manual or live check, if behavior changed

Details:

```text

```

## Human Verification

Describe what you tried manually and what result you saw. If the change cannot be tried manually,
explain why.

```text

```

## Review Checklist

- [ ] Docs updated, or N/A
- [ ] ADR updated for architecture decisions or tradeoffs, or N/A
- [ ] PR title or squash title uses Conventional Commits and is release-note ready
- [ ] Breaking changes include a `BREAKING CHANGE:` footer with reason and migration path in the
      squash commit body or retained commit, or N/A
- [ ] Live E2E/deployment notes included when behavior or deployment changed, or N/A
- [ ] Redaction checked: no Trello credentials, Codex auth files, GitHub tokens, private board links,
      account names, private host paths, or deployment-specific paths

## AI Assistance (if used)

<!--
AI/Vibe-Coded PRs Welcome! 🤖
Built with Codex, Claude, or other AI tools? Awesome — just mark it.
AI PRs are first-class citizens here. We just want transparency so reviewers know what to look for.
-->

- [ ] AI-assisted PR
- [ ] I confirm I understand what the code does

<details>
<summary>AI prompts / session logs (optional, but super helpful)</summary>

<!--
If AI assistance was used, include a concise sanitized trace that helps reviewers understand the
agent session. Use whatever format is clearest for this PR. You do not have to include the full
transcript.

Include when relevant:
- The user prompt(s) that caused the substantive changes in this PR. Fix typos only for readability
  and preserve meaning.
- Follow-up prompts that changed scope, constraints, or implementation.
- A chronological trace of what happened between prompts, including relevant changes, decisions,
  commands, checks, review findings, fixes, limits, and handoff state before the next prompt or final
  handoff.
- Verification evidence: what the agent checked, how it checked it, and what result it observed.
- What was redacted, if anything.

Always redact secrets, credentials, private paths, private board links, account names,
deployment-specific details, and other private context. Keep this concise unless a longer trace
materially helps review.
-->

```text

```

</details>
