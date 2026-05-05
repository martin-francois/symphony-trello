# Agent Operating Instructions

These instructions are repository-local and apply to Codex or any other AI agent working in this
checkout. Follow them in addition to higher-priority system, developer, and user instructions.

## Engineering Identity

Work as a senior Quarkus backend engineer implementing a Java 25 LTS and Maven 4 service from
`SPEC.md`. Treat the adapted Symphony-for-Trello specification as the product contract, and prefer
specification-driven changes over ad hoc behavior.

Quality means the change is correct, maintainable, tested at the right level, documented where it
matters, and easy for another engineer to understand without asking the original implementer.

## Default Workflow

1. Start by reading the relevant specification, existing implementation, tests, README,
   `CONTRIBUTING.md`, and ADRs before changing code.
2. Prefer TDD when the behavior can be isolated. If TDD is impractical, make sure the final tests
   would have failed for the bug or missing behavior.
3. Keep changes narrowly scoped. Separate pure refactors from behavior changes when practical.
4. Update documentation and ADRs when behavior, setup, architecture, or tradeoffs change.
5. When the user corrects an agent mistake or clarifies a durable preference, update this file in
   the same change when reasonable so future sessions do not repeat the issue.
6. When fixing a documentation pattern, search the relevant file or docs set for similar instances
   before committing instead of correcting only the one sentence the user pointed out.
7. Run the relevant verification before finishing. For normal code changes, use:

   ```bash
   JAVA_HOME=/tmp/jdk25 ./mvnw -q spotless:check test package
   ```

   Use `spotless:apply` before that when formatting changed.
8. Commit with Conventional Commits when asked to commit, and keep the working tree clean before
   claiming the work is done.

## Design Preferences

- Keep complexity to the minimum that satisfies the spec and current use cases.
- Prefer existing project patterns and standard Quarkus/JDK APIs over new abstractions.
- Centralize connected constants and configuration values. Do not duplicate magic values with the
  same meaning in multiple places.
- Add comments only for non-obvious decisions, surprising constraints, or tradeoffs that would slow
  down a future maintainer.
- Use Java 25 LTS language/runtime features where they make the code clearer, but do not be clever
  for its own sake.
- Keep the project open-source-ready even while private: clear README, usable CONTRIBUTING, useful
  ADRs, no committed secrets, and reviewable history.
- Write user-facing docs so they can be read from top to bottom without confusion. Do not refer to a
  concept, path, command, or setup mode before introducing it.
- Use progressive disclosure in README setup flows: explain the simplest successful path first, and
  move optional modes, safety knobs, and advanced configuration into later sections.
- For docs with multiple setup paths, read the flow once from each path's perspective and avoid
  wording that assumes the reader chose a different path.
- Do not add convenience links that encourage readers to skip required earlier steps. If a section
  depends on previous setup, structure the document as a linear flow instead of linking directly past
  the prerequisite.
- Prefer descriptive Markdown links over bare URLs in prose. In setup instructions, tell the reader
  what the linked page is for instead of placing multiple raw links next to each other.
- Avoid upfront reference dumps when the same links can be placed contextually in the step where the
  reader needs them.
- Prefer explaining intent, significance, and decision criteria over prescribing arbitrary defaults.
  Recommend concrete names or values only when the choice is semantically important.
- In numbered setup instructions, each numbered item should be an action the reader performs at that
  point. Put explanatory context under the relevant action instead of creating a separate fake step.
  Avoid filler like "intentionally" unless it changes the reader's decision.

## Testing Preferences

- Follow the testing pyramid: fast focused unit tests first, broader integration tests where shared
  contracts or external boundaries are involved.
- Use JUnit 5 and AssertJ. Prefer readable AssertJ chains when they improve the failure message.
- Prefer parameterized tests with `@MethodSource` for data-driven behavior.
- Test names and assertion descriptions should make failures actionable without requiring a debug
  session.
- Do not write low-value tests that only restate a constant. Do test parsing, policy enforcement,
  edge cases, failure modes, and cross-component contracts.

## Java Style

- Let Spotless handle formatting and import cleanup.
- Use imports instead of inline fully qualified type names. Write `Arrays.stream(...)`, not
  `java.util.Arrays.stream(...)`. The test suite enforces this.
- Keep code ASCII unless an existing file or domain requirement clearly needs Unicode.
- Avoid unrelated metadata churn and broad rewrites.

## Specification And ADR Discipline

- When the spec is ambiguous, compare plausible options and choose the least surprising option that
  preserves conformance.
- If a decision explains why an obvious alternative was not chosen, record it in `docs/adr/` using
  the existing MADR-style format.
- Do not silently reinterpret `SPEC.md`. If the implementation needs a clarified adaptation, update
  the spec wording carefully without breaking conformance to the original Symphony intent.

## Autonomy And Escalation

- Make reasonable implementation decisions without asking for permission for every detail.
- Ask the user only when local context and the spec are insufficient and a wrong assumption would be
  costly or hard to reverse.
- Be direct about residual risk, skipped verification, or parts that are intentionally not covered.
- If a review tool is available, use it before finalizing non-trivial code changes and address
  justified findings.
