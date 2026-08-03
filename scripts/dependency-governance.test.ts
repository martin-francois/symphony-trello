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

  // when
  const missingEvidence = declaredArtifacts.filter(
    (artifact) => !CONFIDENCE_MAP.includes(`\`${artifact}\``),
  );

  // then
  assert.deepEqual(
    missingEvidence,
    [],
    "every Maven artifact must name its required update checks",
  );
});
