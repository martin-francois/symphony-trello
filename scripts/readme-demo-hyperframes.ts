/**
 * The one place that names the HyperFrames CLI version for the README demo. The CLI version also
 * selects its Docker renderer image, so this value decides how the committed video is rendered.
 *
 * A Renovate regex manager in renovate.json reads and updates the version literal below, so keep
 * it an exact `x.y.z` string on this one line. This file is a render input: changing the version
 * makes the README demo freshness test fail until `node scripts/render-readme-demo.ts` commits
 * fresh media rendered with that version. See docs/adr/0093-renovate-updates-hyperframes.md.
 */

import {runRenderStep} from "./readme-demo-process.ts";

export const HYPERFRAMES_VERSION = "0.7.64";

export const HYPERFRAMES_PACKAGE = `hyperframes@${HYPERFRAMES_VERSION}`;

/** The pnpm arguments that run the pinned CLI without a package.json or install step. */
export function hyperframesPnpmArgs(cliArgs: readonly string[]): string[] {
  return ["dlx", HYPERFRAMES_PACKAGE, ...cliArgs];
}

export function runHyperframes(cliArgs: readonly string[], cwd: string): void {
  runRenderStep("pnpm", hyperframesPnpmArgs(cliArgs), cwd);
}
