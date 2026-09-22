# PostHog infrastructure

The PostHog projects that receive installation telemetry are defined as code under
`infra/posthog/` and applied with OpenTofu. This page is for the maintainer of this repository and
for anyone running a fork with their own PostHog account. It explains what the definition owns, what
a few API calls own because the provider cannot express them yet, what stays manual, and the exact
commands for a fresh setup, a change, a drift repair, and a deliberate rebuild. The rationale is in
[ADR 0080](adr/0080-posthog-infrastructure-as-code.md); the runtime telemetry design is in
[ADR 0079](adr/0079-installation-telemetry.md) and is not changed by anything here.

## What is owned where

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
| OpenTofu | 1.12.6 | `infra/posthog/mise.toml`, `versions.tf` (`~> 1.12.0`), CI `setup-opentofu` |
| Provider `PostHog/posthog` (registry.opentofu.org/posthog/posthog) | 1.0.21 | `versions.tf`, `infra/posthog/.terraform.lock.hcl` |
| Node | 24 | `package.json` engines through mise |
| GitHub CLI | any current | for the secret handoff only |

Renovate proposes updates for all of them. After a provider update run `scripts/posthog-infra
validate`, then `plan`; the lock file changes with the provider version.

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
copy. The same directory holds `outputs.json` (contains the capture tokens; private) and the
latest `verification-report.md`. Back the directory up before a destroy; restoring it restores
management of the same resources. Two people must not manage the same projects from two state
directories; hand the directory over instead.

## Fresh setup, as tested

```bash
scripts/posthog-infra validate            # offline: init without backend, validate
scripts/posthog-infra init                # binds the private state path
scripts/posthog-infra plan                # exit 2: everything to create
scripts/posthog-infra apply -auto-approve # bootstrap, adopt GeoIP, full apply, gaps, verify
scripts/posthog-infra plan                # exit 0: converged
scripts/posthog-infra gaps diff           # exit 0: converged
scripts/posthog-infra fixture             # test role only: 13 synthetic heartbeats, 10 panels checked
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
4. `verify`: the read-only report described below; a FAIL exits nonzero.

Then hand the production token to the release path with `release-token`. The token travels from
the private outputs file into `gh secret set` on stdin; GitHub confirms the secret's name and update
time only, so the command prints those. To check that a build consumes it, run the packaging test
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

`scripts/posthog-infra verify [path]` writes a Markdown table with one row per check: expected
value, observed value, PASS, FAIL, UNKNOWN (null or absent), or INFO, and the owner of the item. It
covers the settings above for each role, the GeoIP transformation, other transformations (none
expected), destinations and batch exports (none expected), the managed dashboard and its tiles,
sharing (off), duplicate dashboards or insights, untracked dashboards, and two organization-level
controls. The report names the OpenTofu and provider versions and the Git revision, marking a dirty
working tree. It is a point-in-time read-back by the maintainer, not independent attestation, and
it does not show what the client sends: `symphony-trello telemetry preview` does that.

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
