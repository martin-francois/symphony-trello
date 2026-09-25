# PostHog infrastructure

The PostHog projects that receive installation telemetry are defined as code under
`infra/posthog/` and applied with OpenTofu. This page is for the maintainer of this repository and
for anyone running a fork with their own PostHog account. It explains what the definition owns, what
a few API calls own because the provider cannot express them yet, what stays manual, and the exact
commands for a fresh setup, a change, a drift repair, and a deliberate rebuild. The rationale is in
[ADR 0080](adr/0080-posthog-infrastructure-as-code.md); the runtime telemetry design is in
[ADR 0079](adr/0079-installation-telemetry.md) with the erasure extension in [ADR 0082](adr/0082-authenticated-erasure-with-reporting-periods.md).

## What is owned where

The [native erasure trial](telemetry-erasure-research.md#es256-and-jose-rejected) stopped at
`BLOCKED_NATIVE_CAPABILITY`. Its `hog-probe` command uses this wrapper and selected private state,
requires `SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE=1`, and tests unsaved code with asynchronous calls
mocked. It installs no handler and changes no managed resource. Automated erasure is defined but defaults to disabled for both roles; [ADR 0081](adr/0081-gate-automated-erasure-on-hosted-verification.md)
records the gate for any future definition.

| Item | Owner | Where |
| --- | --- | --- |
| Projects (one per role: `production`, `test`), timezone UTC | OpenTofu, `posthog_project` | `infra/posthog/modules/project/main.tf` |
| Privacy settings the provider knows: IP discarding, cookieless hashing off, replay, performance, exception, web-vitals, heatmap, and survey capture off, empty authorized and replay domains, empty internal-user filter | OpenTofu, `posthog_project_settings` | same file |
| The GeoIP transformation PostHog creates with every project, kept disabled | OpenTofu, `posthog_hog_function`, adopted by the bootstrap step of `scripts/posthog-infra apply` | same file |
| Dashboard "Symphony for Trello installations", one SQL insight per panel, tile layout | OpenTofu, `posthog_dashboard`, `posthog_insight`, `posthog_dashboard_layout` | same file, queries in `infra/posthog/queries/` |
| `autocapture_opt_out`, `capture_console_log_opt_in`, `capture_dead_clicks` | API supplement `scripts/posthog-infra gaps` (`scripts/posthog-infra.ts`) | canonical values in `ENVIRONMENT_GAP_SETTINGS` |
| Dashboard fixture and expected panel results | `scripts/posthog-infra fixture` | `infra/posthog/fixtures/dashboard-fixture.json` |
| Release capture token in the GitHub secret `POSTHOG_PROJECT_TOKEN` | `scripts/posthog-infra release-token <owner/repo>` | one tested handoff, never a second owner |
| Organization: EU region, legal agreements, `is_ai_training_opted_in`, public-sharing allowance, member access | Manual, organization settings; read back by `verify` as shared controls | see "Manual prerequisites" |
| Retention (`event_retention_months`, `events_retention_enforced`) | Read-only; the API returned no value for these projects | reported as UNKNOWN |
| PostHog's starter dashboard, the "Internal / Test users" cohort, `primary_dashboard` | Untracked server defaults, harmless; listed by `verify` | leave alone |

The configuration is one root (`infra/posthog/*.tf`) calling one module twice. Both roles get the
same privacy configuration; they differ in name only. Nothing in Git names an existing project,
dashboard, insight, or token: every identifier flows from `posthog_project` outputs, and the only
server-created resource that must be adopted (GeoIP) is looked up by scope during bootstrap.

## Tested versions

| Tool | Version | Pinned in |
| --- | --- | --- |
| OpenTofu | 1.12.6 | `infra/posthog/mise.toml` and the CI `setup-opentofu` step (exact, both Renovate-owned); `versions.tf` keeps a `>= 1.12.0` floor |
| Provider `PostHog/posthog` (registry.opentofu.org/posthog/posthog) | 1.0.21 | `versions.tf`, `infra/posthog/.terraform.lock.hcl` |
| Node | 24 | `package.json` engines through mise |
| GitHub CLI | any current | for the secret handoff only |

Renovate proposes updates for all of them; provider lookups and lock hashes come from the OpenTofu
registry through a package rule in `renovate.json`, and the `required_version` floor is excluded so
Renovate never proposes Terraform releases for it. After a provider update run
`scripts/posthog-infra validate`, then `plan`; the lock file changes with the provider version, and
the CI job's `init` fails on a lock file whose hashes do not match.

## Prerequisites

1. A PostHog Cloud EU account and an organization you administer. The projects are created inside
   it; nothing here creates or changes the organization, its subscription, or its legal agreements.
2. A personal API key (Settings, Personal API keys) with these scopes: `organization:read`,
   `project:read`, `project:write`, `hog_function:read`, `hog_function:write`, `insight:read`,
   `insight:write`, `dashboard:read`, `dashboard:write`, `query:read`, and `sharing_configuration:read`.
   Store it in a file readable only by you, by default `~/posthog-personal-api-key`; another path
   goes in `SYMPHONY_TRELLO_POSTHOG_KEY_FILE`. Every command reads the file and hands the key to the
   child process only. Nothing prints it.
3. Room for two more projects in the organization. Project names must be unique within an
   organization (case-insensitive), so a leftover project with one of the names blocks creation
   with HTTP 409; rename or delete it first.
4. `mise install` inside `infra/posthog/` (or any OpenTofu 1.12.x on the path), Node 24, and for the
   secret handoff an authenticated `gh` with write access to the destination repository.
5. Inputs: copy `infra/posthog/terraform.tfvars.example` to `infra/posthog/terraform.tfvars`
   (ignored by Git) and set `repository` (your fork, `owner/name`), `release_version`, and
   `release_date`. `organization_id` defaults to `@current`, the organization of the key's owner;
   set the UUID when you belong to several.

State lives outside the repository in `~/.local/state/symphony-trello/posthog/` (override with
`SYMPHONY_TRELLO_POSTHOG_STATE_DIR`), mode 700, with OpenTofu's local lock file and `.backup`
copy. The same directory holds OpenTofu's data directory `.terraform/` with the initialized
backend, `outputs.json` (contains the capture tokens; private), the fixture run files, and the
latest `verification-report.md`. Because the data directory sits inside the state directory,
selecting another state directory selects another deployment entirely; every state command first
reads the backend recorded in that data directory and refuses to run when it names a different
state file (`scripts/posthog-infra state-path` prints it). Back the directory up before a
destroy; restoring it restores management of the same resources. Two people must not manage the
same projects from two state directories; hand the directory over instead.

A checkout initialized before this layout kept the data directory under `infra/posthog/.terraform`
and would have followed the first state it was initialized for. Run `scripts/posthog-infra init`
once per state directory to bind the new location; the state file itself does not move, no cloud
resource changes, and the old `infra/posthog/.terraform` directory can be deleted.

## Fresh setup, as tested

```bash
scripts/posthog-infra validate            # offline: init without backend, validate
scripts/posthog-infra init                # binds the private state path
scripts/posthog-infra plan                # exit 2: everything to create
scripts/posthog-infra apply -auto-approve # bootstrap, adopt GeoIP, full apply, gaps, verify
scripts/posthog-infra plan                # exit 0: converged
scripts/posthog-infra gaps diff           # exit 0: converged
scripts/posthog-infra fixture             # test role only: a fresh run-owned cohort, 10 panels checked
scripts/posthog-infra release-token <owner/name>   # production token into that repo's POSTHOG_PROJECT_TOKEN
```

What `apply` does, in order:

1. Bootstrap, only when the state lacks a role's GeoIP resource: applies the two `posthog_project`
   resources alone (`-target`), reads each new project id from the state, looks up the one
   transformation with template `template-geoip` in that project (refusing zero or several), and
   imports it as `module.<role>.posthog_hog_function.geoip`. This is the only targeted apply and the
   only import in the workflow; it exists because PostHog creates that function enabled and a
   declared function without the import would create a second one.
2. Full apply: settings, the adopted GeoIP function set to disabled, the dashboard, ten insights
   from the query files, and the tile layout.
3. `gaps apply`: reads each environment, patches only the differing fields among the three settings
   the provider lacks, and reads back until the server shows them (five attempts, two seconds
   apart); a setting the server accepts but does not honor fails the run.
4. `verify`: the read-only report described below; anything but the outcome `verified` exits
   nonzero.

Then hand the production token to the release path with `release-token`. The command first
prints which state file and production project the token comes from, then the token travels from
the private outputs file into `gh secret set` on stdin; GitHub confirms the secret's name and
update time only, so the command prints those. To check that a build consumes it, run the packaging test
in `ReleasePackagingScriptTest` or `scripts/package-release-assets.sh` with
`SYMPHONY_TRELLO_POSTHOG_PROJECT_TOKEN` set from the same file; source builds and CI stay
non-sending because the repository keeps the placeholder.

## Changing something

1. Edit the definition: `.tf` files for anything the provider owns, a `.sql` file for a panel,
   `dashboard-fixture.json` when a panel's expected result changes, `ENVIRONMENT_GAP_SETTINGS` in
   `scripts/posthog-infra.ts` for the three API-owned settings.
2. `scripts/posthog-infra fmt` and `validate`; `pnpm run verify:scripts` when the TypeScript changed.
3. `scripts/posthog-infra plan` and, for the gap settings, `gaps diff`. Read the plan: a
   replacement of `posthog_project` deletes a project and its data.
4. `scripts/posthog-infra apply`, then `fixture` when a query changed. The report is rewritten.
5. Update `docs/telemetry-dashboard.md` when a panel's meaning changed, and the ADR when a tradeoff
   changed. Commit the definition and the doc together.

A change made in the PostHog UI is drift. `plan` shows it for provider-owned fields (tested with a
project rename and a re-enabled GeoIP transformation), `gaps diff` for the three API-owned settings
(tested with `autocapture_opt_out`). Reapplying restores the definition without recreating anything.
When a manual change was needed in an emergency, put it into the definition immediately and apply,
so the next plan is empty again.

## Destroy and rebuild

`scripts/posthog-infra destroy -auto-approve` deletes every managed resource, projects included,
and removes the private outputs file. PostHog deletes a project asynchronously: it disappears from
the organization's project list at once and its data is removed later. To rebuild from nothing,
move or delete the state directory only after the remote resources are gone, then run the fresh
setup again; new ids and tokens result, and the release secret must be handed over again. Do not
delete the state directory while the projects still exist: that orphans them and the next apply
creates duplicates that the unique-name rule then rejects.

Before the first public release this was the tested path (see ADR 0080). After a release, deleting
the production project or rotating its capture token discards real usage data and silences every
installed copy until the next release; that needs an explicit maintainer decision, recorded in the
ADR, not a routine apply. OpenTofu's `prevent_destroy` lifecycle rule on `posthog_project` is the
one-line way to enforce that decision once it is taken.

## Verification report

`scripts/posthog-infra verify [path]` writes a Markdown table with one row per check: whether the
check is required, expected value, observed value, PASS, FAIL, UNKNOWN (null, absent, or the wrong
type), or INFO, and the owner of the item. The outcome at the top is `verified` only when every
required check passed; `drift` when any check failed; `incomplete` when a required value could
not be read. Only `verified` exits 0, so a missing `anonymize_ips` field can never pass silently.
Advisory rows (the retention fields, which no resource manages, and the organization controls)
are recorded but never make a deployment verified on their own. The expected privacy values come
from the `privacy_policy` output of the definition, so the report compares the live project with
the applied declaration rather than with a second list. It covers each role's settings, the GeoIP
transformation, other transformations (none expected), destinations and batch exports (none
expected), the managed dashboard and its tiles, public sharing on every dashboard and every
insight of the project (untracked ones included), duplicates, and the organization that owns the
projects, read by its id rather than by listing every organization the key can reach. The report
names the OpenTofu and provider versions and the Git revision, marking a dirty working tree. It
is a point-in-time read-back by the maintainer, not independent attestation, and it does not show
what the client sends: `symphony-trello telemetry preview` does that.

### Fixture runs

`scripts/posthog-infra fixture` creates a run file under the state directory with fresh
installation ids for the documented installations and a fixed event uuid per report, sends the
events, waits for exactly those uuids to be queryable, runs each canonical query file scoped to
that cohort (the heartbeat filter gains `AND distinct_id IN (...)`; nothing else in the query
changes), compares with the expected results (the activity windows and registration months are
computed from the run's reference time), and finally queues deletion of the cohort's profiles and
events. Old fixture events from earlier runs, the erasure harness, or anything else in the test
project cannot satisfy or disturb a run. A run that stopped before its events became visible is
retried with the same run file and resends the same events; a finished run file is never reused.
Cleanup completes in PostHog's deletion batch, separately from the panel result.

## Troubleshooting

- `Error creating project ... 409 conflict`: a project with that name exists in the organization.
  Rename the old one (`PATCH /api/organizations/<org>/projects/<id>/`) or retire it with
  `scripts/posthog-infra retire-project <id> "<exact name>"`.
- `Provider produced inconsistent result after apply` on `posthog_project_settings`: PostHog
  accepted but ignored a setting, usually one gated by plan. The provider names the attribute.
  Check the organization's plan before changing the definition.
- `no template-geoip transformation found` or `refusing to choose`: the project has zero or several
  GeoIP functions. Inspect them in Data pipeline, Transformations; the bootstrap adopts exactly one.
- `listing reports N entries but the pages held M`: a paginated read was incomplete; rerun, and if
  it persists the key may lack a read scope on some entries.
- `HTTP 403 ... missing scope`: add the scope to the personal API key.
- The plan wants to recreate insights after a provider update: read the provider changelog first;
  a recreation changes insight ids and the layout follows, but no data is lost.
- `gaps apply` reports a setting still unchanged: the environment serializer rejected it silently;
  compare with the API schema and the provider issue tracker before forcing anything.
- `tofu` cannot find the state: `init` again with the same `SYMPHONY_TRELLO_POSTHOG_STATE_DIR`; the
  backend path is passed at init time and is not stored in Git.
- `data directory ... is bound to ..., not to ...`: the selected state directory's data directory
  was initialized for another state file, or `TF_DATA_DIR` in the environment points elsewhere.
  Run `init` for the selected state directory, or unset `TF_DATA_DIR`.
- The secret went to the wrong repository: `release-token` takes the repository explicitly; set it
  again for the right one and remove it from the wrong one with `gh secret delete`.

## What cannot be recreated

The organization, its subscription and legal acceptances, historical events of a deleted project,
the original project ids and tokens, and anything PostHog keeps internally. A feature missing from
the provider (retention settings, sharing configuration, batch exports, `person_processing_opt_out`)
is unavailable to the definition, not misconfigured; the report marks it UNKNOWN or INFO rather than
PASS.

Per-installation erasure is an operational procedure, not infrastructure; it stays in
[docs/telemetry-maintainer-runbook.md](telemetry-maintainer-runbook.md).

## Native erasure deployment

The optional resources live in `modules/project/erasure.tf`. Both `erasure_enabled` role values
default to false. The canonical handler is `erasure-service.hog.tftpl`; the lifecycle test renders
the same file. The provider stores inputs as secrets. Its local state and variable files still
require owner-only permissions and a protected backup.

A private variable file supplies `erasure_secrets.<role>` with `active_key`, `master_keys`, and
`api_key`. Generate each master from 32 cryptographically random bytes encoded as lowercase hex.
Never replace or discard a key version while installations still use it. The API credential must
be restricted to the intended project with `person:read`, `person:write`, `feature_flag:read` and
`feature_flag:write` and `query:read`. It must not be the infrastructure administrator's credential.

Follow the normal plan, gaps diff, apply and verify sequence. Do not enable production until
[the lifecycle gate](telemetry-erasure-implementation.md) passes. OpenTofu enforces this: a
validation on `erasure_enabled` refuses `production = true` unless `erasure_lifecycle_pass` equals
the SHA-256 of the current `erasure-service.hog.tftpl`. `scripts/posthog-infra plan` and `apply`
pass that value only from a lifecycle ledger whose status is `TWO_PERIOD_LIFECYCLE_PASS` and whose
every stage ran on this handler, so any handler change needs a new hosted run.
`scripts/posthog-infra validate` runs the offline tests in `infra/posthog/tests` that prove the
refusal against a mocked provider. Verification compares the
managed handler hash, enabled state and merge filter with the canonical configuration.

After production activation, the public release variables `POSTHOG_ERASURE_ENDPOINT` and
`POSTHOG_ERASURE_AUDIENCE` carry the managed webhook URL and `symphony:<project-id>` audience.
Packaging validates and checks both in the application archive, and refuses an audience other
than `symphony:<id>` with the ID in `infra/posthog/production-project-id`. `verify` checks that
file against the production project. They contain no credential.
Leaving both variables empty preserves the original profile. Setting only one fails packaging.
Do not publish these variables before the deployment gate passes.

Operational status flags are not configuration resources. `verify` fails its advisory
`erasure.status_flags` check at 500 non-deleted flags, well below PostHog's 2,000-flag limit.
Clients request archival after saving completion; `scripts/posthog-infra erasure-flags <role>
[--archive]` reviews and archives the rest as the maintainer runbook describes. Never interpret a
missing flag as proof of completion.
