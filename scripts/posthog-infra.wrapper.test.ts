import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";

// The wrapper entry point against the real OpenTofu executable: each selected state directory
// must be bound to its own backend, and state commands must refuse a data directory bound to
// another state. Skipped where `tofu` is not installed; no network beyond the provider download.
const WRAPPER = join(process.cwd(), "scripts", "posthog-infra");
const HAS_TOFU = spawnSync("tofu", ["version"], {encoding: "utf8"}).status === 0;

function run(stateDir: string, keyFile: string, args: readonly string[], extraEnv: Record<string, string> = {}) {
  return spawnSync(WRAPPER, args, {
    encoding: "utf8",
    env: {...process.env, SYMPHONY_TRELLO_POSTHOG_STATE_DIR: stateDir, SYMPHONY_TRELLO_POSTHOG_KEY_FILE: keyFile, TF_CLI_ARGS: "-no-color", TF_PLUGIN_CACHE_DIR: process.env["TF_PLUGIN_CACHE_DIR"] ?? join(tmpdir(), "posthog-infra-plugin-cache"), ...extraEnv},
  });
}

test("each state directory gets its own bound backend and a mismatch stops state commands", {skip: !HAS_TOFU && "tofu not installed"}, () => {
  // given
  const root = mkdtempSync(join(tmpdir(), "posthog-infra-wrapper-"));
  const keyFile = join(root, "key");
  writeFileSync(keyFile, "phx_fake_personal_key_for_wrapper_test\n");
  const stateA = join(root, "a");
  const stateB = join(root, "b");

  // when
  const initA = run(stateA, keyFile, ["init"]);
  const initB = run(stateB, keyFile, ["init"]);
  const boundA = run(stateA, keyFile, ["state-path"]).stdout.trim();
  const boundB = run(stateB, keyFile, ["state-path"]).stdout.trim();
  const listA = run(stateA, keyFile, ["tofu", "show"]);
  const recordA = JSON.parse(readFileSync(join(stateA, ".terraform", "terraform.tfstate"), "utf8")) as {backend?: {config?: {path?: string}}};
  // Simulate the old shared-cache layout: point A's selection at B's data directory.
  const crossed = run(stateA, keyFile, ["tofu", "show"], {TF_DATA_DIR: join(stateB, ".terraform")});
  const destroyCrossed = run(stateA, keyFile, ["destroy", "-auto-approve"], {TF_DATA_DIR: join(stateB, ".terraform")});
  const tokenCrossed = run(stateA, keyFile, ["release-token", "owner/repo"], {TF_DATA_DIR: join(stateB, ".terraform")});

  // then
  assert.equal(initA.status, 0, initA.stderr);
  assert.equal(initB.status, 0, initB.stderr);
  assert.equal(boundA, join(stateA, "terraform.tfstate"));
  assert.equal(boundB, join(stateB, "terraform.tfstate"));
  assert.equal(recordA.backend?.config?.path, join(stateA, "terraform.tfstate"));
  assert.ok(existsSync(join(stateB, ".terraform")));
  assert.equal(listA.status, 0, listA.stderr);
  for (const result of [crossed, destroyCrossed, tokenCrossed]) {
    assert.equal(result.status, 2);
    assert.ok(result.stderr.includes("is bound to"), result.stderr);
    assert.ok(result.stderr.includes(join(stateB, "terraform.tfstate")));
  }
});

test("apply refuses arguments that would reach only some phases", {skip: !HAS_TOFU && "tofu not installed"}, () => {
  // given
  const root = mkdtempSync(join(tmpdir(), "posthog-infra-wrapper-"));
  const keyFile = join(root, "key");
  writeFileSync(keyFile, "phx_fake_personal_key_for_wrapper_test\n");

  // when
  const result = run(join(root, "state"), keyFile, ["apply", "-target=module.test.posthog_project.this"]);

  // then
  assert.equal(result.status, 2);
  assert.ok(result.stderr.includes("apply accepts only"), result.stderr);
});
