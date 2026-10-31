import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import test from "node:test";

// The video comparison command is glue over scripts/readme-demo-visual-change.ts, whose FFmpeg
// comparison has its own tests; this covers the command's own failure modes.

function compare(...args: string[]): {status: number | null; stderr: string} {
  const result = spawnSync("node", ["scripts/readme-demo-compare-videos.ts", ...args], {encoding: "utf8"});
  return {status: result.status, stderr: result.stderr};
}

test("the comparison needs exactly two video files", () => {
  const none = compare();
  const three = compare("README.md", "README.md", "README.md");

  assert.equal(none.status, 2);
  assert.match(none.stderr, /Usage: node scripts\/readme-demo-compare-videos\.ts <baseline-video> <video>/);
  assert.equal(three.status, 2);
});

test("a missing file is named by the argument that holds it, before any video is read", () => {
  const baseline = compare("no-such-attachment.mp4", "README.md");
  const video = compare("README.md", "no-such-render.mp4");

  assert.equal(baseline.status, 2);
  assert.match(baseline.stderr, /^<baseline-video> no-such-attachment\.mp4 does not exist$/m);
  assert.equal(video.status, 2);
  assert.match(video.stderr, /^<video> no-such-render\.mp4 does not exist$/m);
});
