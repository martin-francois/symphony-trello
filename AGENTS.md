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
2. If this checkout contains `tessl.json` and the `tessl` command is available, run `tessl install`
   from the repository root before contribution work so generated agent links and rules are fresh.
   If `tessl` is not available, say so briefly and continue by following `AGENTS.md`,
   `CONTRIBUTING.md`, `AI_CONTRIBUTION_POLICY.md`, the issue/PR templates, and the vendored
   `tessl-labs/good-oss-citizen` rules at
   `.tessl/tiles/tessl-labs/good-oss-citizen/rules/good-oss-citizen.md` directly.
3. Prefer TDD when the behavior can be isolated. If TDD is impractical, make sure the final tests
   would have failed for the bug or missing behavior.
4. Keep changes narrowly scoped. Each branch or pull request should cover one cohesive change. If a
   feature or bug fix needs directly related cleanup or refactoring to make that change correct or
   maintainable, keep the cleanup/refactoring focused on that change. Put unrelated cleanup,
   refactoring, formatting, dependency updates, or tooling changes in a separate issue, branch, or
   pull request.
5. Update documentation and ADRs when behavior, setup, architecture, or tradeoffs change.
6. When the user states a generally useful working preference, corrects an agent mistake, says
   something was not done the way they wanted, or explicitly asks for a durable preference to persist,
   update this file in the same change when reasonable so future sessions do not repeat the issue. If
   the user explicitly scopes the instruction to the current session, do not make it durable. Do not
   add new rules only because the agent independently chose a reasonable improvement. If the
   correction is about a concrete preference, add or update the concrete rule for that preference; do
   not replace it with only a generic process reminder. When the correction is about a repeatable
   code, documentation, issue-triage, PR, ADR, or workflow pattern, treat the durable update as part
   of the fix: make the immediate correction, add or update the specific rule that would have
   prevented it, and check nearby rules for conflicts before finishing.
7. When a new user preference changes how this file itself should be maintained, review existing
   agent-added rules for conflicts with that new preference in the same turn.
8. When fixing a documentation pattern, search the relevant file or docs set for similar instances
   before committing instead of correcting only the one sentence the user pointed out.
9. When a catch block intentionally ignores an exception or falls back, make that policy explicit.
   Prefer a small helper whose name states the fallback, a narrow debug log when the failure is useful
   diagnostic context, or tests/spec wording that prove the quiet fallback is intentional. Do not
   leave comment-only or empty catch bodies in production code, and do not add noisy logs for
   expected optional data, spec-defined parse skips, non-POSIX filesystem behavior, or best-effort
   diagnostics.
10. When the user or a review comment identifies a concrete mistake or maintainability pattern, check
   for similar occurrences before finishing. Fix matching cases that are in the current branch scope
   or directly touched by the change. For matching cases outside scope, create or suggest a focused
   follow-up issue instead of silently leaving them for rediscovery.
   In the final response, state the related files or pattern set you checked, what you changed, and
   whether a follow-up issue was created or intentionally not created. This is required even when the
   direct fix is small.
11. When you notice a potential improvement that is outside the current task scope, keep the current
   work focused and create or suggest a GitHub issue instead of adding "future improvement",
   "convenience gap", or similar sections to user-facing documentation.
12. Redact private project names, host paths, Trello card ids, short links, account names, and similar
   internals from committed files, GitHub issues, and user-facing summaries unless the user
   explicitly asks to preserve them. Keep only the minimum technical detail needed to reproduce or
   understand the issue.
13. Run the relevant verification before finishing. For normal code changes, use:

   ```bash
   ./mvnw -q spotless:check verify
   ```

   Use `spotless:apply` before that when formatting changed.
   When a change needs PowerShell verification and local `pwsh` is unavailable, use
   `./scripts/pwsh-docker.sh` for the PowerShell install/uninstall checks and set
   `SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh` for Java tests that support a configurable
   PowerShell command. Do not report PowerShell as skipped only because `pwsh` is missing if Docker
   is available.
14. Commit with Conventional Commits for this repository when asked to commit, and keep the working
   tree clean before claiming the work is done. When an agent creates commits inside another target
   repository for a Trello card, follow that repository's documented commit convention first. If it
   has no documented convention, infer from the last 20 to 50 commits on the default branch. If the
   repository has no commits, only one commit, no reachable default-branch history, or mixed recent
   styles, default to Conventional Commits.
   For feature-branch work in this repository, preserve a single review commit by amending the
   existing branch commit or squashing related local commits before pushing, unless the user
   explicitly asks for multiple commits.
   If the user explicitly wants a multi-commit pull request, keep each commit focused and
   Conventional Commit titled. Use one commit for the user-visible feature or fix and separate
   commits only for directly supporting cleanup or refactoring that belongs to the same cohesive
   change; unrelated work still belongs in a separate branch or pull request.
   When one pull request intentionally covers multiple GitHub issues, keep at least one focused
   commit per issue so each issue has its own reviewable unit. Combine multiple issues in one pull
   request only when the issues are cohesive or merging them together is materially easier. A single
   issue may still use multiple focused commits when requested or when that improves review, but do
   not collapse separate issue work into one mixed commit unless the user explicitly asks.
   When the user asks to address another round of review comments on an existing pull request and
   wants an easy review delta, first squash the already-pushed PR commits into one base commit when
   they ask for that, then make the new review-response changes in a separate follow-up commit. Run
   the Codex review/fix loop before pushing, reply to the handled review threads, and push the
   updated branch.
   Keep `feat/issue-35-plan-b-onboarding` as a single commit on top of `main`; amend or squash and
   force-push when changing that branch.
   The pull request title is linted in CI because the repository normally squash-merges with that
   title. CI also lints pull request commit messages so intentionally multi-commit or rebase-merged
   PRs keep release automation input clean. Before publishing a PR, run the same local check with
   the exact title:

   ```bash
   printf '%s\n' 'docs: describe static-analysis policy' | pnpm dlx --package @commitlint/cli@21.0.1 --package @commitlint/config-conventional@21.0.1 commitlint --config commitlint.config.cjs
   ```

   For a PR that may be rebase-merged or intentionally keeps multiple commits, also lint the commit
   range:

   ```bash
   pnpm dlx --package @commitlint/cli@21.0.1 --package @commitlint/config-conventional@21.0.1 commitlint --config commitlint.config.cjs --from origin/main --to HEAD --verbose
   ```
15. When the user asks for a concrete repo change, commit and push the completed change unless they
   explicitly ask not to.
16. When a change affects runtime behavior and the user is likely to verify it manually afterward,
   ask at the end whether to run the manual deployment verification next when deployment inputs are
   available. Do not deploy or suggest deployment for docs-only changes.
17. When fixing a bug that was observed during live deployed execution, and live verification is
   reasonably possible from the current environment, perform the relevant live deployed verification
   before claiming the fix is complete.
18. When the user provides a GitHub issue that was automatically posted from a local setup failure,
   first debug from the issue as an outside maintainer would. Decide whether the sanitized issue body
   has enough information to identify the root cause. If it does not, improve the diagnostics or
   issue-posting data while keeping secrets, private paths, Trello identifiers, account names, and
   host-specific details redacted. Reproduce locally when feasible, fix the underlying issue once
   enough information is available, add regression coverage, and update expected-vs-unexpected setup
   failure classification when the failure was misclassified.

## Design Preferences

- Keep complexity to the minimum that satisfies the spec and current use cases.
- Prefer existing project patterns and standard Quarkus/JDK APIs over new abstractions.
- Centralize connected constants and configuration values. Do not duplicate magic values with the
  same meaning in multiple places. More generally, when separate code, docs, test data, generated
  templates, constants, literals, or other artifacts represent one concept and a maintainer would
  expect them to change together, centralize or derive them from one shared source in the narrowest
  sensible scope. Strings and numbers are common examples, but the rule is about coupled change, not
  literal type or textual equality. Do not centralize independent values only because they happen to
  look the same today; leave them separate when they represent different concepts that could
  reasonably change independently. When duplicated logic would need to change together at multiple
  call sites, extract a shared helper or abstraction so the coupling is explicit and future
  contributors only have one place to update.
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
  Checkstyle for this repository. Use PMD as a curated source-level analyzer for correctness,
  security, performance, duplication, and maintainability rules that complement Spotless, ArchUnit,
  tests, and other static analyzers. Do not import broad PMD categories or third-party PMD rulesets
  into the blocking gate without first measuring findings against this repository.
- Use static analysis as a local, deterministic agent feedback loop. Fix findings when reasonable,
  rerun the analyzer, rerun the relevant build or test command, and keep changes scoped to the
  current issue. New static-analysis rules should start in report-only, candidate, or otherwise
  non-blocking mode until baseline findings are understood and useful.
- Apply the same triage policy to every static-analysis tool, including PMD, CPD, SpotBugs,
  FindSecBugs, Error Prone, Picnic Error Prone Support, Semgrep, CodeQL, linters, and dependency
  analyzers. Do not call a rule noisy only because it reports many findings. A finding is justified
  when fixing it would make the code meaningfully better, cleaner, safer, faster, or more
  maintainable. Code compiling successfully does not by itself make a supplementary
  static-analysis finding unjustified. A rule is noisy only when representative findings are false
  positives or already cleaner to leave as they are. A large diff is acceptable when the resulting
  code is meaningfully better. High counts of justified findings should become staged cleanup work
  or a candidate profile, not a noisy-rule classification.
- Do not treat a report-only or candidate static-analysis profile as finished while it still
  contains known justified findings. If the current branch cannot fix every finding, make the
  remaining work explicit in GitHub issues before finishing and link every follow-up issue from the
  relevant meta or tracking issue. Keep that meta issue complete and current until the final end
  state is reached: every useful non-noisy rule is enforced by `./mvnw -q spotless:check verify`,
  every justified finding is fixed, every true false positive has a targeted suppression with a
  reason, and deferred or rejected rules have documented rationale. Implementing the meta issue
  should be sufficient to reach the stated static-analysis end state without rediscovering hidden
  follow-up work.
- Handle static-analysis findings in this order: fix justified findings; tune the rule if it is
  valid but too broad; suppress false positives with the narrowest possible scope; include a reason
  for every suppression. Do not disable a whole analyzer, package, source tree, or rule category only
  to make a check pass. When describing false-positive evidence, tie the evidence to the rule's
  semantics. For example, successful compilation is relevant evidence for a type-resolution rule
  that reports an unresolved type, but it is not relevant evidence against most supplementary
  maintainability, correctness, security, performance, or style findings. Exclude generated code
  only when the affected path is actually generated, vendored, or otherwise outside the intended
  analysis scope. Hosted dashboards may add signal, but they must not replace local checks that an
  agent can run, fix, and rerun.
- For PMD, prefer fixing or rule tuning. Use `@SuppressWarnings("PMD.RuleName")` for code-local
  suppressions, `// NOPMD - reason` only for truly line-local cases, and ruleset-level suppression
  only when a repeated false positive can be described precisely. Consider PMD's unnecessary
  suppression checks when practical so stale suppressions are caught.
- For SpotBugs and FindSecBugs, prefer fixing findings. Use `config/spotbugs/exclude.xml` for
  project-level false positives and `@SuppressFBWarnings(value = "...", justification = "...")`
  only when the exception belongs next to the code. Keep filter entries precise by bug pattern,
  class, method, or field, and do not suppress broad packages or all security findings.
- For Error Prone and Picnic Error Prone Support, start in a non-blocking profile until build
  compatibility and baseline findings are understood. Prefer generated or in-place patches for
  mechanical fixes, promote checks from warning to error only after baseline cleanup, use stable
  `-Xep:<CheckName>:OFF|WARN|ERROR` flags for rule control, and keep rewrite/fix profiles explicit
  so normal verification does not unexpectedly modify source files. The current optional Error Prone
  pass is `./mvnw -Perror-prone clean compile`; it is not part of normal
  `./mvnw -q spotless:check verify`. Do not run the optional Error Prone command with Maven `-q`
  because this profile reports findings as warnings while the baseline is evaluated.
- For Semgrep, use custom rules for cross-language guardrails and security patterns that are not
  already covered by specialized linters. Prefer fixing findings, use rule-specific `nosemgrep`
  comments only with a reason, use `.semgrepignore` only for generated, vendored, or irrelevant
  paths, and run private-repository local checks with `--metrics=off`.
- Treat CodeQL as a later public-repository code-scanning layer. Keep local Maven-based checks as
  the primary agent feedback loop, and do not require CodeQL as part of normal local `verify`.
- Keep automation config minimal. Do not restate inherited defaults or duplicate global Renovate
  policy in package rules unless the narrower rule changes behavior.
- Pin GitHub Actions to full commit SHAs with the tracked version tag in a comment. Renovate may
  automerge non-major GitHub Actions updates after the configured release-age delay, but major
  action updates still need human review.
- In contributor-facing documentation, refer to changelog/version/tag tooling as "release
  automation" unless the concrete tool name is necessary for a command, filename, workflow name, or
  maintainer-only implementation detail.
- When configuring build-tool integrations such as Java agents, prefer the official documented
  Maven pattern over hand-built local repository paths or other brittle shortcuts.
- Do not use Perl/Python/Ruby/Node one-liners as the documented implementation of a reproducible
  project workflow when a small Java helper would keep the workflow easier to maintain in this repo.
- Keep the project open-source-ready even while private: clear README, usable CONTRIBUTING, useful
  ADRs, no committed secrets, and reviewable history.
- Lead the README with who the project is for, why they should use it, and the practical benefits
  before implementation details. Move technical mechanics into supporting sections once the value is
  clear.
- Keep README content focused on users and operators who want to evaluate, install, configure, run,
  troubleshoot, or deploy Symphony for Trello. Move contributor-only content such as source-checkout
  development runs, build/test commands, CI details, coding standards, and PR process rules to
  `CONTRIBUTING.md`, `AGENTS.md`, or focused developer docs. Before adding README content, ask
  whether a first-time user or operator needs it to succeed with the product; if not, put it
  elsewhere.
- In human contributor docs and GitHub templates, keep AI-agent-only guidance at the bottom after
  sections that apply to all contributors, including security and maintainer process sections. Do not
  put AI-only setup instructions in the middle where a human reader might assume the remaining
  document is only for agents and stop reading.
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
- Do not make users inspect workflow or config internals to recover from CLI errors. When the
  program knows the relevant command, path, option, or environment variable names for this run,
  print those exact values in the next step instead of saying that they are "referenced by the
  workflow", "configured locally", or similar.
- When user-facing text prints a path, command, URL, file name, environment variable, or other value
  the user may copy, do not put sentence punctuation immediately after it. Put the value in
  backticks, on its own line, or before explanatory text so punctuation cannot be mistaken as part of
  the value.
- When investigating local setup, diagnostics, worker, or board-routing failures on a machine where
  the installed command is available, use `symphony-trello diagnostics` for the public-safe overview.
  If the failure depends on information the default report intentionally omits, run
  `symphony-trello diagnostics --deep` to add deeper public-safe checks such as Codex and GitHub
  auth-status probes. If the sanitized report uses `board_hash`, `key_hash`, or `<path:...>` tokens
  and you need to map them back to the real local board, workflow, env file, workspace, state
  directory, or log file, run `symphony-trello diagnostics --show-private-context` locally,
  optionally with `--board` or `--workflow` to narrow the scope. Treat that output as private
  investigation data: do not paste it into GitHub issues, Trello comments, PR descriptions,
  committed files, or final user summaries. Use it to decide what to inspect or fix, then report
  only sanitized conclusions unless the user explicitly asks for local private values.
- When adding setup, onboarding, installer, or lifecycle failure codes, decide whether the failure
  is expected or unexpected while designing the failure path. Treat it as expected when the user can
  fix it from the command output without a maintainer, such as invalid CLI shape, missing required
  input, declined setup action, missing credentials, missing login, missing prerequisite, ambiguous
  selector, or a known external authorization/permission/not-found response. Expected failures
  should print an actionable error or next step and should not create diagnostics or GitHub issue
  reports by default. Implement expected setup failures as `TrelloBoardSetupException` codes listed
  in `SetupDiagnosticReporter.EXPECTED_SETUP_FAILURE_CODES`; add a `userActionHint(...)` case only
  when the exception message itself does not already contain the exact recovery command. Treat a
  failure as unexpected when it suggests a bug, unsafe state, tool/runtime failure, unreadable
  generated state, failed workflow read/write, failed worker start/stop/health, transport failure,
  rate limit, server error, malformed external payload, or any condition where a maintainer needs
  sanitized system/workflow/log context to debug it. Unexpected setup failures should not be added
  to `EXPECTED_SETUP_FAILURE_CODES`, so `SetupDiagnosticReporter.shouldReport(...)` can create a
  sanitized report. When an apparently unexpected boundary failure wraps a known user-correctable
  cause, such as local worker startup logs showing Trello authentication failure, map it to the
  specific expected setup code before it reaches the CLI. If an unexpected exception message could
  contain private paths, tokens, Trello identifiers, account names, or raw external payloads, wrap it
  before it reaches the CLI boundary with a safe message and preserve the original cause. Tests must
  prove expected failures do not write troubleshooting reports, unexpected failures still do, and any
  printed next step is not misleading.
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
- For installer/onboarding changes, do not rely on syntax checks or dry runs alone. Add or update
  deterministic lifecycle coverage that drives prompts through a real pseudo-terminal when prompts
  exist, uses test doubles for external tools and services, verifies install/update/start/status
  behavior, and proves uninstall removes installer-managed files and state without external side
  effects.
- When changing installer, updater, onboarding, or uninstaller output, review the text from a
  first-time user's perspective before committing. Make dry-run output name the same major actions
  as the real run, distinguish files that will be removed from auth/data that will be preserved,
  avoid labels that can be mistaken for commands to execute, avoid internal shorthand in prompts,
  show exact paths for this run when deletion is possible, and keep follow-up instructions to one
  clear next action.
- For installer/onboarding UX, prefer the smooth default path over extra confirmation prompts when
  the action has a clear opt-out or safe fallback. Do not add prompts only to make a choice feel more
  explicit. If the choice follows another mature OSS installer precedent, document that precedent and
  the tradeoff in an ADR.
- Do not add positive installer or CLI flags that only restate default behavior. Before adding a
  positive flag, check whether the command behaves differently with the flag than without it. If the
  flag does not change behavior, keep the default behavior implicit and expose only the opt-out flag
  or real alternate mode. A positive flag is appropriate only when it selects a distinct behavior,
  resolves ambiguity, preserves needed compatibility, or is part of a real multi-mode choice.
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
- Use "connect" and "disconnect" for setup/configuration actions between Symphony and Trello
  boards. Use "manage" or "managing" for runtime behavior. Avoid "watch", "stop watching", "use",
  and "stop using" for the Symphony/Trello relationship. When a Trello entity word could be
  ambiguous, qualify it, for example `Trello comment`, `Trello list`, or `Trello card`.
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
- For deployment filesystem access, describe the concept as "allowed host paths". The allowed
  entries can be multiple files or folders; do not imply they must be repository or project roots.
  Explain that undeclared host paths are blocked by default for security reasons so Trello cards
  cannot make Codex read or edit unrelated files. Blocker comments for filesystem access must name
  the inaccessible path, explain the security default, and point to the exact manual systemd setting
  that relaxes access.
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
- When a bug is found, explicitly ask why the current suite missed it and add the smallest
  regression test that would have failed before the fix. Prefer writing that test before the fix
  when practical. If the fix already exists, temporarily revert or otherwise disable the fix when
  feasible to confirm the regression test fails for the original bug, then restore the fix and make
  the test pass.
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
- Prefer streams for real collection transformations and lookups when they make the code clearer
  than manual loop state. Do not replace a readable collection stream with a loop just to avoid an
  `Optional`, especially when the loop needs labeled `continue`, mutable sentinel flags beyond the
  natural state of the algorithm, or duplicated branch flow.
- Avoid Optional-as-null-control-flow. Do not use `optional.isPresent()` followed by
  `optional.get()`, `optional.orElseThrow()`, or equivalent value reads, and do not convert an
  `Optional` to nullable state with `optional.orElse(null)` just to branch on `value != null`. Do
  not convert an `Optional` to a one-element collection with `optional.stream().toList()` just to
  enter a loop or return early. This does not forbid streaming real collections and ending with an
  Optional-returning terminal operation when the source may contain many values. Use `findFirst()`
  only when encounter order is part of the required behavior; use `findAny()` when any matching
  value is equivalent. Before adding or keeping `findFirst()`, temporarily switch that specific
  lookup to `findAny()` and run the narrow relevant test. If the test does not fail but encounter
  order is still semantically required, add or update a realistic test that asserts the first-match
  contract, then keep `findFirst()`. If no realistic order-dependent scenario exists, use
  `findAny()` instead. In those cases, still keep the resulting `Optional` as the control-flow
  boundary with whichever `Optional` API expresses the intent most clearly, such as `map`,
  `flatMap`, `filter`, `or`, `orElse`, `orElseGet`, `orElseThrow`, `ifPresent`, or
  `ifPresentOrElse`; this is guidance, not a closed list. If the absent branch throws a checked
  exception or needs prompting, plain branching at that boundary is acceptable because the checked
  exception is part of the method's honest contract. Keep this exception narrow: use it only to
  choose between a present value and a checked-IO/prompting fallback, not as a general Optional
  style. Do not add a dependency or project-specific functional helper only to avoid this explicit
  branch unless the pattern becomes common enough to justify an ADR-level style decision.
  Keep `isPresent()` only when the boolean presence check is itself the clearest logic and the value
  is not immediately read.
- Use named HTTP status constants or focused helper methods for production HTTP status handling.
  Avoid raw status-code literals such as `200`, `404`, or `429` in production code when a named
  constant or helper makes the intent clearer. Tests and fake servers may use literals when the
  status itself is the scenario under test.
- Keep code ASCII unless an existing file or domain requirement clearly needs Unicode.
- Avoid unrelated metadata churn and broad rewrites.

## Specification And ADR Discipline

- Before making code changes, check the relevant `SPEC.md` contract. If the requested change would
  break or narrow conformance to the adapted Symphony-for-Trello spec or the original upstream
  Symphony intent, stop and ask the user for explicit confirmation before implementing it.
- Treat `SPEC.md` as the normative contract for this project. Keep spec-defined concepts as close
  as practical to the upstream Symphony specification, adapted only where Trello requires a
  different tracker model. The upstream reference implementation is useful evidence for intent and
  edge cases. A difference from it is acceptable when the Java behavior still follows `SPEC.md`, fits
  Trello, and is covered by project ADRs. Do not copy reference behavior merely because it exists.
- Use `SPEC.md` for normative behavior and compatibility requirements. Use ADRs to explain why a
  decision was made, which alternatives were considered, and how to confirm the decision still
  holds. When a decision changes required behavior, update both: `SPEC.md` for the contract and an
  ADR for the rationale. If `SPEC.md` and an ADR conflict, treat `SPEC.md` as authoritative and
  update the ADR or create an issue for the mismatch.
- If Symphony for Trello intentionally differs from the upstream Symphony specification for a reason
  other than Trello adaptation, update `SPEC.md` with the local contract and add or update an ADR
  that labels the decision as an intentional upstream divergence, explains why it is needed, and
  states what would need to change before realigning with upstream.
- When the spec is ambiguous, compare plausible options and choose the least surprising option that
  preserves conformance.
- If a decision explains why an obvious alternative was not chosen, record it in `docs/adr/` using
  the official MADR template structure: YAML front matter with `status`, `date`,
  `decision-makers`, `consulted`, and `informed`; then `Context and Problem Statement`, `Decision
  Drivers`, `Considered Options`, `Decision Outcome`, `Consequences`, `Confirmation`,
  `Pros and Cons of the Options`, and `More Information` when relevant. Fill the sections with
  concrete project-specific content; do not leave template placeholders or write a short free-form
  note.
- Create or update an ADR whenever the user and agent discuss multiple implementation options and
  decide on one, or whenever implementation work requires choosing between meaningful alternatives.
  The ADR must make the selected approach explicit, list the alternatives considered, and explain
  why they were not chosen. Do not leave these decisions only in chat, PR comments, or commit
  messages.
- When an implementation approach, refactor, dependency, tool, API shape, user-flow behavior, or
  testing strategy is attempted or seriously considered and then rejected for non-obvious reasons,
  create or update an ADR that records the rejected approach and why. The goal is to prevent future
  maintainers or agents from repeating attractive but unsuitable work and rediscovering the same
  problem.
- Before adding an ADR, inspect `docs/adr/` and use the next unused numeric prefix for the target
  branch. Do not create duplicate ADR numbers.
- Follow MADR status semantics. New decisions normally use `status: accepted`. When a later ADR
  supersedes an earlier accepted decision, update the earlier ADR status to
  `superseded by [ADR 0000](0000-short-name.md)` and add a short note near the top explaining what
  superseded it. Do not leave an ADR as simply `accepted` when the decision is no longer current.
- Write ADRs in simple, factual language. Avoid flowery wording, vague emphasis, and unnecessarily
  long sentences. Use words and sentence structures that are clear for non-native English speakers,
  while still following the MADR template exactly. Prefer clear, unambiguous user-manual style over
  polished prose; readers should not have to infer what the decision means.
- In ADRs, reference GitHub issues and pull requests with descriptive full Markdown links, not
  shorthand references. Use `[GitHub issue #6](https://github.com/martin-francois/symphony-trello/issues/6)`
  or `[GitHub PR #116](https://github.com/martin-francois/symphony-trello/pull/116)`.
  Apply the same format in ADR front matter such as `consulted`; quote Markdown links in YAML
  arrays. Do not use bare `#6`, `issue #6`, `PR #116`, or bare GitHub URLs in ADRs.
- When adding or updating ADRs, document the options that were seriously considered, why the chosen
  option won, what becomes easier, what becomes worse, and how future maintainers can confirm the
  decision is still implemented.
- When a conversation resolves a durable tradeoff that future maintainers would otherwise have to
  rediscover, add or update an ADR in the same change. Signals include choosing one reasonable
  approach over another, removing or narrowing a supported path, accepting a user-facing tradeoff, or
  explaining why an obvious alternative should not be used.
- When a session contains multiple durable design decisions, review the recent conversation and
  commit history before finishing so relevant decisions are captured in ADRs instead of living only
  in chat.
- Treat naming decisions for public CLI flags, commands, config fields, workflow fields, API fields,
  labels, or other user-visible contract terms as ADR-worthy when multiple plausible names were
  discussed or rejected. Capture the chosen name, rejected names, why each was rejected, and the
  decision criteria in the same change.
- Run markdownlint when changing Markdown, especially ADRs. The CI lint job is based on MADR's
  markdownlint workflow and uses `.markdownlint-cli2.yaml`; do not disable rules inline unless the
  exception is narrow and justified.
- Do not silently reinterpret `SPEC.md`. If the implementation needs a clarified adaptation, update
  the spec wording carefully without breaking conformance to the original Symphony intent.
- When drafting or refining GitHub issues for behavior that `SPEC.md` leaves implementation-defined,
  describe the desired outcome and explicitly compare plausible implementation layers. Do not assume
  the Java scheduler/service must own behavior that may fit better in generated workflow text,
  repository-local skills, or scoped agent tools.
- When creating a GitHub issue or changing an issue's scope, check the other open issues for hard
  ordering dependencies. Add dependencies bidirectionally using the exact headings
  `Must be implemented before:` and `Must be implemented after:` so both sides stay discoverable.
  Do not add loose related links as dependencies; use this only when one issue really must land
  before another.
- When an open issue has one or more unresolved `Must be implemented after:` dependencies, make sure
  it has the `blocked` label. When all issues listed under `Must be implemented after:` are closed,
  remove the `blocked` label so the issue queue reflects that it can be started.
- When the user asks for an issue triage audit, triage sweep, issue audit, or similar wording, audit
  all open issues, not only the examples named by the user. Read each open issue's title, body,
  labels, milestone, and relevant comments. Update issue bodies directly so they read as current and
  intentionally scoped, not as historical chat notes. Remove stale dependency wording once blockers
  are closed, add missing useful links, fix incorrect links, update labels and milestones, maintain
  bidirectional dependency lines, and add or remove `blocked` based on unresolved
  `Must be implemented after:` dependencies. Issue-to-issue relationship links must be
  bidirectional: if an open issue says it is related to, coordinates with, follows up from, or is a
  prerequisite for another open issue, the other issue should link back with the matching
  relationship unless the reference is only historical provenance for closed work. Prefer editing
  issue descriptions over adding comments unless a historical note or external artifact link must be
  preserved. Run the audit in cycles: after making changes, fetch the open issue set again and
  repeat the body/label/milestone/link/dependency review until one full pass finds nothing else to
  change. Summarize which issues were changed, how many cycles ran, and which issues were
  intentionally left unchanged.
- During issue triage, add `needs-human-review` when an issue cannot be implemented as written
  until a maintainer decision, owner-only repository action, external account/form submission,
  secret provisioning, external prerequisite, or explicit human review happens. Issues with
  `needs-human-review` are not ready for an implementer as written, so they must also have the
  `not-ready` label. Remove `needs-human-review` once the decision/action/prerequisite is captured
  in the issue and an implementer can proceed without asking the maintainer first; remove
  `not-ready` at the same time unless another not-ready reason remains. Do not use this label for
  ordinary technical decisions that the issue already asks the implementer to evaluate and document.
- During issue triage, keep labels aligned with implementability. Use `not-ready` when the issue is
  an idea/research note, lacks enough accepted scope to implement, or needs a prior non-dependency
  decision/action before work can start. Use `idea` only for speculative product or design options
  that are not ready to implement; every `idea` issue must also have `not-ready`. Do not add `idea`
  to already-scoped work that an implementer can start from the issue description, even when the
  feature was originally discussed as a possibility. Idea issue descriptions should ask interested
  users to upvote the issue so maintainers can judge demand before accepting or prioritizing the
  work. Use `help wanted` for open implementable issues except dependency dashboard issues and
  issues marked `not-ready`. Use `good first issue` only when a coding agent could be given only
  "implement this issue <url>" and, in one shot without an elaborate extra prompt, submit a PR that
  would need no maintainer PR comments and receive LGTM in roughly 80% of attempts. The same bar
  applies to a developer who is new to the repository. That means the issue must be small,
  well-scoped, low-risk, independent of unresolved decisions or external timing, and specific enough
  that the expected implementation is clear. Use
  `already-implemented` when the
  issue appears to describe behavior that already exists, but do not close it unless the user asks
  or the implementation is verified.
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

## GitHub Issue Triage

- When asked for an issue triage sweep, review open issues in cycles. In each cycle, update stale
  descriptions, labels, milestones, dependency links, and missing context directly. Stop only after a
  full pass finds nothing else worth changing.
- Use `needs-human-review` only when the issue is missing a human decision, required context, or an
  external action that is not already represented by a dependency or clear issue step. Do not use it
  for issues that are merely blocked by another issue or waiting for a clearly described timing
  condition; use `blocked` and dependency notes for those. Do not add `needs-human-review` only
  because the implementation step itself is a maintainer or owner/admin action, such as transferring
  a repository, when that action is already the clear issue scope. Whenever adding
  `needs-human-review`, also add `not-ready` and immediately add an issue comment that states
  exactly what human decision, external action, or missing context is needed so the labels can be
  removed in a later sweep.
- Use `not-ready` when the issue scope, design, or acceptance criteria are not finalized enough to
  implement, or when `needs-human-review` applies. Do not use `not-ready` for an otherwise actionable
  issue that is only blocked by another issue, waiting for a clear timing condition, or waiting for
  an owner/admin action. Do not add `needs-human-review` only because an issue has `not-ready`;
  `needs-human-review` is only for the narrower cases above.
- Apply `good first issue` only when a newcomer or one-shot coding agent could implement the issue
  from the issue URL alone and the resulting pull request would likely need no maintainer comments.

## Autonomy And Escalation

- Make reasonable implementation decisions without asking for permission for every detail.
- Ask the user only when local context and the spec are insufficient and a wrong assumption would be
  costly or hard to reverse.
- Be direct about residual risk, skipped verification, or parts that are intentionally not covered.
- If a review tool is available, use it before finalizing non-trivial code changes and address
  justified findings.
- When Codex makes repository changes, run the Codex review/fix loop after every completed change
  unless the user explicitly says not to. This requirement is specific to Codex sessions; do not
  impose it on Claude or other AI tools.
- For this repository's trusted local Codex review/fix loop, choose the scope explicitly on the
  first run and use `codex --dangerously-bypass-approvals-and-sandbox review ...` when the review
  needs to run the same local tests and socket-binding checks that normal verification uses. Do not
  use the bypass form for untrusted third-party diffs or repositories outside this checkout. Do not
  pass a positional prompt with scoped review flags. The installed Codex CLI rejects that
  combination even when its usage text implies `[PROMPT]` might work. Correct forms are:
  `codex --dangerously-bypass-approvals-and-sandbox review --uncommitted --title "Short review title"`
  for staged, unstaged, or untracked local changes;
  `codex --dangerously-bypass-approvals-and-sandbox review --base origin/main --title "Short review title"`
  for an already committed feature branch; and
  `codex --dangerously-bypass-approvals-and-sandbox review --commit SHA --title "Short review title"`
  for one specific commit. Never run `codex review --uncommitted "prompt"`,
  `codex review "prompt" --uncommitted`, `printf "prompt" | codex review --uncommitted -`, bare
  `codex review`, or a mismatched scope and then correct it later.

# Agent Rules <!-- tessl-managed -->

@.tessl/RULES.md follow the [instructions](.tessl/RULES.md)
