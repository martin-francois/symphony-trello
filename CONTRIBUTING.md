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
  PMD checks, CPD duplication checks, selected Error Prone and Picnic compiler checks for production
  and test sources,
  SpotBugs bytecode checks, FindSecBugs security checks, ArchUnit rules, test suite, build, and
  JaCoCo checks.
  ArchUnit rejects circular dependencies between production top-level packages. Coverage currently
  requires at least 80% line coverage.
- Add or update tests for scheduler, Trello normalization, workspace safety, prompt rendering, or
  Codex protocol behavior when those areas change.
- Reuse shared test support before adding new fixtures. Look under
  `src/test/java/ch/fmartin/symphony/trello/testsupport` and nearby package fixtures for fake Trello
  servers, setup command builders, workflow/env content builders, manifest/workflow assertions, and
  terminal transcript helpers.
- Use imports instead of inline fully qualified Java type names in code. PMD enforces this so helpers
  like `java.util.Arrays.stream(...)` should be written as `Arrays.stream(...)` with an import.
- Preserve JSpecify nullness annotations on annotated Java boundaries. Add `@Nullable` when a new or
  changed boundary intentionally accepts or returns `null`, and use `@NullMarked` only for packages
  or types whose nullness contracts have been reviewed.
- Document non-obvious design choices in `docs/adr/`.
- Keep refactors separate from behavior changes when practical.
- Use a Conventional Commit PR title or squash commit title when the change is merged. CI checks
  pull request titles with commitlint because the release automation uses those titles for normal
  squash merges. CI also checks pull request commit messages so rebase-merged or intentionally
  multi-commit PRs keep release automation input clean. Messages that reach `main` must be
  release-note ready so the automation can choose the next SemVer version and update
  [CHANGELOG.md](CHANGELOG.md).

`./mvnw -q spotless:check verify` remains the default local validation command. It runs Spotless
formatting checks, curated PMD source checks, CPD duplication checks, selected Error Prone and Picnic
compiler checks for production and test sources, SpotBugs bytecode checks, FindSecBugs security
checks, the deterministic test suite, ArchUnit architecture checks, the application build, and the
JaCoCo coverage gate. The ArchUnit checks reject circular dependencies between production top-level
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
already cleaner to leave as they are. A large diff is acceptable when the resulting code is
meaningfully better. A rule is not noisy only because it finds many justified problems. A finding is
justified when fixing it would make the code meaningfully better, cleaner, safer, faster, or more
maintainable; compiling successfully does not by itself make a supplementary static-analysis finding
unjustified. Use `@SuppressWarnings("PMD.RuleName")` for code-local suppressions and
`// NOPMD - reason` only for truly line-local cases.

An optional PMD candidate pass is available for measuring additional source rules before they become
blocking:

```bash
./mvnw -q -Ppmd-candidate pmd:pmd
```

Review `target/pmd.xml` after running it. Candidate findings must be fixed, tuned, or explicitly
left as candidate-only before any rule moves into the blocking `verify` gate. PMD CPD duplication
checks are part of `./mvnw -q spotless:check verify`; run `./mvnw -q pmd:cpd` when you need the
standalone duplication report.

An optional jPinpoint PMD pass is available for measuring third-party PMD 7 XPath rules for
performance, logging, concurrency, data-mixup, and sustainability concerns:

```bash
./mvnw -q -Pjpinpoint test-compile pmd:pmd@jpinpoint-report
```

This profile loads a vendored, pinned snapshot of the upstream `PMD-jPinpoint-rules` PMD 7 Java
ruleset and writes the PMD XML report to `target/jpinpoint-pmd/pmd.xml`. The local wrapper keeps
jPinpoint's `UnresolvedType` rule enabled, but adds a narrow ruleset-level suppression for a known
false positive where the type-resolution rule does not resolve valid record accessor calls in
handwritten source. The profile is report-only and intentionally separate from both
`./mvnw -q spotless:check verify` and `-Ppmd-candidate`. Classify jPinpoint findings before
promoting individual rules, and prefer small follow-up issues for cross-cutting rule families. A
jPinpoint rule is not noisy only because it reports many justified findings. The jPinpoint report
uses a separate PMD execution so the `verify`-bound PMD check keeps using the curated blocking
ruleset.

SpotBugs and FindSecBugs project-level false positives belong in `config/spotbugs/exclude.xml`;
code-local exceptions should use `@SuppressFBWarnings(value = "...", justification = "...")`. Error
Prone and the selected Picnic Error Prone Support rule families run as blocking production and
test-source compiler checks in normal validation. New Error Prone or Picnic checks should still
start in a non-blocking branch or profile until their baseline is understood, then use stable
`-Xep:<CheckName>:OFF|WARN|ERROR` flags for rule control.

Semgrep covers focused cross-language repository guardrails under `config/semgrep`. CI runs the
same local rules with:

```bash
./scripts/semgrep-docker.sh
```

Use rule-specific `nosemgrep` comments only for true false positives and include a reason. CodeQL is
a later public-repository code-scanning layer and is not part of normal local `verify`. Hosted
dashboards can add signal, but they must not replace local checks contributors can run, fix, and
rerun.

Fuzzing and deterministic chaos tests cover parser and external-boundary failure modes that are hard
to exhaust with example-based tests. CI runs the Jazzer fuzz tests in deterministic regression mode
and runs the chaos tests so parser and boundary regressions are caught during pull request checks.
The repository also has a maintainer-owned scheduled GitHub Actions workflow that runs active Jazzer
fuzzing from `main` and files `bug` + `fuzzed` issues when it finds failures. Contributors do not
need to run continuous fuzzing before every pull request, but should use the 15- to 30-minute active
fuzzing commands in [Fuzzing](docs/fuzzing.md) when changing parser, prompt-line safety, workflow
loading, or Trello reference/checklist parsing logic. The same page also documents longer
agent-requested fuzzing runs, scheduled fuzzing behavior, and the OSS-Fuzz project files.

GitHub Secret Scanning with repository custom patterns is the primary protection for
GitHub-hosted issue, pull request, review, and comment text. Private-context scanning uses
BetterLeaks plus repository-specific rules under `config/betterleaks` as a local and CI complement.
Run it before committing files or manually posting copied diagnostic text:

```bash
scripts/check-private-context --worktree
scripts/check-private-context --git-range origin/main..HEAD
printf '%s\n' 'text to post' | scripts/check-private-context --stdin --label github-body
```

If the scanner reports Trello-shaped values that are only test data, make them clearly synthetic
rather than suppressing the finding. See
[Private Context Scanning](docs/security/private-context-scanning.md) for the local commands and
the GitHub Secret Scanning responsibility split.

PowerShell installer tests use native `pwsh` automatically on Windows. CI runs the PowerShell
installer checks on a native Windows runner. On Linux, set `SYMPHONY_TRELLO_TEST_PWSH` when you
need to exercise the PowerShell path; the local wrapper runs `pwsh` through Microsoft's .NET SDK
container:

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

When Release Please creates a GitHub release, the release workflow checks out the tag, builds the
packaged Quarkus app, uploads `install.sh`, `install.ps1`, `uninstall.sh`, `uninstall.ps1`, Linux
and Windows release archives, and a SHA3-256 `checksums.txt` file. Release assets are not replaced
in place; if a public release is wrong or incomplete, publish a new patch release after fixing the
release pipeline. To validate the packaging step locally, run:

```bash
scripts/package-release-assets.sh 0.2.0
```

Replace `0.2.0` with the release version being tested.

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

## Running a Codex live bug bash

This repository has two explicit Codex skills for maintainer QA:

- `$live-bugbash` runs the bug bash and writes local issue drafts.
- `$publish-bugbash-issues` publishes reviewed drafts to GitHub.

Both skills are stored under `.agents/skills/` and exposed to Codex through `.codex/skills/`, so
they can be invoked by mentioning the skill name in a Codex goal.

### Run the bug bash

Safe run with fake Trello, fake Codex, and fake GitHub:

```text
/goal Use $live-bugbash until <future timestamp>.
```

Real run against Trello, Codex, and GitHub sandbox repositories:

```text
/goal Use $live-bugbash until <future timestamp>. Do a real live bugbash without fakes.
```

Real run on a hardened host, including dangerous access-mode coverage:

```text
/goal Use $live-bugbash until <future timestamp>. Do a real live bugbash on a hardened host.
```

Continue after an earlier run:

```text
/goal Use $live-bugbash until <future timestamp>. PREVIOUS_RUN_ID=live-bugbash-20260629T103000Z.
```

Useful output is written under:

```text
target/live-bugbash/<RUN_ID>/final-report.md
target/live-bugbash/<RUN_ID>/cleanup-summary.md
target/live-bugbash/<RUN_ID>/issues/
```

The active exploration window is not passive waiting time. Codex should keep testing, triaging,
inspecting code, gathering evidence, cleaning up, or reporting while useful work remains. It should
not sleep or poll timestamps just to consume the window.

### Publish reviewed findings

After the run, review the drafts in:

```text
target/live-bugbash/<RUN_ID>/issues
```

Before publishing, ensure each selected draft's `AI Assistance (if used)` section discloses the
AI-assisted issue and confirms that a person reviewed and understands the report. The publisher skips
drafts where that per-draft confirmation is missing.

Then publish the reviewed drafts:

```text
/goal Use $publish-bugbash-issues for RUN_ID=live-bugbash-20260629T103000Z. I have reviewed the drafts and approve publishing them to martin-francois/symphony-trello.
```

The publishing skill creates new GitHub issues for unique confirmed bugs. If a draft matches an
existing issue, it comments on that issue with a short sanitized note saying the bug was also
reproduced during the bug-bash run. It does not edit, close, reopen, relabel, assign, milestone, or
otherwise change existing issues.

Publication results are written under:

```text
target/live-bugbash/<RUN_ID>/publication-report.md
target/live-bugbash/<RUN_ID>/published-issues.json
```

### Safety notes

- `$live-bugbash` is fake by default. It uses real Trello, real Codex, or real GitHub only when the
  goal explicitly asks for a real run.
- Real Trello data used by a bug bash must be disposable and run-scoped.
- `$live-bugbash` must not write to existing GitHub repositories, issues, pull requests, settings,
  secrets, collaborators, billing, releases, workflows, or organization settings. In real GitHub
  mode it may write only to newly created private sandbox repositories whose names include the run
  ID.
- `$publish-bugbash-issues` writes to `martin-francois/symphony-trello` only after the drafts were
  reviewed and the goal explicitly approves publishing. It may create new issues and add duplicate
  reproduction comments. It must not perform other GitHub mutations.
- Hardened host mode allows run-scoped dangerous access-mode tests, such as
  `setup-local --danger-full-access`, `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true`, workflow-authored
  `dangerFullAccess`, `codex --sandbox danger-full-access --ask-for-approval never`, and Codex
  `--dangerously-bypass-approvals-and-sandbox` or `--yolo` when real Codex is enabled and
  supported.
- Hardened host mode is not permission to trash the system. Codex must not intentionally damage,
  wipe, stress, credential-scan, broad-delete, globally reconfigure, kill unrelated processes,
  mutate unrelated host state, or inspect unrelated private data.
- Installer, uninstaller, setup, and cleanup tests must use run-scoped HOME, XDG, SYMPHONY, prefix,
  config, state, cache, workspace, manifest, env, and log paths.
- `.github/ISSUE_TEMPLATE/bug_report.yml` remains the source of truth for issue body fields and must
  not be copied into either skill.

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
