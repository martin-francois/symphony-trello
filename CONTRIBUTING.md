# Contributing

This repository is maintained as if it were open source, even while visibility is private.

## Development Setup

1. Install Java 25 LTS.
2. Use the Maven wrapper: `./mvnw test`.
3. Install Codex CLI if you want to run real worker sessions.
4. Create a local `WORKFLOW.md` from `WORKFLOW.example.md`.
5. Keep Trello secrets in environment variables, not in committed files.

## Quality Bar

Before submitting changes:

- Run `./mvnw test`.
- Run `./mvnw spotless:check`.
- Add or update tests for scheduler, Trello normalization, workspace safety, prompt rendering, or
  Codex protocol behavior when those areas change.
- Document non-obvious design choices in `docs/adr/`.
- Keep refactors separate from behavior changes when practical.

## Commit Style

Use Conventional Commits:

- `feat: add retry snapshot endpoint`
- `fix: suppress stale worker exit retries`
- `test: cover archived-list Trello normalization`
- `docs: document production safety posture`

## Local Trello Testing

Use a disposable Trello board and token. Do not run real smoke tests against a production board until
the workflow prompt, active lists, terminal lists, and workspace hooks have been reviewed.

Recommended smoke path:

1. Create one test card in an active list.
2. Start Symphony with a temporary `workspace.root`.
3. Confirm the card appears as running or retrying in `/api/v1/state`.
4. Move the card to a terminal list.
5. Confirm the workspace is removed after reconciliation.

## AI Disclosure

When this project becomes public, follow the target repository or organization policy for AI
assistance disclosure. If no policy exists, disclose material AI assistance in the pull request body.
