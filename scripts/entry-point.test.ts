import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync, readdirSync, readFileSync, symlinkSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";

// A home directory or checkout reached through a symlink must still run each script's main; a
// silent exit 0 there once made `posthog-infra verify` report success without checking anything.
const SCRIPTS = import.meta.dirname;

function throughSymlink(script: string, env: Record<string, string>) {
  const link = join(mkdtempSync(join(tmpdir(), "entry-point-")), "scripts");
  symlinkSync(SCRIPTS, link);
  const {POSTHOG_API_KEY: _key, SYMPHONY_TRELLO_ERASURE_LIFECYCLE: _optIn, ...inherited} = process.env;
  return spawnSync(process.execPath, [join(link, script), ...(env["ARGS"] ?? "").split(" ").filter(Boolean)], {
    encoding: "utf8",
    env: {...inherited, ...env},
  });
}

test("posthog-infra runs its command when launched through a symlinked directory", () => {
  const result = throughSymlink("posthog-infra.ts", {ARGS: "verify --key-file /nonexistent/key"});
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /personal API key file not readable/);
});

test("the erasure lifecycle runner refuses without opt-in when launched through a symlinked directory", () => {
  const result = throughSymlink("erasure-lifecycle-live.ts", {});
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /Lifecycle gate failed/);
});

test("every script decides whether it is the entry point through entry-point.ts", () => {
  const direct = readdirSync(SCRIPTS)
    .filter((file) => /\.(ts|mts|js|mjs)$/.test(file) && !file.includes(".test.") && file !== "entry-point.ts")
    .filter((file) => readFileSync(join(SCRIPTS, file), "utf8").includes("process.argv[1]"));
  assert.deepEqual(direct, []);
});
