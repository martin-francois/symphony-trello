# Documentation & README

## Scope

How to write user-facing documentation, README content, CLI and installer text, generated workflow
prompts, and product terminology. Deployment steps and installer lifecycle testing live in
[Deployment & live verification](deployment-and-live-verification.md).

## Open-source readiness and README focus

- Keep the project open-source-ready even while private: clear README, usable CONTRIBUTING, useful
  ADRs, no committed secrets, and reviewable history.
- Lead the README with who the project is for, why they should use it, and the practical benefits
  before implementation details. Move technical mechanics into supporting sections once the value is
  clear.
- Order README sections by what most users need first. Assume many readers stop when the next section
  looks irrelevant, so put the shortest successful path and common usage before reference material,
  edge cases, advanced setup, and maintainer details.
- Keep README content focused on users and operators who want to evaluate, install, configure, run,
  troubleshoot, or deploy Symphony for Trello. Move contributor-only content such as source-checkout
  development runs, build/test commands, CI details, coding standards, and PR process rules to
  `CONTRIBUTING.md`, `AGENTS.md`, or focused developer docs. Before adding README content, ask
  whether a first-time user or operator needs it to succeed with the product; if not, put it
  elsewhere.
- Put detailed workflow mechanics, such as process-to-workflow-to-board mapping, in the workflow
  contract section instead of the README opening.
- In human contributor docs and GitHub templates, keep AI-agent-only guidance at the bottom after
  sections that apply to all contributors, including security and maintainer process sections. Do not
  put AI-only setup instructions in the middle where a human reader might assume the remaining
  document is only for agents and stop reading.

## Prose style

- Write README prose in simple, direct language. Prefer short sentences and plain words over polished
  or elaborate wording.
- Avoid redundant clarifications that create false significance. If a sentence already states the
  point clearly, do not add a follow-up such as "not only X", "meaning...", or "in other words..."
  unless it removes a real ambiguity, adds an exception, or changes the action the reader should
  take. When revising text based on feedback, preserve the intended scope. Do not carry over an old
  narrow example or introduce a new limiting qualifier unless that limit is intentional. If the
  intended scope is unclear, ask a clarifying question and offer likely scope options before writing
  the final text.
- Avoid unclear normalization jargon in code and docs. Prefer simple words such as "default",
  "resolved", "configured", or "official".
- In contributor-facing documentation, refer to changelog/version/tag tooling as "release
  automation" unless the concrete tool name is necessary for a command, filename, workflow name, or
  maintainer-only implementation detail.
- Write user-facing docs so they can be read from top to bottom without confusion. Do not refer to a
  concept, path, command, or setup mode before introducing it.
- Prefer explaining intent, significance, and decision criteria over prescribing arbitrary defaults.
  Recommend concrete names or values only when the choice is semantically important.

## Product terminology

- Use "Symphony for Trello" as the human-facing product name. Use `symphony-trello` only for
  technical identifiers such as artifact IDs, service names, application names, and other places
  where spaces or title case are not suitable.
- Use **connect/disconnect/manage** for the relationship between Symphony and Trello boards in
  user-facing docs, CLI output, generated workflow prompts, GitHub issues, and tests. Use **connect**
  / **disconnect** for setup and configuration actions: users connect a Trello board to Symphony or
  disconnect a Trello board from Symphony. Use **manage** / **managing** for runtime behavior:
  Symphony manages connected Trello boards by reading cards/comments and performing configured actions
  such as moving cards, adding comments, creating/updating pull requests, and merging when configured.
  Example option labels: `Keep connected Trello boards`, `Connect another Trello board`, and
  `Disconnect a Trello board from Symphony`.
  When a word refers to any Trello entity and could be ambiguous, qualify it with `Trello`. This is a
  general rule, not a fixed list: apply it to boards, lists, cards, comments, labels, members,
  checklists, attachments, actions, or any other Trello API/domain entity whenever context alone does
  not clearly identify Trello. For example, use `Trello comment` if `comment` could mean a GitHub PR
  comment, GitHub issue comment, Trello comment, or code comment; use `Trello list` if `list` could
  mean a Java `List<>`, a generic collection, or a Trello list.
  Do not use "watch", "stop watching", "use", or "stop using" for this Trello-board relationship;
  "watch" is still fine for unrelated technical behavior such as watching files, logs, status pages,
  or retrying cards.
- Use "connect" and "disconnect" for setup/configuration actions between Symphony and Trello boards.
  Use "manage" or "managing" for runtime behavior. Avoid "watch", "stop watching", "use", and "stop
  using" for the Symphony/Trello relationship. When a Trello entity word could be ambiguous, qualify
  it, for example `Trello comment`, `Trello list`, or `Trello card`.
- In user-facing docs, CLI text, and generated workflow prompts, call visible Trello board lanes
  "lists". On first use in the README, briefly explain that a Trello list is the board column that
  contains cards, for example `Ready for Codex` or `In Progress`.
- Use Trello's UI term "archived" in prose for archived cards, lists, and boards. Use `closed` only
  when naming Trello REST API fields or parameters, and explain nearby that Trello's API uses `closed`
  for archived resources. Distinguish archived lists from deleted lists; Trello requires a list to be
  archived before it can be permanently deleted.

## Options, config fields, and CLI behavior

- Do not document options or config fields by only restating their names. Explain what the value is
  used for, when the reader should change it, and how to choose a sensible value.
- For CLI commands, follow the principle of least surprise: avoid silent no-ops, print actionable
  success/error output, and prefer preserving existing user files with a clear alternate path over
  making repeated setup commands appear to do nothing.
- Keep installed command semantics and shared default resolution in the Java CLI when Java is
  available. Install and uninstall scripts should contain only platform bootstrap, shell profile
  editing, prerequisite installation, process cleanup before Java is available, and behavior that is
  genuinely shell-specific. When a script change duplicates logic across Bash and PowerShell, first
  move the behavior into Java and test it once. If duplication is unavoidable, document why near the
  duplicated script logic or in an ADR when the tradeoff is architectural.
- Do not make users inspect workflow or config internals to recover from CLI errors. When the program
  knows the relevant command, path, option, or environment variable names for this run, print those
  exact values in the next step instead of saying that they are "referenced by the workflow",
  "configured locally", or similar.
- When user-facing text prints a path, command, URL, file name, environment variable, or other value
  the user may copy, do not put sentence punctuation immediately after it. Put the value in backticks,
  on its own line, or before explanatory text so punctuation cannot be mistaken as part of the value.
- Do not add positive installer or CLI flags that only restate default behavior. Before adding a
  positive flag, check whether the command behaves differently with the flag than without it. If the
  flag does not change behavior, keep the default behavior implicit and expose only the opt-out flag
  or real alternate mode. A positive flag is appropriate only when it selects a distinct behavior,
  resolves ambiguity, preserves needed compatibility, or is part of a real multi-mode choice.

## Setup failure classification

- When adding setup, onboarding, installer, or lifecycle failure codes, decide whether the failure is
  expected or unexpected while designing the failure path. Treat it as expected when the user can fix
  it from the command output without a maintainer, such as invalid CLI shape, missing required input,
  declined setup action, missing credentials, missing login, missing prerequisite, ambiguous
  selector, or a known external authorization/permission/not-found response. Expected failures should
  print an actionable error or next step and should not create diagnostics or GitHub issue reports by
  default. Implement expected setup failures as `TrelloBoardSetupException` codes listed in
  `SetupDiagnosticReporter.EXPECTED_SETUP_FAILURE_CODES`; add a `userActionHint(...)` case only when
  the exception message itself does not already contain the exact recovery command. Treat a failure as
  unexpected when it suggests a bug, unsafe state, tool/runtime failure, unreadable generated state,
  failed workflow read/write, failed worker start/stop/health, transport failure, rate limit, server
  error, malformed external payload, or any condition where a maintainer needs sanitized
  system/workflow/log context to debug it. Unexpected setup failures should not be added to
  `EXPECTED_SETUP_FAILURE_CODES`, so `SetupDiagnosticReporter.shouldReport(...)` can create a
  sanitized report. When an apparently unexpected boundary failure wraps a known user-correctable
  cause, such as local worker startup logs showing Trello authentication failure, map it to the
  specific expected setup code before it reaches the CLI. If an unexpected exception message could
  contain private paths, tokens, Trello identifiers, account names, or raw external payloads, wrap it
  before it reaches the CLI boundary with a safe message and preserve the original cause. Tests must
  prove expected failures do not write troubleshooting reports, unexpected failures still do, and any
  printed next step is not misleading.

## Installer and onboarding output

- When changing installer, updater, onboarding, or uninstaller output, review the text from a
  first-time user's perspective before committing. Make dry-run output name the same major actions as
  the real run, distinguish files that will be removed from auth/data that will be preserved, avoid
  labels that can be mistaken for commands to execute, avoid internal shorthand in prompts, show exact
  paths for this run when deletion is possible, and keep follow-up instructions to one clear next
  action.
- For installer/onboarding UX, prefer the smooth default path over extra confirmation prompts when the
  action has a clear opt-out or safe fallback. Do not add prompts only to make a choice feel more
  explicit. If the choice follows another mature OSS installer precedent, document that precedent and
  the tradeoff in an ADR.
- When documenting Windows setup, present WSL2 with the Linux installer as the recommended path.
  Mention native Windows PowerShell only as a best-effort path. Keep this framing in README setup
  docs, installer reference docs, SPEC updates, and user-facing Windows installer text.
- In prerequisites, name only tools the reader must install or provide. Do not list the Maven wrapper
  as a prerequisite when it is already committed. For command-line tools such as Codex, state exactly
  how the service finds them, such as `PATH` lookup or a configurable command path.
- Keep prerequisite lists simple. Put short install requirements in the list, then add one plain
  explanatory sentence when lookup, authentication, or configuration details matter.
- When referring to the local `codex` command in user setup docs, call it "Codex CLI" so readers do
  not confuse it with other Codex surfaces.

## Setup flow and links

- Use progressive disclosure in README setup flows: explain the simplest successful path first, and
  move optional modes, safety knobs, and advanced configuration into later sections.
- Keep deployment instructions focused on operating a prepared version. Do not put contributor or CI
  verification commands such as formatting, linting, or full test runs into deployment steps.
- For docs with multiple setup paths, read the flow once from each path's perspective and avoid
  wording that assumes the reader chose a different path.
- Put "who this path is for" guidance next to the commands for that path. Do not make readers remember
  an earlier decision list or scroll back to choose the right section.
- Do not add convenience links that encourage readers to skip required earlier steps. If a section
  depends on previous setup, structure the document as a linear flow instead of linking directly past
  the prerequisite.
- Prefer descriptive Markdown links over bare URLs in prose. In setup instructions, tell the reader
  what the linked page is for instead of placing multiple raw links next to each other.
- Avoid upfront reference dumps when the same links can be placed contextually in the step where the
  reader needs them.
- For optional external docs in setup steps, prefer omitting the link over adding "if the page
  changed" text. Use a precise official link when it materially reduces ambiguity for the current
  action or replaces duplicated vendor-owned setup instructions that are better kept up to date by the
  vendor.
- In numbered setup instructions, each numbered item should be an action the reader performs at that
  point. Put explanatory context under the relevant action instead of creating a separate fake step.
  Avoid filler like "intentionally" unless it changes the reader's decision.
- Keep setup steps focused on what the reader must do now. Avoid naming external documentation
  terminology or adding fallback-guide chatter unless it solves a likely problem in that step.
- Avoid repeating limitations already made clear by the surrounding setup context.
- Do not qualify setup instructions with "if possible" or similar hedges when the expected value is
  known.

## Third-party UI references

- When suggesting user-visible names for external systems, include the integration or scope when it
  helps the user recognize what the entry is for later.
- When documenting third-party UI fields, use the current vendor label exactly when known instead of
  approximating it.
- For third-party authorization screens, describe the concrete labels and permissions the user sees,
  then connect them to the project need only when necessary.
- When describing third-party UI layout, prefer location wording observed in the current UI and avoid
  overly specific positions unless they help the reader find the control.

## References

- [Deployment & live verification](deployment-and-live-verification.md)
- [Specification & ADR discipline](specification-and-adr-discipline.md)
- [Private-context redaction](private-context-redaction.md)
