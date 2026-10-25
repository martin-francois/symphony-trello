import assert from "node:assert/strict";
import test from "node:test";
import {scenarioTimeoutMs} from "./scenario-timeout.ts";

test("an eleven-minute soak has no competing ten-minute scenario timer", () => {
  assert.equal(scenarioTimeoutMs({family: "soak"}, null, 0), null);
  assert.equal(scenarioTimeoutMs({family: "soak"}, 11 * 60_000, 0), 11 * 60_000);
});

test("ordinary scenarios retain their limit and every scenario obeys the run budget", () => {
  assert.equal(scenarioTimeoutMs({family: "setup"}, null, 0), 10 * 60_000);
  assert.equal(scenarioTimeoutMs({family: "setup"}, 11 * 60_000, 0), 10 * 60_000);
  for (const family of ["soak", "setup"] as const) {
    assert.equal(scenarioTimeoutMs({family}, 60_000, 0), 60_000);
    assert.equal(scenarioTimeoutMs({family}, 0, 1), 0);
  }
});
