/**
 * The one place that names the HyperFrames CLI version for the README demo. The CLI version also
 * selects its Docker renderer image, so this value decides how the committed video is rendered.
 *
 * A Renovate regex manager in renovate.json reads and updates the version literal below, so keep
 * it an exact `x.y.z` string on this one line. This file is hashed as a render input with that
 * literal normalized: a new version alone keeps the committed media fresh, because the render
 * manifest records the version that rendered them, while any other change here makes the README
 * demo freshness test fail until `node scripts/render-readme-demo.ts` commits fresh media. See
 * docs/adr/0093-renovate-updates-hyperframes.md and
 * docs/adr/0149-check-hyperframes-updates-by-rendering-in-ci.md.
 */

import {runRenderStep} from "./readme-demo-process.ts";

export const HYPERFRAMES_VERSION = "0.8.192";

export const HYPERFRAMES_PACKAGE = `hyperframes@${HYPERFRAMES_VERSION}`;

/** The pnpm arguments that run the pinned CLI without a package.json or install step. */
export function hyperframesPnpmArgs(cliArgs: readonly string[]): string[] {
  return ["dlx", HYPERFRAMES_PACKAGE, ...cliArgs];
}

export function runHyperframes(cliArgs: readonly string[], cwd: string): void {
  runRenderStep("pnpm", hyperframesPnpmArgs(cliArgs), cwd);
}
