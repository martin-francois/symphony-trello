---
status: accepted
date: 2026-10-12
decision-makers: [François Martin, Claude]
consulted:
  - "[ADR 0054](0054-powershell-installer-verification-runtime.md)"
  - "[ADR 0124](0124-look-up-betterleaks-through-the-github-package-feed.md)"
  - "[ADR 0125](0125-read-mcr-push-times-for-the-dotnet-sdk-image.md)"
  - "[Dependency upgrade confidence](../testing/dependency-upgrade-confidence.md)"
  - "[GitHub PR #901](https://github.com/martin-francois/symphony-trello/pull/901)"
  - "[GitHub Docs: troubleshooting required status checks](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/collaborating-on-repositories-with-code-quality-features/troubleshooting-required-status-checks)"
informed: [Future maintainers, Contributors]
---

# Run PowerShell Tests On The Pinned .NET SDK Image

## Context and Problem Statement

`scripts/pwsh-docker.sh` runs `pwsh` from `mcr.microsoft.com/dotnet/sdk`, pinned by tag and digest.
Since [ADR 0125](0125-read-mcr-push-times-for-the-dotnet-sdk-image.md), Renovate proposes updates
for that image. Digest updates automerge like every other non-major update. Majors wait for a
maintainer; PR #901 moved the image from 8.0 to 10.0 that way.

No required check started the image. The `test` job only checks the shape of the image reference,
and the PowerShell tests skip there because `SYMPHONY_TRELLO_TEST_PWSH` is unset.
`windows-powershell` runs the runner's own `pwsh`, as
[ADR 0054](0054-powershell-installer-verification-runtime.md) decided. An image that could not run
the installers would therefore merge as soon as the other checks passed. That breaks the rule in
[Dependency upgrade confidence](../testing/dependency-upgrade-confidence.md) that a dependency
automerges only when a required check exercises it.

## Decision Drivers

* A .NET SDK image update must run the PowerShell installer checks before it merges.
* The update keeps the ordinary non-major automerge, with no manual step.
* Normal pull requests do not pull the image; ADR 0054 removed that cost from Linux CI.
* A check that is required must report on every pull request, including the ones that skip the
  work.

## Considered Options

* A required job that runs the PowerShell checks through the wrapper when a pull request changes it.
* Run the PowerShell checks through the wrapper on every pull request.
* Turn off automerge for .NET SDK image updates.
* A separate workflow limited to the wrapper with an `on.pull_request.paths` filter.

## Decision Outcome

Chosen option: "A required job that runs the PowerShell checks through the wrapper when a pull
request changes it", because it exercises every image update before merge and costs a normal pull
request one checkout.

The `pinned-powershell-image` job in the CI workflow runs on every pull request. It checks out the
merge commit GitHub builds for the pull request with its first parent, which is the base branch
tip, and compares `scripts/pwsh-docker.sh` between the two. When the file is unchanged, the job
reports a notice and succeeds. When it changed, the job runs `scripts/check-pwsh-docker`. That
script runs the install and uninstall dry runs through the wrapper, then
`InstallerScriptTest#*powershell*+*powerShell*` with `SYMPHONY_TRELLO_TEST_PWSH` pointing at the
wrapper. The dry runs go first so they pull the image; a single installer test has 60 seconds. A
PowerShell test skips itself when the wrapper is unusable, and Maven counts skips as passes, so
the script fails unless the test report shows zero skips.

The job watches only `scripts/pwsh-docker.sh`, so the image must be pinned there and nowhere else.
The Renovate custom manager for the image therefore matches that file alone. Before this decision it
also listed `.github/workflows/ci.yml`, which stopped pinning the image when ADR 0054 moved the
PowerShell checks to Windows.

Unlike the `pinned-betterleaks-image` job from ADR 0124, this job runs the pull request's own
scripts and tests. The `test` job already runs pull request code the same way, and the job has
`contents: read`, a checkout without stored credentials and no secret, so it needs no trusted copy
of the base branch.

The `Required merge checks` ruleset lists `pinned-powershell-image`. GitHub merges an automerged
image update only after the job passed with the image the pull request pins.

### Consequences

* Good, because a .NET SDK image that cannot run the installers fails its own pull request.
* Good, because image updates keep automerging without a maintainer.
* Good, because a pull request that leaves the wrapper alone pulls no image and finishes the job in
  seconds.
* Good, because the job also covers hand-written changes to the wrapper, such as new mounts or
  runtime detection.
* Bad, because the required check lives in the repository ruleset, outside version control. The
  script test covers the workflow, not the ruleset.
* Bad, because the job tests a Linux PowerShell in a container, not Windows. It answers whether the
  image runs the installers, which is the only question an image update raises; `windows-powershell`
  still covers Windows behavior.
* Neutral, because a change to `scripts/check-pwsh-docker` or to the PowerShell tests alone does not
  start the image. Those run on Windows in `windows-powershell`.

### Confirmation

This decision remains implemented when:

* the script test `.NET SDK image updates automerge only after PowerShell tests through the image
  they pin` passes;
* `gh api repos/martin-francois/symphony-trello/rulesets --jq '.[].name'` lists `Required merge
  checks`, and that ruleset's `required_status_checks` include `pinned-powershell-image`; and
* the next .NET SDK image update shows `pinned-powershell-image` running
  `PowerShell installer checks through the pinned image` with 0 skipped tests.

## Pros and Cons of the Options

### A Required Job That Runs The PowerShell Checks Through The Wrapper When A Pull Request Changes It

* Good, because the check runs exactly when the image or the wrapper changes.
* Good, because the job always reports, so the ruleset can require it.
* Bad, because the change detection is repository code that has to name the right file.

### Run The PowerShell Checks Through The Wrapper On Every Pull Request

* Good, because there is no change detection to get wrong.
* Bad, because every pull request pulls an image of about 1 GB and runs 53 PowerShell tests in it,
  which ADR 0054 removed from Linux CI on purpose.

### Turn Off Automerge For .NET SDK Image Updates

* Good, because it needs no new job.
* Bad, because a maintainer would have to test and merge every digest update by hand. Microsoft
  pushes a new digest for every .NET servicing release and every base-image patch (ADR 0125).
* Bad, because the repository keeps automerge for updates that only the new version can test and
  adds a required check instead, as ADR 0124 did for BetterLeaks.

### A Separate Workflow Limited To The Wrapper With An `on.pull_request.paths` Filter

* Good, because GitHub skips the whole workflow for other pull requests.
* Bad, because a workflow skipped by a path filter reports no check at all. A required check that
  never reports keeps every other pull request pending, as GitHub's documentation on required
  status checks describes.

## More Information

The job runs on a GitHub-hosted Ubuntu runner, where `docker` is Docker. On a Linux host with
rootless Podman, the installer tests strip `SYMPHONY_TRELLO_CONTAINER_RUNTIME` and `XDG_*` and give
every run its own `HOME`. Per [ADR 0054](0054-powershell-installer-verification-runtime.md), the
wrapper runs the runtime with the account's home from `getent passwd`, so the image is no longer
pulled once per test home
([GitHub issue #777](https://github.com/martin-francois/symphony-trello/issues/777)).
`scripts/check-pwsh-docker` still meets
[GitHub issue #823](https://github.com/martin-francois/symphony-trello/issues/823), no
`--userns=keep-id` behind the podman-docker shim. That fix changes only how the wrapper calls the
runtime, so this job runs it as soon as its pull request touches the wrapper.
