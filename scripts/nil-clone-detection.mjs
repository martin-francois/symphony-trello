#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

export function nilCommitFromDockerfile(dockerfile) {
  const match = /^ARG NIL_COMMIT=([a-f0-9]{40})$/mu.exec(dockerfile);
  if (!match) throw new Error("config/nil/Dockerfile does not pin a valid NIL_COMMIT");
  return match[1];
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "..");
const NIL_COMMIT = nilCommitFromDockerfile(
  readFileSync(join(repositoryRoot, "config", "nil", "Dockerfile"), "utf8"),
);
const IMAGE = `symphony-trello-nil:${NIL_COMMIT.slice(0, 12)}-jls25`;
const TIMEOUT_MILLIS = 10 * 60 * 1000;
const DETECTOR_CONFIGURATION = Object.freeze({
  minimumLines: 25,
  minimumTokens: 200,
  verificationThresholdPercent: 90,
});

function run(command, args, options = {}) {
  return spawnSync(command, args, {
    cwd: repositoryRoot,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    ...options,
  });
}

function successfulOutput(command, args) {
  const result = run(command, args);
  if (result.status !== 0) {
    throw new Error(`${command} failed (${result.status}): ${result.stderr || result.stdout}`);
  }
  return result.stdout;
}

export function assertSuccessfulDetector(result) {
  if (result.error?.code === "ETIMEDOUT") {
    throw new Error("NIL clone detection timed out");
  }
  if (result.error) {
    throw new Error(`NIL clone detection could not run: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`NIL clone detection failed (${result.status}): ${result.stderr || result.stdout}`);
  }
}

function normalizedPath(value) {
  const prefix = `/input/`;
  if (!value.startsWith(prefix)) {
    throw new Error(`NIL returned a path outside its read-only input: ${value}`);
  }
  return value.slice(prefix.length);
}

function compareLocations(left, right) {
  return left.file.localeCompare(right.file) || left.start - right.start || left.end - right.end;
}

function cloneId(clone) {
  return `${clone.left.file}:${clone.left.start}-${clone.left.end}|${clone.right.file}:${clone.right.start}-${clone.right.end}`;
}

function cloneCategory(clone) {
  const production = (location) => location.file.startsWith("src/main/java/");
  return production(clone.left) === production(clone.right)
    ? production(clone.left) ? "production-production" : "test-test"
    : "production-test";
}

export function parseCloneCsv(csv, trackedFiles) {
  const tracked = new Set(trackedFiles);
  const clones = [];
  const seen = new Set();
  for (const [index, rawLine] of csv.split(/\r?\n/u).entries()) {
    if (rawLine.length === 0) continue;
    const fields = rawLine.split(",");
    if (fields.length !== 6) {
      throw new Error(`Malformed NIL CSV at line ${index + 1}: expected 6 fields`);
    }
    const [leftPath, leftStartText, leftEndText, rightPath, rightStartText, rightEndText] = fields;
    const left = { file: normalizedPath(leftPath), start: Number(leftStartText), end: Number(leftEndText) };
    const right = { file: normalizedPath(rightPath), start: Number(rightStartText), end: Number(rightEndText) };
    for (const location of [left, right]) {
      if (!tracked.has(location.file)) throw new Error(`NIL returned an untracked path: ${location.file}`);
      if (!Number.isSafeInteger(location.start) || !Number.isSafeInteger(location.end) || location.start < 1 || location.end < location.start) {
        throw new Error(`Malformed NIL source range at line ${index + 1}`);
      }
    }
    const clone = compareLocations(left, right) <= 0 ? { left, right } : { left: right, right: left };
    const id = cloneId(clone);
    if (!seen.has(id)) {
      clones.push(clone);
      seen.add(id);
    }
  }
  return clones.sort((a, b) => cloneId(a).localeCompare(cloneId(b)));
}

export function verifyParsedFiles(stderr, trackedFiles) {
  const prefix = "NIL_PARSED_FILE\t";
  const parsed = stderr
    .split(/\r?\n/u)
    .filter((line) => line.startsWith(prefix))
    .map((line) => normalizedPath(line.slice(prefix.length)))
    .sort();
  const expected = [...trackedFiles].sort();
  assert.deepEqual(parsed, expected, "NIL did not parse every tracked Java input exactly once");
}

export function verifyBaseline(clones, baseline) {
  assert.equal(baseline.schemaVersion, 1, "Unsupported NIL baseline schema");
  assert.deepEqual(
    baseline.configuration,
    DETECTOR_CONFIGURATION,
    "NIL baseline configuration does not match the wrapper",
  );
  for (const entry of baseline.clones) {
    assert.equal(entry.decision, "accepted", `Unclassified NIL baseline entry: ${cloneId(entry)}`);
    assert.ok(entry.rationale?.trim(), `Missing NIL baseline rationale: ${cloneId(entry)}`);
    const expectedCategory = cloneCategory(entry);
    assert.equal(entry.category, expectedCategory, `Incorrect NIL baseline category: ${cloneId(entry)}`);
  }
  const expected = baseline.clones.map(({ left, right }) => ({ left, right })).sort((a, b) => cloneId(a).localeCompare(cloneId(b)));
  assert.deepEqual(clones, expected, "NIL clone report differs from the reviewed baseline");
}

export function createCloneReport(clones, baseline, inputFiles, upstreamCommit) {
  const reviewedById = new Map(baseline.clones.map((entry) => [cloneId(entry), entry]));
  const detectedIds = new Set(clones.map(cloneId));
  return {
    detector: { name: "NIL", upstreamCommit, compatibility: "JLS25 fail-closed" },
    inputFiles,
    parseErrors: 0,
    configuration: DETECTOR_CONFIGURATION,
    clones: clones.map((clone) => {
      const reviewed = reviewedById.get(cloneId(clone));
      return {
        ...clone,
        category: cloneCategory(clone),
        decision: reviewed?.decision ?? "unreviewed",
        rationale: reviewed?.rationale ?? null,
      };
    }),
    baselineOnlyClones: baseline.clones.filter((clone) => !detectedIds.has(cloneId(clone))),
  };
}

function trackedJavaFiles() {
  return successfulOutput("git", ["ls-files", "-z", "--", "src/main/java", "src/test/java"])
    .split("\0")
    .filter((path) => path.endsWith(".java"));
}

function copyInputs(files, inputDirectory) {
  for (const file of files) {
    const destination = join(inputDirectory, ...file.split("/"));
    mkdirSync(dirname(destination), { recursive: true });
    copyFileSync(join(repositoryRoot, file), destination);
  }
}

function containerMount(path, destination, mode) {
  return `${path.split(sep).join("/")}:${destination}:${mode}`;
}

export function containerRuntimeIdentity(runtime, getuid = process.getuid, getgid = process.getgid) {
  if (typeof getuid !== "function" || typeof getgid !== "function") return [];
  const identity = `${getuid()}:${getgid()}`;
  return runtime === "podman" ? ["--userns=keep-id", "--user", identity] : ["--user", identity];
}

function main() {
  const runtime = process.env.SYMPHONY_TRELLO_CONTAINER_RUNTIME || "docker";
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "symphony-trello-nil-"));
  const inputDirectory = join(temporaryDirectory, "input");
  const outputDirectory = join(temporaryDirectory, "output");
  const containerName = `symphony-trello-nil-${process.pid}`;
  const targetDirectory = join(repositoryRoot, "target", "nil-clones");

  try {
    const files = trackedJavaFiles();
    if (files.length === 0) throw new Error("No tracked Java inputs were found");
    mkdirSync(inputDirectory, { recursive: true });
    mkdirSync(outputDirectory, { recursive: true });
    copyInputs(files, inputDirectory);

    if (process.env.NIL_CLONE_SKIP_BUILD !== "true") {
      const build = run(runtime, ["build", "--pull=false", "--tag", IMAGE, join(repositoryRoot, "config", "nil")], {
        timeout: TIMEOUT_MILLIS,
      });
      assertSuccessfulDetector(build);
    }

    const runtimeIdentity = containerRuntimeIdentity(runtime);
    const analysis = run(
      runtime,
      [
        "run", "--rm", "--name", containerName, "--network=none", "--read-only", "--cap-drop=ALL",
        "--security-opt=no-new-privileges", "--security-opt=label=disable", "--pids-limit=256",
        ...runtimeIdentity,
        "--ulimit", "nofile=1024:1024", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
        "--volume", containerMount(inputDirectory, "/input", "ro"),
        "--volume", containerMount(outputDirectory, "/output", "rw"),
        "--workdir", "/output", IMAGE,
        "--src", "/input", "--output", "/output/results.csv", "--threads", "2",
        "--min-line", String(DETECTOR_CONFIGURATION.minimumLines),
        "--min-token", String(DETECTOR_CONFIGURATION.minimumTokens),
        "--verification-threshold", String(DETECTOR_CONFIGURATION.verificationThresholdPercent),
      ],
      { timeout: TIMEOUT_MILLIS },
    );
    if (analysis.error?.code === "ETIMEDOUT") {
      run(runtime, ["rm", "--force", containerName]);
    }
    assertSuccessfulDetector(analysis);

    const resultsPath = join(outputDirectory, "results.csv");
    if (!existsSync(resultsPath)) throw new Error("NIL completed without producing results.csv");
    verifyParsedFiles(analysis.stderr, files);
    const clones = parseCloneCsv(readFileSync(resultsPath, "utf8"), files);
    const baseline = JSON.parse(readFileSync(join(repositoryRoot, "config", "nil", "baseline.json"), "utf8"));
    mkdirSync(targetDirectory, { recursive: true });
    const report = createCloneReport(clones, baseline, files.length, NIL_COMMIT);
    writeFileSync(join(targetDirectory, "report.json"), `${JSON.stringify(report, null, 2)}\n`);
    verifyBaseline(clones, baseline);
    process.stdout.write(`NIL analyzed ${files.length} tracked Java files and matched ${clones.length} reviewed clone pairs.\n`);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
}

if (resolve(process.argv[1] || "") === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
