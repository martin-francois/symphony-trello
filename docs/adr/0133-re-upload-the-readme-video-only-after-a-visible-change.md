---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub PR #1076](https://github.com/martin-francois/symphony-trello/pull/1076)"
  - "[ADR 0093](0093-renovate-updates-hyperframes.md)"
  - "[ADR 0074](0074-hyperframes-readme-demo-video.md)"
  - "[FFmpeg ssim filter documentation](https://ffmpeg.org/ffmpeg-filters.html#ssim)"
informed: [Future maintainers, Contributors]
---

# Re-upload the README video only after a visible change

## Context and Problem Statement

GitHub plays the README demo from an uploaded attachment URL, not from `docs/assets/demo.mp4`. A
maintainer can replace that attachment only by dragging the MP4 into GitHub's web editor.
[ADR 0093](0093-renovate-updates-hyperframes.md) made that upload a step of every HyperFrames
update, because every update re-renders the video.

Most updates do not change what a viewer sees. Between HyperFrames 0.8.174 and 0.8.181
([GitHub PR #1076](https://github.com/martin-francois/symphony-trello/pull/1076)), with no change
to the composition, both renders have 4,095 frames at 1920x1080 and 30 fps. The lowest
whole-frame SSIM between them is 0.998491. The two videos differ only in encoder noise and in
sub-pixel edges that nobody can see at normal size. On 2026-10-30, the owner decided to merge that
update with the existing attachment, and asked that the upload be required only when the new render
visibly differs from the old one.

How does a maintainer know whether a new render visibly differs from the video the README shows?

## Decision Drivers

* The manual upload MUST be required when a viewer could see a difference, for example a changed
  caption word, a new scene, or a different timeline.
* The upload SHOULD NOT be required for encoder noise or other changes nobody can see.
* The answer MUST come from the render command, so it is the same for every maintainer.
* The verdict and its threshold MUST be defined once in code.
* Changing the threshold MUST NOT force a new render.

## Considered Options

* Tile-based SSIM: compare 120 by 120 pixel tiles in every frame and require an upload when any
  tile scores below 0.95.
* Whole-frame SSIM: compare whole frames and require an upload when any frame scores below a
  threshold.
* Re-upload after every HyperFrames update, as before.
* Compare byte or hash equality with the committed MP4.
* Compare with the attachment downloaded from the README URL instead of a committed MP4.

## Decision Outcome

Chosen option: "Tile-based SSIM", because it is the only automated option whose measured noise and
measured one-word change are far apart.

`scripts/readme-demo-visual-change.ts` holds the comparison. After the existing media checks pass,
`scripts/render-readme-demo.ts` reads `docs/assets/demo.mp4` from a commit, `HEAD` unless
`--compare-with <revision>` names another one, and compares it with the new render. When frame
size, frame rate, or frame count differ, or the commit has no video, the render reports that the
attachment needs an upload without computing SSIM. The comparison runs after the media and the
manifest are written, so a comparison error keeps the validated render and reports that an upload
is needed, with the error. Otherwise FFmpeg's `ssim` filter scores every
120 by 120 pixel tile of every frame. If any tile scores below 0.95, the render reports a visible
change. The render prints one `README video:` line with the verdict, the lowest tile score, and its
frame and position.

`SSIM_TILE_PIXELS` and `MIN_UNCHANGED_TILE_SSIM` define the tile size and the threshold once. The
module is not a render input in `scripts/readme-demo-manifest.ts`: it does not change any pixel, so
a threshold change does not need a new render.

A HyperFrames update pull request merges with the existing attachment when the render reports no
visible change. `docs/demo/README.md` and the Renovate pull-request notes quote both verdicts.

### Measurements

All values are the lowest SSIM over all frames and, for tiles, over all tiles. "Noise" compares the
HyperFrames 0.8.174 and 0.8.181 renders with no source change. "Caption" compares the 0.8.181
render with a throwaway 0.8.181 render that changed one word of one caption, "board" to "list" in
the second scene. The word count stayed the same, so the timeline did not move.

| Comparison | Noise | Caption |
| --- | --- | --- |
| Whole frame, all planes | 0.998491 | 0.997189 |
| Whole frame, luma only | 0.998287 | 0.996528 |
| 8 by 8 grid of 240 by 135 tiles | 0.985299 | 0.959808 |
| 16 by 9 grid of 120 by 120 tiles | 0.979292 | 0.916500 |

The render that ships with this decision, a second render with the new version compared with the
0.8.174 video on `main`, scored 0.981146 as its lowest tile, close to the first noise measurement.

At whole-frame level a changed word scores higher than 0.997, only 0.0013 below the noise. A
threshold such as 0.99 would report no visible change for that word. With 120 by 120 tiles the gap
is 0.063, and 0.95 leaves about 0.03 on each side. The comparison of the full 136.5-second video
takes about 50 seconds, which is short next to the render itself.

### Consequences

* Good, because updates that change nothing visible merge without the web-editor upload.
* Good, because a single changed caption word fails the comparison, as measured.
* Good, because the render prints the frame and position of the lowest tile, so the maintainer knows
  where to look.
* Good, because changing the threshold does not invalidate the committed media.
* Bad, because the threshold rests on one noise measurement and one caption measurement. A future
  renderer change may add more noise, for example a new font rasterizer. That would report a visible
  change and ask for an upload that was not needed, which costs one manual upload. A smaller change
  than one word, such as one character in small text, may stay above the threshold.
* Bad, because the attachment can fall behind `main`. After a merge without upload, `main` holds a
  newer render than the attachment. The next comparison uses that newer render, so several changes
  that each stay above the threshold could add up to a difference the comparison never sees. Each
  such step is invisible on its own, and any render that moves the timeline or changes a word still
  reports a visible change.
* Bad, because the render needs about 50 seconds more.
* Bad, because the FFmpeg-backed test skips on the hosted runner, which has no FFmpeg. CI checks
  the threshold logic and the statistics parser, but not the filter graph itself.

### Confirmation

`pnpm run verify:scripts` runs these checks in the required `script-tests` job:

* `scripts/readme-demo-visual-change.test.ts` parses FFmpeg's SSIM statistics, checks that tiles
  cover a frame, and checks that the threshold puts both measured values from the table above on
  the correct side. It checks that only "no visible change" keeps the existing attachment, that the
  baseline comes from the named commit and not from the working tree, and that an unknown
  `--compare-with` revision fails before the render starts. When FFmpeg is on `PATH`, it also
  encodes small fixture videos and requires a re-encode to pass, a word-sized dark box to fail, and
  a longer video to report a different shape. The hosted runner has no FFmpeg, so that test runs
  locally only.
* `scripts/readme-demo-hyperframes.test.ts` requires the HyperFrames Renovate notes to quote both
  verdicts.

To re-check the threshold after a HyperFrames update, read the lowest tile score that the render
prints. A score between 0.95 and 0.97 for a render without source changes means the noise has
grown toward the threshold, and the measurements above should be repeated.

## Pros and Cons of the Options

### Tile-based SSIM

FFmpeg splits both videos into 120 by 120 pixel tiles, scores each tile in each frame with SSIM, and
the lowest score decides. A tile is about the size of one caption word at 1080p.

* Good, because a local change, such as one word, dominates the score of the tile that holds it.
* Good, because noise stays spread out and keeps every tile far above the threshold.
* Bad, because the filter graph has 144 chains, and FFmpeg needs a statistics file per tile.

### Whole-frame SSIM

FFmpeg scores each whole frame with SSIM, and the lowest score decides.

* Good, because it is one short FFmpeg command.
* Bad, because one changed word changes less than 1 percent of the pixels, so the frame score stays
  above 0.997. The threshold would have to sit within 0.0013 of the measured noise.

### Re-upload after every update

The maintainer replaces the attachment after every render, as
[ADR 0093](0093-renovate-updates-hyperframes.md) required.

* Good, because the attachment always matches `main`.
* Bad, because most updates need a manual web-editor upload of a video that looks the same.

### Byte or hash equality

The render would require an upload whenever the MP4 bytes differ from the committed file.

* Good, because it needs no threshold.
* Bad, because HyperFrames renders are not byte-identical across versions or even across runs, so
  this is the same as re-uploading after every update.

### Compare with the downloaded attachment

The render would download the video from the attachment URL in `README.md` and compare with that.

* Good, because it compares with exactly what viewers see, so the drift described above cannot
  happen.
* Bad, because the render would need network access and would have to find and parse the URL in
  `README.md`.
* Bad, because today the attachment and the MP4 on `main` are the same file. On 2026-10-30 the
  downloaded attachment had the same SHA-256 as `docs/assets/demo.mp4` on `main`, so the extra step
  gives no different answer until a merge without upload.

## More Information

The maintenance steps are in
[docs/demo/README.md](../demo/README.md#updating-the-inline-readme-video). If the attachment drifts
from `main` over many merges without upload, revisit the downloaded-attachment option or run the
render with `--compare-with` set to the commit of the last upload.
