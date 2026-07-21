# README demo video

This [HyperFrames](https://github.com/heygen-com/hyperframes) project renders the README hero
video and poster from optimized captures of a real disposable Trello-to-GitHub run:

- `docs/assets/readme-demo.mp4` (H.264, silent, no audio track)
- `docs/assets/readme-demo-poster.png`

The whole render stack is free and open source: HyperFrames is Apache-2.0, the composition is
plain HTML/CSS with native Web Animations API motion (no GSAP, no CDN scripts), and encoding uses
FFmpeg. See [ADR 0071](../adr/0071-hyperframes-readme-demo-video.md) for why HyperFrames was
chosen over Remotion and the other candidates.

## Re-rendering

Requirements: Node.js 22+, FFmpeg, and pnpm. The HyperFrames CLI is fetched on demand at a pinned
version, so no `package.json` or install step is needed here.

Render and verify both assets in one step from the repository root:

```bash
node scripts/render-readme-demo.ts
```

The script runs the composition checks, renders the MP4, snapshots the poster frame, and fails if
the MP4 is not a single silent H.264 stream of the expected length.

For iterating on the composition, run the CLI directly from this directory:

```bash
pnpm dlx hyperframes@0.7.64 lint             # fast static feedback
pnpm dlx hyperframes@0.7.64 check            # full browser gate (layout, motion, contrast)
pnpm dlx hyperframes@0.7.64 preview          # live preview in the browser
pnpm dlx hyperframes@0.7.64 snapshot --at 17,29,42.5   # stills for visual review
```

## Structure

- `index.html` — the whole composition: one `<section class="clip">` per scene with cumulative
  `data-start` times, plus a script that builds the board overlays and every animation. The
  script reads scene timing back from the `data-start` attributes, so the HTML is the single
  timing table. The file stays monolithic on purpose: the scenes share the board geometry
  constants and list builder, which sub-composition files would have to duplicate, so the
  `composition_file_too_large` and `timeline_track_too_dense` lint warnings are accepted.
- `index.motion.json` — motion assertions `check` verifies against the seeked timeline.
- `assets/captures/` — safe, optimized exports from the real run (see below).
- `assets/fonts/` — pinned Inter and JetBrains Mono files from Fontsource 5.3.0, with their
  OFL-1.1 license texts, so rendering does not fetch mutable hosted fonts.

## Source captures

`assets/captures/` contains real Trello and GitHub UI captures from a disposable demo run: a
throwaway Trello board (`Symphony for Trello — Demo`), a demo repository, and a real Codex run
that opened, reworked, and merged pull request `fix: clarify missing Trello token error`. Larger
raw recordings and full browser screenshots are intentionally not committed because they can
include browser/session metadata and UI chrome that is not needed to regenerate the demo.

The GitHub captures are privacy-sanitized derivatives of that run. The account name and headshot
are replaced by the synthetic `demo-user` identity and a generic avatar. The review and checks
chrome is reconstructed to show the resolved review and successful validation states documented
by the run, without retaining personal account details.

What is code-rendered on top of the captures, rather than captured:

- captions, actor badges, window chrome, the intro and hero frames, and the phone bezel;
- the moving Trello card and, for the two lists involved in each move, complete code-rendered
  Trello lists over the capture, so drag placeholders, list growth and shrink, and counter
  changes animate the way Trello animates them (the captures are still frames, so the movement
  between them is reconstructed in code);
- the complete Done list: the capture cuts it off at the right edge, so it is code-rendered
  wherever it matters, and the two truncated background card titles ("Retry Trello polling on…",
  "Add status page for ru…") are completed as "Retry Trello polling on errors" and "Add status
  page for runs";
- in the answered-review scene, a green callout box around the kept Codex response;
- the Codex Workpad panel, restyled from the workpad content of the real run so the text stays
  readable at video size.

No credentials, tokens, Trello ids, or private paths appear in the captures or the composition;
`scripts/check-private-context` is the gate for that.
