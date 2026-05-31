# Contributing

Read the [Code of Conduct](CODE_OF_CONDUCT.md) before participating. It applies to issues, pull
requests, reviews, and other project spaces.

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

3. Use the Maven wrapper: `./mvnw -q spotless:check verify`.
4. Install Codex CLI if you want to run real worker sessions.
5. Create a local `WORKFLOW.md` from `WORKFLOW.example.md`.
6. Copy `.env.example` to `.env`, set Trello credentials there, and keep `.env` uncommitted:

   ```bash
   cp .env.example .env
   chmod 600 .env
   ```

## Running From Source

Start with the browser steps in `README.md` if you do not yet have Trello credentials. The board
setup commands in the README use the installed `symphony-trello` wrapper. From a source checkout
without the wrapper, run the same Java CLI through Maven:

```bash
./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue"'
./mvnw -q exec:java -Dexec.args='import-board --board abc123 --active "Ready for Codex" --in-progress "In Progress" --terminal Done --blocked Blocked'
```

If those source-checkout commands print `symphony-trello start` under `Next`, use the installed
wrapper command, or use `./mvnw quarkus:dev` for a developer run from the checkout.

If you already have both Trello credentials and `WORKFLOW.md`, put the credentials in an ignored
project-root `.env` file:

```properties
TRELLO_API_KEY=replace-with-generated-key
TRELLO_API_TOKEN=replace-with-generated-token
```

Exported environment variables with the same names also work and take precedence over `.env`.

Start the service from the checkout:

```bash
./mvnw quarkus:dev
```

By default the status page binds to `127.0.0.1:18080`. Use `SYMPHONY_HTTP_PORT=0` for an ephemeral
test port, configure `server.port` in `WORKFLOW.md`, or pass `--port` for local development.
Command-line `--port` wins over `server.port`.

Packaged runs also accept a positional workflow path and `--port`:

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar ./WORKFLOW.md --port 18081
```

## Specification Alignment

`SPEC.md` is the project contract. It adapts OpenAI Symphony from Linear to Trello while keeping
shared Symphony concepts close to the upstream spec where practical. The upstream reference
implementation is useful context for intent and edge cases. A difference from it is acceptable when
the Java behavior still follows `SPEC.md`, fits Trello, and is covered by project ADRs.

## Quality Bar

Before submitting changes:

- Run `./mvnw -q spotless:check verify`; CI enforces the same Spotless formatting checks, curated
  PMD checks, SpotBugs bytecode checks, FindSecBugs security checks, ArchUnit rules, test suite,
  build, and JaCoCo checks. ArchUnit rejects circular dependencies between production top-level
  packages. Coverage currently requires at least 80% line coverage.
- Add or update tests for scheduler, Trello normalization, workspace safety, prompt rendering, or
  Codex protocol behavior when those areas change.
- Use imports instead of inline fully qualified Java type names in code. PMD enforces this so helpers
  like `java.util.Arrays.stream(...)` should be written as `Arrays.stream(...)` with an import.
- Document non-obvious design choices in `docs/adr/`.
- Keep refactors separate from behavior changes when practical.
- Use a Conventional Commit PR title or squash commit title when the change is merged. CI checks
  pull request titles with commitlint because the release automation uses those titles for normal
  squash merges. CI also checks pull request commit messages so rebase-merged or intentionally
  multi-commit PRs keep release automation input clean. Messages that reach `main` must be
  release-note ready so the automation can choose the next SemVer version and update
  [CHANGELOG.md](CHANGELOG.md).

`./mvnw -q spotless:check verify` remains the default local validation command. It runs Spotless
formatting checks, curated PMD source checks, SpotBugs bytecode checks, FindSecBugs security checks,
the deterministic test suite, ArchUnit architecture checks, the application build, and the JaCoCo
coverage gate. The ArchUnit checks reject circular dependencies between production top-level
packages. `verify` also fails if line coverage drops below 80%. The test suite does not call Trello.

Static-analysis findings are meant to be actionable in a local feedback loop. Prefer fixing a
finding, then rerunning the analyzer and the relevant build or test command. If a rule is useful but
too broad, tune the rule before suppressing individual findings. Suppress only true false positives,
use the narrowest source or versioned-configuration scope available, and include a clear reason for
every suppression. Do not disable an analyzer, package, source tree, or rule category only to make a
check pass.

PMD is a curated source-level analyzer in this repository, not a one-off narrow check. New PMD rules
or broad third-party rulesets should first run in report-only or candidate mode so maintainers can
classify findings before making them blocking. A rule is noisy when its findings are false positives,
already cleaner to leave as they are, or lower-value than the churn needed to satisfy the rule. A
rule is not noisy only because it finds many justified problems. Use
`@SuppressWarnings("PMD.RuleName")` for code-local suppressions and `// NOPMD - reason` only for
truly line-local cases.

An optional PMD candidate pass is available for measuring additional source rules before they become
blocking:

```bash
./mvnw -q -Ppmd-candidate pmd:pmd
```

Review `target/pmd.xml` after running it. Candidate findings must be fixed, tuned, or explicitly
left as candidate-only before any rule moves into the blocking `verify` gate. CPD duplication checks
are still measured separately with `./mvnw -q pmd:cpd`; they are not blocking until the current
duplication baseline is cleaned up or deliberately accepted.

SpotBugs and FindSecBugs project-level false positives belong in `config/spotbugs/exclude.xml`;
code-local exceptions should use `@SuppressFBWarnings(value = "...", justification = "...")`. Error
Prone and Picnic Error Prone Support should start in a non-blocking profile and use stable
`-Xep:<CheckName>:OFF|WARN|ERROR` flags for rule control. Semgrep suppressions should be
rule-specific `nosemgrep` comments with a reason, and private-repository local runs should use
`--metrics=off`. CodeQL is a later public-repository code-scanning layer and is not part of normal
local `verify`. Hosted dashboards can add signal, but they must not replace local checks
contributors can run, fix, and rerun.

An optional Error Prone pass is available for local source-level feedback:

```bash
./mvnw -Perror-prone clean compile
```

This profile is intentionally not part of the default `./mvnw -q spotless:check verify` command. It
uses Error Prone as a warning-oriented candidate pass while maintainers watch baseline findings and
rule usefulness. The profile explicitly enables `OptionalNotPresent` so Optional presence checks that
read the value unsafely are visible during local review. The `clean` phase is part of the command so
Maven recompiles sources instead of skipping analysis when classes are already up to date. Do not add
`-q` to this command because the profile currently reports findings as warnings.

PowerShell installer tests use native `pwsh` when it is installed. CI also runs them through
Microsoft's .NET SDK container:

```bash
./scripts/pwsh-docker.sh -NoProfile -File ./install.ps1 --dry-run --no-onboard
./scripts/pwsh-docker.sh -NoProfile -File ./uninstall.ps1 --dry-run --yes
SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh ./mvnw -Dtest=InstallerScriptTest test
```

## Commit Style

Use Conventional Commits. For a PR with one logical change, maintainers squash the PR and use the PR
title as the source of truth for changelog and version bump decisions:

- `feat: add retry snapshot endpoint`
- `fix: suppress stale worker exit retries`
- `test: cover archived-list Trello normalization`
- `docs: document production safety posture`

Each PR must still cover one cohesive change. If a feature or bug fix needs directly related cleanup
or refactoring to make that change correct or maintainable, keep those as separate focused commits.
Use one commit for the user-visible feature or fix and separate commits for each dedicated type of
supporting cleanup or refactoring. Unrelated cleanup, refactoring, formatting, dependency changes,
or tooling changes belong in a separate PR. Maintainers may rebase-merge cohesive multi-commit PRs
without squashing, so every commit title should be a useful Conventional Commit.

Use `feat:` for user-visible additions and `fix:` for user-visible bug fixes. Add a
`BREAKING CHANGE:` footer only when a release really requires manual action from users or operators.
Do not edit `CHANGELOG.md` manually; the release automation updates it.

Before opening or retitling a pull request, run the same title check that CI uses:

```bash
printf '%s\n' 'docs: describe static-analysis policy' | pnpm dlx --package @commitlint/cli@21.0.1 --package @commitlint/config-conventional@21.0.1 commitlint --config commitlint.config.cjs
```

Replace the sample title with the exact pull request title. Normal squash-merge PRs are covered by
that PR-title check. For multi-commit PRs that maintainers may rebase-merge, check the commit range
too:

```bash
pnpm dlx --package @commitlint/cli@21.0.1 --package @commitlint/config-conventional@21.0.1 commitlint --config commitlint.config.cjs --from origin/main --to HEAD --verbose
```

Every retained commit title in that range must be a useful Conventional Commit.

## Maintainer Release Setup

The release automation intentionally uses GitHub Actions' default `GITHUB_TOKEN`. Pull requests
created with that token do not trigger another normal `pull_request` CI run, which keeps generated
release pull requests from rerunning the full project validation just for changelog and version
updates.

Confirm the repository's GitHub Actions settings allow workflows to create pull requests. Without
that owner setting, Release Please cannot open or update generated release pull requests with
`GITHUB_TOKEN` even though the workflow grants `pull-requests: write`.

Before merging a generated release pull request, review the release notes and generated file changes.
The implementation changes included in that release should already have passed CI on their own pull
requests before reaching `main`. If repository rules require full PR CI on every pull request, the
repository owner may need to use an explicit maintainer bypass for generated release pull requests
after reviewing the release automation output.

## Issues

Before opening a pull request, search the existing issues and pull requests. Open a new issue when
the change is not trivial, then wait for maintainer feedback or assignment before starting larger
work.

Symphony for Trello should use only Trello features available on the Trello Free plan by default.
Any exception needs a clear use case and maintainer approval in an issue before implementation. If an
approved feature requires a paid Trello plan, the user-facing README must mark that feature clearly
and name the required Trello plan.

Use the issue templates and include enough detail for another person to reproduce the problem or
evaluate the proposal. Do not include Trello credentials, Codex auth files, GitHub tokens, private
Trello board links, or unrelated host paths.

## Pull Requests

Create pull requests from a topic branch and fill out the template. Link the issue with `Fixes #123`
when the PR should close it. Keep the PR focused on one user-visible change or one cleanup.

Before marking a PR ready for review:

- run the validation commands listed in [Quality Bar](#quality-bar);
- update tests and docs when behavior changes;
- complete the GitHub CLA check when prompted;
- enable maintainer edits when contributing from a fork.

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

## Security Reports

Do not open public issues for vulnerabilities or leaked credentials. Follow [SECURITY.md](SECURITY.md)
and keep Trello credentials, Codex auth files, GitHub tokens, private board links, and unrelated host
paths out of public reports.

## AI Disclosure

AI-assisted contributions are allowed when a human owns the result. Follow
[AI_CONTRIBUTION_POLICY.md](AI_CONTRIBUTION_POLICY.md) and disclose material AI assistance in the
pull request body.

## AI Agent Setup

AI agents working in this repository must also follow `AGENTS.md`. It captures the project-specific
engineering preferences that should persist across Codex sessions.

When using an AI agent for a contribution, give it this instruction before it changes code:

> This repository vendors the `tessl-labs/good-oss-citizen` guidance. If the `tessl` command is
> available, run `tessl install` from the repository root before starting so generated agent links
> and rules are fresh. If `tessl` is not available, say so and continue by following `AGENTS.md`,
> `CONTRIBUTING.md`, `AI_CONTRIBUTION_POLICY.md`, the issue/PR templates, and the vendored
> `tessl-labs/good-oss-citizen` rules under `.tessl/tiles/` directly.

The install command is:

```bash
tessl install
```

The checked-in `tessl.json` pins and vendors `tessl-labs/good-oss-citizen`, so the guidance is
available from a fresh clone even before generated Tessl files are refreshed.

If you want this guidance and the `tessl` command is missing, install the Tessl CLI from the
[official Tessl installation guide](https://docs.tessl.io/introduction-to-tessl/installation), then
rerun `tessl install` from the repository root.
