---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[ADR 0093](0093-renovate-updates-hyperframes.md)"
  - "[ADR 0133](0133-re-upload-the-readme-video-only-after-a-visible-change.md)"
  - "[ADR 0074](0074-hyperframes-readme-demo-video.md)"
  - "[ADR 0071](0071-automate-openrewrite-renovate-updates.md)"
  - "[GitHub PR #1183](https://github.com/martin-francois/symphony-trello/pull/1183)"
  - "[GitHub PR #1191](https://github.com/martin-francois/symphony-trello/pull/1191)"
  - "[GitHub issue #1185](https://github.com/martin-francois/symphony-trello/issues/1185)"
  - "[GitHub: triggering a workflow from a workflow](https://docs.github.com/en/actions/how-tos/writing-workflows/choosing-when-your-workflow-runs/triggering-a-workflow#triggering-a-workflow-from-a-workflow)"
  - "[GitHub: automatically merging a pull request](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/incorporating-changes-from-a-pull-request/automatically-merging-a-pull-request)"
  - "[GitHub community discussion #28219: no API for comment attachments](https://github.com/orgs/community/discussions/28219)"
  - "[gh CLI issue #13256: attach local files](https://github.com/cli/cli/issues/13256)"
  - "[Renovate: platformAutomerge](https://docs.renovatebot.com/configuration-options/#platformautomerge)"
  - "[Renovate 44.181.1: GitHub branch status](https://github.com/renovatebot/renovate/blob/44.181.1/lib/modules/platform/github/index.ts)"
informed: [Future maintainers, Contributors]
---

# Check HyperFrames updates by rendering in CI and keep the committed media

Amends the render-by-hand part of [ADR 0093](0093-renovate-updates-hyperframes.md): Renovate still
discovers the version the way that ADR decided, but a HyperFrames update no longer needs a render
commit. CI renders the demo with the proposed version and compares it with the README video
attachment; the committed media stay, and the update merges on its own when nothing visible
changed. It also amends the baseline of
[ADR 0133](0133-re-upload-the-readme-video-only-after-a-visible-change.md) for that check: the
downloaded attachment, not a committed video.

## Context and Problem Statement

[ADR 0093](0093-renovate-updates-hyperframes.md) made every HyperFrames update a manual job: a
maintainer checks out the Renovate branch, renders the README demo with Docker, commits the media
and merges. [ADR 0133](0133-re-upload-the-readme-video-only-after-a-visible-change.md) removed the
README attachment upload from that job for renders with no visible change, which has been every
render so far. HyperFrames publishes about three releases a day, so after the seven-day release
age Renovate proposes about three updates a day, and
[GitHub PR #1183](https://github.com/martin-francois/symphony-trello/pull/1183) and
[GitHub PR #1191](https://github.com/martin-francois/symphony-trello/pull/1191) show what each one
costs: an agent renders, pushes 9 MB of media that look the same, edits the pull request body so
the metadata check passes
([GitHub issue #1185](https://github.com/martin-francois/symphony-trello/issues/1185)), and
merges. On 2026-10-31 the owner decided that one pull request per update is fine as long as each
one merges on its own, and that a render with no visible change is not committed at all.

How can a HyperFrames update reach `main` without a person and without a new MP4 in every
update, and what still needs one?

## Decision Drivers

* A HyperFrames update whose render shows no visible change MUST merge without anyone doing
  anything, and MUST NOT add a re-rendered MP4 to the repository.
* An update whose render differs visibly MUST stop before the merge and tell the owner what to
  do, because GitHub accepts a README attachment only through its web editor: there is no REST or
  GraphQL endpoint for `user-attachments`
  ([community discussion #28219](https://github.com/orgs/community/discussions/28219),
  [gh CLI issue #13256](https://github.com/cli/cli/issues/13256)).
* The README demo freshness check MUST keep failing when a scene, a capture, a font, a timing
  rule or a render script changes without a render, and MUST stop failing when only the
  HyperFrames version moves.
* No workflow holds a write token or a secret while it runs the HyperFrames CLI, and no job runs
  pull request content outside `pull_request`
  ([GitHub Actions security](../agents/github-actions-security.md)).
* The comparison MUST answer whether README readers would see a difference, so small invisible
  steps cannot add up to a visible one without any check noticing.
* The merge MUST wait for the render check. The seven-day release age, the `hyperframes` label
  and the `major update` policy stay.
* The automated path MUST NOT need a pull request body edit; the metadata check skips bot pushes
  ([ADR 0141](0141-check-the-ai-assistance-disclosure-deterministically.md)).

## Considered Options

For the media:

* Hash the pin with its version normalized, record the rendering version, and let CI render and compare without committing.
* Render in CI and push the media with a GitHub App token.
* Keep rendering and committing by hand.

For the merge:

* Renovate's own automerge until the render check is required.
* Renovate's platform automerge right away.
* A workflow that merges through the API.

For the README attachment:

* Automate the attachment upload.
* Fail the check, label and comment.

For the comparison baseline:

* The README attachment, downloaded from the demo embed.
* The video committed on `main`.

## Decision Outcome

Chosen options: "Hash the pin with its version normalized, record the rendering version, and let CI render and compare without committing", "Renovate's own automerge until the render check is required", "Fail the check, label and comment", and "The README attachment, downloaded from the demo embed", because together
they merge every update that changes nothing visible to README readers with no person and no new
media, keep every job that runs the HyperFrames CLI read-only, and leave the one step GitHub
offers no API for to the owner.

`docs/demo/render-manifest.json` gains `hyperframesVersion`, the version that rendered the
committed media. `scripts/readme-demo-hyperframes.ts` stays a hashed render input, but
`normalizeHyperframesPin` in `scripts/readme-demo-manifest.ts` replaces its version literal with
`0.0.0` before the file is hashed; the manifest writer and the freshness test hash it through that
one function, and the same module reads the version from the pin's one line the way Renovate does.
The freshness test compares every manifest field except that version and requires the pinned
version to be the recorded one or newer, so a Renovate update passes `script-tests` while a change
to a scene, a capture, a font, the timing, a render script or any other byte of the pin still
fails it. A manual render records the version it ran with.

`.github/workflows/hyperframes-renovate.yml` runs on `pull_request` only. Its `readme-demo-render`
job runs for a same-repository pull request on a `renovate/hyperframes` branch with `contents:
read` and no secret. It checks out the merge commit, which is `main` plus the version line,
installs FFmpeg and runs `scripts/render-hyperframes-update`.

That script first checks the README demo embed, on every run and whatever the video comparison
says: in `README.md` on `main` and on the pull request, the first non-blank line after the
`<!-- readme-demo-video -->` marker must be a `user-attachments` URL, which
`scripts/readme-demo-embed` reads. Blank lines are skipped
because an editor can insert one on drag and drop. Without the marker and URL on `main`, the
check fails before rendering with a message that says so. When the pull request changes the URL,
`main`'s URL must be gone from `README.md` and the file GitHub serves at the new URL must be the
committed video byte for byte. GitHub serves a public repository's attachments without
authentication (checked on 2026-10-31 with `curl`: 200, the full file, after a redirect to a
signed S3 URL), so the job downloads them with `curl` limited to HTTPS for the request and every
redirect, and capped at GitHub's video attachment limit, `GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES`
in `scripts/readme-demo-manifest.ts`, which the render keeps `demo.mp4` below: a larger file can
never be the committed video. On 2026-10-31 the cap left about 0.9 MB of headroom over the
committed video.

The baseline of the comparison is the attachment the pull request's embed points to once it is
verified, otherwise the one on `main`: the video README readers see. While the pull request keeps
`main`'s `docs/assets/demo.mp4`, `scripts/readme-demo-compare-videos.ts` compares the render with
that attachment using the tile SSIM of
[ADR 0133](0133-re-upload-the-readme-video-only-after-a-visible-change.md): a `keep` verdict
passes the check, a `re-upload` verdict fails it with the frame and tile to look at and points to
the "What needs a person" steps in [docs/demo/README.md](../demo/README.md#updating-hyperframes),
the one place those steps are written. `main`'s committed video is used only to tell whether the
pull request commits its own video. Once a person commits a render, the CI render must reproduce the committed video (the
render's own comparison with `HEAD`), and the committed video is compared with the attachment.
When they look the same, for example after a composition fix with no visible effect, no new
attachment is needed. When they differ visibly, the check passes only once the committed video is
uploaded and its URL is on the embed line. Every part holds: the check passes. Otherwise it fails
and names the missing parts, so the re-upload path can turn green and merge by hand, and later
through the required check. A failed download of the baseline fails the check, since nothing can
be compared, and so does a comparison that fails: it prints the re-upload verdict, the safe advice
for a local render, but measured nothing, so the check reports it as a failed render and never as
a visible change or a missing step. The render's outputs are discarded.

The verdict text lives only in `scripts/readme-demo-visual-change.ts`. The Bash script reads a
log's verdict and the attachment size limit through `scripts/readme-demo-values.ts`, and the
tests import the constants.

The `report` job, with `pull-requests: write` only, checks out its script from the pull request's
base commit, then adds the `needs-maintainer-review` label and one comment, edited in place on
later runs, that links the guide section. If writing the ownership comment fails, the report removes
only the label it added during that run, so a retry does not mistake it for a preexisting label.
A failed rollback leaves a diagnostic requiring manual cleanup. The report recognizes its own
comment by author `github-actions[bot]` plus a marker, and a
`keep` verdict removes the label only when that comment says the workflow added it. Reports for
one pull request run one at a time, and a report whose head a newer push replaced writes nothing,
so a slow or re-run report for an older push never overwrites the newer verdict. Both jobs run
only for a same-repository pull request whose author is `renovate[bot]` on a `renovate/hyperframes`
branch, in that order, as the OpenRewrite workflow guards.

The HyperFrames package rule in `renovate.json` sets `automerge: true` and
`platformAutomerge: false`. GitHub's native auto-merge waits only for the ruleset's required
checks, which are `test`, `code-change` and the three pinned-image checks, so with platform
automerge the version-only commit could merge while the render still runs or after it failed.
Renovate's own automerge waits until every check run on the head concluded, so it waits for
`readme-demo-render`. Once the owner adds `readme-demo-render` to the `Required merge checks`
ruleset, which is a repository setting outside version control, the rule can drop
`platformAutomerge: false` and merge the way every other non-major update does.
[GitHub issue #1201](https://github.com/martin-francois/symphony-trello/issues/1201) tracks that
setting.

The pull request body is not edited: every push on the automated path is Renovate's, so the
metadata check in `commitlint.yml` does not run. A person who renders by hand still adds the
template sections, as [GitHub issue #1185](https://github.com/martin-francois/symphony-trello/issues/1185)
describes.

### Consequences

* Good, because an update with no visible change merges with no person involved and adds nothing
  to the repository but the version line.
* Good, because an update with a visible change stops with a failing check, a label and a
  comment that name the frame and tile to look at, and merges only after the owner renders,
  commits, uploads the attachment on the branch and merges by hand; the check then passes only
  when the CI render reproduces the committed video and, if that video differs visibly from the
  attachment, the README embed points at an attachment that is the committed file.
* Good, because the check compares with what README readers see, so invisible steps cannot add
  up to a visible difference between the attachment and the demo, and a pull request that drops
  the marker or swaps the embed URL fails even when the video looks the same.
* Good, because no job holds a write token or a secret while it runs the HyperFrames CLI, and no
  job runs on `pull_request_target`.
* Good, because nothing on the branch is lost when Renovate rebases it.
* Bad, because the committed media fall behind the pinned version: the manifest says which version
  rendered them, and a maintainer who re-renders for a composition change renders with the pinned
  one.
* Bad, because until the owner makes `readme-demo-render` a required check, the merge depends on
  Renovate's own automerge, which Renovate 44.181.1 blocks while any check run on the head is
  cancelled or pending; the size labeler's queue leaves cancelled runs on some heads, and such an
  update waits for the next Renovate push to the branch.
* Bad, because each Renovate update costs at least one render of roughly 15 minutes on a hosted
  runner, and with `:rebaseStalePrs` every push to `main` rebases an open update and restarts
  that render, so on a busy day one update renders several times before it merges.
* Bad, because the check depends on GitHub serving the attachment: a failed download fails the
  check until a rerun, and the job downloads about 9 MB on every run.

### Confirmation

`pnpm run verify:scripts` runs these checks in the `script-tests` job:

* `scripts/readme-demo-freshness.test.ts` compares the committed manifest with the current inputs
  without the version, requires the pinned version to be the recorded one or newer, shows in a
  fixture that a version-only update keeps the same source digest while a render script change or
  a change to the pin outside its version line does not, and requires the manifest writer to
  refuse a version that changed while the render ran.
* `scripts/render-hyperframes-update.test.ts` runs the render script against a fixture merge
  commit whose README has another attachment URL before the embed, with a fake `node` that hands
  `scripts/readme-demo-values.ts` to the real Node.js and a fake `curl`: the keep and re-upload
  outcomes against the attachment, blank lines after the marker, a marker missing on `main` and
  on the pull request, a swapped or non-attachment embed while the video looks the same, a
  baseline that cannot be downloaded, a committed render that looks like the attachment and needs
  no upload, one that differs and is uploaded, and the incomplete outcomes (not reproduced, embed
  unchanged, `main`'s URL left elsewhere, attachment bytes differ, download failed, file over the
  size cap), plus a render or comparison that dies, prints no verdict, or reports a failed
  comparison.
  `scripts/readme-demo-compare-videos.test.ts` covers the comparison command's argument errors,
  and `scripts/readme-demo-visual-change.test.ts` reads both verdicts and a failed comparison back
  from a log.
* `scripts/report-hyperframes-render.test.ts` runs the report script against a fake `gh`: no
  comments, a spoofed marker comment from another user, a comment on a second page, a label the
  workflow added and removes, a label a person set that stays, the incomplete and failed texts,
  a tolerated 404 and a fatal 403 on the label removal, an unknown outcome, and a head that a
  newer push moved, which writes nothing.
* `scripts/hyperframes-renovate.test.ts` requires the workflow to run on `pull_request` only with
  no secret, the render job to hold `contents: read` and to check out the merge commit without a
  token, the report job to hold `pull-requests: write` only, to run one at a time per pull request
  without cancelling, and to pass the run's head SHA, both jobs to be gated to
  same-repository `renovate/hyperframes` branches by `renovate[bot]`, the render script to read
  verdicts through `scripts/readme-demo-values.ts` instead of repeating their text, to compare
  with the downloaded attachment over HTTPS only, never to commit or push, and to point to the
  one guide section that holds the owner's steps.
* `scripts/readme-demo-embed.test.ts` reads the embed from the real `README.md`, so a change that
  drops the marker or the attachment URL fails `script-tests` at once instead of failing the next
  HyperFrames update; it also tells a missing marker from a line that is not an attachment URL.
* `scripts/readme-demo-hyperframes.test.ts` requires the HyperFrames rule to set
  `automerge: true` and `platformAutomerge: false`, to decide automerge last for non-major
  updates, and to name the workflow, the check, the label, the three generated files, both
  verdicts and the guide section in its notes.
* `scripts/github-actions-security.test.ts` requires the audit table row.

What only the next real update can show: the hosted runner renders within the job's two-hour
limit, and Renovate merges once every check on the head is green.

## Pros and Cons of the Options

### Hash the pin with its version normalized, record the rendering version, and let CI render and compare without committing

The manifest records the version that rendered the committed media, the pin is hashed with its
version literal normalized, and a `pull_request` job renders with the proposed version and throws
the result away after the comparison.

* Good, because nothing is pushed, so no write token, no App and no secret exist.
* Good, because every Renovate rebase or newer version simply renders again.
* Bad, because each of those renders costs about 15 minutes of a hosted runner, and with
  `:rebaseStalePrs` every push to `main` restarts the render of an open update.
* Bad, because the committed media are rendered with an older version than the pin.

### Render in CI and push the media with a GitHub App token

The design this ADR first considered: a `pull_request_target` workflow would validate the pull
request, render from the base commit, push the media on top of Renovate's commit with an App
token, and merge through the API once GitHub reported the pull request clean, with the render
commit's author in `gitIgnoredAuthors` so Renovate kept rebasing.

* Good, because `main` would always hold media rendered with the pinned version.
* Bad, because it needs a GitHub App and two secrets that the repository does not have; on
  2026-10-31 the OpenRewrite publication job expected the same secrets and had never minted a
  token.
* Bad, because a push with `GITHUB_TOKEN` cannot replace the App: it creates the `pull_request`
  runs only in an approval-required state, and a dispatched CI would not run `code-change`.
* Bad, because every Renovate rebase drops the render commit, so an update renders again after
  every push to `main` until a quiet window lets it merge.
* Bad, because a third privileged job family would hold a write token.
* Bad, because every update adds 9 MB of media that look the same to the repository.

### Keep rendering and committing by hand

A maintainer renders on the Renovate branch, commits the media and merges, as
[ADR 0093](0093-renovate-updates-hyperframes.md) decided.

* Good, because nothing new runs in CI.
* Bad, because three updates a day each need a Docker render, a push, a body edit and a merge.

### Renovate's own automerge until the render check is required

The rule sets `automerge: true` with `platformAutomerge: false`, so Renovate merges on one of its
own runs once it sees a green head. After the owner makes `readme-demo-render` a required check,
the rule drops the `platformAutomerge` override and GitHub merges.

* Good, because Renovate waits for every check on the head, so the render check gates the merge
  from the first update.
* Bad, because a cancelled check run on the head blocks Renovate until the next push.

### Renovate's platform automerge right away

Renovate would enable GitHub's native auto-merge when it opens the pull request, as the other
non-major rules do.

* Good, because GitHub merges the moment the required checks pass.
* Bad, because the render check is not required, so the version-only commit could merge while
  the render runs or after it failed.

### A workflow that merges through the API

A `workflow_run` job would call the merge endpoint once GitHub reported the pull request clean.

* Bad, because it needs a write token in a privileged job for no gain over Renovate's merge.

### Automate the attachment upload

A job would replace the README's inline video attachment after a render with a visible change.

* Bad, because GitHub offers no endpoint for `user-attachments`; the only known automations
  replay a signed-in browser session, which no workflow has.

### Fail the check, label and comment

The check fails with the frame and tile to look at, and the report job labels the pull request and
links the guide section with the steps.

* Good, because the owner gets the frame and tile to look at and the exact steps.
* Bad, because a visible change still needs a person, by design.

### The README attachment, downloaded from the demo embed

The check downloads the video from the URL on the embed line and compares with it, the option
[ADR 0133](0133-re-upload-the-readme-video-only-after-a-visible-change.md) rejected for the
manual render.

* Good, because it compares with what README readers see. On 2026-10-31 the attachment had the
  same SHA-256 as the `docs/assets/demo.mp4` committed in `4fa3686e` (HyperFrames 0.8.174,
  2026-10-27), and seven commits on `main` had changed the video since. Its lowest tile SSIM
  against `main`'s video was 0.968813, and 0.967257 against the render committed with this
  decision, which scored 0.973380 against `main`'s video: the drift ADR 0133 warned about had begun.
* Good, because ADR 0133's two objections no longer apply to the check: the job already parses
  the embed line and downloads the attachment to verify an upload, so the comparison adds no
  network access and no parsing, and the attachment and `main`'s video are no longer the same
  file, so the answer can differ.
* Bad, because the check needs the attachment to download on every run.

### The video committed on `main`

The render is compared with `docs/assets/demo.mp4` at the merge commit's first parent.

* Good, because it needs no download for the comparison.
* Bad, because every merge with a `keep` verdict leaves the attachment behind, so each update is
  compared with a newer video than readers see, and small steps can add up to a visible
  difference that no comparison reports.

## More Information

The update steps for both paths are in [docs/demo/README.md](../demo/README.md#updating-hyperframes).
The owner's one setting, adding `readme-demo-render` to the `Required merge checks` ruleset, is
[GitHub issue #1201](https://github.com/martin-francois/symphony-trello/issues/1201); after it,
remove `platformAutomerge: false` from the HyperFrames rule and the matching assertion in
`scripts/readme-demo-hyperframes.test.ts`.
