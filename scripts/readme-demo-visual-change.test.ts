import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, join, relative} from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";
import {VIDEO_RELATIVE_PATH} from "./readme-demo-manifest.ts";
import {
  classifyTileSimilarity,
  compareRenderWithBaseline,
  compareWithBaselineVideo,
  describeVisualComparison,
  KEEP_ATTACHMENT_VERDICT,
  lowestTileSimilarity,
  MIN_UNCHANGED_TILE_SSIM,
  needsAttachmentReupload,
  resolveBaseline,
  REUPLOAD_ATTACHMENT_VERDICT,
  SSIM_TILE_PIXELS,
  ssimTiles,
  type Tile,
  type VideoShape,
  type VisualComparison,
  writeBaselineVideo,
} from "./readme-demo-visual-change.ts";

// Lowest tile SSIM of real 1080p renders, measured for ADR 0132: HyperFrames 0.8.174 against
// 0.8.181 with unchanged sources, and 0.8.181 against a render that changed one caption word.
const NOISE_SSIM = 0.979292;
const CAPTION_WORD_SSIM = 0.9165;

const checkoutRoot = fileURLToPath(new URL("../", import.meta.url));

const FFMPEG_TOOLS_AVAILABLE = ["ffmpeg", "ffprobe"].every(
  (tool) => spawnSync(tool, ["-version"]).status === 0,
);
const NEEDS_FFMPEG = {skip: FFMPEG_TOOLS_AVAILABLE ? false : "FFmpeg and ffprobe are not on PATH"};

const SHAPE: VideoShape = {width: 1920, height: 1080, frameRate: "30/1", frameCount: 4095};
const TILE: Tile = {x: 0, y: 0, width: SSIM_TILE_PIXELS, height: SSIM_TILE_PIXELS};

function temporaryDirectory(t: test.TestContext, prefix: string): string {
  const directory = mkdtempSync(join(tmpdir(), prefix));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  return directory;
}

function git(cwd: string, ...args: string[]): void {
  execFileSync(
    "git",
    ["-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", ...args],
    {cwd},
  );
}

/** Encodes one second of a white 320x180 frame, optionally with a word-sized dark box. */
function encodeFixtureVideo(path: string, {seconds = 1, box = false} = {}): string {
  const wordBox = "drawbox=x=150:y=40:w=60:h=14:color=black:t=fill";
  const filters = ["format=yuv420p", ...(box ? [wordBox] : [])];
  execFileSync("ffmpeg", [
    "-v", "error",
    "-f", "lavfi",
    "-i", `color=c=white:s=320x180:r=30:d=${seconds}`,
    "-vf", filters.join(","),
    path,
  ]);
  return path;
}

test("the least similar frame in a tile's FFmpeg SSIM statistics decides", () => {
  // given
  const stats = [
    "n:1 Y:1.000000 U:1.000000 V:1.000000 All:1.000000 (inf)",
    "n:2 Y:0.998287 U:0.999102 V:0.999031 All:0.998491 (28.213411)",
    "n:3 Y:0.999540 U:0.999800 V:0.999700 All:0.999620 (34.202113)",
    "",
  ].join("\n");

  // when
  const lowest = lowestTileSimilarity(stats, TILE);

  // then
  assert.deepEqual(lowest, {frame: 2, tile: TILE, ssim: 0.998491});
});

test("SSIM statistics without a frame line are rejected", () => {
  // given
  const statsWithoutFrames = "";

  // when
  const parse = (): unknown => lowestTileSimilarity(statsWithoutFrames, TILE);

  // then
  assert.throws(parse, /FFmpeg printed no per-frame SSIM values/);
});

test("tiles cover the whole frame, with narrower tiles at the right and bottom edges", () => {
  // when
  const fullHd = ssimTiles(1920, 1080);
  const fixture = ssimTiles(320, 180);

  // then
  assert.equal(fullHd.length, 16 * 9);
  assert.ok(
    fullHd.every(({width, height}) => width === SSIM_TILE_PIXELS && height === SSIM_TILE_PIXELS),
    "1080p divides into whole tiles",
  );
  assert.deepEqual(fixture, [
    {x: 0, y: 0, width: 120, height: 120},
    {x: 120, y: 0, width: 120, height: 120},
    {x: 240, y: 0, width: 80, height: 120},
    {x: 0, y: 120, width: 120, height: 60},
    {x: 120, y: 120, width: 120, height: 60},
    {x: 240, y: 120, width: 80, height: 60},
  ]);
});

test("a tile exactly at the threshold still counts as unchanged", () => {
  // given
  const atThreshold = {frame: 7, tile: TILE, ssim: MIN_UNCHANGED_TILE_SSIM};
  const belowThreshold = {frame: 7, tile: TILE, ssim: MIN_UNCHANGED_TILE_SSIM - 0.000001};

  // when
  const results = [atThreshold, belowThreshold].map(
    (lowest) => classifyTileSimilarity(lowest).kind,
  );

  // then
  assert.deepEqual(results, ["no visible change", "visible change"]);
});

test("the threshold separates re-render noise from a one-word caption change", () => {
  // when
  const noise = classifyTileSimilarity({frame: 3056, tile: TILE, ssim: NOISE_SSIM});
  const caption = classifyTileSimilarity({frame: 223, tile: TILE, ssim: CAPTION_WORD_SSIM});

  // then
  assert.equal(noise.kind, "no visible change", "a HyperFrames update without visible change");
  assert.equal(caption.kind, "visible change", "one changed caption word");
});

test("only an unchanged comparison keeps the existing README attachment", () => {
  // given
  const lowest = {frame: 1, tile: TILE, ssim: 1};
  const comparisons: readonly VisualComparison[] = [
    {kind: "no baseline video", revision: "HEAD"},
    {kind: "comparison failed", error: "ffprobe failed"},
    {kind: "different shape", baseline: SHAPE, rendered: {...SHAPE, frameCount: 4096}},
    {kind: "visible change", lowest},
    {kind: "no visible change", lowest},
  ];

  // when
  const decisions = comparisons.map((comparison) => ({
    kind: comparison.kind,
    reupload: needsAttachmentReupload(comparison),
    message: describeVisualComparison(comparison),
  }));

  // then
  assert.deepEqual(
    decisions.map(({kind, reupload}) => ({kind, reupload})),
    [
      {kind: "no baseline video", reupload: true},
      {kind: "comparison failed", reupload: true},
      {kind: "different shape", reupload: true},
      {kind: "visible change", reupload: true},
      {kind: "no visible change", reupload: false},
    ],
  );
  for (const {kind, reupload, message} of decisions) {
    assert.ok(
      message.includes(reupload ? REUPLOAD_ATTACHMENT_VERDICT : KEEP_ATTACHMENT_VERDICT),
      `the ${kind} message must state the verdict the guide quotes: ${message}`,
    );
  }
});

/** A repository whose first commit has `firstVideo` and whose HEAD has `headVideo`. */
function createBaselineRepository(
  t: test.TestContext,
  {firstVideo, headVideo}: {firstVideo?: string; headVideo: string},
): string {
  const repoRoot = temporaryDirectory(t, "readme-demo-baseline-");
  git(repoRoot, "init", "--quiet");
  const videoPath = join(repoRoot, VIDEO_RELATIVE_PATH);
  mkdirSync(dirname(videoPath), {recursive: true});
  if (firstVideo === undefined) {
    git(repoRoot, "commit", "--quiet", "--allow-empty", "-m", "first");
  } else {
    writeFileSync(videoPath, firstVideo);
    git(repoRoot, "add", VIDEO_RELATIVE_PATH);
    git(repoRoot, "commit", "--quiet", "-m", "first");
  }
  git(repoRoot, "tag", "first");
  writeFileSync(videoPath, headVideo);
  git(repoRoot, "add", VIDEO_RELATIVE_PATH);
  git(repoRoot, "commit", "--quiet", "-m", "head");
  return repoRoot;
}

function readBaselineVideo(
  t: test.TestContext,
  repoRoot: string,
  revision: string,
): string | undefined {
  const destination = join(temporaryDirectory(t, "readme-demo-baseline-output-"), "baseline.mp4");
  const path = writeBaselineVideo(repoRoot, resolveBaseline(repoRoot, revision), destination);
  return path === undefined ? undefined : readFileSync(path, "utf8");
}

test("the baseline is the video committed at the revision, not the working-tree copy", (t) => {
  // given
  const repoRoot = createBaselineRepository(t, {
    firstVideo: "first render",
    headVideo: "head render",
  });
  writeFileSync(join(repoRoot, VIDEO_RELATIVE_PATH), "uncommitted render");

  // when
  const videos = ["HEAD", "first"].map((revision) => readBaselineVideo(t, repoRoot, revision));

  // then
  assert.deepEqual(videos, ["head render", "first render"]);
});

test("a revision without a demo video has no baseline video", (t) => {
  // given
  const repoRoot = createBaselineRepository(t, {headVideo: "head render"});

  // when
  const video = readBaselineVideo(t, repoRoot, "first");

  // then
  assert.equal(video, undefined);
});

test("an unknown --compare-with revision fails before the render starts", (t) => {
  // given
  const repoRoot = createBaselineRepository(t, {headVideo: "head render"});

  // when
  const resolve = (): unknown => resolveBaseline(repoRoot, "origin/no-such-branch");

  // then
  assert.throws(resolve, /--compare-with origin\/no-such-branch does not name a commit/);
});

test("a comparison error is reported as a needed upload instead of failing the render", (t) => {
  // given
  const repoRoot = createBaselineRepository(t, {headVideo: "not a video"});
  const workDirectory = temporaryDirectory(t, "readme-demo-failed-comparison-");
  const baseline = resolveBaseline(repoRoot, "HEAD");

  // when
  const comparison = compareRenderWithBaseline(
    repoRoot,
    baseline,
    join(workDirectory, "missing-render.mp4"),
    workDirectory,
  );

  // then
  assert.equal(comparison.kind, "comparison failed");
  assert.equal(needsAttachmentReupload(comparison), true);
});

test("the maintenance guide states the tile size and threshold the code uses", () => {
  // given
  const guide = readFileSync(join(checkoutRoot, "docs", "demo", "README.md"), "utf8");

  // when
  const statements = [
    `tiles of ${SSIM_TILE_PIXELS} by ${SSIM_TILE_PIXELS} pixels`,
    `below ${MIN_UNCHANGED_TILE_SSIM}.`,
  ];

  // then
  for (const statement of statements) {
    assert.ok(guide.includes(statement), `docs/demo/README.md must say "${statement}"`);
  }
});

test("FFmpeg comparison keeps a re-encode and flags a word-sized change", NEEDS_FFMPEG, (t) => {
  // given
  const directory = temporaryDirectory(t, "readme-demo-ssim-");
  const baseline = encodeFixtureVideo(join(directory, "baseline.mp4"));
  const reencoded = encodeFixtureVideo(join(directory, "reencoded.mp4"));
  const captioned = encodeFixtureVideo(join(directory, "captioned.mp4"), {box: true});
  const longer = encodeFixtureVideo(join(directory, "longer.mp4"), {seconds: 2});

  // Relative paths prove the comparison does not depend on FFmpeg's working directory. node:test
  // runs the tests of one file one at a time, so no other test sees the changed directory.
  const previousDirectory = process.cwd();
  process.chdir(directory);
  t.after(() => process.chdir(previousDirectory));

  // when
  const kinds = [reencoded, captioned, longer].map(
    (rendered) => compareWithBaselineVideo(
      {revision: "HEAD", path: relative(directory, baseline)},
      relative(directory, rendered),
      directory,
    ).kind,
  );

  // then
  assert.deepEqual(kinds, ["no visible change", "visible change", "different shape"]);
});

test("a render without a baseline video needs an upload", () => {
  // given
  const baseline = {revision: "HEAD", path: undefined};

  // when
  const comparison = compareWithBaselineVideo(baseline, "unused.mp4", tmpdir());

  // then
  assert.deepEqual(comparison, {kind: "no baseline video", revision: "HEAD"});
});
