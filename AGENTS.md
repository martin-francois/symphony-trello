# Agent Operating Instructions

These instructions are repository-local and apply to Codex or any other AI agent working in this
checkout. Follow them in addition to higher-priority system, developer, and user instructions.

Symphony for Trello is a senior-engineered Quarkus backend on Java 25 LTS and Maven 3 that
implements the adapted Symphony-for-Trello specification in `SPEC.md`; work as a senior Quarkus
engineer driven by that spec.

**Package manager:** Maven via `./mvnw` (do not invoke a system Maven). The few Node-based tools
use pnpm via Corepack; do not add a `package.json` only to run a JavaScript CLI.

Before starting any task, open [docs/agents/default-workflow.md](docs/agents/default-workflow.md)
and follow it.

When a user corrects a repeatable mistake or states a generally useful working preference, make the
durable agent-doc update proactively in the same change; do not wait for the user to explicitly ask
for persistence. If the right persistence scope is unclear, ask before finishing.
When an installed skill eval issue captures bad or missing behavior, add or update the corresponding
temporary repo-local override described in [Default workflow](docs/agents/default-workflow.md) and
[Java style & design preferences](docs/agents/java-style.md).
Before finishing a task where you made or explained a deliberate design tradeoff, or where the code
does not make the meaning or rationale obvious, make the decision explicit in the code where
possible and check whether
[Specification & ADR discipline](docs/agents/specification-and-adr-discipline.md) requires an ADR.
Add or update it before the user asks.
For repeated literals, scenario-table values, or parallel edits that look coupled, apply the
centralization and refactoring guidance in
[Java style & design preferences](docs/agents/java-style.md).
When adding or editing tests, apply the AssertJ and test-duplication guidance in
[Testing](docs/agents/testing.md), including avoiding assertion loops or assertion streams when
AssertJ can express the expectation directly.

Until the first public release, implement only the canonical current contract. Do not add or retain
product migration, legacy-shape support, backward-compatibility shims, old-private-state fallbacks,
or automatic upgrade code for private pre-release data; update the private deployment manually once.
See [Pre-public clean breaks](docs/agents/default-workflow.md#pre-public-clean-breaks).

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
- [Documentation & README](docs/agents/documentation-and-readme.md)
- [GitHub issue triage](docs/agents/github-issue-triage.md)
- [Autonomy & escalation](docs/agents/autonomy-and-escalation.md) including the Codex review/fix loop
- [Private-context redaction](docs/agents/private-context-redaction.md)
- [Maintaining agent docs](docs/agents/maintaining-agent-docs.md)
- [Progressive disclosure source](docs/agents/progressive-disclosure-source.md)

# Agent Rules <!-- tessl-managed -->

@.tessl/RULES.md follow the [instructions](.tessl/RULES.md)
