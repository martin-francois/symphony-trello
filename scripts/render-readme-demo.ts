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
import {
  copyFileSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HYPERFRAMES = "hyperframes@0.7.64";
/** Constant-rate factor chosen so the demo video stays README-friendly. */
const VIDEO_CRF = "27";
/** The final hero frame; keep inside the last scene of docs/demo/index.html. */
const POSTER_TIME_SECONDS = "92.5";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const demoDir = join(repoRoot, "docs", "demo");
const videoPath = join(repoRoot, "docs", "assets", "readme-demo.mp4");
const posterPath = join(repoRoot, "docs", "assets", "readme-demo-poster.png");

function readExpectedDurationSeconds(): number {
  const html = readFileSync(join(demoDir, "index.html"), "utf8");
  const rootTag = html.match(/<[a-z][^>]*\bid=["']root["'][^>]*>/i)?.[0];
  const htmlDuration = Number(rootTag?.match(/\bdata-duration=["']([^"']+)["']/)?.[1]);
  if (!Number.isFinite(htmlDuration) || htmlDuration <= 0) {
    throw new Error("docs/demo/index.html must declare a positive data-duration on #root");
  }

  const motion = JSON.parse(readFileSync(join(demoDir, "index.motion.json"), "utf8")) as {
    duration?: unknown;
  };
  if (typeof motion.duration !== "number" || !Number.isFinite(motion.duration) || motion.duration <= 0) {
    throw new Error("docs/demo/index.motion.json must declare a positive numeric duration");
  }
  if (htmlDuration !== motion.duration) {
    throw new Error(
      `demo duration mismatch: index.html declares ${htmlDuration}s, ` +
      `index.motion.json declares ${motion.duration}s`,
    );
  }
  return htmlDuration;
}

function run(command: string, args: string[]): void {
  console.log(`\n$ ${command} ${args.join(" ")}`);
  const windowsPnpm = process.platform === "win32" && command === "pnpm";
  const executable = windowsPnpm ? (process.env.ComSpec ?? "cmd.exe") : command;
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

const expectedDurationSeconds = readExpectedDurationSeconds();
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
if (Math.abs(probe.duration - expectedDurationSeconds) > 1) {
  throw new Error(
    `expected ~${expectedDurationSeconds}s of video, ffprobe reports ${probe.duration}s`,
  );
}

const megabyte = 1024 * 1024;
console.log(`\nreadme-demo.mp4: ${(statSync(videoPath).size / megabyte).toFixed(1)} MB, ` +
  `${probe.duration.toFixed(1)}s, H.264, silent (no audio stream)`);
console.log(`readme-demo-poster.png: ${(statSync(posterPath).size / megabyte).toFixed(1)} MB`);
console.log("Done.");
