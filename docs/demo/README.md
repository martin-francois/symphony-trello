# README demo video

This [HyperFrames](https://github.com/heygen-com/hyperframes) project renders the README hero
video and poster from optimized captures of a real disposable Trello-to-GitHub run:

- `docs/assets/readme-demo.mp4` (H.264, silent, no audio track)
- `docs/assets/readme-demo-poster.png`
- `docs/demo/render-manifest.json` (render-input and artifact checksums)

The whole render stack is free and open source: HyperFrames is Apache-2.0, the composition is
plain HTML/CSS with native Web Animations API motion (no GSAP, no CDN scripts), and encoding uses
FFmpeg. See [ADR 0074](../adr/0074-hyperframes-readme-demo-video.md) for why HyperFrames was
chosen over Remotion and the other candidates.

## Re-rendering

Requirements: a Git working tree, Git on `PATH`, Node.js 22.18+, Docker, FFmpeg with `ffprobe`, and
pnpm. The HyperFrames CLI is fetched on demand at a pinned version, and rendering uses its Docker
environment so the production browser has the system font support needed to load the bundled fonts.
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

The normal `pnpm run verify:scripts` CI gate recomputes that manifest. It fails when a composition,
capture, font, render configuration, render script, MP4, or poster changes without running the
render command and committing all three generated files. It also fails when the committed MP4 is
not strictly below GitHub's 10,000,000-byte (10 MB) video-attachment limit. This ceiling exists
because [GitHub limits video attachments to 10 MB for repositories owned by users or organizations
on its free plan](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files),
and uploading such an attachment supplies the URL used by the inline README player. Documentation
and font license files are excluded because they do not affect rendered pixels. The manifest also
covers `.gitattributes`, which requests LF checkout bytes for demo inputs and render scripts.
Hashing uses Git's canonical blob content, so an existing Windows checkout with CRLF files produces
the same digest as CI.

## Updating the inline README video

GitHub serves an inline README video from an uploaded attachment URL, not directly from the MP4 in
the repository. After re-rendering and committing the generated assets:

1. Download `docs/assets/readme-demo.mp4` from the pull-request branch to your computer.
2. Rename the downloaded file to `demo.mp4`. This rename MUST happen before uploading it.
3. Open `README.md` in GitHub and select the pencil icon to use the inline editor.
4. Remove the existing demo media block, then drag `demo.mp4` from your computer into that
   location in the editor and wait for GitHub to insert the new uploaded-attachment URL.
5. Preview the README and confirm that GitHub displays the URL as an inline video player and that
   playback works.
6. Commit the README edit through GitHub.

The uploaded file MUST remain strictly below 10 MB; exactly 10,000,000 bytes fails the render and CI
checks.

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

For iterating on the composition, run the CLI directly from this directory:

```bash
pnpm dlx hyperframes@0.7.64 lint             # fast static feedback
pnpm dlx hyperframes@0.7.64 check            # full browser gate (layout, motion, contrast)
pnpm dlx hyperframes@0.7.64 preview          # live preview in the browser
ffmpeg -i ../assets/readme-demo.mp4 -ss 42.5 -frames:v 1 /tmp/demo-frame.png
```

Extract review stills from the rendered MP4 so they use the same browser and font render as the
committed video.

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
