import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {FAKE_CODEX_CONTRACT, fakeCodexStepKinds} from "./codex.ts";

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
