/**
 * Renders the README demo video and poster from the HyperFrames composition
 * in docs/demo, then verifies the committed outputs:
 *
 * - docs/assets/readme-demo.mp4 (H.264, silent, no audio stream)
 * - docs/assets/readme-demo-poster.png (hero frame near the end)
 *
 * Usage: node scripts/render-readme-demo.ts [--skip-check]
 *
 * Requires Node 22.18+, Docker, FFmpeg, ffprobe, and pnpm. The HyperFrames
 * CLI is fetched on demand at the exact pinned version, and its Docker
 * renderer supplies the production browser, fonts, and encoder.
 */

import { spawnSync } from "node:child_process";
import { readFileSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HYPERFRAMES = "hyperframes@0.7.64";
/** Target bitrate keeps text crisp while staying well below GitHub's file limit. */
const VIDEO_BITRATE = "1M";
const MEBIBYTE = 1024 * 1024;
const MIN_VIDEO_BYTES = 6 * MEBIBYTE;
const MIN_DARK_PIXEL_RATIO = 0.01;
/** The final hero frame; keep inside the last scene of docs/demo/index.html. */
const POSTER_TIME_SECONDS = "92.5";

const TEXT_REGION_SAMPLES = [
  { label: "intro", time: "2", crop: "crop=1620:500:150:280" },
  { label: "board caption", time: "20", crop: "crop=1760:230:80:30" },
  { label: "review caption", time: "50", crop: "crop=1760:230:80:30" },
  { label: "phone caption", time: "80", crop: "crop=1150:220:60:30" },
  { label: "closing message", time: "92", crop: "crop=1720:300:100:80" },
] as const;

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

function verifyRenderedText(path: string): void {
  for (const sample of TEXT_REGION_SAMPLES) {
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

const expectedDurationSeconds = readExpectedDurationSeconds();
const skipCheck = process.argv.includes("--skip-check");

if (!skipCheck) {
  run("pnpm", ["dlx", HYPERFRAMES, "check"]);
}

run("pnpm", [
  "dlx",
  HYPERFRAMES,
  "render",
  "--docker",
  "--quality",
  "high",
  "--video-bitrate",
  VIDEO_BITRATE,
  "--output",
  videoPath,
]);

// Extract the poster from the completed video so it uses the exact same font
// and browser render as the MP4. HyperFrames' standalone snapshot path can
// fail to load bundled fonts independently of the render path.
run("ffmpeg", [
  "-y",
  "-loglevel",
  "error",
  "-i",
  videoPath,
  "-ss",
  POSTER_TIME_SECONDS,
  "-frames:v",
  "1",
  posterPath,
]);

const probe = ffprobe(videoPath);
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
const videoBytes = statSync(videoPath).size;
if (videoBytes <= MIN_VIDEO_BYTES) {
  throw new Error(
    `expected readme-demo.mp4 to exceed 6 MiB, found ${(videoBytes / MEBIBYTE).toFixed(1)} MiB`,
  );
}
verifyRenderedText(videoPath);

console.log(`\nreadme-demo.mp4: ${(videoBytes / MEBIBYTE).toFixed(1)} MiB, ` +
  `${probe.duration.toFixed(1)}s, H.264, silent (no audio stream)`);
console.log(`readme-demo-poster.png: ${(statSync(posterPath).size / MEBIBYTE).toFixed(1)} MiB`);
console.log("Done.");
