import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, statSync, writeFileSync} from "node:fs";
import {dirname, resolve} from "node:path";

export const MANIFEST_RELATIVE_PATH = "docs/demo/render-manifest.json";
export const VIDEO_RELATIVE_PATH = "docs/assets/demo.mp4";
export const POSTER_RELATIVE_PATH = "docs/assets/readme-demo-poster.png";
export const GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES = 10_000_000;
export const HYPERFRAMES_VERSION_PATH = "scripts/readme-demo-hyperframes.ts";
// readme-demo-visual-change.ts is left out: it only reports on a finished render, so a threshold
// change needs no new render. The HyperFrames pin is hashed with its version literal normalized:
// the manifest records the version the media were rendered with, and a newer version alone does
// not make them stale, while any other change to that file does (ADR 0149).
const RENDER_SCRIPT_PATHS = [
  ".gitattributes",
  HYPERFRAMES_VERSION_PATH,
  "scripts/readme-demo-manifest.ts",
  "scripts/readme-demo-process.ts",
  "scripts/readme-demo-timing.ts",
  "scripts/render-readme-demo.ts",
] as const;
// The one line Renovate updates; scripts/readme-demo-hyperframes.ts documents the shape.
const HYPERFRAMES_VERSION_LINE = /^export const HYPERFRAMES_VERSION = "(\d+\.\d+\.\d+)";$/gmu;
// What the version literal is replaced with before the pin is hashed.
const NORMALIZED_HYPERFRAMES_VERSION = "0.0.0";
const NON_RENDER_INPUTS = new Set([
  "docs/demo/README.md",
  MANIFEST_RELATIVE_PATH,
]);

export interface ReadmeDemoSourceState {
  schemaVersion: 1;
  sourceSha256: string;
  sourceFiles: string[];
  /** The HyperFrames version the pin named when the state was read; a render records it. */
  hyperframesVersion: string;
}

export interface ReadmeDemoManifest extends ReadmeDemoSourceState {
  videoSha256: string;
  posterSha256: string;
}

/**
 * The pin's bytes with its version literal replaced by a fixed value, so the digest covers every
 * other byte of the file but not the version Renovate updates. The manifest writer and the
 * freshness test hash the pin through this one function.
 */
export function normalizeHyperframesPin(content: Buffer): Buffer {
  const source = content.toString("utf8");
  const normalized = source.replace(
    HYPERFRAMES_VERSION_LINE,
    (line, version: string) => line.replace(version, NORMALIZED_HYPERFRAMES_VERSION),
  );
  return Buffer.from(normalized, "utf8");
}

/** The HyperFrames version the pin at `repoRoot` names, read the way Renovate reads it. */
export function readHyperframesVersion(repoRoot: string): string {
  const source = readFileSync(resolve(repoRoot, HYPERFRAMES_VERSION_PATH), "utf8");
  const matches = [...source.matchAll(HYPERFRAMES_VERSION_LINE)];
  const version = matches.length === 1 ? matches[0]?.[1] : undefined;
  if (version === undefined) {
    throw new Error(
      `${HYPERFRAMES_VERSION_PATH} must contain exactly one HyperFrames version line, found ${matches.length}`,
    );
  }
  return version;
}

export function assertBelowGitHubVideoAttachmentLimit(videoPath: string): void {
  const videoBytes = statSync(videoPath).size;
  if (videoBytes >= GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES) {
    throw new Error(
      `expected demo.mp4 to stay below GitHub's 10 MB attachment limit, `
      + `found ${(videoBytes / 1_000_000).toFixed(1)} MB`,
    );
  }
}

function sha256File(path: string): string {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function listDemoSourceFiles(repoRoot: string): string[] {
  const result = spawnSync(
    "git",
    ["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "docs/demo"],
    {cwd: repoRoot, encoding: "utf8"},
  );
  if (result.status !== 0) {
    const detail = result.stderr?.trim() || result.error?.message || "no error detail";
    throw new Error(
      `Git and a Git working tree are required to list README demo inputs: ${detail}`,
    );
  }

  const demoFiles = result.stdout
    .split("\0")
    .filter((path) => path !== "")
    .filter((path) => !NON_RENDER_INPUTS.has(path))
    .filter((path) => !path.endsWith("/LICENSE"))
    .filter((path) => existsSync(resolve(repoRoot, path)));

  return [...new Set([...demoFiles, ...RENDER_SCRIPT_PATHS])].sort();
}

function canonicalBlobId(repoRoot: string, path: string, content: Buffer): string {
  const result = spawnSync(
    "git",
    ["-c", "core.autocrlf=true", "hash-object", `--path=${path}`, "--stdin"],
    {cwd: repoRoot, encoding: "utf8", input: content},
  );
  if (result.status !== 0) {
    const detail = result.stderr?.trim() || result.error?.message || "no error detail";
    throw new Error(`could not hash README demo input ${path}: ${detail}`);
  }
  return result.stdout.trim();
}

function buildSourceState(
  repoRoot: string,
  snapshotDemoDir?: string,
): ReadmeDemoSourceState {
  const sourceFiles = listDemoSourceFiles(repoRoot);
  const hash = createHash("sha256");
  hash.update("symphony-trello-readme-demo-inputs-v1\0");
  for (const path of sourceFiles) {
    const content = readFileSync(resolve(repoRoot, path));
    if (snapshotDemoDir !== undefined && path.startsWith("docs/demo/")) {
      const snapshotPath = resolve(snapshotDemoDir, path.slice("docs/demo/".length));
      mkdirSync(dirname(snapshotPath), {recursive: true});
      writeFileSync(snapshotPath, content);
    }
    hash.update(path);
    hash.update("\0");
    hash.update(canonicalBlobId(
      repoRoot,
      path,
      path === HYPERFRAMES_VERSION_PATH ? normalizeHyperframesPin(content) : content,
    ));
    hash.update("\0");
  }
  return {
    schemaVersion: 1,
    sourceSha256: hash.digest("hex"),
    sourceFiles,
    hyperframesVersion: readHyperframesVersion(repoRoot),
  };
}

export function buildReadmeDemoSourceState(repoRoot: string): ReadmeDemoSourceState {
  return buildSourceState(repoRoot);
}

export function createReadmeDemoSourceSnapshot(
  repoRoot: string,
  snapshotDemoDir: string,
): ReadmeDemoSourceState {
  return buildSourceState(repoRoot, snapshotDemoDir);
}

export function buildReadmeDemoManifest(repoRoot: string): ReadmeDemoManifest {
  return {
    ...buildReadmeDemoSourceState(repoRoot),
    videoSha256: sha256File(resolve(repoRoot, VIDEO_RELATIVE_PATH)),
    posterSha256: sha256File(resolve(repoRoot, POSTER_RELATIVE_PATH)),
  };
}

export function readReadmeDemoManifest(repoRoot: string): ReadmeDemoManifest {
  const manifestPath = resolve(repoRoot, MANIFEST_RELATIVE_PATH);
  if (!existsSync(manifestPath)) {
    throw new Error(
      "README demo render manifest is missing; run `node scripts/render-readme-demo.ts`",
    );
  }
  return JSON.parse(
    readFileSync(manifestPath, "utf8"),
  ) as ReadmeDemoManifest;
}

export function writeReadmeDemoManifest(
  repoRoot: string,
  sourceBeforeRender: ReadmeDemoSourceState,
): void {
  const currentManifest = buildReadmeDemoManifest(repoRoot);
  if (
    currentManifest.sourceSha256 !== sourceBeforeRender.sourceSha256
    || JSON.stringify(currentManifest.sourceFiles) !== JSON.stringify(sourceBeforeRender.sourceFiles)
  ) {
    throw new Error(
      "README demo inputs changed during rendering; discard the artifacts and render again",
    );
  }
  // The version is not in the digest, so it gets its own guard: the manifest must name the
  // version that rendered, not one pinned while the render ran.
  if (currentManifest.hyperframesVersion !== sourceBeforeRender.hyperframesVersion) {
    throw new Error(
      "HyperFrames version changed during rendering; discard the artifacts and render again",
    );
  }
  const manifest = `${JSON.stringify(currentManifest, null, 2)}\n`;
  writeFileSync(resolve(repoRoot, MANIFEST_RELATIVE_PATH), manifest, "utf8");
}
