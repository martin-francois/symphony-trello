import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {readFileSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import test from "node:test";
import {createFakeCommandEnvironment} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/queue-clusterfuzzlite-watchdog");

function run(failures = 0) {
  const fakeCommands = createFakeCommandEnvironment("clusterfuzzlite-watchdog-queue-bin-", {
    gh: `#!/bin/bash
set -euo pipefail
attempt="$(( $(cat "$ATTEMPTS_FILE") + 1 ))"
printf '%s' "$attempt" >"$ATTEMPTS_FILE"
printf '%s\n' "$*" >>"$FAKE_COMMAND_LOG"
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
