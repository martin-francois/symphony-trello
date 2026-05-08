# Agent Operating Instructions

These instructions are repository-local and apply to Codex or any other AI agent working in this
checkout. Follow them in addition to higher-priority system, developer, and user instructions.

## Engineering Identity

Work as a senior Quarkus backend engineer implementing a Java 25 LTS and Maven 3 service from
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
8. When you notice a potential improvement that is outside the current task scope, keep the current
   work focused and create or suggest a GitHub issue instead of adding "future improvement",
   "convenience gap", or similar sections to user-facing documentation.
9. Redact private project names, host paths, Trello card ids, short links, account names, and similar
   internals from committed files, GitHub issues, and user-facing summaries unless the user
   explicitly asks to preserve them. Keep only the minimum technical detail needed to reproduce or
   understand the issue.
10. Run the relevant verification before finishing. For normal code changes, use:

   ```bash
   ./mvnw -q spotless:check verify
   ```

   Use `spotless:apply` before that when formatting changed.
11. Commit with Conventional Commits for this repository when asked to commit, and keep the working
   tree clean before claiming the work is done. When an agent creates commits inside another target
   repository for a Trello card, follow that repository's documented commit convention first. If it
   has no documented convention, infer from the last 20 to 50 commits on the default branch. If the
   repository has no commits, only one commit, no reachable default-branch history, or mixed recent
   styles, default to Conventional Commits.
12. When the user asks for a concrete repo change, commit and push the completed change unless they
   explicitly ask not to.
13. When a change affects runtime behavior and the user is likely to verify it manually afterward,
   deploy it with the Ansible workflow before finishing when the deployment inputs are available.
   If unsure, ask at the end whether to deploy with Ansible next. Do not deploy or suggest
   deployment for docs-only changes.
14. When fixing a bug that was observed during live deployed execution, and live verification is
   reasonably possible from the current environment, deploy with Ansible and perform the relevant
   live deployed verification before claiming the fix is complete.

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
- Prefer pnpm for Node-based tooling in this repository. Use Corepack-pinned pnpm in CI instead of
  adding a `package.json` solely to run a JavaScript CLI in this Java project.
- When pinning tool versions outside their native manifest files, ensure Renovate can update them
  through an existing manager or an explicit custom manager.
- Prefer ArchUnit for architecture rules that can be checked from compiled classes. Do not add
  Checkstyle for this repository. Add PMD only for narrow source rules that Spotless and ArchUnit
  cannot enforce cleanly, and avoid broad PMD rulesets that create noisy or low-value findings.
- Keep automation config minimal. Do not restate inherited defaults or duplicate global Renovate
  policy in package rules unless the narrower rule changes behavior.
- Pin GitHub Actions to full commit SHAs with the tracked version tag in a comment. Renovate may
  automerge non-major GitHub Actions updates after the configured release-age delay, but major
  action updates still need human review.
- When configuring build-tool integrations such as Java agents, prefer the official documented
  Maven pattern over hand-built local repository paths or other brittle shortcuts.
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
- Use **connect/disconnect/manage** for the relationship between Symphony and Trello boards in
  user-facing docs, CLI output, generated workflow prompts, GitHub issues, and tests. Use
  **connect** / **disconnect** for setup and configuration actions: users connect a Trello board to
  Symphony or disconnect a Trello board from Symphony. Use **manage** / **managing** for runtime
  behavior: Symphony manages connected Trello boards by reading cards/comments and performing
  configured actions such as moving cards, adding comments, creating/updating pull requests, and
  merging when configured. Example option labels: `Keep connected Trello boards`, `Connect another
  Trello board`, and `Disconnect a Trello board from Symphony`.
  When a word refers to any Trello entity and could be ambiguous, qualify it with `Trello`. This is
  a general rule, not a fixed list: apply it to boards, lists, cards, comments, labels, members,
  checklists, attachments, actions, or any other Trello API/domain entity whenever context alone does
  not clearly identify Trello. For example, use `Trello comment` if `comment` could mean a GitHub PR
  comment, GitHub issue comment, Trello comment, or code comment; use `Trello list` if `list` could
  mean a Java `List<>`, a generic collection, or a Trello list.
  Do not use "watch", "stop watching", "use", or "stop using" for this Trello-board relationship;
  "watch" is still fine for unrelated technical behavior such as watching files, logs, status
  pages, or retrying cards.
- Use `ch.fmartin.symphony.trello` as the Java package root and Maven group ID. Do not use
  `com.openai...` for implementation namespaces because this project is an adapted variant, not an
  official OpenAI implementation.
- Put detailed workflow mechanics, such as process-to-workflow-to-board mapping, in the workflow
  contract section instead of the README opening.
- Write README prose in simple, direct language. Prefer short sentences and plain words over
  polished or elaborate wording.
- Avoid redundant clarifications that create false significance. If a sentence already states the
  point clearly, do not add a follow-up such as "not only X", "meaning...", or "in other words..."
  unless it removes a real ambiguity, adds an exception, or changes the action the reader should
  take. When revising text based on feedback, preserve the intended scope. Do not carry over an old
  narrow example or introduce a new limiting qualifier unless that limit is intentional. If the
  intended scope is unclear, ask a clarifying question and offer likely scope options before writing
  the final text.
- Do not document options or config fields by only restating their names. Explain what the value is
  used for, when the reader should change it, and how to choose a sensible value.
- For CLI commands, follow the principle of least surprise: avoid silent no-ops, print actionable
  success/error output, and prefer preserving existing user files with a clear alternate path over
  making repeated setup commands appear to do nothing.
- Avoid unclear normalization jargon in code and docs. Prefer simple words such as "default",
  "resolved", "configured", or "official".
- For local Trello credentials, use ignored project-root `.env` files created from `.env.example`.
  Real environment variables still take precedence over `.env`. Do not print secret values in
  command output.
- Write user-facing docs so they can be read from top to bottom without confusion. Do not refer to a
  concept, path, command, or setup mode before introducing it.
- Use progressive disclosure in README setup flows: explain the simplest successful path first, and
  move optional modes, safety knobs, and advanced configuration into later sections.
- Keep deployment instructions focused on operating a prepared version. Do not put contributor or CI
  verification commands such as formatting, linting, or full test runs into deployment steps.
- In prerequisites, name only tools the reader must install or provide. Do not list the Maven wrapper
  as a prerequisite when it is already committed. For command-line tools such as Codex, state exactly
  how the service finds them, such as `PATH` lookup or a configurable command path.
- Keep prerequisite lists simple. Put short install requirements in the list, then add one plain
  explanatory sentence when lookup, authentication, or configuration details matter.
- When referring to the local `codex` command in user setup docs, call it "Codex CLI" so readers do
  not confuse it with other Codex surfaces.
- In user-facing docs, CLI text, and generated workflow prompts, call visible Trello board lanes
  "lists". On first use in the README, briefly explain that a Trello list is the board column that
  contains cards, for example `Ready for Codex` or `In Progress`.
- Use Trello's UI term "archived" in prose for archived cards, lists, and boards. Use
  `closed` only when naming Trello REST API fields or parameters, and explain nearby that Trello's
  API uses `closed` for archived resources. Distinguish archived lists from deleted lists; Trello
  requires a list to be archived before it can be permanently deleted.
- For deployment auth, prefer reusing the existing Codex CLI auth file from `codex login`. Do not
  steer users toward configuring raw OpenAI API keys unless they explicitly ask for that mode.
- For repository-changing work, generated workflows and PR publishing instructions should create
  ready-for-review, non-draft pull requests by default. Use a draft PR only when the Trello card
  explicitly asks for a draft PR.
- For PR-bound commits, generated workflows and commit instructions should reuse a checkout-local
  Git author only when the author is complete and marked as verified for this workflow. Only call
  GitHub APIs again when that verified author config is missing or incomplete.
- When generated workflows reference Symphony's `.codex/skills`, make sure deployed per-card
  workspaces receive the namespaced shipped skills after workspace sync hooks and before Codex
  starts. Do not rely on the target repository containing Symphony-specific skill files, and do not
  dirty checkout-root Git status with shipped skill files.
- Keep legacy workflows compatible: install workspace-local shipped skills only when the rendered
  prompt references the namespaced Symphony skill paths, so older workflows that clone into an empty
  workspace root stay empty until Codex runs.
- For deployment filesystem access, describe the concept as "allowed host paths". Use
  `symphony_trello_allowed_host_paths` for Ansible and treat
  `symphony_trello_allowed_project_roots` as a compatibility alias only. The allowed entries can be
  multiple files or folders; do not imply they must be repository or project roots. Explain that
  undeclared host paths are blocked by default for security reasons so Trello cards cannot make Codex
  read or edit unrelated files. Blocker comments for filesystem access must name the inaccessible
  path, explain the security default, and point to the exact manual or Ansible setting that relaxes
  access.
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
- Use JUnit 6 and AssertJ. Prefer readable AssertJ chains when they improve the failure message.
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
- For live PR-author verification, inspect every commit in the resulting pull request through GitHub,
  not only the new commit Codex just created. A card is not proven fixed when an existing PR still
  contains earlier commits authored by a generic Codex identity.
- Do not call a live deployment healthy just because the systemd service is active. When a real
  workflow has active work, verify that Codex can authenticate, the work finishes or reaches an
  expected terminal state, Trello handoff happens when required, and `/api/v1/state` has no
  unexpected running or retrying entries.
- Live deployment verification loops must poll Trello comments and the card's current list on every
  pass, not only the local state endpoint. Use a fresh timestamp cutoff after each restart so old
  blocker comments do not fail a fixed run, but fail immediately on any new blocker, auth, or sandbox
  comment.
- Do not manually move a Trello card to prove an automatic workflow transition works. For transition
  bugs, fix the code or workflow, reproduce with a fresh card or freshly queued card, and verify that
  Symphony performs the move itself.
- For Trello in-progress routing, verify `max_concurrent_agents` against visible board state, not
  only `/api/v1/state`. When an in-progress list is configured, cards waiting for retry/backoff or
  blocked by concurrency should not remain in `In Progress`; that list should show cards currently
  worked by active Codex workers.
- For PR handoff behavior, do not treat every non-green CI state as blocked. If CI fails because of
  the card's changes or current branch, the card should stay active while Codex reruns the failing
  check or closest local equivalent and fixes reproducible failures. If the related CI failure passes
  locally and looks flaky after a reasonable refresh or rerun, handoff to human review is acceptable
  with a Trello caveat. If CI is pending or stale, the card should stay active while Codex waits,
  refreshes, or reruns checks. If CI is unavailable because of external quota/infrastructure limits,
  Codex must run equivalent local CI checks before handoff. If CI fails for a clearly unrelated
  reason, Codex should not spend time reproducing that unrelated failure locally; handoff is
  acceptable when card-specific validation and related checks are clean and the Trello comment
  records why the failure is unrelated.
- For live E2E, run the deterministic fake-Codex phase before real Codex. Then run strict real-Codex
  checks against real Trello with fresh cards and wait for both Trello handoff completion and
  `/api/v1/state` returning to zero running/retrying before claiming real-Codex coverage.
- Live import-board coverage must include at least one genuinely non-default existing board
  structure, not only a Symphony-generated board imported back again. Use explicit non-default
  active and terminal list names, then verify fake-Codex first and strict real-Codex after.
- When a live run uncovers a broken scenario after an earlier health claim, record the reproducible
  scenario in `docs/live-e2e.md` and create or update GitHub issues before fixing behavior. Capture
  both the user-visible symptoms and the underlying cause when they are distinct. Keep the broken
  live state available when the user asks to reproduce it before a fix.
- When reporting test coverage gaps, do not list a redundant missing check if an existing check is
  already close enough for the risk the user asked about. Explain what the existing check covers.

## Java Style

- Let Spotless handle formatting and import cleanup.
- Use imports instead of inline fully qualified type names. Write `Arrays.stream(...)`, not
  `java.util.Arrays.stream(...)`. PMD enforces this with the narrow
  `UnnecessaryFullyQualifiedName` rule.
- Keep code ASCII unless an existing file or domain requirement clearly needs Unicode.
- Avoid unrelated metadata churn and broad rewrites.

## Specification And ADR Discipline

- Before making code changes, check the relevant `SPEC.md` contract. If the requested change would
  break or narrow conformance to the adapted Symphony-for-Trello spec or the original upstream
  Symphony intent, stop and ask the user for explicit confirmation before implementing it.
- When the spec is ambiguous, compare plausible options and choose the least surprising option that
  preserves conformance.
- If a decision explains why an obvious alternative was not chosen, record it in `docs/adr/` using
  the official MADR template structure: YAML front matter with `status`, `date`,
  `decision-makers`, `consulted`, and `informed`; then `Context and Problem Statement`, `Decision
  Drivers`, `Considered Options`, `Decision Outcome`, `Consequences`, `Confirmation`,
  `Pros and Cons of the Options`, and `More Information` when relevant. Fill the sections with
  concrete project-specific content; do not leave template placeholders or write a short free-form
  note.
- When adding or updating ADRs, document the options that were seriously considered, why the chosen
  option won, what becomes easier, what becomes worse, and how future maintainers can confirm the
  decision is still implemented.
- When a session contains multiple durable design decisions, review the recent conversation and
  commit history before finishing so relevant decisions are captured in ADRs instead of living only
  in chat.
- Run markdownlint when changing Markdown, especially ADRs. The CI lint job is based on MADR's
  markdownlint workflow and uses `.markdownlint-cli2.yaml`; do not disable rules inline unless the
  exception is narrow and justified.
- Do not silently reinterpret `SPEC.md`. If the implementation needs a clarified adaptation, update
  the spec wording carefully without breaking conformance to the original Symphony intent.
- When drafting or refining GitHub issues for behavior that `SPEC.md` leaves implementation-defined,
  describe the desired outcome and explicitly compare plausible implementation layers. Do not assume
  the Java scheduler/service must own behavior that may fit better in generated workflow text,
  repository-local skills, or scoped agent tools.
- When adding or changing behavior that extends beyond `SPEC.md` but does not conflict with it,
  append the extension contract to `SPEC.md` in the same change. Keep implementation-specific
  extensions clearly labeled as optional or Java implementation extensions so the core adapted
  Symphony contract stays readable.
- When asked to audit specification alignment, compare `SPEC.md` with the current conversation,
  ADRs, README, generated workflow, skills, deployment docs, and implementation in cycles. In each
  cycle, update `SPEC.md` for any supported behavior or optional extension that is missing from the
  contract. Stop only after a full pass finds nothing else to add. If the implementation violates
  the updated spec, create or update a GitHub issue with the exact mismatch, affected behavior, and
  acceptance criteria instead of silently changing the contract to fit the code.

## Autonomy And Escalation

- Make reasonable implementation decisions without asking for permission for every detail.
- Ask the user only when local context and the spec are insufficient and a wrong assumption would be
  costly or hard to reverse.
- Be direct about residual risk, skipped verification, or parts that are intentionally not covered.
- If a review tool is available, use it before finalizing non-trivial code changes and address
  justified findings.
