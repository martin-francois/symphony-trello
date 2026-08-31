import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {resolve} from "node:path";
import test from "node:test";
import {createFakeCommandEnvironment} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/monitor-clusterfuzzlite-running");

function run(iterations: string, intervalSeconds: string) {
  const fakeCommands = createFakeCommandEnvironment("clusterfuzzlite-watchdog-monitor-bin-", {
    gh: `#!/bin/bash
set -euo pipefail
printf 'gh %s\n' "$*" >>"$FAKE_COMMAND_LOG"
if [[ "$1" == api ]]; then
  printf '1\n'
fi
`,
    sleep: `#!/bin/bash
set -euo pipefail
printf 'sleep %s\n' "$*" >>"$FAKE_COMMAND_LOG"
`,
  });

  const result = spawnSync("bash", [script], {
    encoding: "utf8",
    env: fakeCommands.environment({
      CFL_WATCH_INTERVAL_SECONDS: intervalSeconds,
      CFL_WATCH_ITERATIONS: iterations,
      GH_TOKEN: "test-token",
      GITHUB_REPOSITORY: "owner/project",
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
  assert.equal((log.match(/^gh /gm) ?? []).length, 3);
  assert.equal((log.match(/^sleep 15$/gm) ?? []).length, 2);
  assert.doesNotMatch(log, /workflow run continuous-fuzzing\.yml/);
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
