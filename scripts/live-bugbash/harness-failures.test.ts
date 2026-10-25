import assert from "node:assert/strict";
import {spawn, spawnSync} from "node:child_process";
import {chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, symlinkSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";
import {waitFor} from "./lib/symphony.ts";

function isolatedHarness(scanner: string | null) {
  const directory = mkdtempSync(join(tmpdir(), "bugbash-report-failure-"));
  cpSync(resolve("scripts/live-bugbash"), join(directory, "scripts/live-bugbash"), {recursive: true});
  symlinkSync(resolve("node_modules"), join(directory, "node_modules"), "dir");
  if (scanner !== null) {
    const path = join(directory, "scripts/check-private-context");
    writeFileSync(path, scanner);
    chmodSync(path, 0o755);
  }
  const bin = join(directory, "bin");
  mkdirSync(bin);
  writeFileSync(join(bin, "git"), '#!/bin/sh\nif [ "$1" = rev-parse ]; then printf "%s\\n" "$TEST_REVISION"; fi\n');
  chmodSync(join(bin, "git"), 0o755);
  return {
    directory,
    root: join(directory, "target/live-bugbash/report-proof"),
    run(revision: string, resume = false, command?: string) {
      return spawnSync(process.execPath, [join(directory, "scripts/live-bugbash/harness.ts"),
        "--run-id", "report-proof", "--scenario", "interim-real-trello-board-cleanup", ...(resume ? ["--resume", "report-proof"] : []),
        ...(command === undefined ? [] : ["--symphony-command", command])], {
        cwd: directory, encoding: "utf8", timeout: 20_000,
        env: {...process.env, PATH: `${bin}:${process.env["PATH"]}`, TEST_REVISION: revision},
      });
    },
  };
}

for (const scanner of ["#!/bin/sh\necho 'synthetic scanner failure' >&2\nexit 2\n", null]) {
  test(`a ${scanner === null ? "missing" : "failing"} scanner prevents a successful harness exit`, () => {
    // given
    const fixture = isolatedHarness(scanner);
    try {
      // when
      const result = fixture.run("1111111111111111111111111111111111111111");

      // then
      assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`);
      assert.match(readFileSync(join(fixture.root, "final-report.md"), "utf8"), /unavailable:/);
      assert.match(result.stdout, /Final report private-context scan: unavailable/);
    } finally {
      rmSync(fixture.directory, {recursive: true, force: true});
    }
  });
}

for (const changed of [false, true]) {
  test(`resume ${changed ? "reruns" : "retains"} previous passes when the tested revision ${changed ? "changes" : "matches"}`, () => {
    // given
    const fixture = isolatedHarness("#!/bin/sh\nexit 0\n");
    const originalRevision = "1111111111111111111111111111111111111111";
    const revision = changed ? "2222222222222222222222222222222222222222" : originalRevision;
    try {
      const first = fixture.run(originalRevision);
      assert.equal(first.status, 0, first.stderr);
      const results = join(fixture.root, "state/results.json");
      const earlier = JSON.parse(readFileSync(results, "utf8")) as Array<{startedAt: string; targetCommit: string}>;

      // when
      const resumed = fixture.run(revision, true);

      // then
      assert.equal(resumed.status, 0, resumed.stderr);
      const current = JSON.parse(readFileSync(results, "utf8")) as Array<{startedAt: string; targetCommit: string}>;
      assert.equal(current[0]?.startedAt === earlier[0]?.startedAt, !changed, "only matching-revision evidence may be reused");
      assert.equal(current[0]?.targetCommit, revision);
      const progress = readFileSync(join(fixture.root, "progress.md"), "utf8");
      assert.equal(progress.includes("start interim-real-trello-board-cleanup"), changed);
      const historical = readdirSync(join(fixture.root, "private-evidence/run")).find((name) => name.startsWith("results-before-resume-"));
      assert.ok(historical, "resume must retain the previous evidence");
      assert.deepEqual(JSON.parse(readFileSync(join(fixture.root, "private-evidence/run", historical), "utf8")), earlier);
      assert.match(readFileSync(join(fixture.root, "coverage-ledger.md"), "utf8"), new RegExp(revision.slice(0, 12)));
    } finally {
      rmSync(fixture.directory, {recursive: true, force: true});
    }
  });
}

test("resume reruns an existing installation changed behind the same command path and checkout revision", () => {
  // given
  const fixture = isolatedHarness("#!/bin/sh\nexit 0\n");
  const revision = "1111111111111111111111111111111111111111";
  const command = join(fixture.directory, "bin", "existing-symphony");
  writeFileSync(command, "#!/bin/sh\nprintf '%s\\n' installed-first\n", {mode: 0o755});
  writeFileSync(join(fixture.directory, "scripts/live-bugbash/scenarios/index.ts"), `
export const SCENARIOS = {
  "interim-real-trello-board-cleanup": async (context) => {
    const cli = await context.cli();
    const outcome = await cli.exec(["--version"]);
    context.check.exitCode(outcome, 0, "the existing command succeeds");
    return outcome.stdout.trim();
  },
};
`);
  try {
    const first = fixture.run(revision, false, command);
    assert.equal(first.status, 0, first.stderr);
    assert.match(readFileSync(join(fixture.root, "coverage-ledger.md"), "utf8"), /installed-first/);
    writeFileSync(command, "#!/bin/sh\nprintf '%s\\n' installed-second\n");

    // when
    const resumed = fixture.run(revision, true, command);

    // then
    assert.equal(resumed.status, 0, resumed.stderr);
    assert.match(readFileSync(join(fixture.root, "coverage-ledger.md"), "utf8"), /installed-second/);
    const results = JSON.parse(readFileSync(join(fixture.root, "state/results.json"), "utf8")) as Array<{targetCommit: string | null}>;
    assert.equal(results[0]?.targetCommit, null, "the checkout revision does not identify the external application");
    assert.match(readFileSync(join(fixture.root, "final-report.md"), "utf8"), /existing installation; revision unverified; rows rerun on resume/);
  } finally {
    rmSync(fixture.directory, {recursive: true, force: true});
  }
});

test("signal interruption waits for the active scenario to settle before final cleanup and scans", async () => {
  // given
  const fixture = isolatedHarness("#!/bin/sh\ntest -f target/live-bugbash/report-proof/state/scenario-settled\n");
  writeFileSync(join(fixture.directory, "scripts/live-bugbash/scenarios/index.ts"), `
import {writeFileSync} from "node:fs";
export const SCENARIOS = {
  "interim-real-trello-board-cleanup": async (context) => {
    const completed = new Promise(resolve => context.signal?.addEventListener("abort", () => {
      setImmediate(() => {
        writeFileSync(context.run.path("state", "scenario-settled"), "complete");
        resolve("cancelled");
      });
    }, {once: true}));
    writeFileSync(context.run.path("state", "scenario-ready"), "ready");
    return completed;
  },
};
`);
  const cleanupPath = join(fixture.directory, "scripts/live-bugbash/lib/cleanup.ts");
  const cleanupSource = readFileSync(cleanupPath, "utf8")
    .replace('import {existsSync, rmSync}', 'import {existsSync, rmSync, writeFileSync}')
    .replace('  const notes: string[] = [];', `  if (!existsSync(run.path("state", "scenario-settled"))) {
    writeFileSync(run.path("state", "early-cleanup"), "cleanup raced the active scenario");
  }
  const notes: string[] = [];`);
  writeFileSync(cleanupPath, cleanupSource);
  const child = spawn(process.execPath, [join(fixture.directory, "scripts/live-bugbash/harness.ts"),
    "--run-id", "report-proof", "--scenario", "interim-real-trello-board-cleanup"], {
    cwd: fixture.directory, stdio: "pipe", timeout: 10_000,
  });
  let transcript = "";
  child.stdout.on("data", (data: Buffer) => {transcript += data.toString();});
  child.stderr.on("data", (data: Buffer) => {transcript += data.toString();});
  const exited = new Promise<number | null>((resolve) => child.on("close", resolve));
  try {
    await waitFor("the active scenario readiness acknowledgement", () => existsSync(join(fixture.root, "state/scenario-ready")), 5_000, 10);

    // when
    child.kill("SIGTERM");
    const status = await exited;

    // then
    assert.equal(status, 130, transcript);
    assert.equal(existsSync(join(fixture.root, "state/scenario-settled")), true, "cleanup cannot race an abandoned scenario");
    assert.equal(existsSync(join(fixture.root, "state/early-cleanup")), false, "cleanup must wait through the active scenario cancellation callback");
    assert.match(transcript, /Final report private-context scan: clean/);
  } finally {
    child.kill("SIGKILL");
    await exited;
    rmSync(fixture.directory, {recursive: true, force: true});
  }
});
