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
5. When the user corrects an agent mistake, says something was not done the way they wanted, or
   explicitly asks for a durable preference to persist, update this file in the same change when
   reasonable so future sessions do not repeat the issue. Do not add new rules only because the agent
   independently chose a reasonable improvement.
6. When a new user preference changes how this file itself should be maintained, review existing
   agent-added rules for conflicts with that new preference in the same turn.
7. When fixing a documentation pattern, search the relevant file or docs set for similar instances
   before committing instead of correcting only the one sentence the user pointed out.
8. Run the relevant verification before finishing. For normal code changes, use:

   ```bash
   ./mvnw -q spotless:check verify
   ```

   Use `spotless:apply` before that when formatting changed.
9. Commit with Conventional Commits when asked to commit, and keep the working tree clean before
   claiming the work is done.
10. When the user asks for a concrete repo change, commit and push the completed change unless they
   explicitly ask not to.

## Design Preferences

- Keep complexity to the minimum that satisfies the spec and current use cases.
- Prefer existing project patterns and standard Quarkus/JDK APIs over new abstractions.
- Centralize connected constants and configuration values. Do not duplicate magic values with the
  same meaning in multiple places.
- Add comments only for non-obvious decisions, surprising constraints, or tradeoffs that would slow
  down a future maintainer.
- Use Java 25 LTS language/runtime features where they make the code clearer, but do not be clever
  for its own sake.
- Use the SDKMAN-managed Azul Zulu Java 25 LTS default for local work. Do not hardcode temporary JDK
  paths or prefix Maven commands with custom `JAVA_HOME`/`PATH` assignments; `java`, `javac`, and
  `./mvnw` should resolve through the shell environment.
- Prefer Java/JVM-based maintained project tooling over Python or other helper languages when the
  task can be handled cleanly in Java. Short shell snippets in documentation are fine for command
  orchestration, but committed reusable helpers should fit the repository's Java-first maintenance
  model unless there is a concrete reason not to.
- Do not use Perl/Python/Ruby/Node one-liners as the documented implementation of a reproducible
  project workflow when a small Java helper would keep the workflow easier to maintain in this repo.
- Keep the project open-source-ready even while private: clear README, usable CONTRIBUTING, useful
  ADRs, no committed secrets, and reviewable history.
- Lead the README with who the project is for, why they should use it, and the practical benefits
  before implementation details. Move technical mechanics into supporting sections once the value is
  clear.
- Use "Symphony for Trello" as the human-facing product name. Use `symphony-trello` only for
  technical identifiers such as artifact IDs, service names, application names, and other places
  where spaces or title case are not suitable.
- Put detailed workflow mechanics, such as process-to-workflow-to-board mapping, in the workflow
  contract section instead of the README opening.
- Write README prose in simple, direct language. Prefer short sentences and plain words over
  polished or elaborate wording.
- Do not document options or config fields by only restating their names. Explain what the value is
  used for, when the reader should change it, and how to choose a sensible value.
- Avoid unclear normalization jargon in code and docs. Prefer simple words such as "default",
  "resolved", "configured", or "official".
- For local Trello credentials, use ignored project-root `.env` files created from `.env.example`.
  Real environment variables still take precedence over `.env`. Do not print secret values in
  command output.
- Write user-facing docs so they can be read from top to bottom without confusion. Do not refer to a
  concept, path, command, or setup mode before introducing it.
- Use progressive disclosure in README setup flows: explain the simplest successful path first, and
  move optional modes, safety knobs, and advanced configuration into later sections.
- In prerequisites, name only tools the reader must install or provide. Do not list the Maven wrapper
  as a prerequisite when it is already committed. For command-line tools such as Codex, state exactly
  how the service finds them, such as `PATH` lookup or a configurable command path.
- Keep prerequisite lists simple. Put short install requirements in the list, then add one plain
  explanatory sentence when lookup, authentication, or configuration details matter.
- When referring to the local `codex` command in user setup docs, call it "Codex CLI" so readers do
  not confuse it with other Codex surfaces.
- For docs with multiple setup paths, read the flow once from each path's perspective and avoid
  wording that assumes the reader chose a different path.
- Put "who this path is for" guidance next to the commands for that path. Do not make readers
  remember an earlier decision list or scroll back to choose the right section.
- Do not add convenience links that encourage readers to skip required earlier steps. If a section
  depends on previous setup, structure the document as a linear flow instead of linking directly past
  the prerequisite.
- Prefer descriptive Markdown links over bare URLs in prose. In setup instructions, tell the reader
  what the linked page is for instead of placing multiple raw links next to each other.
- Avoid upfront reference dumps when the same links can be placed contextually in the step where the
  reader needs them.
- Prefer explaining intent, significance, and decision criteria over prescribing arbitrary defaults.
  Recommend concrete names or values only when the choice is semantically important.
- When suggesting user-visible names for external systems, include the integration or scope when it
  helps the user recognize what the entry is for later.
- When documenting third-party UI fields, use the current vendor label exactly when known instead of
  approximating it.
- For third-party authorization screens, describe the concrete labels and permissions the user sees,
  then connect them to the project need only when necessary.
- When describing third-party UI layout, prefer location wording observed in the current UI and avoid
  overly specific positions unless they help the reader find the control.
- Do not qualify setup instructions with "if possible" or similar hedges when the expected value is
  known.
- Keep setup steps focused on what the reader must do now. Avoid naming external documentation
  terminology or adding fallback-guide chatter unless it solves a likely problem in that step.
- Avoid repeating limitations already made clear by the surrounding setup context.
- For optional external docs in setup steps, prefer omitting the link over adding "if the page
  changed" text. Use a precise official link when it materially reduces ambiguity for the current
  action or replaces duplicated vendor-owned setup instructions that are better kept up to date by
  the vendor.
- In numbered setup instructions, each numbered item should be an action the reader performs at that
  point. Put explanatory context under the relevant action instead of creating a separate fake step.
  Avoid filler like "intentionally" unless it changes the reader's decision.

## Testing Preferences

- Follow the testing pyramid: fast focused unit tests first, broader integration tests where shared
  contracts or external boundaries are involved.
- Use JUnit 5 and AssertJ. Prefer readable AssertJ chains when they improve the failure message.
- Use Mockito for mocks. Keep purpose-built fakes only when they model an external protocol,
  stateful fixture, or concurrency behavior more clearly than Mockito stubbing.
- Prefer parameterized tests with `@MethodSource` for data-driven behavior.
- Structure unit tests with `// given`, `// when`, and `// then` sections separated by blank lines.
- Test names and assertion descriptions should make failures actionable without requiring a debug
  session.
- Do not write low-value tests that only restate a constant. Do test parsing, policy enforcement,
  edge cases, failure modes, and cross-component contracts.
- Do not add tests whose only purpose is to exercise POJOs, records, getters, setters, or generated
  accessors without logic. Coverage should come from meaningful behavior.
- When reporting live E2E results, state which external systems were real and which parts used test
  doubles. Do not imply that real Codex completed a path when only deterministic fake Codex completed
  it against real Trello.
- For live E2E, run the deterministic fake-Codex phase before real Codex. Then run strict real-Codex
  checks against real Trello with fresh cards and wait for both Trello handoff completion and
  `/api/v1/state` returning to zero running/retrying before claiming real-Codex coverage.
- Live import-board coverage must include at least one genuinely non-default existing board
  structure, not only a Symphony-generated board imported back again. Use explicit non-default
  active and terminal list names, then verify fake-Codex first and strict real-Codex after.
- When reporting test coverage gaps, do not list a redundant missing check if an existing check is
  already close enough for the risk the user asked about. Explain what the existing check covers.

## Java Style

- Let Spotless handle formatting and import cleanup.
- Use imports instead of inline fully qualified type names. Write `Arrays.stream(...)`, not
  `java.util.Arrays.stream(...)`. The test suite enforces this.
- Keep code ASCII unless an existing file or domain requirement clearly needs Unicode.
- Avoid unrelated metadata churn and broad rewrites.

## Specification And ADR Discipline

- Before making code changes, check the relevant `SPEC.md` contract. If the requested change would
  break or narrow conformance to the adapted Symphony-for-Trello spec or the original upstream
  Symphony intent, stop and ask the user for explicit confirmation before implementing it.
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
