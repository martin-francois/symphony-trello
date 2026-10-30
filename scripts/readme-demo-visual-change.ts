/**
 * Decides whether a new README demo render looks different from a committed one, which tells the
 * maintainer whether the README's uploaded video attachment needs replacing. See
 * docs/adr/0133-re-upload-the-readme-video-only-after-a-visible-change.md.
 */

import {spawnSync} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import {VIDEO_RELATIVE_PATH} from "./readme-demo-manifest.ts";

/** Square tiles of about one caption word at 1080p, so a changed word dominates its tile's SSIM. */
export const SSIM_TILE_PIXELS = 120;

/** Re-render noise kept tiles above 0.979; one changed caption word dropped its tile to 0.917. */
export const MIN_UNCHANGED_TILE_SSIM = 0.95;

const MEBIBYTE = 1024 * 1024;
const COMMITTED_VIDEO_BUFFER_BYTES = 32 * MEBIBYTE;

/** The stream properties that must match before frames can be compared one to one. */
export interface VideoShape {
  width: number;
  height: number;
  frameRate: string;
  frameCount: number;
}

/** A region of the frame, in pixels from the top-left corner. */
export interface Tile {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface TileSimilarity {
  /** One-based frame number, as FFmpeg's SSIM statistics print it. */
  frame: number;
  tile: Tile;
  ssim: number;
}

export type VisualComparison =
  | {kind: "no baseline video"; revision: string}
  | {kind: "comparison failed"; error: string}
  | {kind: "different shape"; baseline: VideoShape; rendered: VideoShape}
  | {kind: "visible change"; lowest: TileSimilarity}
  | {kind: "no visible change"; lowest: TileSimilarity};

const SSIM_STATS_LINE = /^n:(\d+) .*\bAll:(\d+(?:\.\d+)?)\b/u;

/** Splits a frame into tiles of at most SSIM_TILE_PIXELS, with narrower tiles at the edges. */
export function ssimTiles(width: number, height: number): Tile[] {
  const tiles: Tile[] = [];
  for (let y = 0; y < height; y += SSIM_TILE_PIXELS) {
    for (let x = 0; x < width; x += SSIM_TILE_PIXELS) {
      tiles.push({
        x,
        y,
        width: Math.min(SSIM_TILE_PIXELS, width - x),
        height: Math.min(SSIM_TILE_PIXELS, height - y),
      });
    }
  }
  return tiles;
}

function tileStatsFile(index: number): string {
  return `tile-${index}.log`;
}

/**
 * An FFmpeg filter graph that writes the per-frame SSIM of each tile to its own statistics file.
 * The file names are relative, so the graph needs no path escaping when FFmpeg runs in their
 * folder.
 */
function tiledSsimFilterGraph(tiles: readonly Tile[]): string {
  const labels = (prefix: string): string =>
    tiles.map((_, index) => `[${prefix}${index}]`).join("");
  const chains = tiles.map(({x, y, width, height}, index) => {
    const crop = `crop=${width}:${height}:${x}:${y}`;
    // FFmpeg needs one unconnected output for the null muxer; every other chain ends in a sink.
    const sink = index === tiles.length - 1 ? "" : ",nullsink";
    return `[c${index}]${crop}[cc${index}];[r${index}]${crop}[rc${index}];`
      + `[cc${index}][rc${index}]ssim=stats_file=${tileStatsFile(index)}${sink}`;
  });
  return [
    `[0:v]split=${tiles.length}${labels("c")}`,
    `[1:v]split=${tiles.length}${labels("r")}`,
    ...chains,
  ].join(";");
}

/** Returns the least similar frame of one tile from FFmpeg's SSIM statistics. */
export function lowestTileSimilarity(ssimStats: string, tile: Tile): TileSimilarity {
  let lowest: TileSimilarity | undefined;
  for (const line of ssimStats.split("\n")) {
    const match = SSIM_STATS_LINE.exec(line);
    if (match === null) {
      continue;
    }
    const ssim = Number(match[2]);
    if (lowest === undefined || ssim < lowest.ssim) {
      lowest = {frame: Number(match[1]), tile, ssim};
    }
  }
  if (lowest === undefined) {
    throw new Error("FFmpeg printed no per-frame SSIM values");
  }
  return lowest;
}

export function classifyTileSimilarity(lowest: TileSimilarity): VisualComparison {
  return lowest.ssim < MIN_UNCHANGED_TILE_SSIM
    ? {kind: "visible change", lowest}
    : {kind: "no visible change", lowest};
}

function sameShape(baseline: VideoShape, rendered: VideoShape): boolean {
  return baseline.width === rendered.width
    && baseline.height === rendered.height
    && baseline.frameRate === rendered.frameRate
    && baseline.frameCount === rendered.frameCount;
}

function failureDetail(stderr: unknown, error: Error | undefined): string {
  return String(stderr ?? "").trim() || error?.message || "no error detail";
}

function probeVideoShape(path: string): VideoShape {
  const result = spawnSync(
    "ffprobe",
    [
      "-v", "error",
      "-select_streams", "v:0",
      "-show_entries", "stream=width,height,r_frame_rate,nb_frames",
      "-of", "json",
      path,
    ],
    {encoding: "utf8"},
  );
  if (result.status !== 0) {
    throw new Error(`ffprobe failed for ${path}: ${failureDetail(result.stderr, result.error)}`);
  }
  const [stream] = (JSON.parse(result.stdout) as {
    streams: {width: number; height: number; r_frame_rate: string; nb_frames?: string}[];
  }).streams;
  const frameCount = Number(stream?.nb_frames);
  if (stream === undefined || !Number.isInteger(frameCount)) {
    throw new Error(`ffprobe reports no video frame count for ${path}`);
  }
  return {
    width: stream.width,
    height: stream.height,
    frameRate: stream.r_frame_rate,
    frameCount,
  };
}

function measureLowestTileSimilarity(
  baselinePath: string,
  renderedPath: string,
  shape: VideoShape,
  workDirectory: string,
): TileSimilarity {
  const tiles = ssimTiles(shape.width, shape.height);
  const result = spawnSync(
    "ffmpeg",
    [
      "-v", "error",
      "-nostats",
      // FFmpeg runs in the statistics folder, so relative input paths must be resolved first.
      "-i", resolve(baselinePath),
      "-i", resolve(renderedPath),
      "-filter_complex", tiledSsimFilterGraph(tiles),
      "-f", "null",
      "-",
    ],
    {cwd: workDirectory, encoding: "utf8"},
  );
  if (result.status !== 0) {
    throw new Error(
      "could not compare the rendered README demo with the baseline video: "
      + failureDetail(result.stderr, result.error),
    );
  }
  return tiles
    .map((tile, index) => lowestTileSimilarity(
      readFileSync(join(workDirectory, tileStatsFile(index)), "utf8"),
      tile,
    ))
    .reduce((lowest, candidate) => (candidate.ssim < lowest.ssim ? candidate : lowest));
}

/** The commit whose demo video a new render is compared with. */
export interface Baseline {
  /** The revision as the maintainer named it, such as `HEAD` or `origin/main`. */
  revision: string;
  commit: string;
}

/** Resolves the `--compare-with` revision before rendering, so a typo fails in seconds. */
export function resolveBaseline(repoRoot: string, revision: string): Baseline {
  const result = spawnSync(
    "git",
    ["rev-parse", "--verify", "--quiet", "--end-of-options", `${revision}^{commit}`],
    {cwd: repoRoot, encoding: "utf8"},
  );
  if (result.status !== 0) {
    throw new Error(`--compare-with ${revision} does not name a commit`);
  }
  return {revision, commit: result.stdout.trim()};
}

/**
 * Writes the baseline commit's demo video to `destination` and returns that path, or undefined when
 * that commit has no demo video. Reading a commit rather than the working tree keeps the baseline
 * fixed when the render runs more than once before a commit.
 */
export function writeBaselineVideo(
  repoRoot: string,
  baseline: Baseline,
  destination: string,
): string | undefined {
  const objectName = `${baseline.commit}:${VIDEO_RELATIVE_PATH}`;
  const exists = spawnSync("git", ["cat-file", "-e", objectName], {cwd: repoRoot});
  if (exists.status !== 0) {
    return undefined;
  }
  const result = spawnSync(
    "git",
    ["cat-file", "blob", objectName],
    {cwd: repoRoot, maxBuffer: COMMITTED_VIDEO_BUFFER_BYTES},
  );
  if (result.status !== 0 || !Buffer.isBuffer(result.stdout)) {
    throw new Error(
      `could not read the README demo video at ${baseline.revision}: `
      + failureDetail(result.stderr, result.error),
    );
  }
  writeFileSync(destination, result.stdout);
  return destination;
}

/**
 * Compares two videos tile by tile in every frame. An undefined `baseline.path` means the baseline
 * commit has no demo video.
 */
export function compareWithBaselineVideo(
  baseline: {revision: string; path: string | undefined},
  renderedPath: string,
  workDirectory: string,
): VisualComparison {
  if (baseline.path === undefined) {
    return {kind: "no baseline video", revision: baseline.revision};
  }
  const baselineShape = probeVideoShape(baseline.path);
  const rendered = probeVideoShape(renderedPath);
  if (!sameShape(baselineShape, rendered)) {
    return {kind: "different shape", baseline: baselineShape, rendered};
  }
  const statsDirectory = mkdtempSync(join(workDirectory, "ssim-"));
  try {
    return classifyTileSimilarity(
      measureLowestTileSimilarity(baseline.path, renderedPath, rendered, statsDirectory),
    );
  } finally {
    rmSync(statsDirectory, {recursive: true, force: true});
  }
}

/**
 * Reads the baseline commit's video and compares the render with it. An error becomes a failed
 * comparison, so a comparison problem never discards a render that already passed its checks.
 */
export function compareRenderWithBaseline(
  repoRoot: string,
  baseline: Baseline,
  renderedPath: string,
  workDirectory: string,
): VisualComparison {
  try {
    const destination = join(workDirectory, "baseline-demo.mp4");
    const path = writeBaselineVideo(repoRoot, baseline, destination);
    const committed = {revision: baseline.revision, path};
    return compareWithBaselineVideo(committed, renderedPath, workDirectory);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return {kind: "comparison failed", error: message};
  }
}

/** Whether the README's uploaded video attachment must be replaced with the new render. */
export function needsAttachmentReupload(comparison: VisualComparison): boolean {
  return comparison.kind !== "no visible change";
}

function describeShape({width, height, frameRate, frameCount}: VideoShape): string {
  return `${width}x${height}, ${frameRate} fps, ${frameCount} frames`;
}

function describeLowest({frame, tile, ssim}: TileSimilarity): string {
  return `lowest tile SSIM ${ssim.toFixed(6)} in frame ${frame} at x=${tile.x}, y=${tile.y}, `
    + `threshold ${MIN_UNCHANGED_TILE_SSIM}`;
}

function describeReason(comparison: VisualComparison): string {
  switch (comparison.kind) {
    case "no baseline video":
      return `${comparison.revision} has no ${VIDEO_RELATIVE_PATH} to compare with`;
    case "different shape":
      return `baseline ${describeShape(comparison.baseline)}, `
        + `rendered ${describeShape(comparison.rendered)}`;
    case "comparison failed":
      return `the comparison failed: ${comparison.error}`;
    case "visible change":
    case "no visible change":
      return `${comparison.kind}, ${describeLowest(comparison.lowest)}`;
  }
}

/** The verdicts the render prints; the maintenance guide and Renovate's notes quote them. */
export const REUPLOAD_ATTACHMENT_VERDICT = "re-upload the README video attachment";
export const KEEP_ATTACHMENT_VERDICT = "keep the existing README video attachment";

export function describeVisualComparison(comparison: VisualComparison): string {
  const verdict = needsAttachmentReupload(comparison)
    ? `${REUPLOAD_ATTACHMENT_VERDICT} as docs/demo/README.md describes`
    : KEEP_ATTACHMENT_VERDICT;
  return `README video: ${verdict} (${describeReason(comparison)})`;
}
