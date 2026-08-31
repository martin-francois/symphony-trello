import assert from "node:assert/strict";
import {resolve} from "node:path";
import test from "node:test";
import {runWithRetryingGh} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/queue-clusterfuzzlite-watchdog");

function run(failures = 0) {
  return runWithRetryingGh(script, "clusterfuzzlite-watchdog-queue-bin-", failures);
}

test("queues the next continuous watchdog run", () => {
  // given
  const dispatchFailures = 0;

  // when
  const {attempts, log, result} = run(dispatchFailures);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal(attempts, 1);
  assert.match(
    log,
    /workflow run continuous-fuzzing-watchdog\.yml --repo owner\/project --ref main/,
  );
});

test("retries a transient watchdog dispatch failure", () => {
  // given
  const dispatchFailures = 1;

  // when
  const {attempts, result} = run(dispatchFailures);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal(attempts, 2);
  assert.match(result.stderr, /dispatch attempt 1 failed/);
});

test("fails after three watchdog dispatch failures", () => {
  // given
  const dispatchFailures = 3;

  // when
  const {attempts, result} = run(dispatchFailures);

  // then
  assert.equal(result.status, 1);
  assert.equal(attempts, 3);
  assert.match(result.stderr, /could not queue its successor after 3 attempts/);
});
