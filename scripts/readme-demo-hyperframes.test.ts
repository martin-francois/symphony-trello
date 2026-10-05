import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import test from "node:test";
import * as canonicalPin from "./readme-demo-hyperframes.ts";
import {
  HYPERFRAMES_VERSION_PATH,
  MANIFEST_RELATIVE_PATH,
  POSTER_RELATIVE_PATH,
  VIDEO_RELATIVE_PATH,
} from "./readme-demo-manifest.ts";
import {
  matchesNonMajor,
  RENOVATE_CONFIG,
  type RenovatePackageRule,
} from "./test-support/renovate-config.ts";
import {
  applyRegexUpdate,
  extractRegexDependencies,
  type RegexDependency,
} from "./test-support/renovate-regex-manager.ts";

type HyperframesPin = typeof canonicalPin;

const repoRoot = fileURLToPath(new URL("../", import.meta.url));
const PIN_SOURCE = readFileSync(join(repoRoot, HYPERFRAMES_VERSION_PATH), "utf8");
const PACKAGE_RULES = RENOVATE_CONFIG.packageRules;
const RENDERED_ARTIFACTS = [VIDEO_RELATIVE_PATH, POSTER_RELATIVE_PATH, MANIFEST_RELATIVE_PATH];

function onlyHyperframesDependency(content: string): RegexDependency {
  const dependencies = extractRegexDependencies(HYPERFRAMES_VERSION_PATH, content);
  assert.equal(dependencies.length, 1, "Renovate must find exactly one HyperFrames pin");
  return dependencies[0] as RegexDependency;
}

function newerPatchRelease(version: string): string {
  const [major, minor, patch] = version.split(".").map(Number);
  return `${major}.${minor}.${(patch ?? 0) + 1}`;
}

async function importPinModule(t: test.TestContext, source: string): Promise<HyperframesPin> {
  const directory = mkdtempSync(join(tmpdir(), "readme-demo-hyperframes-"));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  const modulePath = join(directory, "readme-demo-hyperframes.ts");
  writeFileSync(modulePath, source);
  // The pin module imports its sibling render-step runner.
  copyFileSync(
    join(repoRoot, "scripts", "readme-demo-process.ts"),
    join(directory, "readme-demo-process.ts"),
  );
  return (await import(pathToFileURL(modulePath).href)) as HyperframesPin;
}

// Renovate applies a package rule unless one of its match lists excludes the package.
function appliesToHyperframes(rule: RenovatePackageRule): boolean {
  return (rule.matchDatasources ?? ["npm"]).includes("npm")
    && (rule.matchPackageNames ?? ["hyperframes"]).includes("hyperframes");
}

test("Renovate discovers the canonical pin as the exact npm package hyperframes", () => {
  // when
  const dependency = onlyHyperframesDependency(PIN_SOURCE);

  // then
  assert.equal(dependency.depName, "hyperframes");
  assert.equal(dependency.datasource, "npm");
  assert.equal(dependency.currentValue, canonicalPin.HYPERFRAMES_VERSION);
  assert.match(dependency.currentValue, /^\d+\.\d+\.\d+$/u, "the pin must be an exact version");
});

test("a newer HyperFrames release moves every derived reference with the pin", async (t) => {
  // given
  const dependency = onlyHyperframesDependency(PIN_SOURCE);
  const newerVersion = newerPatchRelease(dependency.currentValue);

  // when
  const updatedSource = applyRegexUpdate(PIN_SOURCE, dependency, newerVersion);
  const updatedPin = await importPinModule(t, updatedSource);

  // then
  assert.equal(onlyHyperframesDependency(updatedSource).currentValue, newerVersion);
  assert.equal(updatedPin.HYPERFRAMES_VERSION, newerVersion);
  assert.equal(updatedPin.HYPERFRAMES_PACKAGE, `hyperframes@${newerVersion}`);
  assert.deepEqual(updatedPin.hyperframesPnpmArgs(["check"]), [
    "dlx",
    `hyperframes@${newerVersion}`,
    "check",
  ]);
});

test("no file other than the canonical pin names a HyperFrames version", () => {
  // given
  const escapedVersion = canonicalPin.HYPERFRAMES_VERSION.replaceAll(".", "\\.");
  const versionMentions = [
    "hyperframes@[0-9]",
    `hyperframes[^0-9]{0,20}${escapedVersion}([^0-9]|$)`,
  ];

  // when
  const result = spawnSync(
    "git",
    [
      "grep", "--untracked", "-I", "-n", "-i", "-E",
      ...versionMentions.flatMap((pattern) => ["-e", pattern]),
      "--", ".", `:!${HYPERFRAMES_VERSION_PATH}`,
    ],
    {cwd: repoRoot, encoding: "utf8"},
  );

  // then
  assert.equal(
    result.status,
    1,
    `run HyperFrames through ${HYPERFRAMES_VERSION_PATH} instead of repeating its version:\n`
      + `${result.stdout}${result.stderr}`,
  );
});

test("HyperFrames updates require a re-render, visual review, and manual merge", () => {
  // given
  const hyperframesRules = PACKAGE_RULES.filter(
    ({matchPackageNames}) => matchPackageNames?.includes("hyperframes"),
  );
  const majorRuleIndex = PACKAGE_RULES.findIndex(
    ({matchUpdateTypes}) => matchUpdateTypes?.includes("major"),
  );

  // when
  const nonMajorAutomergeDecision = PACKAGE_RULES.filter(
    (rule) => appliesToHyperframes(rule) && matchesNonMajor(rule) && rule.automerge !== undefined,
  ).at(-1);

  // then
  assert.equal(hyperframesRules.length, 1);
  const [rule] = hyperframesRules;
  assert.equal(nonMajorAutomergeDecision, rule, "the HyperFrames rule must decide automerge last");
  assert.equal(rule?.automerge, false);
  assert.equal(rule?.groupName, null);
  assert.deepEqual(rule?.addLabels, ["hyperframes"]);
  assert.ok(
    PACKAGE_RULES.indexOf(rule as RenovatePackageRule) < majorRuleIndex,
    "the major-update rule must stay last so it still labels HyperFrames majors",
  );
  const notes = rule?.prBodyNotes?.join("\n") ?? "";
  for (const instruction of ["node scripts/render-readme-demo.ts", ...RENDERED_ARTIFACTS]) {
    assert.ok(notes.includes(instruction), `pull request notes must mention ${instruction}`);
  }
});
