import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, rmSync, truncateSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import test from "node:test";
import {
  assertBelowGitHubVideoAttachmentLimit,
  buildReadmeDemoManifest,
  createReadmeDemoSourceSnapshot,
  GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES,
  HYPERFRAMES_VERSION_PATH,
  readHyperframesVersion,
  readReadmeDemoManifest,
  writeReadmeDemoManifest,
} from "./readme-demo-manifest.ts";
import {HYPERFRAMES_VERSION} from "./readme-demo-hyperframes.ts";
import {applyRegexUpdate, extractRegexDependencies} from "./test-support/renovate-regex-manager.ts";

const repoRoot = fileURLToPath(new URL("../", import.meta.url));

function writeCrossPlatformFixture(root: string, newline: "\n" | "\r\n"): void {
  const textFiles = new Map([
    [
      ".gitattributes",
      [
        ".gitattributes text eol=lf",
        "docs/demo/** text=auto eol=lf",
        "scripts/readme-demo-*.ts text eol=lf",
      ],
    ],
    ["docs/demo/index.html", ["<main>", "  Demo", "</main>"]],
    [HYPERFRAMES_VERSION_PATH, ['export const HYPERFRAMES_VERSION = "1.2.3";']],
    ["scripts/readme-demo-manifest.ts", ["export const manifest = true;"]],
    ["scripts/readme-demo-process.ts", ["export const process = true;"]],
    ["scripts/readme-demo-timing.ts", ["export const timing = true;"]],
    ["scripts/render-readme-demo.ts", ["export const render = true;"]],
  ]);

  for (const [path, lines] of textFiles) {
    const absolutePath = join(root, path);
    mkdirSync(dirname(absolutePath), {recursive: true});
    writeFileSync(absolutePath, `${lines.join(newline)}${newline}`);
  }
}

function createManifestFixture(t: test.TestContext): string {
  const fixtureRoot = mkdtempSync(join(tmpdir(), "readme-demo-manifest-"));
  t.after(() => rmSync(fixtureRoot, {recursive: true, force: true}));
  execFileSync("git", ["init", "--quiet"], {cwd: fixtureRoot});
  mkdirSync(join(fixtureRoot, "docs", "assets"), {recursive: true});
  writeFileSync(join(fixtureRoot, "docs", "assets", "demo.mp4"), "video");
  writeFileSync(join(fixtureRoot, "docs", "assets", "readme-demo-poster.png"), "poster");
  writeCrossPlatformFixture(fixtureRoot, "\n");
  return fixtureRoot;
}

/** Numeric comparison of two x.y.z versions: negative, zero or positive. */
function compareVersions(left: string, right: string): number {
  const parts = (version: string): number[] => version.split(".").map(Number);
  const [leftParts, rightParts] = [parts(left), parts(right)];
  return leftParts.map((part, index) => part - (rightParts[index] ?? 0)).find((delta) => delta !== 0) ?? 0;
}

test("committed README demo artifacts match every render input", () => {
  const {hyperframesVersion: renderedWith, ...expected} = readReadmeDemoManifest(repoRoot);
  const {hyperframesVersion: pinned, ...actual} = buildReadmeDemoManifest(repoRoot);

  assert.deepEqual(
    actual,
    expected,
    "README demo sources or artifacts changed without a successful render; "
      + "run `node scripts/render-readme-demo.ts` and commit all generated files",
  );
  // The pin is hashed with its version normalized: a Renovate update must not make the media
  // stale (ADR 0149). The media may be older than the pin, never newer.
  assert.match(renderedWith, /^\d+\.\d+\.\d+$/u, "the manifest must record the rendering HyperFrames version");
  assert.ok(
    compareVersions(pinned, renderedWith) >= 0,
    `the pinned HyperFrames ${pinned} is older than the ${renderedWith} that rendered the media`,
  );
});

test("the manifest records the version the pin names", () => {
  assert.equal(readHyperframesVersion(repoRoot), HYPERFRAMES_VERSION);
  assert.equal(buildReadmeDemoManifest(repoRoot).hyperframesVersion, HYPERFRAMES_VERSION);
});

test("the version comparison is numeric", () => {
  assert.ok(compareVersions("0.8.191", "0.8.191") === 0);
  assert.ok(compareVersions("0.8.200", "0.8.191") > 0);
  assert.ok(compareVersions("0.9.0", "0.8.191") > 0);
  assert.ok(compareVersions("0.8.19", "0.8.191") < 0);
});

test("a version-only HyperFrames update keeps the committed README demo fresh", (t) => {
  // given
  const fixtureRoot = createManifestFixture(t);
  const renderedManifest = buildReadmeDemoManifest(fixtureRoot);
  const pinPath = join(fixtureRoot, HYPERFRAMES_VERSION_PATH);
  const pinSource = readFileSync(pinPath, "utf8");
  const [pin] = extractRegexDependencies(HYPERFRAMES_VERSION_PATH, pinSource);
  assert.ok(pin, "Renovate must find the fixture's HyperFrames pin");

  // when
  writeFileSync(pinPath, applyRegexUpdate(pinSource, pin, "1.2.4"));
  const updatedManifest = buildReadmeDemoManifest(fixtureRoot);

  // then
  assert.equal(
    updatedManifest.sourceSha256,
    renderedManifest.sourceSha256,
    "a newer HyperFrames version alone must not invalidate the committed media",
  );
  assert.equal(renderedManifest.hyperframesVersion, "1.2.3");
  assert.equal(updatedManifest.hyperframesVersion, "1.2.4");
  assert.equal(updatedManifest.videoSha256, renderedManifest.videoSha256);
  assert.equal(updatedManifest.posterSha256, renderedManifest.posterSha256);
});

test("a change to the pin outside its version line invalidates the committed README demo", (t) => {
  // given
  const fixtureRoot = createManifestFixture(t);
  const renderedManifest = buildReadmeDemoManifest(fixtureRoot);
  const pinPath = join(fixtureRoot, HYPERFRAMES_VERSION_PATH);

  // when
  writeFileSync(pinPath, `${readFileSync(pinPath, "utf8")}export const EXTRA_CLI_ARGS = ["--quality", "low"];\n`);

  // then
  assert.notEqual(
    buildReadmeDemoManifest(fixtureRoot).sourceSha256,
    renderedManifest.sourceSha256,
    "only the version literal is normalized; every other byte of the pin stays a render input",
  );
});

test("a change to a render script still invalidates the committed README demo", (t) => {
  // given
  const fixtureRoot = createManifestFixture(t);
  const renderedManifest = buildReadmeDemoManifest(fixtureRoot);

  // when
  writeFileSync(join(fixtureRoot, "scripts", "readme-demo-timing.ts"), "export const timing = false;\n");

  // then
  assert.notEqual(buildReadmeDemoManifest(fixtureRoot).sourceSha256, renderedManifest.sourceSha256);
});

test("committed README demo video stays below GitHub's 10 MB attachment limit", () => {
  assertBelowGitHubVideoAttachmentLimit(
    join(repoRoot, "docs", "assets", "demo.mp4"),
  );
});

test("GitHub video attachment limit is strict", (t) => {
  const fixtureRoot = mkdtempSync(join(tmpdir(), "readme-demo-size-"));
  t.after(() => rmSync(fixtureRoot, {recursive: true, force: true}));
  const videoPath = join(fixtureRoot, "demo.mp4");
  writeFileSync(videoPath, "");

  truncateSync(videoPath, GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES - 1);
  assert.doesNotThrow(() => assertBelowGitHubVideoAttachmentLimit(videoPath));

  truncateSync(videoPath, GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES);
  assert.throws(
    () => assertBelowGitHubVideoAttachmentLimit(videoPath),
    /stay below GitHub's 10 MB attachment limit/,
  );
});

test("motion assertions do not duplicate the composition duration", () => {
  const motion = JSON.parse(
    readFileSync(join(repoRoot, "docs", "demo", "index.motion.json"), "utf8"),
  ) as {duration?: unknown};

  assert.equal(
    motion.duration,
    undefined,
    "the calculated index.html timeline is the sole README demo duration source",
  );
});

test("source digest is identical for LF and CRLF checkout bytes", (t) => {
  // given
  const fixtureRoot = createManifestFixture(t);
  const lfManifest = buildReadmeDemoManifest(fixtureRoot);

  // when
  writeCrossPlatformFixture(fixtureRoot, "\r\n");
  const crlfManifest = buildReadmeDemoManifest(fixtureRoot);

  // then
  assert.deepEqual(crlfManifest, lfManifest);
});

test("manifest writer rejects render inputs changed after preflight", (t) => {
  // given
  const fixtureRoot = createManifestFixture(t);
  const sourceBeforeRender = buildReadmeDemoManifest(fixtureRoot);

  // when
  writeFileSync(join(fixtureRoot, "docs", "demo", "index.html"), "changed after render started\n");

  // then
  assert.throws(
    () => {
      writeReadmeDemoManifest(fixtureRoot, sourceBeforeRender);
    },
    /README demo inputs changed during rendering/,
  );
});

test("manifest writer rejects a HyperFrames version changed after preflight", (t) => {
  // given
  const fixtureRoot = createManifestFixture(t);
  const sourceBeforeRender = buildReadmeDemoManifest(fixtureRoot);
  const pinPath = join(fixtureRoot, HYPERFRAMES_VERSION_PATH);
  const pinSource = readFileSync(pinPath, "utf8");
  const [pin] = extractRegexDependencies(HYPERFRAMES_VERSION_PATH, pinSource);
  assert.ok(pin);

  // when
  writeFileSync(pinPath, applyRegexUpdate(pinSource, pin, "1.2.4"));

  // then
  assert.throws(
    () => {
      writeReadmeDemoManifest(fixtureRoot, sourceBeforeRender);
    },
    /HyperFrames version changed during rendering/,
  );
});

test("render snapshot keeps the exact bytes used for its source state", (t) => {
  // given
  const fixtureRoot = createManifestFixture(t);
  const snapshotRoot = mkdtempSync(join(tmpdir(), "readme-demo-snapshot-"));
  t.after(() => rmSync(snapshotRoot, {recursive: true, force: true}));
  const snapshotDemoDir = join(snapshotRoot, "demo");
  const expectedSourceSha256 = buildReadmeDemoManifest(fixtureRoot).sourceSha256;
  const sourceState = createReadmeDemoSourceSnapshot(fixtureRoot, snapshotDemoDir);
  const snapshotHtml = join(snapshotDemoDir, "index.html");

  // when
  writeFileSync(join(fixtureRoot, "docs", "demo", "index.html"), "transient edit\n");

  // then
  assert.equal(readFileSync(snapshotHtml, "utf8"), "<main>\n  Demo\n</main>\n");
  assert.equal(sourceState.sourceSha256, expectedSourceSha256);
});
