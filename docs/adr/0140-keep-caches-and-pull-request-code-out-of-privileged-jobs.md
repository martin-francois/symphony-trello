---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub Actions security](../agents/github-actions-security.md)"
  - "[TanStack postmortem](https://tanstack.com/blog/npm-supply-chain-compromise-postmortem)"
  - "[GHSA-g7cv-rxg3-hmpx](https://github.com/advisories/GHSA-g7cv-rxg3-hmpx)"
  - "[GitHub: securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)"
  - "[GitHub: dependency caching](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)"
  - "[GitHub: secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)"
  - "[ADR 0069](0069-repository-owned-pull-request-size-labeling.md)"
  - "[ADR 0071](0071-automate-openrewrite-renovate-updates.md)"
  - "[ADR 0075](0075-automate-pull-request-author-handoffs.md)"
  - "[ADR 0124](0124-look-up-betterleaks-through-the-github-package-feed.md)"
informed: [Future maintainers, Contributors]
---

# Keep caches and pull request code out of privileged jobs

The audit summary of this decision is amended by
[ADR 0143](0143-keep-workflow-step-code-in-repository-files.md). The metadata-only workflows now
check out their script files at `github.sha`, sparse and without the token, still with no cache;
the rules and the audit table on the page carry the current state.

[ADR 0148](0148-submit-maven-dependencies-only-from-main.md) settled the dependency submission
question this audit left open: the workflow no longer runs on `pull_request`.

## Context and Problem Statement

On 2026-05-11 an attacker published 84 malicious versions of 42 `@tanstack/*` packages without
stealing a single credential. A fork pull request ran TanStack's `bundle-size.yml` on
`pull_request_target`, which executed the fork's build in the base repository's cache scope.
`actions/cache` saved the poisoned pnpm store on exit, because `permissions:` does not gate cache
writes. The release workflow on the next push to `main` restored that store, and the attacker's
code read the OIDC token from runner memory and published with valid provenance.

This repository has six workflows on privileged triggers and two jobs that hold publish-grade
credentials. Each privileged workflow was designed with its own ADR, but no page said what every
workflow must and must not do, and nothing checked the caches in release jobs. How should the
repository state the rules, check them, and settle the findings of a full audit?

## Decision Drivers

* A pull request must never run code or restore files in a job that holds a secret, a write
  token, or `id-token: write`.
* The rules must hold for any agent on any machine, so a test has to check what a test can check.
* Release and publish jobs must not depend on a cache that another workflow fills.
* Workflow run time on pull requests stays as it is; only jobs on `main` may get slower.
* The audit must say for every workflow what it checks out, what it caches, and what it holds.

## Considered Options

* A rules page with a script test, and drop the caches.
* Keep the caches and rely on GitHub's read-only cache scope for `pull_request_target`.
* Keep the caches with a `main`-only restore key.
* Rely on CodeQL for Actions and Scorecard alone.

## Decision Outcome

Chosen option: "A rules page with a script test, and drop the caches".

[GitHub Actions security](../agents/github-actions-security.md) holds the rules and the audit
table. `scripts/github-actions-security.test.ts` parses every workflow and fails when an action is
not pinned to a full SHA, when a `run:` block contains `${{`, when a checkout keeps its token
without being listed, when a privileged job checks out another repository or a ref other than a
base SHA or the release ref, when the step that produces `base_sha` does not read the pull
request's base, when a privileged workflow declares a `cache-mode`, or when a job with a
privileged trigger, a repository secret, `id-token: write` or a deployment environment uses a
cache action or any cache-named input. A privileged job is one in a workflow with a privileged
trigger or one that holds a secret, `id-token: write` or an environment. The page's audit table must name every workflow, and the test checks that too.

The audit found no exploitable path. No workflow could write `main`'s cache scope from a pull
request: the metadata-only workflows have no checkout and no cache, and `openrewrite-renovate.yml`
checks out only the base SHA and uses no cache. Two jobs still restored a Maven repository from
the cache while holding credentials: the Release Please job with `id-token: write`, and the
fuzzing coverage job with the storage token. Both now build from a clean runner. The CodeQL
checkout and the private-context push checkout now set `persist-credentials: false`, and one
`run:` line in the fuzzing batch job passes its matrix value through `env:` so the test needs no
exception list.

The script test lists three checkouts that keep their token: the OpenRewrite publication checkout
pushes with the App token, and the two Release Please checkouts run in a trusted push-to-`main`
job that holds the same token in `env:` for `gh`. Whether those two can drop the token is left to
the owner, because the release workflow runs only on `main` and cannot be tried on a pull request.

### Consequences

* Good, because a cache poisoned by any future workflow bug cannot reach the release job or the
  storage token.
* Good, because the mechanical rules fail a test instead of depending on a reviewer noticing.
* Good, because the audit table gives the next reviewer a starting point per workflow.
* Bad, because the release and daily coverage jobs download Maven dependencies on every run. Both
  run on `main` only, so pull requests pay nothing.
* Bad, because the rule list exists twice, as prose and as the test. The page names the test
  beside each rule so a change to one reaches the other.
* Neutral, because `dependency-submission.yml` keeps `contents: write` on `pull_request`. GitHub
  drops the write for fork runs, and a same-repository author already holds write access, so the
  audit records it rather than changing it. [ADR 0148](0148-submit-maven-dependencies-only-from-main.md)
  later removed the `pull_request` trigger.

### Confirmation

Run the script tests:

```bash
corepack pnpm run verify:scripts
```

`scripts/github-actions-security.test.ts` must pass, and reverting any of the four workflow fixes
in this change must make it fail. CodeQL's `actions` queries and the weekly Scorecard run cover the
untrusted-checkout, injection and pinning rules a second time on `main`.

## Pros and Cons of the Options

### A rules page with a script test, and drop the caches

The page states the rules and the audit; the test fails a pull request that breaks a mechanical
rule; the two credentialed jobs build without a cache.

* Good, because it closes the precondition the TanStack attack needed, whatever the other
  workflows do later.
* Good, because the test runs locally in seconds.
* Bad, because two jobs on `main` get a few minutes slower.

### Keep the caches and rely on GitHub's read-only cache scope for `pull_request_target`

GitHub now gives `pull_request_target`, `workflow_run` and `issue_comment` runs read-only access
to the default branch's cache scope by default, so a poisoned save from such a run fails with a
warning.

* Good, because nothing changes in the repository.
* Bad, because a `cache-mode: write` key at the workflow or job level turns writes back on.
* Bad, because the release job would still trust whatever the cache holds.

### Keep the caches with a `main`-only restore key

Use `actions/cache/restore` in the release job with a key that only push-to-`main` runs save.

* Good, because the release build keeps its download savings.
* Bad, because a key is a convention, not a boundary. Any privileged job that can save a cache
  can save it under that key.

### Rely on CodeQL for Actions and Scorecard alone

Both already run here and flag untrusted checkouts, injection and unpinned actions.

* Good, because no repository code is needed.
* Bad, because neither flags a cache in a credentialed job, and CodeQL findings arrive as
  alerts after the merge, not as a failing check before it.

## More Information

`docs/agents/github-actions-security.md` is the page an agent follows for every workflow change.
The review rule on that page asks each workflow pull request to say which rules apply.
