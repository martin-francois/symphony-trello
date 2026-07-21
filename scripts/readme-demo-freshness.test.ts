import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import test from "node:test";
import {
  buildReadmeDemoManifest,
  createReadmeDemoSourceSnapshot,
  readReadmeDemoManifest,
  writeReadmeDemoManifest,
} from "./readme-demo-manifest.ts";

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
    ["scripts/readme-demo-manifest.ts", ["export const manifest = true;"]],
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
  writeFileSync(join(fixtureRoot, "docs", "assets", "readme-demo.mp4"), "video");
  writeFileSync(join(fixtureRoot, "docs", "assets", "readme-demo-poster.png"), "poster");
  writeCrossPlatformFixture(fixtureRoot, "\n");
  return fixtureRoot;
}

test("committed README demo artifacts match every render input", () => {
  const expected = readReadmeDemoManifest(repoRoot);
  const actual = buildReadmeDemoManifest(repoRoot);

  assert.deepEqual(
    actual,
    expected,
    "README demo sources or artifacts changed without a successful render; "
      + "run `node scripts/render-readme-demo.ts` and commit all generated files",
  );
});

test("motion assertions do not duplicate the composition duration", () => {
  const motion = JSON.parse(
    readFileSync(join(repoRoot, "docs", "demo", "index.motion.json"), "utf8"),
  ) as {duration?: unknown};

  assert.equal(
    motion.duration,
    undefined,
    "index.html data-duration is the sole README demo duration source",
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
