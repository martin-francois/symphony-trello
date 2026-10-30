import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { parse } from "yaml";
import { applyRegexUpdate, extractRegexDependencies } from "./test-support/renovate-regex-manager.ts";

const POM = readFileSync(new URL("../pom.xml", import.meta.url), "utf8");
const CONFIDENCE_MAP = readFileSync(
  new URL("../docs/testing/dependency-upgrade-confidence.md", import.meta.url),
  "utf8",
);
const CI_WORKFLOW = readFileSync(
  new URL("../.github/workflows/ci.yml", import.meta.url),
  "utf8",
);

const PROJECT_ARTIFACTS = new Set([
  "${quarkus.platform.artifact-id}",
  "symphony-trello",
]);

test("Renovate updates the actual Palantir formatter pin without changing other POM content", () => {
  // given
  const formatterPins = extractRegexDependencies("pom.xml", POM).filter(
    ({ depName }) => depName === "com.palantir.javaformat:palantir-java-format",
  );
  assert.equal(formatterPins.length, 1, "the configured formatter must have exactly one Renovate owner");
  const [formatter] = formatterPins;
  assert.ok(formatter, "the configured formatter version must be extracted");
  assert.equal(formatter.datasource, "maven");

  // when
  const updated = applyRegexUpdate(POM, formatter, "99.0.0");

  // then
  assert.match(updated, /<palantirJavaFormat>\s*<version>99\.0\.0<\/version>/u);
  assert.equal(updated.replace("99.0.0", formatter.currentValue), POM);
});

// A row documents its artifacts only when both evidence cells are filled; a name that appears in
// prose, or beside an empty cell, proves nothing about the required check.
function artifactsWithEvidence(confidenceMap: string): Set<string> {
  const documented = new Set<string>();

  for (const row of confidenceMap.split("\n")) {
    const cells = row
      .split("|")
      .slice(1, -1)
      .map((cell) => cell.trim());
    const [artifacts, failureSurface, requiredDetection] = cells;
    if (
      cells.length !== 3 ||
      artifacts === undefined ||
      failureSurface === undefined ||
      requiredDetection === undefined ||
      failureSurface === "" ||
      requiredDetection === ""
    ) {
      continue;
    }

    for (const match of artifacts.matchAll(/`([^`]+)`/gu)) {
      if (match[1] !== undefined) {
        documented.add(match[1]);
      }
    }
  }

  return documented;
}

test("every Maven dependency and plugin has upgrade-confidence evidence", () => {
  // given
  const declaredArtifacts = [
    ...new Set(
      [...POM.matchAll(/<artifactId>([^<]+)<\/artifactId>/gu)].map(
        (match) => match[1],
      ),
    ),
  ].filter(
    (artifact): artifact is string =>
      artifact !== undefined && !PROJECT_ARTIFACTS.has(artifact),
  );
  const documented = artifactsWithEvidence(CONFIDENCE_MAP);

  // when
  const missingEvidence = declaredArtifacts.filter(
    (artifact) => !documented.has(artifact),
  );

  // then
  assert.deepEqual(
    missingEvidence,
    [],
    "every Maven artifact must name its required update checks",
  );
});

test("an artifact without both evidence cells has no upgrade-confidence evidence", () => {
  // given
  const confidenceMap = [
    "| Dependencies and plugins | Primary failure surface | Required detection |",
    "| ------------------------ | ----------------------- | ------------------ |",
    "| `documented-artifact`    | Configuration parsing   | Parser tests       |",
    "| `missing-detection`      | Configuration parsing   |                    |",
    "| `missing-surface`        |                         | Parser tests       |",
    "",
    "The map also mentions `prose-only-artifact` outside the table.",
  ].join("\n");

  // when
  const documented = artifactsWithEvidence(confidenceMap);

  // then
  assert.deepEqual([...documented], ["documented-artifact"]);
});

test("Renovate validation is reproducible and permits only required builds", () => {
  // given
  const expectedCommand =
    "pnpm dlx --allow-build=core-js-pure --allow-build=dtrace-provider " +
    "--allow-build=protobufjs --allow-build=re2 --package renovate@44.178.5 " +
    "renovate-config-validator renovate.json --strict";
  const ciWorkflow = parse(CI_WORKFLOW) as {
    jobs?: {
      renovate?: {
        steps?: Array<{ name?: unknown; run?: unknown }>;
      };
    };
  };

  // when
  const validationCommand = ciWorkflow.jobs?.renovate?.steps?.find(
    ({ name }) => name === "Validate Renovate config",
  )?.run;

  // then
  assert.equal(validationCommand, expectedCommand);
});
