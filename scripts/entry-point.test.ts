import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync, readdirSync, readFileSync, rmSync, symlinkSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";

// A home directory or checkout reached through a symlink must still run each script's main. A
// script that compared import.meta.url with process.argv[1] exited 0 there without doing anything.
const SCRIPTS = import.meta.dirname;

function throughSymlink(script: string, args: readonly string[]) {
  const directory = mkdtempSync(join(tmpdir(), "entry-point-"));
  const link = join(directory, "scripts");
  symlinkSync(SCRIPTS, link);
  // The PostHog scripts must fail on the missing key and the missing opt-in, whatever the shell has.
  const {POSTHOG_API_KEY: _key, SYMPHONY_TRELLO_ERASURE_LIFECYCLE: _optIn, ...env} = process.env;
  try {
    return spawnSync(process.execPath, [join(link, script), ...args], {encoding: "utf8", env});
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
}

test("a repaired script runs its main when launched through a symlinked directory", () => {
  // given
  const script = "audit-issue-labels.ts";

  // when
  const result = throughSymlink(script, ["--help"]);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /usage: node scripts\/audit-issue-labels\.ts/);
});

test("posthog-infra runs its command when launched through a symlinked directory", () => {
  // given
  const script = "posthog-infra.ts";

  // when
  const result = throughSymlink(script, ["verify", "--key-file", "/nonexistent/key"]);

  // then
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /personal API key file not readable/);
});

test("the erasure lifecycle runner refuses without opt-in when launched through a symlinked directory", () => {
  // given
  const script = "erasure-lifecycle-live.ts";

  // when
  const result = throughSymlink(script, []);

  // then
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /cannot complete erasure lifecycle verification: inspect the protected ledger/);
});

test("every script decides whether it is the entry point through entry-point.ts", () => {
  // given
  const scripts = readdirSync(SCRIPTS).filter(
    (file) => /\.(ts|mts|js|mjs)$/.test(file) && !file.includes(".test.") && file !== "entry-point.ts",
  );

  // when
  const direct = scripts.filter((file) => readFileSync(join(SCRIPTS, file), "utf8").includes("process.argv[1]"));

  // then
  assert.deepEqual(direct, []);
});
