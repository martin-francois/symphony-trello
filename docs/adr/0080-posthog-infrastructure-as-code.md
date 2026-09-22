---
status: accepted
date: 2026-09-22
decision-makers: [François Martin, Claude Code]
consulted:
  - "[OpenTofu documentation](https://opentofu.org/docs/)"
  - "[OpenTofu plan command and detailed exit codes](https://opentofu.org/docs/cli/commands/plan/)"
  - "[OpenTofu provider requirements](https://opentofu.org/docs/language/providers/requirements/)"
  - "[OpenTofu sensitive data in state](https://opentofu.org/docs/language/state/sensitive-data/)"
  - "[PostHog Terraform provider](https://github.com/PostHog/terraform-provider-posthog)"
  - "[PostHog API reference](https://posthog.com/docs/api)"
  - "[PostHog data storage and privacy controls](https://posthog.com/docs/privacy/data-storage)"
  - "[GitHub CLI secret set](https://cli.github.com/manual/gh_secret_set)"
  - "[ADR 0079](0079-installation-telemetry.md)"
informed: [Future maintainers, Fork maintainers, Contributors]
---

# Define The PostHog Projects As OpenTofu Infrastructure

## Context and Problem Statement

ADR 0079 sends one `installation_heartbeat` per installation per day to a dedicated PostHog EU
project. That project, its test twin, their privacy settings, the disabled GeoIP transformation,
the dashboard, and its ten SQL insights were created by hand through the API on 2026-09-22 and
documented in prose. Nothing could reproduce them: a fork maintainer had to follow a runbook, the
dashboard queries lived in a Markdown page separate from the saved insights, and a change in the
PostHog UI left no trace. Before the first release both projects hold synthetic data only and are
disposable.

How should the PostHog configuration be defined, applied, verified, and rebuilt so that the
maintainer, a fork, or an agent without this conversation can reproduce it from an empty
organization, and so that every later change goes through a reviewable definition?

## Decision Drivers

* The maintainer prefers open-source infrastructure tooling.
* A fork must be able to create an equivalent setup from its own account without our state, ids,
  tokens, or transcript.
* The privacy-minimizing design of ADR 0079 must be the deployed state, not a documented wish:
  IP discarding on, GeoIP off, every browser-side capture off, no destinations or sharing.
* One owner per setting; drift must be visible in a plan or an explicit diff, never hidden.
* Small: two projects do not justify a reconciliation framework, a hosted state service, or a
  second application.
* Evidence must say what was observed and when, without implying that published configuration
  proves permanent server behavior.
* Real data must not become destroyable by routine automation after the first release.

## Considered Options

* OpenTofu with the official `PostHog/posthog` provider, plus narrow API supplements
* Terraform CLI with the same provider
* Chronological API-call scripts (replay the setup)
* UI-only setup with a written runbook
* A custom provider or a generic REST reconciler
* Import-only adoption of the existing projects versus rebuild-first verification
* Hosted state and credentialed auto-apply in CI versus private local state and explicit apply

## Decision Outcome

Chosen option: OpenTofu 1.12.6 with the official provider 1.0.21 resolved from the OpenTofu
registry (`registry.opentofu.org/posthog/posthog`), one module applied twice for the `production`
and `test` roles, canonical SQL files for the panels, one TypeScript supplement for the three
environment settings the provider lacks and for the read-only report, and one Bash entry point
(`scripts/posthog-infra`) that sequences init, bootstrap, apply, supplements, verification, the
fixture check, the release-secret handoff, and destroy. State stays private on the maintainer's
machine under `~/.local/state/symphony-trello/posthog/`; CI validates formatting and the
configuration without credentials. Rebuild-first verification was executed on the disposable
pre-release projects: create, verify, converge, drift, destroy, recreate from a clean state, verify
again. The rules for future changes are in
[docs/agents/deployment-and-live-verification.md](../agents/deployment-and-live-verification.md).

Two facts shaped the mechanics. PostHog creates an enabled GeoIP transformation with every new
project, and the provider can only manage a Hog function it created or imported; declaring one
would create a second, and the default would stay enabled. The entry point therefore applies the
two `posthog_project` resources first, looks up each project's single `template-geoip` function,
imports it, and only then applies the full configuration that sets it disabled. This is the one
targeted apply and the one import in the workflow, and it needs no historical id. Second, PostHog
rejects a project name that already exists in the organization, so a rebuild must rename or retire
the old project before the new one can be created; the entry point offers `retire-project`.

### Consequences

- Good, because a fork runs six documented commands with its own key and inputs and gets the same
  projects, settings, dashboard, and panels, with fresh ids and tokens.
- Good, because every panel query is one file used by both the deployed insight and the fixture
  check, so the dashboard cannot diverge from the documentation.
- Good, because drift is visible: a renamed project, a re-enabled GeoIP function, and a flipped
  `autocapture_opt_out` were each detected by `plan` or `gaps diff` and repaired by one reapply.
- Good, because the report distinguishes PASS from UNKNOWN: retention fields the API does not
  return stay UNKNOWN rather than being read as "no retention".
- Bad, because three settings live outside the plan until the provider learns them; the supplement
  has its own diff, apply, read-back, and tests, and the exit path is a provider release.
- Bad, because the bootstrap uses `-target` once; OpenTofu warns about it on every first apply.
- Bad, because state is on one machine. Handing management to another person means handing over
  the directory; two directories would mean two owners of the same projects.
- Neutral, because the provider sends the deprecated `dashboards` field when creating an insight;
  PostHog announces an opt-in requirement for API callers. A provider update will follow.
- Neutral, because PostHog deletes a project with ingested events two days after the request and
  keeps its name reserved until then, so a rebuild on the same day renames the pending project.

### Confirmation

Executed on 2026-09-22 against the maintainer's EU organization with OpenTofu 1.12.6 and provider
1.0.21, from the working tree that became this change:

- `scripts/posthog-infra validate` and `plan` from an empty state: 30 resources to add.
- Cycle 1 `apply`: two projects created, both GeoIP functions imported and set disabled, 26
  resources added, the two API-gap settings patched and read back, report 53 pass, 0 fail, 4
  unknown (retention fields absent), 3 informational. A refresh-enabled `plan -detailed-exitcode`
  returned 0 and `gaps diff` returned 0.
- Fixture: 13 synthetic heartbeats sent to the test role through the documented schema; all ten
  panel queries returned the expected results from
  `infra/posthog/fixtures/dashboard-fixture.json`.
- Drift: project rename detected (plan exit 2) and repaired; `autocapture_opt_out` flipped and
  detected (`gaps diff` exit 2) and repaired; GeoIP re-enabled, detected, and repaired; plan exit 0
  after each repair.
- Destroy: 30 resources destroyed; PostHog removed the empty production project at once and
  scheduled the test project, which held events, for deletion two days later while keeping its
  name reserved.
- Cycle 2 from a deleted state directory and provider cache: the same definitions recreated both
  roles with new ids and tokens; the converged plan, gap diff, report, and fixture check were
  repeated. The release secret `POSTHOG_PROJECT_TOKEN` was set from the cycle-2 production token
  through `release-token`, which GitHub confirms by name and time only; the packaging script's own
  check verified that a locally packaged application carries that value.
- Semantic comparison of the retired projects with the rebuilt ones: identical privacy settings;
  deliberate differences are the emptied internal-user filter and the explicit empty replay-domain
  list.
- Offline: `pnpm run verify:scripts` (the supplement's tests cover the wrong-project guard, a
  denied scope, a missing key or token, ambiguous GeoIP discovery, incomplete pagination, a
  server-ignored setting, a stale read-back, a failed patch with a clean rerun, an unexpected
  transformation, null settings, duplicates, and secret-free output), `scripts/posthog-infra fmt
  -check` and `validate` in CI, ShellCheck and shfmt on the entry point, the Renovate config
  validator, markdownlint, and `./mvnw -q spotless:check verify` for the repository.

Not confirmed: retention values (the API returns none for these projects); whether PostHog's
infrastructure processes source addresses before discarding them; and the legal prerequisites
tracked in the runbook, which a successful apply does not settle.

## Pros and Cons of the Options

### OpenTofu with the official provider plus narrow API supplements

Declarative desired state, open-source CLI, the vendor's own provider, and a few explicit API
calls for what the provider lacks. Status: selected.

- Good, because the provider covers projects, settings, Hog functions, insights, dashboards, and
  layouts, and is released weekly.
- Good, because OpenTofu's registry resolves the provider without any override; the lock file pins
  its hashes.
- Bad, because the provider is young (1.0.0 in December 2025) and misses a few settings.
- Neutral, because the provider's repository name says Terraform; the CLI choice is separate.

### Terraform CLI

Same provider, HashiCorp's CLI. Status: not selected.

- Good, because documentation for the provider is written against it.
- Bad, because the maintainer prefers open-source tooling and OpenTofu runs the same provider.

### Chronological API-call scripts

Replay the calls that built the setup. Status: not selected.

- Good, because every field is reachable.
- Bad, because a replay has no notion of current state, cannot show drift, and duplicates on rerun.

### UI-only setup with a runbook

Status: not selected; it was the starting point.

- Bad, because nothing verifies the result, and a fork cannot reproduce it faithfully.

### Custom provider or generic REST reconciler

Status: not selected.

- Bad, because two projects do not justify owning a provider, and a generic reconciler hides
  which fields it owns.

### Import-only adoption

Import the hand-made projects and stop at a no-op plan. Status: not selected as proof, kept as a
recovery mechanism.

- Good, because it preserves ids and data.
- Bad, because a no-op plan over imported resources proves nothing about creation from nothing,
  which is what a fork needs; the data was disposable, so rebuild-first cost nothing.

### Hosted state and credentialed auto-apply in CI

Status: not selected.

- Bad, because a management key in CI would apply from untrusted pull requests unless guarded, and
  an ephemeral CI state cannot detect drift; a private local state with an explicit apply serves
  two projects.

## More Information

- [docs/posthog-infrastructure.md](../posthog-infrastructure.md): setup, change, drift, rebuild,
  troubleshooting, and the report format.
- [docs/telemetry-dashboard.md](../telemetry-dashboard.md): what each panel means; the queries are
  the files under `infra/posthog/queries/`.
- [docs/telemetry-maintainer-runbook.md](../telemetry-maintainer-runbook.md): provider setup
  status, release prerequisites, and per-installation erasure, which stays operational.
- [ADR 0079](0079-installation-telemetry.md): the runtime telemetry decision this ADR does not
  change.
