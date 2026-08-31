import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

const script = resolve("scripts/verify-clusterfuzzlite-storage");

function run(args: string[], failingPath?: string) {
  const fakeBin = mkdtempSync(join(tmpdir(), "clusterfuzzlite-storage-bin-"));
  const log = join(fakeBin, "gh.log");
  const gh = join(fakeBin, "gh");
  writeFileSync(log, "");
  writeFileSync(
    gh,
    `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$GH_LOG"
if [[ -n "\${FAILING_PATH:-}" && "$*" == *"$FAILING_PATH"* ]]; then
  exit 1
fi
`,
  );
  chmodSync(gh, 0o755);

  const result = spawnSync("bash", [script, ...args], {
    encoding: "utf8",
    env: {
      ...process.env,
      CFL_STORAGE_REPOSITORY: "owner/fuzz-storage",
      FAILING_PATH: failingPath,
      GH_LOG: log,
      PATH: `${fakeBin}:${process.env.PATH}`,
    },
  });

  return {log: readFileSync(log, "utf8"), result};
}

test("verifies every requested target on the corpus branch", () => {
  const {log, result} = run(["corpus", "FirstFuzzer", "SecondFuzzer"]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /repos\/owner\/fuzz-storage\/contents\/corpus\/FirstFuzzer -f ref=main/);
  assert.match(log, /repos\/owner\/fuzz-storage\/contents\/corpus\/SecondFuzzer -f ref=main/);
});

test("fails when a requested target was not persisted", () => {
  const {result} = run(["corpus", "MissingFuzzer"], "corpus/MissingFuzzer");

  assert.equal(result.status, 1);
  assert.match(result.stderr, /did not persist corpus\/MissingFuzzer/);
});

test("verifies the browsable coverage report on the Pages branch", () => {
  const {log, result} = run(["coverage"]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(
    log,
    /contents\/coverage\/latest\/report\/linux\/report\.html -f ref=gh-pages/,
  );
});

test("rejects incomplete verification requests", () => {
  assert.equal(run(["corpus"]).result.status, 2);
  assert.equal(run(["coverage", "UnexpectedFuzzer"]).result.status, 2);
  assert.equal(run(["unknown"]).result.status, 2);
});
