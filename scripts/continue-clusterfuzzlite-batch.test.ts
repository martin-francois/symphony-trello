import assert from "node:assert/strict";
import {resolve} from "node:path";
import test from "node:test";
import {runWithRetryingGh} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/continue-clusterfuzzlite-batch");

function run(cycleIndex: string, failures = 0) {
  return runWithRetryingGh(script, "clusterfuzzlite-continuation-bin-", failures, {
    CFL_CYCLE_INDEX: cycleIndex,
  });
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
