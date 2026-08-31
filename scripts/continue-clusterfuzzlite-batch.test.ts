import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {readFileSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import test from "node:test";
import {createFakeCommandEnvironment} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/continue-clusterfuzzlite-batch");

function run(cycleIndex: string, failures = 0) {
  const fakeCommands = createFakeCommandEnvironment("clusterfuzzlite-continuation-bin-", {
    gh: `#!/bin/bash
set -euo pipefail
attempt="$(( $(cat "$ATTEMPTS_FILE") + 1 ))"
printf '%s' "$attempt" >"$ATTEMPTS_FILE"
printf '%s\\n' "$*" >>"$FAKE_COMMAND_LOG"
if ((attempt <= FAILURES)); then
  exit 1
fi
`,
    sleep: "#!/bin/bash\nexit 0\n",
  });
  const attempts = join(fakeCommands.directory, "attempts");
  writeFileSync(attempts, "0");

  const result = spawnSync("bash", [script], {
    encoding: "utf8",
    env: fakeCommands.environment({
      ATTEMPTS_FILE: attempts,
      CFL_CYCLE_INDEX: cycleIndex,
      FAILURES: String(failures),
      GH_TOKEN: "test-token",
      GITHUB_REPOSITORY: "owner/project",
    }),
  });

  return {
    attempts: Number.parseInt(readFileSync(attempts, "utf8"), 10),
    log: fakeCommands.readLog(),
    result,
  };
}

test("dispatches the next normal continuous batch", () => {
  // given
  const cycleIndex = "0";

  // when
  const {attempts, log, result} = run(cycleIndex);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal(attempts, 1);
  assert.match(
    log,
    /workflow run continuous-fuzzing\.yml --repo owner\/project --ref main -f operation=batch -f fuzz_seconds=4950 -f continue_fuzzing=true -f cycle_index=1/,
  );
});

test("dispatches the shorter maintenance batch after three normal cycles", () => {
  // given
  const cycleIndex = "2";

  // when
  const {log, result} = run(cycleIndex);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /-f fuzz_seconds=4500 -f continue_fuzzing=true -f cycle_index=3/);
});

test("wraps the maintenance cycle back to the first normal cycle", () => {
  // given
  const cycleIndex = "3";

  // when
  const {log, result} = run(cycleIndex);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /-f fuzz_seconds=4950 -f continue_fuzzing=true -f cycle_index=0/);
});

test("retries a transient dispatch failure", () => {
  // given
  const transientFailures = 1;

  // when
  const {attempts, result} = run("1", transientFailures);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal(attempts, 2);
  assert.match(result.stderr, /dispatch attempt 1 failed/);
});

test("fails after three unsuccessful dispatch attempts", () => {
  // given
  const failedAttempts = 3;

  // when
  const {attempts, result} = run("1", failedAttempts);

  // then
  assert.equal(result.status, 1);
  assert.equal(attempts, 3);
  assert.match(result.stderr, /could not dispatch the successor after 3 attempts/);
});

test("rejects an invalid cycle", () => {
  // given
  const invalidCycleIndex = "4";

  // when
  const {result} = run(invalidCycleIndex);

  // then
  assert.equal(result.status, 2);
});
