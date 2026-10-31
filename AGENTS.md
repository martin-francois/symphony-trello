# Agent Operating Instructions

These instructions are repository-local and apply to Codex or any other AI agent working in this
checkout. Follow them in addition to higher-priority system, developer, and user instructions.

Symphony for Trello is a senior-engineered Quarkus backend on Java 25 LTS and Maven 3 that
implements the adapted Symphony-for-Trello specification in `SPEC.md`; work as a senior Quarkus
engineer driven by that spec.

**Package manager:** Maven via `./mvnw` (do not invoke a system Maven). The few Node-based tools
use pnpm at the exact `packageManager` version, installed as `CONTRIBUTING.md` Development Setup
says; do not add a `package.json` only to run a JavaScript CLI.

Before creating any commit that may be pushed, verify the configured author against the
authenticated GitHub account even when the checkout already carries an author-verification marker.
Before every push, inspect the author of every outgoing commit. Never publish generic or test
identities such as `Test User` or `example.invalid`; follow the commit and push skills to correct a
feature branch, and stop on the default branch unless the maintainer explicitly authorizes a history
rewrite.

Every commit that reaches `main` is one cohesive change, on every pull request, outside
contributors' included. Squash a one-change pull request; otherwise clean up its history to one
commit per change and merge it with a rebase merge. Fold each review fix into the commit it fixes,
keeping that commit's author, unless the fix is a cohesive change of its own, and re-check the
Commit History in Main checkboxes after every push. Pull-request review checks the history; see
[Committing and pull requests](docs/agents/default-workflow.md#committing-and-pull-requests) and
[Outside contributor pull requests](docs/agents/default-workflow.md#outside-contributor-pull-requests).

Before starting any task, open [docs/agents/default-workflow.md](docs/agents/default-workflow.md)
and follow it.

When a user corrects a repeatable mistake or states a generally useful working preference, make the
durable agent-doc update proactively in the same change; do not wait for the user to explicitly ask
for persistence. If the right persistence scope is unclear, ask before finishing.
Unless the user explicitly requests a one-off fix, every fix MUST address the general root cause
rather than only the immediate symptom. When the original failure can be reproduced safely and
within scope, verify it before and after the change to prove that the root-cause fix resolves it.
Add a regression test or another durable guardrail proportionate to the failure. If direct
reproduction, post-fix verification, or a guardrail is infeasible, document the specific constraint
and remaining risk in the handoff.
When an installed skill eval issue captures bad or missing behavior, add or update the corresponding
temporary repo-local override described in [Default workflow](docs/agents/default-workflow.md) and
[Java style & design preferences](docs/agents/java-style.md).
At the point a task makes or explains a deliberate design tradeoff, before implementing or
publishing the chosen path, make the decision explicit in the code where possible and check whether
[Specification & ADR discipline](docs/agents/specification-and-adr-discipline.md) requires an ADR.
An implementation, pull-request description, checklist, review reply, or chat summary is not a
substitute for a required ADR. Add or update it before the user asks.
For repeated literals, scenario-table values, parallel edits that look coupled, and Java collection
mutability defaults, apply the centralization and refactoring guidance in
[Java style & design preferences](docs/agents/java-style.md).
When adding or editing tests, apply the AssertJ and test-duplication guidance in
[Testing](docs/agents/testing.md), including avoiding assertion loops or assertion streams when
AssertJ can express the expectation directly.

Follow the compatibility and migration rules in
[Compatibility discipline](docs/agents/default-workflow.md#compatibility-discipline).

Never let a GitHub Actions workflow run or restore anything a pull request controls in a
privileged context; follow [GitHub Actions security](docs/agents/github-actions-security.md) for
every workflow change.

For normal code changes, run `./mvnw -q spotless:check verify` before finishing (use
`spotless:apply` first when formatting changed). Static-analysis and lint gates must be clean; give
every suppression the narrowest possible scope and a documented reason.

When asked to "rebase main to the Symphony repo" or similar, follow the upstream Symphony rebase
process in [Default workflow](docs/agents/default-workflow.md#upstream-symphony-rebase).

## Maintenance

AGENTS.md is intentionally minimal and should not contain topic-specific guidance. See
[Maintaining agent docs](docs/agents/maintaining-agent-docs.md). If you are asked to add detailed
guidance here, put it in docs/agents and add a link instead.

## More detailed instructions (progressive disclosure)

- [Default workflow](docs/agents/default-workflow.md)
- [Specification & ADR discipline](docs/agents/specification-and-adr-discipline.md)
- [Testing](docs/agents/testing.md)
- [Deployment & live verification](docs/agents/deployment-and-live-verification.md)
- [Java style & design preferences](docs/agents/java-style.md)
- [Static analysis policy](docs/agents/static-analysis.md)
- [GitHub Actions workflows](docs/agents/github-actions.md): step code lives in `scripts/`, not in
  the workflow file
- [GitHub Actions security](docs/agents/github-actions-security.md)
- [Dependency updates](docs/agents/dependency-updates.md)
- [Documentation & README](docs/agents/documentation-and-readme.md)
- Creating, editing, or triaging GitHub issues: [GitHub issue triage](docs/agents/github-issue-triage.md);
  label triage follows its Triage labels policy and ends with `node scripts/audit-issue-labels.ts`,
  and sweeps answer people's comments and paid-help or bounty offers as that page says
- [Autonomy & escalation](docs/agents/autonomy-and-escalation.md) including the Codex review/fix loop
- [Private-context redaction](docs/agents/private-context-redaction.md)
- [Maintaining agent docs](docs/agents/maintaining-agent-docs.md)
- [Progressive disclosure source](docs/agents/progressive-disclosure-source.md)
- [Reference provenance](docs/agents/reference-provenance.md)

# Agent Rules <!-- tessl-managed -->

@.tessl/RULES.md follow the [instructions](.tessl/RULES.md)
