# Contributing

This repository is maintained as if it were open source, even while visibility is private.

AI agents working in this repository must also follow `AGENTS.md`. It captures the project-specific
engineering preferences that should persist across Codex sessions.

## Development Setup

1. Install SDKMAN if it is not already installed:

   ```bash
   curl -s "https://get.sdkman.io" | bash
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   ```

2. Install the project Java runtime and make it the default for your shell:

   ```bash
   sdk install java 25.0.3-zulu
   sdk default java 25.0.3-zulu
   java -version
   ```

   The repository also includes `.sdkmanrc`, so `sdk env install` can install the pinned runtime
   when SDKMAN's environment feature is enabled.

3. Use the Maven wrapper: `./mvnw verify`.
4. Install Codex CLI if you want to run real worker sessions.
5. Create a local `WORKFLOW.md` from `WORKFLOW.example.md`.
6. Copy `.env.example` to `.env`, set Trello credentials there, and keep `.env` uncommitted:

   ```bash
   cp .env.example .env
   chmod 600 .env
   ```

## Quality Bar

Before submitting changes:

- Run `./mvnw spotless:check`.
- Run `./mvnw verify`; CI enforces the same JaCoCo coverage gate and currently requires at least
  80% line coverage.
- Add or update tests for scheduler, Trello normalization, workspace safety, prompt rendering, or
  Codex protocol behavior when those areas change.
- Use imports instead of inline fully qualified Java type names in code. The test suite enforces this
  so helpers like `java.util.Arrays.stream(...)` should be written as `Arrays.stream(...)` with an
  import.
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

For a reproducible board-creation, import, handoff, and concurrency check, follow
[docs/live-e2e.md](docs/live-e2e.md). It uses `.env`, disposable boards, and a deterministic
app-server test double so Trello behavior can be verified without depending on model output.

Recommended smoke path:

1. Create one test card in an active list.
2. Start Symphony with a temporary workspace directory so smoke-test checkouts stay away from real
   project work.
3. Confirm the card appears as running or retrying in `/api/v1/state`.
4. Move the card to a terminal list.
5. Confirm the workspace is removed after reconciliation.

## AI Disclosure

When this project becomes public, follow the target repository or organization policy for AI
assistance disclosure. If no policy exists, disclose material AI assistance in the pull request body.
