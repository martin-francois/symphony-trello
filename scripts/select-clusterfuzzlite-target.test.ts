import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, mkdtempSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

const script = resolve("scripts/select-clusterfuzzlite-target");
const fullShareSeconds = "4950";
const fuzzTargets = [
  "RepositorySourceFuzzer",
  "TrelloCardPayloadFuzzer",
  "TrelloCardReferenceParserFuzzer",
  "TrelloChecklistClassifierFuzzer",
  "TrelloHandoffToolFuzzer",
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

  const result = spawnSync("bash", [script, selected, fullShareSeconds], {
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

  const result = spawnSync("bash", [script, "UnknownFuzzer", fullShareSeconds], {
    encoding: "utf8",
    env: {...process.env, CFL_BUILD_OUT: buildOut},
  });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /Unknown ClusterFuzzLite target/);
  assert.deepEqual(readdirSync(buildOut).sort(), before);
});

test("reclaims container-owned build output before selecting a target", () => {
  const buildOut = buildFixture();
  const selected = "WorkflowLoaderFuzzer";
  const uid = process.getuid?.();
  const gid = process.getgid?.();
  if (uid === undefined || gid === undefined) {
    throw new Error("This test requires POSIX user and group identifiers.");
  }
  const fakeBin = mkdtempSync(join(tmpdir(), "clusterfuzzlite-fake-bin-"));
  const sudoLog = join(fakeBin, "sudo.log");
  const sudo = join(fakeBin, "sudo");
  writeFileSync(
    sudo,
    `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_SUDO_LOG"
last_argument="\${!#}"
chmod u+w "$last_argument"
`,
  );
  chmodSync(sudo, 0o755);
  chmodSync(buildOut, 0o555);

  const result = spawnSync("bash", [script, selected, fullShareSeconds], {
    encoding: "utf8",
    env: {
      ...process.env,
      CFL_BUILD_OUT: buildOut,
      FAKE_SUDO_LOG: sudoLog,
      PATH: `${fakeBin}:${process.env.PATH}`,
    },
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(readFileSync(sudoLog, "utf8"), new RegExp(`chown -R -- ${uid}:${gid} `));
  assert.deepEqual(readdirSync(buildOut).sort(), [
    selected,
    `${selected}.dict`,
    `${selected}.options`,
    `${selected}_seed_corpus.zip`,
    "jazzer_driver",
  ]);
});

function selectedSeconds(target: string, fullShare: string, automatic = false) {
  const buildOut = buildFixture();
  const output = join(buildOut, "github-output");
  const result = spawnSync("bash", [script, target, fullShare], {
    encoding: "utf8",
    env: {...process.env, CFL_BUILD_OUT: buildOut, GITHUB_OUTPUT: output, CFL_AUTOMATIC_BUDGET: String(automatic)},
  });
  assert.equal(result.status, 0, result.stderr);
  const written = readFileSync(output, "utf8");
  assert.match(written, /^fuzz_seconds=\d+\n$/);
  return Number(written.replace("fuzz_seconds=", ""));
}

for (const scenario of [
  {target: "TrelloCardPayloadFuzzer", fullShare: "4950", seconds: "4950"},
  {target: "TrelloCardReferenceParserFuzzer", fullShare: "4950", seconds: "2475"},
  {target: "TrelloChecklistClassifierFuzzer", fullShare: "4500", seconds: "2250"},
  {target: "TrelloChecklistClassifierFuzzer", fullShare: "1", seconds: "1"},
]) {
  test(`gives ${scenario.target} ${scenario.seconds} of ${scenario.fullShare} full-share seconds`, () => {
    assert.equal(selectedSeconds(scenario.target, scenario.fullShare), Number(scenario.seconds));
  });
}

for (const cycle of [
  {baseline: "4950", total: 19800, full: 3960, half: 1980},
  {baseline: "4500", total: 18000, full: 3600, half: 1800},
]) {
  test(`keeps the automatic cycle aggregate at ${cycle.total} seconds`, () => {
    const seconds = fuzzTargets.map((target) => selectedSeconds(target, cycle.baseline, true));
    assert.equal(seconds.reduce((total, value) => total + value, 0), cycle.total);
    assert.equal(selectedSeconds("TrelloHandoffToolFuzzer", cycle.baseline, true), cycle.full);
    assert.equal(selectedSeconds("TrelloCardReferenceParserFuzzer", cycle.baseline, true), cycle.half);
  });
}

test("keeps a manual full share independent of the automatic aggregate", () => {
  const seconds = fuzzTargets.map((target) => selectedSeconds(target, fullShareSeconds));
  assert.equal(seconds.reduce((total, value) => total + value, 0), 24750);
  assert.equal(selectedSeconds("TrelloHandoffToolFuzzer", fullShareSeconds), 4950);
  assert.equal(selectedSeconds("TrelloChecklistClassifierFuzzer", fullShareSeconds), 2475);
});

for (const fullShare of ["", "0", "4950s", "-60"]) {
  test(`rejects the full-share budget ${JSON.stringify(fullShare)} before changing the build`, () => {
    const buildOut = buildFixture();
    const before = readdirSync(buildOut).sort();

    const result = spawnSync("bash", [script, "WorkflowLoaderFuzzer", fullShare], {
      encoding: "utf8",
      env: {...process.env, CFL_BUILD_OUT: buildOut},
    });

    assert.equal(result.status, 2);
    assert.match(result.stderr, /Full-share fuzzing seconds must be a positive whole number/);
    assert.deepEqual(readdirSync(buildOut).sort(), before);
  });
}

test("rejects an invalid automatic-budget mode before changing the build", () => {
  const buildOut = buildFixture();
  const before = readdirSync(buildOut).sort();
  const result = spawnSync("bash", [script, "WorkflowLoaderFuzzer", fullShareSeconds], {
    encoding: "utf8",
    env: {...process.env, CFL_BUILD_OUT: buildOut, CFL_AUTOMATIC_BUDGET: "yes"},
  });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /cannot select automatic fuzzing budget "yes": expected true or false/);
  assert.deepEqual(readdirSync(buildOut).sort(), before);
});
