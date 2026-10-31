# README demo video

This [HyperFrames](https://github.com/heygen-com/hyperframes) project renders the README hero
video and poster from optimized captures of a real disposable Trello-to-GitHub run:

- `docs/assets/demo.mp4` (H.264, silent, no audio track)
- `docs/assets/readme-demo-poster.png`
- `docs/demo/render-manifest.json` (render-input and artifact checksums)

The whole render stack is free and open source: HyperFrames is Apache-2.0, the composition is
plain HTML/CSS with native Web Animations API motion (no GSAP, no CDN scripts), and encoding uses
FFmpeg. See [ADR 0074](../adr/0074-hyperframes-readme-demo-video.md) for why HyperFrames was
chosen over Remotion and the other candidates.

## Re-rendering

Requirements: a Git working tree, Git on `PATH`, Node.js 22.18+, Docker, FFmpeg with `ffprobe`, and
pnpm. The HyperFrames CLI is fetched on demand at the version pinned in
`scripts/readme-demo-hyperframes.ts`, and rendering uses its Docker environment so the production
browser has the system font support needed to load the bundled fonts.
No `package.json` or install step is needed here. The script validates the Git requirement before it
starts the Docker render.

Render and verify both assets in one step from the repository root:

```bash
node scripts/render-readme-demo.ts
```

The script runs the composition checks, renders the MP4 at constant-quality CRF 26, and extracts its
poster frame. It fails if the MP4 is not a single silent H.264 stream of the expected length, does
not exceed 6 MiB, reaches GitHub's 10 MB free-plan video-attachment limit, or has missing text in
representative intro, board, review, phone, or closing regions. After every check passes, it updates
`render-manifest.json` with the exact render-input file list and SHA-256
values for the inputs, MP4, and poster. It also fails instead of writing the manifest when any input
changes while the render is running. HyperFrames reads an immutable temporary snapshot populated
from the same file reads used to calculate the source state, so even an edit that is restored before
the render finishes cannot affect the artifact without affecting its recorded state. Container input
and output stay in a system temporary directory until media validation passes; on SELinux hosts the
script labels only that directory for the renderer, leaving the checkout's security labels unchanged.
It also compares the new MP4 with the committed one and reports whether the README attachment needs
replacing. See [Updating the inline README video](#updating-the-inline-readme-video).

The normal `pnpm run verify:scripts` CI gate recomputes that manifest. It fails when a composition,
capture, font, render configuration, render script, MP4, or poster changes without running the
render command and committing all three generated files; the HyperFrames version is recorded, not
hashed, so a newer pin alone keeps the gate green (see [Updating HyperFrames](#updating-hyperframes)). It also fails when the committed MP4 is
not strictly below GitHub's video-attachment limit, `GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES` in
`scripts/readme-demo-manifest.ts`. This ceiling exists
because [GitHub limits video attachments to 10 MB for repositories owned by users or organizations
on its free plan](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files),
and uploading such an attachment supplies the URL used by the inline README player. Documentation
and font license files are excluded because they do not affect rendered pixels. The manifest also
covers `.gitattributes`, which requests LF checkout bytes for demo inputs and render scripts.
Hashing uses Git's canonical blob content, so an existing Windows checkout with CRLF files produces
the same digest as CI.

## Updating the inline README video

GitHub serves an inline README video from an uploaded attachment URL, not directly from the MP4 in
the repository. Replacing that attachment is a manual step in GitHub's web editor, so the render
command tells you whether it is needed. After its checks pass, it compares the new MP4 with
`docs/assets/demo.mp4` as committed at `HEAD` and prints one of these lines:

- `README video: keep the existing README video attachment (no visible change, ...)`. No frame
  differs visibly from the committed video. Commit the generated files and leave `README.md` as it
  is. The attachment then shows the previous render, which looks the same.
- `README video: re-upload the README video attachment as docs/demo/README.md describes (...)`.
  At least one region of one frame differs visibly, the two videos differ in size, frame rate, or
  frame count, or the compared commit has no video. Replace the attachment as described below.

The comparison splits every frame into tiles of 120 by 120 pixels and computes FFmpeg's SSIM, a
similarity score from 0 to 1, for each tile in each frame. A change is visible when any tile scores
below 0.95. `scripts/readme-demo-visual-change.ts` defines both numbers.
[ADR 0133](../adr/0133-re-upload-the-readme-video-only-after-a-visible-change.md) records the
measured re-render noise, a measured one-word caption change, and why whole-frame SSIM was not
enough. If the comparison itself fails, the render keeps the new media and asks for an upload.

The comparison reads the video from a commit, not from the working tree, so running the render twice
before committing compares both runs with the same video. Compare with `main` when `HEAD` already
holds a newer render, for example after rebasing a branch that has a render commit:

```bash
node scripts/render-readme-demo.ts --compare-with origin/main
```

The README attachment can be older than the video on `main`: every render merged with the `keep`
verdict leaves the previous attachment in place, so several small steps can add up between the
attachment and `main`. The HyperFrames Renovate check therefore compares with the attachment
itself. To run the same comparison by hand, download the URL on the first non-blank line after the
`<!-- readme-demo-video -->` comment in `README.md` and compare the new render with it:

```bash
curl -fL --proto =https --proto-redir =https -o /tmp/readme-attachment.mp4 <attachment URL>
node scripts/readme-demo-compare-videos.ts /tmp/readme-attachment.mp4 docs/assets/demo.mp4
```

To replace the attachment:

1. Download `docs/assets/demo.mp4` from the pull-request branch to your computer.
2. Open `README.md` in GitHub and select the pencil icon to use the inline editor.
3. Replace the URL on the line after the `<!-- readme-demo-video -->` comment: delete it, then
   drag `demo.mp4` from your computer onto that line and wait for GitHub to insert the new
   uploaded-attachment URL. Keep the comment; the HyperFrames Renovate check reads the first
   non-blank line after it through `scripts/readme-demo-embed`, `script-tests` fails without it,
   and the old URL must not stay anywhere in `README.md`.
4. Preview the README and confirm that GitHub displays the URL as an inline video player and that
   playback works.
5. Commit the README edit through GitHub. For a HyperFrames update, commit it to the Renovate
   branch, so the `readme-demo-render` check sees the new URL on the pull request.

The uploaded file MUST remain strictly below `GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES` in
`scripts/readme-demo-manifest.ts`; a file of exactly that size fails the render and CI checks.

Every scene reserves a reading hold before its visual action. The composition defines the normal
and reflective reading rates once as named constants in `index.html`. Scenes that ask the viewer to
formulate a task, review or approve work, consider working from anywhere, or absorb the closing
takeaway use the reflective pace (`s03`, `s08`, `s11`, `s12`, `s15`, and `s16`). The remaining
workflow narration uses the normal pace. Elements marked with `data-reading-copy` supply the word
count. This includes the authored actor pill, heading, and supporting text, but excludes decorative
window chrome and detailed UI such as the Codex Workpad, which is visual evidence rather than text
the viewer is expected to read in full.

The composition rounds each reading hold up to the next tenth of a second and adds it to the scene's
fixed visual-action duration. It shifts the scene's visual motion until after that hold, then derives
every cumulative scene start and the composition duration. Changing either rate therefore adjusts
the complete timeline without editing individual timings. A short progress line indicates the
reading interval and disappears when the visual action starts. In card-movement scenes, the
explanation is centered on its own during the reading hold. It then lifts into caption position while
the board fades in; the cursor or Symphony badge starts only after that reveal.

For iterating on the composition, run the pinned CLI in this directory from the repository root.
The wrapper reads the version from `scripts/readme-demo-hyperframes.ts`, so these commands never go
out of date:

```bash
node scripts/readme-demo-hyperframes-cli.ts lint      # fast static feedback
node scripts/readme-demo-hyperframes-cli.ts check     # full browser gate (layout, motion, contrast)
node scripts/readme-demo-hyperframes-cli.ts preview   # live preview in the browser
ffmpeg -i docs/assets/demo.mp4 -ss 42.5 -frames:v 1 /tmp/demo-frame.png
```

Extract review stills from the rendered MP4 so they use the same browser and font render as the
committed video.

## Updating HyperFrames

`scripts/readme-demo-hyperframes.ts` is the only file that names the HyperFrames version. The render
script and the CLI wrapper above import it, and the CLI version also selects the Docker renderer
image. A regex manager in `renovate.json` reads the same line, so Renovate opens an update pull
request with the `hyperframes` label once a release has passed the repository's seven-day release
age.

The version file is hashed as a render input with its version literal normalized:
`render-manifest.json` records the version that rendered the committed media as
`hyperframesVersion`, and the freshness check accepts a pinned version that is the recorded one or
newer. A Renovate update therefore passes `script-tests` without a render commit, and the committed
media are rendered with an older version than the pin until the next render for a composition
change. Any other change to the version file, or to any other render input, still fails the check
until a render is committed. Do not edit `render-manifest.json` by hand to make the check pass: the
manifest would then claim that media from an older render came from the current inputs.

The `readme-demo-render` check of the `HyperFrames Renovate automation` workflow decides the
update instead ([ADR 0149](../adr/0149-check-hyperframes-updates-by-rendering-in-ci.md)). On a
same-repository `renovate/hyperframes` pull request by Renovate it renders the README demo with
the proposed version and discards the result. The baseline is the README attachment, the video
README readers see, which the check downloads (GitHub serves a public repository's attachments
without authentication):

- First, the README demo embed: in `README.md` on `main` and on the pull request, the first
  non-blank line after the `<!-- readme-demo-video -->` comment must be a `user-attachments` URL.
  Without it on `main`, the check fails before rendering and says so. When the pull request
  changes that URL, `main`'s URL must be gone from `README.md` and the file GitHub serves at the
  new URL must be the committed `docs/assets/demo.mp4` byte for byte; that new attachment is then
  the baseline.
- While the pull request keeps `main`'s `docs/assets/demo.mp4`, the usual Renovate update, the
  render is compared with the attachment
  (`node scripts/readme-demo-compare-videos.ts`, the same tile SSIM). No visible change: the check
  passes and Renovate merges once every check is green. Nothing is pushed to the branch.
- Once the pull request commits its own `docs/assets/demo.mp4`, the render must reproduce that
  committed video, and the committed video is compared with the attachment. If they look the same,
  no new attachment is needed. If they differ visibly, the check passes only after the committed
  video is uploaded and its URL is on the embed line.

Every part holds: the check passes. Otherwise it fails and says which part is missing.

A render takes about 15 minutes on a hosted runner, and a newer version or a rebase renders again.

What needs a person:

- **The check fails with `re-upload the README video attachment`.** The new render differs
  visibly from the README attachment. The workflow adds the `needs-maintainer-review` label and a
  comment; the run log names the frame and tile position of the lowest score. Render by hand as
  described next and compare stills around that frame with the attachment, downloaded as shown in
  [Updating the inline README video](#updating-the-inline-readme-video). If the change is wanted:
  1. Commit the three generated files to the Renovate branch and push.
  2. If the new video differs visibly from the attachment, which the check reports, replace the
     README inline video on that branch as described in
     [Updating the inline README video](#updating-the-inline-readme-video), committing the
     `README.md` edit to the Renovate branch, not to `main`. A committed video that looks the same
     as the attachment, for example after a composition fix with no visible effect, needs no
     upload.
  3. Wait for the check to pass on the new head: it requires the reproduced video and, after a
     visible change, the new attachment, and its comment names whichever part is still missing.
  4. Merge the pull request by hand. Renovate does not merge a branch a person pushed to, and the
     metadata check needs the pull request template sections in the body
     ([GitHub issue #1185](https://github.com/martin-francois/symphony-trello/issues/1185)).

  If the change is not wanted, close the pull request; Renovate then skips that version.
- **The check fails as incomplete.** The comment says what is missing: the embed line or its
  marker is gone or holds no `user-attachments` URL, the CI render does not reproduce the
  committed video, the embed line still holds `main`'s URL although the committed video differs
  visibly from that attachment, `main`'s URL is still mentioned elsewhere, the new attachment is
  not the committed file, or its download failed. Finish the missing step from the list above.
- **The render or one of its checks fails.** The check fails and the comment links the run. If
  the new version needs a composition change, make it on the Renovate branch, render by hand and
  commit the three generated files. Otherwise close the pull request. Renovate then skips that
  version, or the whole major version for a major update, and proposes the next newer release.

To render by hand on the Renovate branch:

1. Read the [HyperFrames release notes](https://github.com/heygen-com/hyperframes/releases) for
   every version between the old and the new pin.
2. Check out the Renovate branch and run
   `node scripts/render-readme-demo.ts --compare-with origin/main` from the repository root. It
   has the same requirements as a normal re-render and runs the same checks.
3. Read the `README video:` line the render prints and act on it as described above. Renovate
   stops updating a branch after a person pushes to it, so rebase it yourself if it falls behind
   `main`, and do not tick the rebase checkbox: Renovate would recreate the branch without the
   render commit.

## Structure

- `index.html` — the whole composition: one `<section class="clip">` per scene, two named reading
  rate constants, fixed visual-action durations, plus a script that derives cumulative
  starts and the composition duration before building the board overlays and every animation. The
  file stays monolithic on purpose: the scenes share the
  board geometry constants and list builder, which sub-composition files would have to duplicate,
  so the `composition_file_too_large` and `timeline_track_too_dense` lint warnings are accepted.
- `index.motion.json` — motion assertions `check` verifies against the seeked timeline. It omits
  HyperFrames' optional `duration` field so the composition duration is not duplicated.
- `render-manifest.json` — generated freshness proof checked by the script-test CI job.
- `assets/captures/` — safe, optimized exports from the real run (see below).
- `assets/fonts/` — pinned Inter and JetBrains Mono files from Fontsource 5.3.0, with their
  OFL-1.1 license texts, so rendering does not fetch mutable hosted fonts.

## Source captures

`assets/captures/` contains real Trello and GitHub UI captures from a disposable demo run: a
throwaway Trello board (`Symphony for Trello — Demo`), a demo repository, and a real Codex run
that opened, reworked, and merged pull request `fix: clarify missing Trello token error`. Larger
raw recordings and full browser screenshots are intentionally not committed because they can
include browser/session metadata and UI chrome that is not needed to regenerate the demo.

The GitHub captures intentionally retain project owner François Martin's `martinfrancois`
account name and headshot as product branding. Capture refreshes MUST preserve that owner
branding and MUST NOT replace it with a synthetic identity. Browser/session metadata and other
personal details remain excluded.

What is code-rendered on top of the captures, rather than captured:

- captions, actor badges, window chrome, the intro and hero frames, and the phone bezel;
- the moving Trello card and, for the two lists involved in each move, complete code-rendered
  Trello lists over the capture, so drag placeholders, list growth and shrink, and counter
  changes animate the way Trello animates them (the captures are still frames, so the movement
  between them is reconstructed in code);
- the Human Review list in every board scene where it appears, so an unrelated background card
  present in the source captures stays out of the focused demo;
- the complete Done list: the capture cuts it off at the right edge, so it is code-rendered
  wherever it matters, and the two truncated background card titles ("Retry Trello polling on…",
  "Add status page for ru…") are completed as "Retry Trello polling on errors" and "Add status
  page for runs";
- in the answered-review scene, a white mask over the second, near-duplicate Codex reply in the
  capture (the run replied twice), so the thread reads as one review comment and one response,
  plus a green callout box around the kept response;
- the Codex Workpad panel, restyled from the workpad content of the real run so the text stays
  readable at video size.

No credentials, tokens, Trello ids, or private paths appear in the captures or the composition;
`scripts/check-private-context` is the gate for that.
