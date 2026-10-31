# GitHub Actions security

## Scope

Rules for every file under `.github/workflows`, and the audit of the current workflows against
them. Version pinning policy for dependencies lives in [Dependency updates](dependency-updates.md).
Each privileged workflow's design lives in its ADR; this page says what any workflow may do.
Where step code lives and how a job loads it is in [GitHub Actions workflows](github-actions.md).

The attack these rules close is the one that hit TanStack on 2026-05-11
([postmortem](https://tanstack.com/blog/npm-supply-chain-compromise-postmortem),
[GHSA-g7cv-rxg3-hmpx, CVE-2026-45321](https://github.com/advisories/GHSA-g7cv-rxg3-hmpx)). A fork
pull request started `bundle-size.yml` on `pull_request_target`, which ran the fork's build in the
base repository's context. When the job ended, `actions/cache`'s post step saved the now poisoned
pnpm store under the key the release workflow used. `permissions: contents: read` does not gate
that save. The next push to `main` restored the store into `release.yml`, where the attacker's
binaries read the OIDC token out of runner memory and published 84 malicious versions with valid
provenance. TanStack's fixes were the same four this page requires: restructure the workflow, add
repository-owner guards, pin third-party actions by SHA, purge the caches.

A privileged run here means a run on `pull_request_target`, `workflow_run` or `issue_comment`, or
any job that holds a repository or environment secret or `id-token: write`. A write permission on
a `pull_request` job is not one: GitHub drops it for fork runs, so it reaches only same-repository
branches whose authors already hold write access. The audit table records each such job.

## Rules

Every rule names its check. `scripts/github-actions-security.test.ts` runs in the `script-tests`
job through `pnpm run verify:scripts`; CodeQL scans the `actions` language on pull requests to
`main` that change more than release metadata, and weekly on `main`; Scorecard runs weekly on
`main`. What those cannot see, pull-request review checks.

### Privileged runs

- Never check out, fetch, build, install, test or run anything a pull request controls. That
  covers `actions/checkout` with `ref` or `repository` from the PR head or merge ref, `git fetch`
  or `gh pr checkout` of it, scripts or config read from it, `npm`, `pnpm` or `mvn` run on its
  files, and artifacts from a PR run used before validation. The checkout is not the exploit; the
  next step that runs what it checked out is
  ([GitHub: securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target),
  [GitHub Security Lab: preventing pwn requests](https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/)).
  Check: the script test rejects a checkout in a privileged job that has a `repository` input
  or a `ref` other than a base SHA or the Release Please job's release ref, and checks that the
  step producing `base_sha`, or the module it loads, reads the pull request's base. A base SHA is
  a `base_sha` step output or `github.event.pull_request.base.sha`. It is also `github.sha` in a
  workflow whose every trigger is in `TRIGGERS_WITH_A_TRUSTED_SHA`, the set
  [GitHub Actions workflows](github-actions.md) lists: there it is the commit the workflow file
  was read from, never pull request code. On `pull_request` it is the pull request's merge commit
  and on `push` whatever was pushed, so a workflow with either trigger gets no `github.sha`
  checkout in a privileged job. CodeQL's `actions/untrusted-checkout` query; review for
  hand-written fetches.
- Metadata-only work is fine: read the event payload, call the API, set labels, post comments and
  statuses. Treat every payload field as data, never as a command or a path.
- Pass each event value into a step through `env:`, never as `${{ github.event... }}` inside
  `run:` or a script body
  ([GitHub: secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)).
  Check: the script test rejects `${{` in any `run:` block; CodeQL's `actions/code-injection`
  query.
- Declare a read-only `permissions` block at the top of the workflow (`read-all`, `{}` or read
  grants only) and grant each job only what it needs. Check: the script test for the top-level
  block; Scorecard Token-Permissions; review for the per-job grants.
- Set `persist-credentials: false` on every checkout. The exceptions are a step that pushes with
  the token and a checkout in a trusted push-to-`main` job that already holds the same token in
  `env:`; the script test lists each one with its reason. Check: the script test.
- Keep the guards the privileged workflows use: `head.repo.full_name == github.repository` before
  any same-repository assumption, the sender or author login for bot-only paths, and branch or
  label names only after those. A fork can reuse any branch name. Check: each workflow's script
  test, listed in its ADR.

### Caches

- No cache action, no input that names a cache on any action (`cache`, `cache-dependency-path`,
  `cache-from`, `cache-read-only` and the like), and no other cache restore or save in a workflow
  with a privileged trigger, or in a job that holds a repository secret, `id-token: write` or a
  deployment environment. No `cache-mode` key in a workflow with a privileged trigger.
  The release and publish jobs hold those grants, so they build from a clean runner. Check: the
  script test.
- Caches on `pull_request` are safe. GitHub keys them to the pull request's merge ref, so a run on
  `main` cannot restore them and a pull request run cannot write to `main`'s scope
  ([GitHub: dependency caching, restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)).
  GitHub now gives `pull_request_target`, `workflow_run` and `issue_comment` runs read-only
  access to `main`'s scope by default, but the rule above does not rely on that default, because
  a `cache-mode: write` key at the workflow or job level turns writes back on.

### Untrusted code

- Run pull request code only on `pull_request`, with no secret beyond the automatic token and
  with fork-run approval left at "all external contributors" in the repository settings. Give
  the job read permissions; a write grant there reaches only same-repository branches, as the
  Scope section says, and the audit table records each one. Check: the script test for the
  top-level block; review for the per-job grants; the repository setting is outside version
  control, so the audit below records it.

### Pinning

- Pin every action to a full commit SHA and keep the version in a comment after it. Pin container
  images by digest with the tag beside the digest. Check: the script test for actions; the
  `Renovate looks up ...` tests in `scripts/openrewrite-renovate-workflow.test.ts` for images;
  Scorecard Pinned-Dependencies.

### Release and publish

- `id-token: write`, the Maven Central secrets and the GitHub App key belong only in jobs that
  run from the default branch, on `push`, `schedule`, `branch_protection_rule`, a tag, or through
  Release Please, never in a job a pull request event can reach. The one exception is the OpenRewrite `publish` job: it
  mints a short-lived App token on `pull_request_target` for Renovate's same-repository pull
  request only, never checks out or runs pull request content, and
  [ADR 0071](../adr/0071-automate-openrewrite-renovate-updates.md) records its guards. Check:
  pull-request review against the audit table.

### Review rule

- A pull request that adds or edits a workflow states in its body which rules above apply and how
  the change meets them. Check: pull-request review; the Validation section of the template is
  where it goes.

## Workflows added after the audit

`hyperframes-renovate.yml` (2026-10-31,
[ADR 0149](../adr/0149-check-hyperframes-updates-by-rendering-in-ci.md)). Both jobs are gated to
`head.repo.full_name == github.repository`, then author `renovate[bot]`, then a
`renovate/hyperframes` branch, so a fork never reaches them. `readme-demo-render` is an ordinary
`pull_request` job: it checks out the merge commit without a token, holds `contents: read` and no
secret, runs the HyperFrames CLI from npm, downloads the README video attachment over HTTPS
and discards the render; it pushes nothing. `report`
checks out only `scripts/report-hyperframes-render` from `github.event.pull_request.base.sha`
without a token, so no pull request code runs with its `pull-requests: write`, which a
same-repository author already has; it adds a label and edits one comment that it recognizes by
author `github-actions[bot]` and a marker.

## Audit of 2026-10-30

Repository settings at the time: fork-run approval `all_external_contributors`; the `Required
merge checks` ruleset requires `test`, `code-change`, `pinned-betterleaks-image`,
`pinned-powershell-image` and `pinned-maven-snapshot-image`, a pull request, and resolved threads.
The script test keeps the table complete; update a row when its workflow changes.

| Workflow | Triggers | Checkout | Cache | Secrets or write token | Verdict |
| --- | --- | --- | --- | --- | --- |
| `ci.yml` | `pull_request`, push `main` | PR head, no token | `maven`, PR-scoped; none in `posthog-infra` | none, `contents: read` | OK |
| `codeql.yml` | `pull_request`, push `main`, schedule | PR head, no token | `maven`, PR-scoped | `security-events: write`, read-only in fork runs | OK |
| `coderabbit-config-guard.yml` | `pull_request_target` | `github.sha`, sparse: its script, no token | none | `pull-requests: read` | OK, metadata only |
| `commitlint.yml` | `pull_request` | PR head, no token | none | none, read | OK |
| `compatibility-labeler.yml` | `pull_request_target`, `issues` | `github.sha`, sparse: its script, no token | none | `issues`, `pull-requests: write` | OK, metadata only |
| `continuous-fuzzing-watchdog.yml` | push `main`, `workflow_run` of `Continuous Fuzzing`, dispatch | default branch, no token | none | `actions: write` | OK; `workflow_run` is gated to dispatch runs on `main` and checks out `main` |
| `continuous-fuzzing.yml` | `pull_request`, push `main`, dispatch | default branch or PR head, no token | none | `security-events: write` in `code-change`, read-only in fork runs; `CLUSTERFUZZLITE_STORAGE_TOKEN`, `issues`, `security-events` and `actions: write` in jobs gated to `refs/heads/main` and dispatch | OK |
| `dependency-submission.yml` | push `main` | `main`, no token | `maven`, `main` scope | `contents: write` | OK; no pull request trigger, see [ADR 0148](../adr/0148-submit-maven-dependencies-only-from-main.md) |
| `hyperframes-renovate.yml` | `pull_request` by `renovate[bot]` on same-repository `renovate/hyperframes` branches, skipped for forks | PR merge commit in the render, `base.sha` in `report`, no token | none | `contents: read` in the render; `pull-requests: write` in `report` | OK, see [Workflows added after the audit](#workflows-added-after-the-audit) |
| `openrewrite-renovate.yml` | `pull_request_target`, schedule, dispatch | `github.sha` for its scripts, `base_sha` for the recipe run, no token outside `publish` | none | `statuses: write` in six jobs; App token in `publish` only | OK, see below |
| `private-context.yml` | `pull_request`, push `main` | PR head plus base scanner, plus the merge commit's comparison script, no token | none | none, `contents: read` | OK |
| `pull-request-author-handoff.yml` | `pull_request_target`, `issue_comment` | `github.sha`, sparse: its script, no token | none | `issues: write` | OK, metadata only |
| `pull-request-disclosure.yml` | `pull_request_target`, schedule, dispatch | `github.sha`, sparse: its script, no token | none | `issues`, `pull-requests: write` | OK, metadata only |
| `release-please.yml` | push `main` | `main` and the release tag, token kept | none | `id-token: write`, the PostHog capture token and erasure variables in the asset build step, Maven Central secrets in a separate job | OK |
| `scorecard.yml` | `branch_protection_rule`, schedule, push `main` | `main`, no token | none | `id-token: write` | OK |
| `semgrep.yml` | `pull_request`, push `main` | PR head, no token | none | none, `contents: read` | OK |
| `size-labeler.yml` | `pull_request_target`, `workflow_run`, schedule | `github.sha`, sparse: its script, no token | none | `pull-requests: write` | OK, metadata only |

`openrewrite-renovate.yml` in detail. Every job that loads a `scripts/*.cjs` module first checks
it out at `github.sha`, sparse and without the token. `generate` then checks out `base_sha`
twice, the head of `main` that Renovate's pull request is based on, as reported by the API;
nothing checks out the source head. The only pull request content that reaches a step is
`pom.xml` from the source head, read through the contents API and accepted only when
`trusted/scripts/openrewrite-renovate.ts` from the
base shows that it differs from the base `pom.xml` in allowlisted version properties alone. Maven
then runs on that tree inside a pinned container with no runner token. `generate` holds
`contents: read` and `pull-requests: read`. The App token exists only in `publish`, which checks
out the base branch and consumes an artifact that `scripts/validate-openrewrite-status-artifact`
and `scripts/publish-openrewrite-result` validate before use. A fork pull request on the branch
name `renovate/openrewrite-toolchain` fails `head.repo.full_name == github.repository` in
`invalidate-derived-source`, `attest-source`, `resolve`, `invalidate-reconciled-source`,
`generate` and `report-source-validation`; `verify-derived-source` serves any pull request on
`main` but only posts statuses, and `publish` revalidates the same fields in
`scripts/publish-openrewrite-result` before it pushes. `generate` runs only when
`invalidate-reconciled-source` succeeded, which needs the `OpenRewrite source provenance` status
that `attest-source` sets only when the webhook sender is `renovate[bot]`.

Nothing exploitable was found. No workflow could write `main`'s cache scope from a pull request.
[ADR 0140](../adr/0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md) records what
the audit changed. Open for the owner, with issue drafts handed over with the audit: whether the
Release Please checkouts can drop the token.

## References

- [GitHub Actions workflows](github-actions.md)
- [Dependency updates](dependency-updates.md)
- [Default workflow](default-workflow.md)
- [ADR 0140](../adr/0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md)
- [ADR 0149](../adr/0149-check-hyperframes-updates-by-rendering-in-ci.md)
- [ADR 0069](../adr/0069-repository-owned-pull-request-size-labeling.md),
  [ADR 0071](../adr/0071-automate-openrewrite-renovate-updates.md),
  [ADR 0075](../adr/0075-automate-pull-request-author-handoffs.md),
  [ADR 0085](../adr/0085-enforce-pull-request-template-with-coderabbit.md),
  [ADR 0124](../adr/0124-look-up-betterleaks-through-the-github-package-feed.md)
