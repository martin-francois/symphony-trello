import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const POM = readFileSync(new URL("../pom.xml", import.meta.url), "utf8");
const CONFIDENCE_MAP = readFileSync(
  new URL("../docs/testing/dependency-upgrade-confidence.md", import.meta.url),
  "utf8",
);

const PROJECT_ARTIFACTS = new Set([
  "${quarkus.platform.artifact-id}",
  "symphony-trello",
]);

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
