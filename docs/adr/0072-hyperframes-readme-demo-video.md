---
status: accepted
date: 2026-07-19
decision-makers: [François Martin, Codex]
consulted:
  - "[Polished README demo video issue](https://github.com/martin-francois/symphony-trello/issues/504)"
  - "[Earlier Remotion-based demo PR #503](https://github.com/martin-francois/symphony-trello/pull/503)"
  - "[Earlier Remotion-based demo PR #502](https://github.com/martin-francois/symphony-trello/pull/502)"
  - "[HyperFrames repository](https://github.com/heygen-com/hyperframes)"
  - "[Remotion license](https://www.remotion.dev/docs/license)"
informed: [Future maintainers, Contributors]
---

# Render The README Demo Video With HyperFrames From Real UI Captures

## Context and Problem Statement

The README needs a polished, silent demo video and poster that show the real workflow: a Trello
card moves through the board lists, Codex implements the task, a GitHub pull request is reviewed
and merged, and the card reaches `Done`. The video must be regenerable from committed source with
one command, must use only free and open source tooling, must use real Trello and GitHub UI as the
primary visual source, and must stay small and readable enough for a README.

Which code-first video stack should render the demo, and where should the captured UI come from?

## Decision Drivers

* Every video-generation dependency must be free and open source; no paid, hosted, or
  source-available-only tooling in the regeneration path.
* The durable artifact is code: changing captions, timing, or crops must be a source edit plus a
  render command, not manual video editing.
* Real Trello and GitHub UI captures are the primary visual source, not stylized mockups.
* The result must be verifiable: automated layout/contrast/motion checks plus frame snapshots
  for visual review.
* The render must be regenerable on a fresh machine with Node, Docker, FFmpeg, and pnpm.

## Considered Options

* HyperFrames composition (HTML + Web Animations API) rendered by the HyperFrames CLI
* Remotion (React/TypeScript) composition, continuing the earlier attempts
* Revideo or Motion Canvas (MIT-licensed TypeScript animation frameworks)
* Hand-written FFmpeg filter graphs over the captures

## Decision Outcome

Chosen option: "HyperFrames composition rendered by the HyperFrames CLI", because it is the only
candidate that is both fully open source (Apache-2.0) and actively maintained as a
render-from-code pipeline, it was explicitly requested for this implementation, and its built-in
gates (`lint`, `check` with layout/motion/contrast audits, `snapshot`) give the demo automated
verification the other options would need custom tooling for.

Two consequences of that choice are part of this decision:

* The composition is plain HTML/CSS/JS (HyperFrames' native format) instead of the TypeScript
  source the original issue sketched for a Remotion-style stack. The TypeScript surface is the
  render/verification script `scripts/render-readme-demo.ts`; the scene timing and animation
  logic live in `docs/demo/index.html`.
* Animations use the native Web Animations API adapter, not HyperFrames' default GSAP runtime.
  GSAP is free but not open source (custom Webflow license, not OSI-approved), and dropping it
  also removes the render-time CDN script dependency, so a render needs no network access beyond
  fetching the pinned CLI.

The visual source stays the committed, already-sanitized captures of the real disposable demo run
(real Trello board, real Codex run, real GitHub PR review/rework/merge) that the earlier Remotion
attempts produced. This environment has no Trello credentials, and those captures are real product
UI from a real run, which is exactly what the issue asks for; re-recording would add risk without
adding authenticity. The still captures are composited with a code-rendered moving card, and the
two Trello lists involved in each move are re-rendered in code over the capture so placeholders,
list growth and shrink, and counter changes animate the way Trello animates them between the
captured states. The Done list, which the capture cuts off at the right edge, is code-rendered in
full where the story needs it; the completion of its two truncated background card titles is
documented in [docs/demo/README.md](../demo/README.md).

### Consequences

* Good, because the whole regeneration path is FOSS: HyperFrames (Apache-2.0), its Docker renderer
  with Chromium and FFmpeg, and browser-native animations.
* Good, because `hyperframes check` gates layout overflows, WCAG contrast, frozen frames, and
  motion assertions (`docs/demo/index.motion.json`) on every re-render.
* Good, because the CLI version is pinned (`hyperframes@0.7.64`) in the render script and docs,
  so future renders use the same HyperFrames behavior.
* Bad, because the composition is not TypeScript, so `pnpm run check:scripts` type-checks only
  the render script, not the scene code; the HyperFrames linter and browser checks cover the
  composition instead.
* Bad, because board movement between still captures is reconstructed overlay code that must
  match Trello's visual behavior; the geometry constants in `docs/demo/index.html` document the
  measured capture layout, and snapshots must be re-reviewed when captures change.
* Bad, because HyperFrames builds its versioned Docker renderer from mutable upstream system and
  browser packages. A fresh host is not guaranteed to produce byte-identical output, so the render
  script verifies the required format, duration, size, and representative text regions, and
  maintainers must review extracted frames before committing a regenerated video.

### Confirmation

`node scripts/render-readme-demo.ts` regenerates and verifies both assets with the Docker
renderer; it fails if the MP4 is not a single silent H.264 stream of the expected duration, is not
larger than 6 MiB, or lacks dark text pixels in representative text-only regions. `pnpm dlx
hyperframes@0.7.64 check` passes in `docs/demo`. The committed MP4 and poster match the composition
when re-rendered at the pinned CLI version.

## Pros and Cons of the Options

### HyperFrames composition (HTML + Web Animations API)

HyperFrames (Apache-2.0, by HeyGen) turns HTML/CSS compositions with `data-*` timing attributes
into deterministic MP4s using headless Chromium and FFmpeg. Compositions are previewed live and
audited with built-in lint/check/snapshot commands.

* Good, because Apache-2.0 covers the whole render pipeline with no usage thresholds.
* Good, because deterministic seek-based rendering plus built-in layout, contrast, and motion
  audits catch regressions automatically.
* Good, because no `package.json` or dependency install is needed in the repo; the pinned CLI is
  fetched on demand, matching this repository's rule against tool-only Node packages.
* Bad, because the composition language is HTML/CSS/JS rather than TypeScript, so type checking
  does not cover scene code.
* Neutral, because it is young (2026) but very actively maintained with a large contributor base.

### Remotion (React/TypeScript)

Remotion renders React components to video, as used by the earlier `docs/readme-demo-antigravity-polish`
attempts.

* Good, because scenes are TypeScript and the earlier attempts prove the approach works.
* Bad, because Remotion is source-available under a custom company license, not open source; the
  issue requires a free and open source stack, which makes this a first-order disqualifier.
* Bad, because the previous Remotion attempts accumulated exactly the polish problems this issue
  reopens, and they had no automated layout/contrast/motion gating.

### Revideo or Motion Canvas

MIT-licensed TypeScript animation frameworks; Revideo forked Motion Canvas to add headless
`renderVideo()` rendering.

* Good, because MIT license and TypeScript scene code.
* Bad, because Revideo's maintainers have publicly shifted to a commercial product and upstream
  development has stalled, which is a poor foundation for a long-lived repo asset.
* Bad, because Motion Canvas alone has no first-class headless render path, and neither ships
  automated layout/contrast verification.

### Hand-written FFmpeg filter graphs

Compose the captures, captions, and movement directly with FFmpeg `filter_complex` expressions in
a shell script.

* Good, because it adds no framework dependency at all.
* Bad, because caption layout, easing, cursor motion, and list patches in filter-graph syntax are
  effectively unmaintainable and unreviewable compared to HTML/CSS.
* Bad, because there is no preview or audit tooling; every change needs a full render to see.

## More Information

The demo project layout, re-render commands, and the capture provenance/masking notes live in
[docs/demo/README.md](../demo/README.md). The suggestion to weigh Remotion and HyperFrames equally
came from [GitHub issue #504](https://github.com/martin-francois/symphony-trello/issues/504); the
maintainer then explicitly selected HyperFrames during implementation.
