import assert from "node:assert/strict";
import {mkdtempSync, readFileSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {parseOptions, UsageError} from "./options.ts";
import {resolveRunOptions, saveRunOptions} from "./run-options.ts";
import {RunRoot} from "./run-root.ts";

for (const command of ["--resume", "--cleanup-only"]) {
  test(`${command} restores real targets and the original selection without service access`, () => {
    // given
    const repo = mkdtempSync(join(tmpdir(), "bugbash-resume-"));
    const original = parseOptions(["--run-id", "saved-run", "--trello", "real", "--codex", "real", "--family", "soak", "--trello-workspace-id", "synthetic-workspace", "--soak-duration", "11m", "--time-budget", "15m"]);
    const run = new RunRoot(repo, original.runId);
    run.create();
    saveRunOptions(run, original);
    try {
      // when
      const restored = resolveRunOptions(repo, [command, original.runId]);

      // then
      assert.equal(restored.trello, "real");
      assert.equal(restored.codex, "real");
      assert.equal(restored.trelloWorkspaceId, "synthetic-workspace");
      assert.deepEqual(restored.selection, original.selection);
      assert.deepEqual(restored.soak, original.soak);
      assert.equal(restored.timeBudgetMs, original.timeBudgetMs);
      assert.throws(() => resolveRunOptions(repo, [command, original.runId, "--trello=fake"]), /saved target/);
      assert.throws(() => resolveRunOptions(repo, [command, original.runId, "--profile", "quick"]), /original scenarios/);
      const renewed = resolveRunOptions(repo, [command, original.runId, "--time-budget", "20m", "--soak-duration", "12m"]);
      assert.equal(renewed.timeBudgetMs, 1_200_000);
      assert.equal(renewed.soak.durationMs, 720_000);
      assert.deepEqual(renewed.selection, original.selection);
      assert.doesNotMatch(readFileSync(run.path("state", "run-options.json"), "utf8"), /API_KEY|API_TOKEN|auth.json/);
    } finally {
      rmSync(repo, {recursive: true, force: true});
    }
  });
}

test("a legacy run without saved targets fails before planning or cleanup", () => {
  const repo = mkdtempSync(join(tmpdir(), "bugbash-no-config-"));
  try {
    assert.throws(() => resolveRunOptions(repo, ["--resume", "missing"]), UsageError);
    assert.throws(() => resolveRunOptions(repo, ["--cleanup-only", "missing"]), /saved configuration is missing/);
  } finally {
    rmSync(repo, {recursive: true, force: true});
  }
});
