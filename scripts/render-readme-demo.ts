#!/usr/bin/env node
/**
 * Renders the README demo video and poster from the HyperFrames composition
 * in docs/demo, then verifies the committed outputs:
 *
 * - docs/assets/readme-demo.mp4 (H.264, silent, no audio stream)
 * - docs/assets/readme-demo-poster.png (hero frame near the end)
 *
 * Usage: node scripts/render-readme-demo.ts [--skip-check]
 *
 * Requires Node 22+, FFmpeg/ffprobe, and pnpm (the HyperFrames CLI is
 * fetched on demand at the exact pinned version, so renders stay
 * reproducible).
 */

import { spawnSync } from "node:child_process";
import { copyFileSync, mkdtempSync, readdirSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HYPERFRAMES = "hyperframes@0.7.64";
/** Constant-rate factor chosen so the 86s video stays README-friendly. */
const VIDEO_CRF = "27";
/** The final hero frame; keep inside the last scene of docs/demo/index.html. */
const POSTER_TIME_SECONDS = "89.5";
const EXPECTED_DURATION_SECONDS = 90;

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const demoDir = join(repoRoot, "docs", "demo");
const videoPath = join(repoRoot, "docs", "assets", "readme-demo.mp4");
const posterPath = join(repoRoot, "docs", "assets", "readme-demo-poster.png");

function run(command: string, args: string[]): void {
  console.log(`\n$ ${command} ${args.join(" ")}`);
  const result = spawnSync(command, args, { cwd: demoDir, stdio: "inherit" });
  if (result.status !== 0) {
    throw new Error(`${command} ${args[0] ?? ""} failed with status ${result.status}`);
  }
}

function ffprobe(path: string): { codec: string; duration: number; streamCount: number } {
  const result = spawnSync(
    "ffprobe",
    ["-v", "error", "-print_format", "json", "-show_streams", "-show_format", path],
    { encoding: "utf8" },
  );
  if (result.status !== 0) {
    throw new Error(`ffprobe failed for ${path}: ${result.stderr}`);
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

const skipCheck = process.argv.includes("--skip-check");

if (!skipCheck) {
  run("pnpm", ["dlx", HYPERFRAMES, "check"]);
}

run("pnpm", [
  "dlx",
  HYPERFRAMES,
  "render",
  "--quality",
  "high",
  "--crf",
  VIDEO_CRF,
  "--output",
  videoPath,
]);

const posterDir = mkdtempSync(join(tmpdir(), "readme-demo-poster-"));
try {
  run("pnpm", [
    "dlx",
    HYPERFRAMES,
    "snapshot",
    "--at",
    POSTER_TIME_SECONDS,
    "--no-end",
    "--output",
    posterDir,
  ]);
  const frame = readdirSync(posterDir).find((name) => name.endsWith(".png"));
  if (frame === undefined) {
    throw new Error(`no poster frame written to ${posterDir}`);
  }
  copyFileSync(join(posterDir, frame), posterPath);
} finally {
  rmSync(posterDir, { recursive: true, force: true });
}

const probe = ffprobe(videoPath);
if (probe.streamCount !== 1 || probe.codec !== "h264") {
  throw new Error(
    `expected exactly one H.264 video stream, found ${probe.streamCount} stream(s), codec ${probe.codec}`,
  );
}
if (Math.abs(probe.duration - EXPECTED_DURATION_SECONDS) > 1) {
  throw new Error(
    `expected ~${EXPECTED_DURATION_SECONDS}s of video, ffprobe reports ${probe.duration}s`,
  );
}

const megabyte = 1024 * 1024;
console.log(`\nreadme-demo.mp4: ${(statSync(videoPath).size / megabyte).toFixed(1)} MB, ` +
  `${probe.duration.toFixed(1)}s, H.264, silent (no audio stream)`);
console.log(`readme-demo-poster.png: ${(statSync(posterPath).size / megabyte).toFixed(1)} MB`);
console.log("Done.");
