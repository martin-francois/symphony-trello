import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {resolve} from "node:path";
import test from "node:test";
import {createFakeCommandEnvironment} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/ensure-clusterfuzzlite-running");

function run(continuousRuns: number, unrelatedRuns = 0, apiFailure = false) {
  const fakeCommands = createFakeCommandEnvironment("clusterfuzzlite-watchdog-bin-", {
    gh: `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_COMMAND_LOG"
if [[ "$1" == api ]]; then
  if [[ "$API_FAILURE" == true ]]; then
    exit 1
  fi
  if [[ "$*" == *"Continuous batch cycle "* ]]; then
    printf '%s\\n' "$CONTINUOUS_RUNS"
  else
    printf '%s\\n' "$((CONTINUOUS_RUNS + UNRELATED_RUNS))"
  fi
fi
`,
  });

  const result = spawnSync("bash", [script], {
    encoding: "utf8",
    env: fakeCommands.environment({
      API_FAILURE: String(apiFailure),
      CONTINUOUS_RUNS: String(continuousRuns),
      GH_TOKEN: "test-token",
      GITHUB_REPOSITORY: "owner/project",
      UNRELATED_RUNS: String(unrelatedRuns),
    }),
  });

  return {log: fakeCommands.readLog(), result};
}

test("leaves a queued or running continuous batch chain untouched", () => {
  // given
  const activeContinuousRuns = 1;

  // when
  const {log, result} = run(activeContinuousRuns);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /actions\/workflows\/continuous-fuzzing\.yml\/runs/);
  assert.match(log, /Continuous batch cycle/);
  assert.doesNotMatch(log, /workflow run/);
  assert.match(result.stdout, /already queued or running/);
});

test("dispatches cycle zero when the continuous workflow is idle", () => {
  // given
  const activeContinuousRuns = 0;

  // when
  const {log, result} = run(activeContinuousRuns);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /workflow run continuous-fuzzing\.yml/);
  assert.match(log, /-f fuzz_seconds=4950 -f continue_fuzzing=true -f cycle_index=0/);
});

test("restarts the continuous chain while an unrelated workflow run is active", () => {
  // given
  const activeContinuousRuns = 0;
  const unrelatedRuns = 1;

  // when
  const {log, result} = run(activeContinuousRuns, unrelatedRuns);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /workflow run continuous-fuzzing\.yml/);
});

test("fails instead of dispatching when workflow state cannot be read", () => {
  // given
  const apiFailure = true;

  // when
  const {log, result} = run(0, 0, apiFailure);

  // then
  assert.equal(result.status, 1);
  assert.doesNotMatch(log, /workflow run/);
});
