import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, mkdtempSync, readdirSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

const script = resolve("scripts/select-clusterfuzzlite-target");
const fuzzTargets = [
  "RepositorySourceFuzzer",
  "TrelloCardReferenceParserFuzzer",
  "TrelloChecklistClassifierFuzzer",
  "WorkflowLoaderFuzzer",
] as const;

function buildFixture() {
  const buildOut = mkdtempSync(join(tmpdir(), "clusterfuzzlite-build-out-"));
  for (const target of fuzzTargets) {
    for (const suffix of ["", ".dict", ".options", "_seed_corpus.zip"]) {
      const path = join(buildOut, `${target}${suffix}`);
      writeFileSync(path, suffix === "" ? "#!/bin/bash\n" : "fixture\n");
      if (suffix === "") {
        chmodSync(path, 0o755);
      }
    }
  }
  writeFileSync(join(buildOut, "jazzer_driver"), "helper\n");
  return buildOut;
}

test("keeps the selected fuzzer and removes every other target artifact", () => {
  const buildOut = buildFixture();
  const selected = "RepositorySourceFuzzer";

  const result = spawnSync("bash", [script, selected], {
    encoding: "utf8",
    env: {...process.env, CFL_BUILD_OUT: buildOut},
  });

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(readdirSync(buildOut).sort(), [
    selected,
    `${selected}.dict`,
    `${selected}.options`,
    `${selected}_seed_corpus.zip`,
    "jazzer_driver",
  ]);
});

test("rejects an unknown target before changing the build", () => {
  const buildOut = buildFixture();
  const before = readdirSync(buildOut).sort();

  const result = spawnSync("bash", [script, "UnknownFuzzer"], {
    encoding: "utf8",
    env: {...process.env, CFL_BUILD_OUT: buildOut},
  });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /Unknown ClusterFuzzLite target/);
  assert.deepEqual(readdirSync(buildOut).sort(), before);
});
