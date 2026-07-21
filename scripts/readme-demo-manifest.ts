import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {dirname, resolve} from "node:path";

const MANIFEST_RELATIVE_PATH = "docs/demo/render-manifest.json";
const VIDEO_RELATIVE_PATH = "docs/assets/readme-demo.mp4";
const POSTER_RELATIVE_PATH = "docs/assets/readme-demo-poster.png";
const RENDER_SCRIPT_PATHS = [
  ".gitattributes",
  "scripts/readme-demo-manifest.ts",
  "scripts/render-readme-demo.ts",
] as const;
const NON_RENDER_INPUTS = new Set([
  "docs/demo/README.md",
  MANIFEST_RELATIVE_PATH,
]);

export interface ReadmeDemoSourceState {
  schemaVersion: 1;
  sourceSha256: string;
  sourceFiles: string[];
}

export interface ReadmeDemoManifest extends ReadmeDemoSourceState {
  videoSha256: string;
  posterSha256: string;
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
    hash.update(canonicalBlobId(repoRoot, path, content));
    hash.update("\0");
  }
  return {
    schemaVersion: 1,
    sourceSha256: hash.digest("hex"),
    sourceFiles,
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
  const manifest = `${JSON.stringify(currentManifest, null, 2)}\n`;
  writeFileSync(resolve(repoRoot, MANIFEST_RELATIVE_PATH), manifest, "utf8");
}
