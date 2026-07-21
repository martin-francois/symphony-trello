# README demo video

This [HyperFrames](https://github.com/heygen-com/hyperframes) project renders the README hero
video and poster from optimized captures of a real disposable Trello-to-GitHub run:

- `docs/assets/readme-demo.mp4` (H.264, silent, no audio track)
- `docs/assets/readme-demo-poster.png`
- `docs/demo/render-manifest.json` (render-input and artifact checksums)

The whole render stack is free and open source: HyperFrames is Apache-2.0, the composition is
plain HTML/CSS with native Web Animations API motion (no GSAP, no CDN scripts), and encoding uses
FFmpeg. See [ADR 0073](../adr/0073-hyperframes-readme-demo-video.md) for why HyperFrames was
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

The script runs the composition checks, renders the MP4, extracts its poster frame, and fails if
the MP4 is not a single silent H.264 stream of the expected length, is not larger than 6 MiB, or
has missing text in representative intro, board, review, phone, or closing regions. After every
check passes, it updates `render-manifest.json` with the exact render-input file list and SHA-256
values for the inputs, MP4, and poster. It also fails instead of writing the manifest when any input
changes while the render is running. HyperFrames reads an immutable temporary snapshot populated
from the same file reads used to calculate the source state, so even an edit that is restored before
the render finishes cannot affect the artifact without affecting its recorded state.

The normal `pnpm run verify:scripts` CI gate recomputes that manifest. It fails when a composition,
capture, font, render configuration, render script, MP4, or poster changes without running the
render command and committing all three generated files. Documentation and font license files are
excluded because they do not affect rendered pixels. The manifest also covers `.gitattributes`,
which requests LF checkout bytes for demo inputs and render scripts. Hashing uses Git's canonical
blob content, so an existing Windows checkout with CRLF files produces the same digest as CI.

Every scene that moves a card reserves a reading hold before showing the board. Each scene declares
one of two supported reading paces: straightforward explanatory captions use 120 words per minute,
while text that asks the viewer to decide, reflect, or let an idea sink in uses 60 words per minute.
The approval scene uses the reflective pace; the other card-movement captions are explanatory. The
composition counts the actor pill, heading, and supporting sentence, rounds the hold up to the next
tenth of a second, rejects any other reading rate, and validates that the remaining scene time fits
the complete move. During that hold the explanation is centered on its own. It then lifts into
caption position while the board fades in; the cursor or Symphony badge starts only after that
reveal.

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

- `index.html` — the whole composition: one `<section class="clip">` per scene with cumulative
  `data-start` times, the composition's sole `data-duration`, plus a script that builds the board
  overlays and every animation. The script reads scene timing back from those attributes, so the
  HTML is the single timing table. The file stays monolithic on purpose: the scenes share the
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
