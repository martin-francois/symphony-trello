import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {existsSync, readFileSync, rmSync} from "node:fs";
import {join, resolve} from "node:path";
import test from "node:test";
import {createFakeCommandEnvironment} from "../test-support/fake-command-environment.ts";

const RUN_SH = resolve("scripts/live-bugbash/run.sh");
const RUNS = resolve("target/live-bugbash");

function harness(args: readonly string[], environment: NodeJS.ProcessEnv = process.env) {
  return spawnSync(RUN_SH, args, {encoding: "utf8", env: environment, timeout: 120_000});
}

test("--help prints usage and exits 0", () => {
  // given
  const args = ["--help"];

  // when
  const result = harness(args);

  // then
  assert.equal(result.status, 0);
  assert.match(result.stdout, /^Usage: scripts\/live-bugbash\/run.sh/);
});

test("an unknown option is a usage error", () => {
  // given
  const args = ["--bogus"];

  // when
  const result = harness(args);

  // then
  assert.equal(result.status, 2);
  assert.match(result.stderr, /unknown option --bogus/);
});

test("--list prints the selected rows with automation status", () => {
  // given
  const args = ["--list", "--family", "soak"];

  // when
  const result = harness(args);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /passive-soak-trello-auth-board-process-codex-sentinel\tsoak\tsoak\tsoak\tcovered\tautomated/);
  assert.match(result.stdout, /1 scenario\(s\)/);
});

test("--dry-run reports gating without creating a run root", () => {
  // given
  const runId = `dry-run-test-${process.pid}`;

  // when
  const result = harness(["--dry-run", "--profile", "real-codex", "--run-id", runId]);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /skip\s+direct-real-codex-sentinel \(direct-real-codex\): real-Codex row; pass --codex real to opt in/);
  assert.equal(existsSync(join(RUNS, runId)), false);
});

test("a run executes selected rows against the fake Trello API and writes public reports", () => {
  // given
  const runId = `harness-test-${process.pid}`;
  const scanner = createFakeCommandEnvironment("live-bugbash-scan-", {betterleaks: "#!/bin/bash\nexit 0\n"});
  const args = ["--scenario", "uninstall-dry-run-custom-prefix-safety", "--scenario", "interim-real-trello-board-cleanup", "--run-id", runId];

  try {
    // when
    const result = harness(args, scanner.environment({BETTERLEAKS_COMMAND: join(scanner.directory, "betterleaks")}));

    // then
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    const root = join(RUNS, runId);
    const ledger = readFileSync(join(root, "coverage-ledger.md"), "utf8");
    assert.match(ledger, /\| uninstall-dry-run-custom-prefix-safety \| covered \|/);
    assert.match(ledger, /\| interim-real-trello-board-cleanup \| covered \|/);
    const cleanup = readFileSync(join(root, "cleanup-summary.md"), "utf8");
    assert.match(cleanup, /Run-owned Trello boards registered: 1/);
    assert.match(cleanup, /Still open after cleanup: 0/);
    const report = readFileSync(join(root, "final-report.md"), "utf8");
    assert.match(report, /## Private-context scan\n\nclean:/);
    assert.ok(!report.includes(root), "the final report does not print the run root");
    assert.match(result.stdout, new RegExp(`Reports: target/live-bugbash/${runId}/`));
  } finally {
    rmSync(join(RUNS, runId), {recursive: true, force: true});
  }
});
