# Maintaining agent docs

## Progressive disclosure contract

- AGENTS.md stays minimal and should only contain global essentials and links.
- All topic-specific guidance must live under docs/agents and be linked from AGENTS.md.
- When asked to add content to AGENTS.md, first validate it against the allowlist below; if it is
  topic-specific, add it under docs/agents and link it instead.
- When the user states a generally useful working preference, corrects an agent mistake, says
  something was not done the way they wanted, or explicitly asks for a durable preference to persist,
  capture it in the most relevant docs/agents page in the same change so future sessions do not
  repeat the issue. If the user explicitly scopes the instruction to the current session, do not make
  it durable. Do not add new rules only because the agent independently chose a reasonable
  improvement. If the correction is about a concrete preference, add or update the concrete rule for
  that preference; do not replace it with only a generic process reminder. When the correction is
  about a repeatable code, documentation, issue-triage, PR, ADR, or workflow pattern, treat the
  durable update as part of the fix: make the immediate correction, add or update the specific rule
  that would have prevented it, and check nearby rules for conflicts before finishing.
- When a new user preference changes how these agent docs themselves should be maintained, review
  existing agent-added rules for conflicts with that new preference in the same turn.
- When fixing a documentation pattern, search the relevant file or docs set for similar instances
  before committing instead of correcting only the one sentence the user pointed out.

## What may be added to AGENTS.md (allowlist)

- One-sentence project description.
- Package manager (here Maven via `./mvnw`; pnpm via Corepack for the few Node tools).
- Non-standard build/verify commands (here `./mvnw -q spotless:check verify`).
- Anything truly relevant to every single task.

## What must not be added to AGENTS.md (denylist)

- Testing patterns (how to write or run tests, live E2E rules) — see [Testing](testing.md).
- Java style and design preferences — see [Java style & design preferences](java-style.md).
- Specification and ADR rules — see
  [Specification & ADR discipline](specification-and-adr-discipline.md).
- Static-analysis policy (PMD, SpotBugs, Error Prone, Semgrep, CodeQL, suppressions) — see
  [Static analysis policy](static-analysis.md).
- Git, commit, and PR workflow details — see [Default workflow](default-workflow.md).
- Documentation, README, and CLI/UX wording rules — see
  [Documentation & README](documentation-and-readme.md).
- Deployment and live-verification steps — see
  [Deployment & live verification](deployment-and-live-verification.md).
- Issue-triage labels and rules — see [GitHub issue triage](github-issue-triage.md).
- Tool-specific instructions (Codex review/fix loop, diagnostics, private-context scanner).

## Where to put new guidance (decision tree)

- Is it required for every single task?
  - Yes: add a single sentence to AGENTS.md and link to a docs/agents page if details are needed.
  - No: place it under docs/agents in the most relevant topical file or create a new one.
- If it changes how to run, verify, commit, or release work, prefer
  [Default workflow](default-workflow.md).
- If it is about code style, design tradeoffs, or structure, prefer
  [Java style & design preferences](java-style.md).
- If it is about a static-analysis tool, prefer [Static analysis policy](static-analysis.md).
- If it is about the spec or an ADR, prefer
  [Specification & ADR discipline](specification-and-adr-discipline.md).

## Contradictions

If a requested change conflicts with existing instructions, stop and ask the user which version to
keep. Do not auto-resolve.

## Remove vague rules

When editing, delete or rewrite anything redundant, vague, or overly obvious (for example: "write
clean code" or "follow best practices").

## Minimal template for new docs/agents pages

```md
# <Title>

## Scope

Describe what this page covers and what it does not.

## Rules

- Bullet list of clear, actionable rules.

## References

- Links to related docs/agents pages.
```
