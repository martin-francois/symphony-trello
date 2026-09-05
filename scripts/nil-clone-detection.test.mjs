import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";

import {
  assertSuccessfulDetector,
  containerRuntimeIdentity,
  createCloneReport,
  nilCommitFromDockerfile,
  parseCloneCsv,
  verifyBaseline,
  verifyParsedFiles,
} from "./nil-clone-detection.mjs";

const files = ["src/main/java/example/Alpha.java", "src/test/java/example/AlphaTest.java"];
const csv = "/input/src/test/java/example/AlphaTest.java,20,40,/input/src/main/java/example/Alpha.java,1,30\n";

test("normalizes pair direction and verifies a reviewed baseline", () => {
  const clones = parseCloneCsv(csv, files);
  assert.deepEqual(clones, [{
    left: { file: files[0], start: 1, end: 30 },
    right: { file: files[1], start: 20, end: 40 },
  }]);
  verifyBaseline(clones, {
    schemaVersion: 1,
    configuration: { minimumLines: 25, minimumTokens: 200, verificationThresholdPercent: 90 },
    clones: clones.map((clone) => ({
      ...clone,
      category: "production-test",
      decision: "accepted",
      rationale: "The boundaries are intentionally independent.",
    })),
  });
});

test("rejects unclassified baseline entries", () => {
  const clones = parseCloneCsv(csv, files);
  assert.throws(
    () => verifyBaseline(clones, {
      schemaVersion: 1,
      configuration: { minimumLines: 25, minimumTokens: 200, verificationThresholdPercent: 90 },
      clones: clones.map((clone) => ({ ...clone, category: "production-test", decision: "pending", rationale: "" })),
    }),
    /Unclassified NIL baseline entry/u,
  );
});

test("rejects malformed detector output", () => {
  assert.throws(() => parseCloneCsv("/input/Alpha.java,1,2", files), /expected 6 fields/u);
});

test("rejects parser omissions", () => {
  assert.throws(
    () => verifyParsedFiles(`NIL_PARSED_FILE\t/input/${files[0]}\n`, files),
    /did not parse every tracked Java input exactly once/u,
  );
});

test("rejects detector failures", () => {
  assert.throws(
    () => assertSuccessfulDetector({ status: 7, stderr: "detector failed", stdout: "" }),
    /clone detection failed \(7\): detector failed/u,
  );
});

test("rejects detector timeouts", () => {
  const result = spawnSync(process.execPath, ["-e", "setTimeout(() => {}, 1000)"], {
    encoding: "utf8",
    timeout: 10,
  });
  assert.throws(() => assertSuccessfulDetector(result), /timed out/u);
});

test("derives the detector revision from its single Dockerfile pin", () => {
  assert.equal(
    nilCommitFromDockerfile("FROM example\nARG NIL_COMMIT=967bb983890bf2c4145d2155dfe0e88c02480ad6\n"),
    "967bb983890bf2c4145d2155dfe0e88c02480ad6",
  );
  assert.throws(() => nilCommitFromDockerfile("ARG NIL_COMMIT=main\n"), /does not pin a valid NIL_COMMIT/u);
});

test("uses caller identity only when the host exposes POSIX uid APIs", () => {
  assert.deepEqual(containerRuntimeIdentity("docker", () => 1000, () => 1001), ["--user", "1000:1001"]);
  assert.deepEqual(containerRuntimeIdentity("podman", () => 1000, () => 1001), [
    "--userns=keep-id", "--user", "1000:1001",
  ]);
  assert.deepEqual(containerRuntimeIdentity("docker", null, null), []);
});

test("creates a categorized candidate report before baseline comparison", () => {
  const clones = parseCloneCsv(csv, files);
  const report = createCloneReport(clones, {
    schemaVersion: 1,
    configuration: { minimumLines: 25, minimumTokens: 200, verificationThresholdPercent: 90 },
    clones: [],
  }, files.length, "967bb983890bf2c4145d2155dfe0e88c02480ad6");

  assert.equal(report.clones[0].category, "production-test");
  assert.equal(report.clones[0].decision, "unreviewed");
  assert.equal(report.clones[0].rationale, null);
  assert.deepEqual(report.baselineOnlyClones, []);
});
