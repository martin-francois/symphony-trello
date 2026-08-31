import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import test from "node:test";
import {createFakeCommandEnvironment} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/monitor-clusterfuzzlite-running");

function run(
  iterations: string,
  intervalSeconds: string,
  watchdogDispatchFailures = 0,
  chainApiFailures = 0,
  commandTimeoutSeconds = "120",
) {
  const fakeCommands = createFakeCommandEnvironment("clusterfuzzlite-watchdog-monitor-bin-", {
    gh: `#!/bin/bash
set -euo pipefail
printf 'gh %s\n' "$*" >>"$FAKE_COMMAND_LOG"
if [[ "$1" == workflow ]]; then
  attempt="$(( $(cat "$WATCHDOG_ATTEMPTS_FILE") + 1 ))"
  printf '%s' "$attempt" >"$WATCHDOG_ATTEMPTS_FILE"
  if ((attempt <= WATCHDOG_DISPATCH_FAILURES)); then
    exit 1
  fi
fi
if [[ "$1" == api ]]; then
  attempt="$(( $(cat "$CHAIN_API_ATTEMPTS_FILE") + 1 ))"
  printf '%s' "$attempt" >"$CHAIN_API_ATTEMPTS_FILE"
  if ((attempt <= CHAIN_API_FAILURES)); then
    exit 1
  fi
  printf '1\n'
fi
`,
    sleep: `#!/bin/bash
set -euo pipefail
printf 'sleep %s\n' "$*" >>"$FAKE_COMMAND_LOG"
`,
  });
  const watchdogAttemptsFile = join(fakeCommands.directory, "watchdog-attempts");
  const chainApiAttemptsFile = join(fakeCommands.directory, "chain-api-attempts");
  writeFileSync(watchdogAttemptsFile, "0");
  writeFileSync(chainApiAttemptsFile, "0");

  const result = spawnSync("bash", [script], {
    encoding: "utf8",
    env: fakeCommands.environment({
      CFL_WATCH_INTERVAL_SECONDS: intervalSeconds,
      CFL_WATCH_ITERATIONS: iterations,
      CFL_WATCH_COMMAND_TIMEOUT_SECONDS: commandTimeoutSeconds,
      CHAIN_API_ATTEMPTS_FILE: chainApiAttemptsFile,
      CHAIN_API_FAILURES: String(chainApiFailures),
      GH_TOKEN: "test-token",
      GITHUB_REPOSITORY: "owner/project",
      WATCHDOG_ATTEMPTS_FILE: watchdogAttemptsFile,
      WATCHDOG_DISPATCH_FAILURES: String(watchdogDispatchFailures),
    }),
  });

  return {log: fakeCommands.readLog(), result};
}

test("checks the chain for a bounded number of iterations", () => {
  // given
  const iterations = "3";
  const intervalSeconds = "15";

  // when
  const {log, result} = run(iterations, intervalSeconds);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal((log.match(/^gh api /gm) ?? []).length, 3);
  assert.equal((log.match(/^gh workflow run continuous-fuzzing-watchdog\.yml/gm) ?? []).length, 1);
  assert.ok(log.indexOf("gh workflow run") < log.indexOf("gh api"));
  assert.equal((log.match(/^sleep 15$/gm) ?? []).length, 2);
  assert.doesNotMatch(log, /workflow run continuous-fuzzing\.yml/);
});

test("retries successor creation on the next monitoring iteration", () => {
  // given
  const firstDispatchAttemptsFail = 3;

  // when
  const {log, result} = run("2", "15", firstDispatchAttemptsFail);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal((log.match(/^gh workflow run continuous-fuzzing-watchdog\.yml/gm) ?? []).length, 4);
  assert.equal((log.match(/^gh api /gm) ?? []).length, 2);
  assert.match(result.stderr, /could not queue its successor after 3 attempts/);
});

test("continues monitoring after a transient chain API failure", () => {
  // given
  const firstChainApiCallFails = 1;

  // when
  const {log, result} = run("2", "15", 0, firstChainApiCallFails);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.equal((log.match(/^gh api /gm) ?? []).length, 2);
  assert.match(result.stderr, /chain check 1 failed/);
});

test("fails when it cannot queue a successor during the monitoring window", () => {
  // given
  const everyDispatchAttemptFails = 6;

  // when
  const {log, result} = run("2", "15", everyDispatchAttemptFails);

  // then
  assert.equal(result.status, 1);
  assert.equal((log.match(/^gh workflow run continuous-fuzzing-watchdog\.yml/gm) ?? []).length, 6);
  assert.equal((log.match(/^gh api /gm) ?? []).length, 2);
  assert.match(result.stderr, /did not queue a successor during its monitoring window/);
});

test("fails when it cannot check the chain during the monitoring window", () => {
  // given
  const everyChainApiCallFails = 2;

  // when
  const {log, result} = run("2", "15", 0, everyChainApiCallFails);

  // then
  assert.equal(result.status, 1);
  assert.equal((log.match(/^gh workflow run continuous-fuzzing-watchdog\.yml/gm) ?? []).length, 1);
  assert.equal((log.match(/^gh api /gm) ?? []).length, 2);
  assert.match(result.stderr, /did not complete a chain check during its monitoring window/);
});

test("rejects an invalid iteration count", () => {
  // given
  const invalidIterations = "0";

  // when
  const {log, result} = run(invalidIterations, "15");

  // then
  assert.equal(result.status, 2);
  assert.equal(log, "");
  assert.match(result.stderr, /CFL_WATCH_ITERATIONS must be a positive integer/);
});

test("rejects an invalid check interval", () => {
  // given
  const invalidIntervalSeconds = "0";

  // when
  const {log, result} = run("3", invalidIntervalSeconds);

  // then
  assert.equal(result.status, 2);
  assert.equal(log, "");
  assert.match(result.stderr, /CFL_WATCH_INTERVAL_SECONDS must be a positive integer/);
});

test("rejects an invalid command timeout", () => {
  // given
  const invalidCommandTimeoutSeconds = "0";

  // when
  const {log, result} = run("3", "15", 0, 0, invalidCommandTimeoutSeconds);

  // then
  assert.equal(result.status, 2);
  assert.equal(log, "");
  assert.match(result.stderr, /CFL_WATCH_COMMAND_TIMEOUT_SECONDS must be a positive integer/);
});
