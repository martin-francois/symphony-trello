import assert from "node:assert/strict";
import {mkdtempSync, readFileSync, rmSync, statSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {FAKE_CODEX_CONTRACT, fakeCodexStepKinds, runCodexAuthChanged, runScopedCodexHome} from "./codex.ts";

import {RunRoot} from "./run-root.ts";

const FAKE_SOURCE = readFileSync(new URL("../../FakeCodexAppServer.java", import.meta.url), "utf8");

function runScriptBody(source: string): string {
  const start = source.indexOf("private void runScript(");
  const end = source.indexOf("\n    private ", start + 1);
  assert.ok(start >= 0 && end > start, "FakeCodexAppServer.java has a runScript method");
  return source.slice(start, end);
}

test("every fake Codex step kind the harness writes is a case of the fake's runScript switch", () => {
  // given
  const javaCases = [...runScriptBody(FAKE_SOURCE).matchAll(/case "([^"]+)" ->/g)].map((match) => match[1]);

  // when
  const harnessKinds = fakeCodexStepKinds();

  // then
  assert.deepEqual([...harnessKinds].sort(), [...javaCases].sort());
});

test("the fake Codex variable names and file suffixes the harness uses appear in the fake", () => {
  // given
  const shared = Object.values(FAKE_CODEX_CONTRACT);

  // when
  const missing = shared.filter((value) => !FAKE_SOURCE.includes(`"${value}"`));

  // then
  assert.deepEqual(missing, []);
});


test("reusing a run Codex home preserves refreshed authentication and the operator's original", () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "bugbash-codex-auth-"));
  const previousHome = process.env["CODEX_HOME"];
  process.env["CODEX_HOME"] = directory;
  const original = '{"synthetic":"original"}';
  const refreshed = '{"synthetic":"refreshed"}';
  writeFileSync(join(directory, "auth.json"), original);
  const run = new RunRoot(directory, "auth-refresh");
  run.create();
  try {
    const home = runScopedCodexHome(run);
    const copy = join(home, "auth.json");
    assert.equal(readFileSync(copy, "utf8"), original);
    assert.equal(statSync(copy).mode & 0o777, 0o600);
    writeFileSync(copy, refreshed);

    // when
    const reused = runScopedCodexHome(run);

    // then
    assert.equal(reused, home);
    assert.equal(readFileSync(copy, "utf8"), refreshed);
    assert.equal(readFileSync(join(directory, "auth.json"), "utf8"), original);
    assert.equal(runCodexAuthChanged(run), true);
  } finally {
    if (previousHome === undefined) {
      delete process.env["CODEX_HOME"];
    } else {
      process.env["CODEX_HOME"] = previousHome;
    }
    rmSync(directory, {recursive: true, force: true});
  }
});
