# README demo video

This Remotion project renders the README hero video and poster from optimized captures of a real
disposable Trello-to-GitHub run.

## Commands

From this directory:

```bash
pnpm install
pnpm run typecheck
pnpm run render
pnpm run poster
```

`pnpm run render` writes `../assets/readme-demo.mp4`. `pnpm run poster` writes
`../assets/readme-demo-poster.png`.

## Source captures

`public/captures/` contains safe, optimized exports used by the Remotion composition. Larger raw
recordings and full browser screenshots are intentionally not committed because they can include
browser/session metadata, local context, or extra UI chrome that is not needed to regenerate the
public demo.

Keep the external originals archive when changing masks or crops so edits can be undone if a crop
removes too much context.
