# Deployment & live verification

## Scope

How to deploy and verify Symphony for Trello against real systems: when to run live deployment
verification, how live end-to-end runs must be structured, deployment auth and filesystem access,
shipped-skill installation, and installer/onboarding lifecycle coverage. Unit/integration testing
lives in [Testing](testing.md).

## When to verify a deployed change

- When a change affects runtime behavior and the user is likely to verify it manually afterward, ask
  at the end whether to run live deployment verification next when deployment inputs are
  available. Do not deploy or suggest deployment for docs-only changes.
- When fixing a bug that was observed during live deployed execution, and live verification is
  reasonably possible from the current environment, perform the relevant live deployed verification
  before claiming the fix is complete.

## Debugging auto-posted setup-failure issues

- When the user provides a GitHub issue that was automatically posted from a local setup failure,
  first debug from the issue as an outside maintainer would. Decide whether the sanitized issue body
  has enough information to identify the root cause. If it does not, improve the diagnostics or
  issue-posting data while keeping secrets, private paths, Trello identifiers, account names, and
  host-specific details redacted. Reproduce locally when feasible, fix the underlying issue once
  enough information is available, add regression coverage, and update expected-vs-unexpected setup
  failure classification when the failure was misclassified.

## Live end-to-end verification

- When reporting live E2E results, state which external systems were real and which parts used test
  doubles. Do not imply that real Codex completed a path when only deterministic fake Codex completed
  it against real Trello.
- For live PR-author verification, inspect every commit in the resulting pull request through GitHub,
  not only the new commit Codex just created. A card is not proven fixed when an existing PR still
  contains earlier commits authored by a generic Codex identity.
- Do not call a live deployment healthy just because the managed process is active. When a real
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
  structure, not only a Symphony-generated board imported back again. Use explicit non-default active
  and terminal list names, then verify fake-Codex first and strict real-Codex after.
- When a live run uncovers a broken scenario after an earlier health claim, record the reproducible
  scenario in `docs/live-e2e.md` and create or update GitHub issues before fixing behavior. Capture
  both the user-visible symptoms and the underlying cause when they are distinct. Keep the broken
  live state available when the user asks to reproduce it before a fix.

## Deployment auth and filesystem access

- For deployment auth, prefer reusing the existing Codex CLI auth file from `codex login`. Do not
  steer users toward configuring raw OpenAI API keys unless they explicitly ask for that mode.
- For deployment filesystem access, describe the concept as "allowed host paths". The allowed entries
  can be multiple files or folders; do not imply they must be repository or project roots. Explain
  that undeclared host paths are blocked by default for security reasons so Trello cards cannot make
  Codex read or edit unrelated files. Trello-visible blocker comments and workpad updates for
  filesystem access must not copy absolute host paths, per-card workspace locations, account names, or
  deployment-specific paths. Refer to them as "the requested path" or "the per-card workspace",
  explain the security default, and point to the exact setup option or workflow setting that relaxes
  access.
- Do not assume the person using the Trello board has shell access to the machine where Symphony runs.
  For cards that wait, block, or do not move, make the actionable reason visible on the Trello card
  itself through the workpad, a managed status comment, or the configured handoff/blocker comment.
  Local logs and diagnostics can carry operator detail, but they are not enough as the only user-facing
  explanation.

## Generated workflows, PR publishing, and shipped skills

- For repository-changing work, generated workflows and PR publishing instructions should create
  ready-for-review, non-draft pull requests by default. Use a draft PR only when the Trello card
  explicitly asks for a draft PR.
- For PR-bound commits, generated workflows and commit instructions should reuse a checkout-local Git
  author only when the author is complete and marked as verified for this workflow. Only call GitHub
  APIs again when that verified author config is missing or incomplete.
- When generated workflows reference Symphony's `.codex/skills`, make sure deployed per-card
  workspaces receive the namespaced shipped skills after workspace sync hooks and before Codex
  starts. Do not rely on the target repository containing Symphony-specific skill files, and do not
  dirty checkout-root Git status with shipped skill files.
- Install workspace-local shipped skills only when the rendered prompt references the namespaced
  Symphony skill paths, so workflows whose prompts do not use the shipped skills, such as
  hand-authored workflows that clone into an empty workspace root, keep their expected workspace
  shape.

## Local credentials

- For local Trello credentials, use ignored project-root `.env` files created from `.env.example`.
  Real environment variables still take precedence over `.env`. Do not print secret values in command
  output.

## Installer and onboarding lifecycle coverage

- For installer/onboarding changes, do not rely on syntax checks or dry runs alone. Add or update
  deterministic lifecycle coverage that drives prompts through a real pseudo-terminal when prompts
  exist, uses test doubles for external tools and services, verifies install/update/start/status
  behavior, and proves uninstall removes installer-managed files and state without external side
  effects.

## References

- [Testing](testing.md)
- [Documentation & README](documentation-and-readme.md)
- [Private-context redaction](private-context-redaction.md)
- [GitHub issue triage](github-issue-triage.md)
