---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #616](https://github.com/martin-francois/symphony-trello/issues/616)"
  - "[ADR 0074](0074-hyperframes-readme-demo-video.md)"
  - "[ADR 0071](0071-automate-openrewrite-renovate-updates.md)"
  - "[ADR 0073](0073-test-renovate-managed-pins-by-contract.md)"
  - "[Renovate regex manager documentation](https://docs.renovatebot.com/modules/manager/regex/)"
  - "[Mend-hosted Renovate app configuration](https://docs.renovatebot.com/mend-hosted/hosted-apps-config/)"
  - "[Renovate updating and rebasing documentation](https://docs.renovatebot.com/updating-rebasing/)"
informed: [Future maintainers, Contributors]
---

# Let Renovate Update HyperFrames and Re-render the README Demo by Hand

## Context and Problem Statement

[ADR 0074](0074-hyperframes-readme-demo-video.md) renders the README demo with an exact HyperFrames
CLI version that `pnpm dlx` fetches on demand. The CLI version also selects the HyperFrames Docker
renderer image. The repository has no `package.json` for the demo, so Renovate's built-in npm manager
never saw that version. The version was written in `scripts/render-readme-demo.ts` and repeated in
three maintenance commands in `docs/demo/README.md`.

A HyperFrames update can change browser capture, layout checks, encoding, the MP4, or the poster.
The render manifest therefore treats the render scripts as inputs: a version-only change makes the
README demo freshness check fail until media rendered with the new version is committed. That check
is correct and must stay.

How should Renovate discover and propose HyperFrames updates, where should the version live, and who
produces the new media for an update?

## Decision Drivers

* Renovate MUST discover the version and open update pull requests for it.
* One machine-readable place MUST define the version. The render script, the renderer selection,
  validation, and the documented maintenance commands MUST follow it automatically.
* An update MUST NOT bypass the freshness check, drop the version from the render-input digest, or
  refresh the manifest without a successful render.
* Update pull requests MUST NOT merge automatically, because a maintainer has to review the new
  media. Major updates keep the repository-wide `major update` policy.
* The seven-day release age applies to HyperFrames like every other dependency.
* Any automation that writes regenerated media MUST verify the Renovate branch and MUST NOT run
  pull-request-controlled code with write credentials.
* CI MUST prove that Renovate recognizes the pin and that a version-only change stays stale.

## Considered Options

For the version location and discovery:

* An exact version in `scripts/readme-demo-hyperframes.ts`, read by a Renovate regex manager.
* HyperFrames as a `devDependency` in the root `package.json`, read by Renovate's npm manager.
* The version in the render script plus one regex manager pattern per documented command.

For producing the media of an update:

* A maintainer renders locally on the Renovate branch while the freshness check blocks the merge.
* Renovate `postUpgradeTasks` render the media before Renovate commits.
* A companion GitHub Actions workflow renders the media and publishes it.

## Decision Outcome

Chosen options: "An exact version in `scripts/readme-demo-hyperframes.ts`, read by a Renovate regex
manager" and "A maintainer renders locally on the Renovate branch while the freshness check blocks
the merge", because together they meet every requirement with no new privileged automation, and the
human review that a renderer update needs remains a required step.

`scripts/readme-demo-hyperframes.ts` exports `HYPERFRAMES_VERSION` as an exact `x.y.z` string and
derives the `hyperframes@<version>` package and the `pnpm dlx` arguments from it. The render script
imports it for `check` and `render`. The documented `lint`, `check`, and `preview` commands use
`node scripts/readme-demo-hyperframes-cli.ts`, which imports the same module, so no document repeats
the version. The module is listed as a render input in `scripts/readme-demo-manifest.ts`, which keeps
the version in the render-input digest. Its path matches the existing `.gitattributes` rule for
`scripts/readme-demo-*.ts`, so no attribute change was needed.

A regex manager in `renovate.json` reads that one line as the npm package `hyperframes`. A package
rule sets `automerge: false`, clears inherited groups, adds the `hyperframes` label, and writes the
render and review steps into the pull-request body. The rule comes before the major-update rule, so
majors still receive the `major update` label and manual merge. No rule changes `minimumReleaseAge`.

Renovate's update then changes only the version line. The freshness check fails on that pull request
until a maintainer checks out the branch, runs `node scripts/render-readme-demo.ts`, reviews the
media, and commits the MP4, poster, and manifest. The maintainer also replaces the README inline
video attachment, which GitHub only accepts through its web editor. `docs/demo/README.md` documents
the update and recovery steps.

No workflow renders or publishes the media now. Each update already needs a human for the frame
review and the README attachment upload, so automation would only remove the local render command.
HyperFrames published 93 releases in the 30 days before this decision. Renovate re-proposes a newer
version on its branch until a maintainer pushes the render commit, so a rendering workflow would run
a multi-minute Docker render many times for versions nobody merges.

### Consequences

* Good, because Renovate proposes HyperFrames updates after the seven-day release age, like every
  other dependency.
* Good, because one line holds the version. Renovate rewrites that line, and the render script, the
  renderer image, and the documented commands follow it without further edits.
* Good, because the freshness check still binds the committed media to the exact renderer version.
  A version-only change cannot pass it.
* Good, because no new workflow holds write credentials or runs the updated CLI.
* Bad, because every update needs a local render with Docker, FFmpeg, Node.js, and pnpm before it can
  merge.
* Bad, because the update pull request often moves to a newer version before a maintainer renders.
  Renovate stops updating the branch once a maintainer pushes the render commit.
* Bad, because ticking Renovate's rebase checkbox after the render commit makes Renovate recreate the
  branch without it. The maintenance guide and the pull-request notes warn about this.

### Confirmation

`pnpm run verify:scripts` runs these checks in the required `script-tests` job:

* `scripts/readme-demo-hyperframes.test.ts` applies the configured Renovate regex managers to the
  version file and requires exactly one npm dependency named `hyperframes` whose value is the
  exported version. It applies a simulated newer release the way Renovate replaces the matched value
  and requires the version, package name, and `pnpm dlx` arguments to follow. It fails when any
  other tracked or untracked file names a `hyperframes@<version>` package or mentions HyperFrames
  next to the current version. It requires the HyperFrames package rule to decide
  `automerge: false` last for non-major updates, to come before the major-update rule, and to name
  the render command and all three generated files.
* `scripts/readme-demo-freshness.test.ts` changes only the version in a fixture the way Renovate
  does and requires a different source digest with unchanged media digests.

The script test applies the regex with JavaScript's engine, while Renovate uses RE2. The pattern
uses only syntax both engines read the same way: named groups, character classes, and escaped
literals. A pattern that needs RE2-only behavior would need a real Renovate run in CI.

`renovate-config-validator renovate.json --strict` passes in the required `renovate` job. A local
Renovate run with `--platform=local --dry-run=lookup` extracts `hyperframes` from
`scripts/readme-demo-hyperframes.ts` and proposes only a release older than seven days.

## Pros and Cons of the Options

### Exact version in a dedicated module read by a Renovate regex manager

`scripts/readme-demo-hyperframes.ts` holds the version on one line. A regex manager reads that line
as an npm dependency, and every consumer imports the module.

* Good, because the version has one owner and TypeScript checks every consumer.
* Good, because the demo still needs no `package.json`, lockfile, or install step.
* Good, because the module is a render input, so a version change invalidates the media.
* Bad, because the version line must keep the shape the regex expects. A test fails if Renovate no
  longer finds exactly one match.

### HyperFrames as a root `devDependency`

The root `package.json` would declare `hyperframes`, and the render script would run the installed
CLI. Renovate's npm manager and the lockfile would own the version.

* Good, because Renovate's built-in npm manager discovers the version with no custom pattern.
* Bad, because every `script-tests` run would install HyperFrames and its dependency tree, including
  `sharp`, `esbuild`, and `onnxruntime-node`, which run install scripts that pnpm only allows when
  listed explicitly.
* Bad, because the repository rule says not to add Node dependencies only to run a JavaScript CLI,
  and [ADR 0074](0074-hyperframes-readme-demo-video.md) chose an on-demand CLI for that reason.

### Version in the render script plus a pattern per documented command

The render script would keep the version, and Renovate would also match each `pnpm dlx` command in
`docs/demo/README.md`.

* Good, because the documentation keeps copy-paste `pnpm dlx` commands.
* Bad, because the version exists in several places, and each new mention needs a new pattern.
  [ADR 0073](0073-test-renovate-managed-pins-by-contract.md) rejected this for the same reason.
* Bad, because a mention that no pattern covers drifts silently.

### Local render on the Renovate branch

Renovate's pull request changes only the version. A maintainer renders on that branch, reviews the
media, commits it, and merges by hand. The freshness check blocks the merge until then.

* Good, because it needs no new workflow, token, or GitHub App permission.
* Good, because the human review the media needs happens in the same step.
* Bad, because each update needs a machine with Docker and FFmpeg.
* Bad, because pushing to the branch stops Renovate from updating it.

### Renovate `postUpgradeTasks`

Renovate would run the render command after changing the version and commit the media together with
the version change.

* Good, because the update pull request would arrive complete.
* Bad, because the Mend-hosted app only runs an undocumented allowlist of commands that can change
  over time. A Docker render is not a command the repository can rely on there.
* Bad, because the updated CLI would run inside Renovate's environment, which can write to the
  repository.

### Companion rendering workflow

A read-only GitHub Actions job would render the media for a verified same-repository Renovate branch.
A separate job with a GitHub App token would publish the result, as
[ADR 0071](0071-automate-openrewrite-renovate-updates.md) does for OpenRewrite.

* Good, because maintainers would not need a local render environment.
* Bad, because the maintainer must still review frames and upload the README attachment by hand.
* Bad, because frequent HyperFrames releases would trigger many multi-minute Docker renders for
  versions that are replaced before anyone merges them.
* Bad, because it adds a second privileged publication workflow to maintain and secure.

## More Information

The update and recovery steps are in [docs/demo/README.md](../demo/README.md). If local rendering
becomes a burden, revisit the companion workflow with a schedule that renders only the newest
version.
