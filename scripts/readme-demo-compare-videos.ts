/**
 * Compares two README demo video files with the tile SSIM of ADR 0133 and prints the same
 * `README video:` line the render prints: `keep` when they look the same, `re-upload` when they
 * differ visibly. No render runs.
 *
 * Usage: node scripts/readme-demo-compare-videos.ts <baseline-video> <video>
 *
 * The HyperFrames Renovate automation passes the README attachment it downloaded as
 * <baseline-video>, so the verdict says whether a viewer of the README would see a difference.
 * Requires FFmpeg with ffprobe.
 */

import {existsSync, mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {compareWithBaselineVideo, describeVisualComparison} from "./readme-demo-visual-change.ts";

const USAGE = "Usage: node scripts/readme-demo-compare-videos.ts <baseline-video> <video>";

const [baselinePath, videoPath, ...extra] = process.argv.slice(2);
if (baselinePath === undefined || videoPath === undefined || extra.length > 0) {
  console.error(USAGE);
  process.exit(2);
}
const argumentPaths = [["<baseline-video>", baselinePath], ["<video>", videoPath]] as const;
for (const [argument, path] of argumentPaths) {
  if (!existsSync(path)) {
    console.error(`${argument} ${path} does not exist\n${USAGE}`);
    process.exit(2);
  }
}

const workDirectory = mkdtempSync(join(tmpdir(), "readme-demo-compare-"));
try {
  console.log(`Comparing ${videoPath} with ${baselinePath}`);
  console.log(describeVisualComparison(compareWithBaselineVideo(
    {revision: baselinePath, path: baselinePath},
    videoPath,
    workDirectory,
  )));
} finally {
  rmSync(workDirectory, {recursive: true, force: true});
}
