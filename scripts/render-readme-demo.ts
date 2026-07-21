/**
 * Renders the README demo video and poster from the HyperFrames composition
 * in docs/demo, then verifies the committed outputs:
 *
 * - docs/assets/readme-demo.mp4 (H.264, silent, no audio stream)
 * - docs/assets/readme-demo-poster.png (hero frame near the end)
 * - docs/demo/render-manifest.json (source and artifact SHA-256 values)
 *
 * Usage: node scripts/render-readme-demo.ts [--skip-check]
 *
 * Requires a Git working tree, Node 22.18+, Docker, FFmpeg, ffprobe, and pnpm. The HyperFrames
 * CLI is fetched on demand at the exact pinned version, and its Docker
 * renderer supplies the production browser, fonts, and encoder.
 */

import { spawnSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  statSync,
} from "node:fs";
import {tmpdir} from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  createReadmeDemoSourceSnapshot,
  writeReadmeDemoManifest,
} from "./readme-demo-manifest.ts";
import {
  type CompositionTiming,
  materializeReadmeDemoTiming,
} from "./readme-demo-timing.ts";

const HYPERFRAMES = "hyperframes@0.7.64";
/** Constant quality spends fewer bits on static UI while keeping text crisp. */
const VIDEO_CRF = "26";
const MEBIBYTE = 1024 * 1024;
const MEGABYTE = 1_000_000;
const MIN_VIDEO_BYTES = 6 * MEBIBYTE;
const MAX_VIDEO_BYTES = 10 * MEGABYTE;
const MIN_DARK_PIXEL_RATIO = 0.01;

interface TextRegionSample {
  label: string;
  time: string;
  crop: string;
}

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const videoPath = join(repoRoot, "docs", "assets", "readme-demo.mp4");
const posterPath = join(repoRoot, "docs", "assets", "readme-demo-poster.png");

function textRegionSamples(timing: CompositionTiming): TextRegionSample[] {
  const sceneTime = (sceneId: string, offset: number): string =>
    ((timing.sceneStarts.get(sceneId) ?? 0) + offset).toFixed(1);
  return [
    {label: "intro", time: "2", crop: "crop=1620:500:150:280"},
    {label: "move explanation", time: sceneTime("s04", 2), crop: "crop=1760:560:80:250"},
    {label: "review caption", time: sceneTime("s08", 2), crop: "crop=1760:230:80:30"},
    {label: "phone caption", time: sceneTime("s15", 1), crop: "crop=1150:220:60:30"},
    {label: "closing message", time: sceneTime("s16", 2), crop: "crop=1720:300:100:80"},
  ];
}

function run(command: string, args: string[], demoDir: string): void {
  console.log(`\n$ ${command} ${args.join(" ")}`);
  const windowsPnpm = process.platform === "win32" && command === "pnpm";
  const executable = windowsPnpm ? "cmd.exe" : command;
  const commandArgs = windowsPnpm ? ["/d", "/s", "/c", "pnpm.cmd", ...args] : args;
  const result = spawnSync(executable, commandArgs, { cwd: demoDir, stdio: "inherit" });
  if (result.status !== 0) {
    const detail = result.error?.message;
    throw new Error(
      `${command} ${args[0] ?? ""} failed with status ${result.status}` +
      (detail === undefined ? "" : `: ${detail}`),
    );
  }
}

function labelContainerMountForSelinux(path: string): void {
  if (process.platform !== "linux" || !existsSync("/sys/fs/selinux/enforce")) {
    return;
  }
  const result = spawnSync("chcon", ["-Rt", "container_file_t", path], {encoding: "utf8"});
  if (result.status !== 0) {
    const detail = result.stderr?.trim() || result.error?.message || "no error detail";
    throw new Error(`could not label the README demo render directory for SELinux: ${detail}`);
  }
}

function ffprobe(path: string): { codec: string; duration: number; streamCount: number } {
  const result = spawnSync(
    "ffprobe",
    ["-v", "error", "-print_format", "json", "-show_streams", "-show_format", path],
    { encoding: "utf8" },
  );
  if (result.status !== 0) {
    const detail = result.stderr?.trim() || result.error?.message || "no error detail";
    throw new Error(`ffprobe failed for ${path}: ${detail}`);
  }
  const parsed = JSON.parse(result.stdout) as {
    streams: { codec_type: string; codec_name: string }[];
    format: { duration: string };
  };
  const video = parsed.streams.find((stream) => stream.codec_type === "video");
  if (video === undefined) {
    throw new Error(`${path} has no video stream`);
  }
  return {
    codec: video.codec_name,
    duration: Number(parsed.format.duration),
    streamCount: parsed.streams.length,
  };
}

function verifyRenderedText(path: string, samples: TextRegionSample[]): void {
  for (const sample of samples) {
    const result = spawnSync(
      "ffmpeg",
      [
        "-v",
        "error",
        "-ss",
        sample.time,
        "-i",
        path,
        "-vf",
        `${sample.crop},format=gray`,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "pipe:1",
      ],
      { maxBuffer: 8 * MEBIBYTE },
    );
    if (result.status !== 0 || !Buffer.isBuffer(result.stdout) || result.stdout.length === 0) {
      const detail = Buffer.isBuffer(result.stderr)
        ? result.stderr.toString().trim()
        : result.error?.message;
      throw new Error(
        `could not inspect rendered text in the ${sample.label} frame` +
        (detail === undefined || detail === "" ? "" : `: ${detail}`),
      );
    }

    let darkPixels = 0;
    for (const luminance of result.stdout) {
      if (luminance < 100) {
        darkPixels += 1;
      }
    }
    const darkPixelRatio = darkPixels / result.stdout.length;
    if (darkPixelRatio < MIN_DARK_PIXEL_RATIO) {
      throw new Error(
        `${sample.label} frame has too little rendered text: ` +
        `${(darkPixelRatio * 100).toFixed(2)}% dark pixels`,
      );
    }
  }
}

const renderRoot = mkdtempSync(join(tmpdir(), "symphony-trello-readme-demo-"));
// Keep container labels and partial render output away from the Git working tree.
chmodSync(renderRoot, 0o755);
const snapshotDemoDir = join(renderRoot, "demo");
const renderOutputDir = join(renderRoot, "output");
mkdirSync(renderOutputDir, {recursive: true});
chmodSync(renderOutputDir, 0o777);
const renderedVideoPath = join(renderOutputDir, "readme-demo.mp4");
const renderedPosterPath = join(renderOutputDir, "readme-demo-poster.png");

try {
  // Hash and copy each composition input from the same read, then render only that immutable copy.
  const sourceBeforeRender = createReadmeDemoSourceSnapshot(repoRoot, snapshotDemoDir);
  const timing = materializeReadmeDemoTiming(snapshotDemoDir);
  labelContainerMountForSelinux(renderRoot);
  const expectedDurationSeconds = timing.duration;
  const skipCheck = process.argv.includes("--skip-check");

  if (!skipCheck) {
    run("pnpm", ["dlx", HYPERFRAMES, "check"], snapshotDemoDir);
  }

  run("pnpm", [
    "dlx",
    HYPERFRAMES,
    "render",
    "--docker",
    "--quality",
    "high",
    "--crf",
    VIDEO_CRF,
    "--output",
    renderedVideoPath,
  ], snapshotDemoDir);

  // Extract the poster from the final hero so it uses the exact same font
  // and browser render as the MP4. HyperFrames' standalone snapshot path can
  // fail to load bundled fonts independently of the render path.
  const posterTimeSeconds = (expectedDurationSeconds - 0.5).toFixed(1);
  run("ffmpeg", [
    "-y",
    "-loglevel",
    "error",
    "-i",
    renderedVideoPath,
    "-ss",
    posterTimeSeconds,
    "-frames:v",
    "1",
    renderedPosterPath,
  ], snapshotDemoDir);

  const probe = ffprobe(renderedVideoPath);
  if (probe.streamCount !== 1 || probe.codec !== "h264") {
    throw new Error(
      `expected exactly one H.264 video stream, found ${probe.streamCount} stream(s), codec ${probe.codec}`,
    );
  }
  if (!Number.isFinite(probe.duration) || Math.abs(probe.duration - expectedDurationSeconds) > 1) {
    throw new Error(
      `expected ~${expectedDurationSeconds}s of video, ffprobe reports ${probe.duration}s`,
    );
  }
  const videoBytes = statSync(renderedVideoPath).size;
  if (videoBytes <= MIN_VIDEO_BYTES) {
    throw new Error(
      `expected readme-demo.mp4 to exceed 6 MiB, found ${(videoBytes / MEBIBYTE).toFixed(1)} MiB`,
    );
  }
  if (videoBytes >= MAX_VIDEO_BYTES) {
    throw new Error(
      `expected readme-demo.mp4 to stay below GitHub's 10 MB attachment limit, `
      + `found ${(videoBytes / MEGABYTE).toFixed(1)} MB`,
    );
  }
  verifyRenderedText(renderedVideoPath, textRegionSamples(timing));
  copyFileSync(renderedVideoPath, videoPath);
  copyFileSync(renderedPosterPath, posterPath);
  writeReadmeDemoManifest(repoRoot, sourceBeforeRender);

  console.log(`\nreadme-demo.mp4: ${(videoBytes / MEBIBYTE).toFixed(1)} MiB, ` +
    `${probe.duration.toFixed(1)}s, H.264, silent (no audio stream)`);
  console.log(`readme-demo-poster.png: ${(statSync(posterPath).size / MEBIBYTE).toFixed(1)} MiB`);
  console.log("render-manifest.json: source and artifact checksums updated");
  console.log("Done.");
} finally {
  rmSync(renderRoot, {recursive: true, force: true});
}
