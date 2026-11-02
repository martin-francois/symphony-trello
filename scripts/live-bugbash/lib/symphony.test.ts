import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import test from "node:test";
import {existsSync, mkdtempSync, readFileSync, rmSync, watch, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {setImmediate as nextTurn} from "node:timers/promises";
import {RunRoot} from "./run-root.ts";
import {installFromSource, pollUntil, runCommand, TimeoutError, waitFor} from "./symphony.ts";

test("pollUntil returns the first value the check produces", async () => {
  // given
  let calls = 0;
  const check = () => (++calls >= 3 ? "ready" : false);

  // when
  const value = await pollUntil(check, 1_000, 1);

  // then
  assert.equal(value, "ready");
  assert.equal(calls, 3);
});

test("pollUntil returns null at the deadline while waitFor throws a TimeoutError", async () => {
  // given
  const never = () => null;

  // when
  const polled = await pollUntil(never, 20, 5);

  // then
  assert.equal(polled, null);
  await assert.rejects(() => waitFor("nothing", never, 20, 5), TimeoutError);
});

test("cancelling a command stops its process group before cleanup awaits the installer", async () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "bugbash-cancel-"));
  const parentPid = join(directory, "parent-pid");
  const holderPid = join(directory, "holder-pid");
  const stopped = join(directory, "stopped");
  const controller = new AbortController();
  // The pipe-holding subshell writes the readiness marker once bash has reset its TERM trap, so
  // every process that can hold the pipes exists before the test sends the group SIGTERM. A marker
  // written before the fork lets SIGTERM arrive first, and the late sleep then holds the pipes until
  // the SIGKILL grace runs out.
  const descendant = `trap 'printf stopped > "$2"; exit 0' TERM; { echo $BASHPID > "$1"; exec sleep 60; } & wait`;
  let ownedGroupPid: number | null = null;
  const readyPid = (path: string): number | null => {
    if (!existsSync(path)) return null;
    const pid = Number(readFileSync(path, "utf8"));
    return Number.isSafeInteger(pid) && pid > 0 ? pid : null;
  };
  let completed: Awaited<ReturnType<typeof runCommand>> | undefined;
  const command = runCommand("bash", ["-c", 'echo $$ > "$1"; bash -c "$4" bash "$2" "$3" & wait', "bash", parentPid, holderPid, stopped, descendant], {
    cwd: directory,
    timeoutMs: 10_000,
    signal: controller.signal,
  }).then((outcome) => {
    completed = outcome;
    return outcome;
  });
  try {
    ownedGroupPid = await waitFor("installer process group ready", () => readyPid(parentPid), 2_000, 5);
    await waitFor("pipe-holding descendant ready", () => readyPid(holderPid), 2_000, 5);

    // when
    controller.abort();
    await waitFor("descendant termination acknowledgement", () => existsSync(stopped) && readFileSync(stopped, "utf8") === "stopped", 2_000, 5);
    const outcome = await waitFor("command group to close its inherited pipes", () => completed, 2_000, 5);

    // then
    assert.notEqual(outcome.status, 0);
    assert.equal(readFileSync(stopped, "utf8"), "stopped");
  } finally {
    // Mutation failures must also stop the test-owned group before deleting its evidence.
    if (ownedGroupPid !== null && Number.isSafeInteger(ownedGroupPid) && ownedGroupPid > 0) {
      try {
        process.kill(-ownedGroupPid, "SIGKILL");
      } catch {
        // The test-owned group has already exited.
      }
    }
    await command;
    rmSync(directory, {recursive: true, force: true});
  }
});

for (const cancellation of ["abort", "timeout", "abort then timeout"] as const) {
  test(`${cancellation} does not send SIGKILL after the command group closes`, {timeout: 10_000}, async (t) => {
    // given
    const directory = mkdtempSync(join(tmpdir(), "bugbash-late-kill-"));
    const readiness = processReadiness(directory);
    const controller = new AbortController();
    const realKill = process.kill.bind(process);
    let ownedGroupPid: number | undefined;
    let completed = false;
    const kill = t.mock.method(process, "kill", (pid: number, signal?: NodeJS.Signals | number) => {
      // Observe a stale signal without ever sending it to a possibly reused process-group number.
      if (ownedGroupPid !== undefined && pid === -ownedGroupPid && signal === "SIGKILL") return true;
      return realKill(pid, signal);
    });
    t.mock.timers.enable({apis: ["setTimeout"]});
    const program = 'require("node:fs").writeFileSync(process.argv[1], `${process.pid}\\n`); setInterval(() => {}, 60_000);';
    const command = runCommand(process.execPath, ["-e", program, readiness.path], {
      cwd: directory,
      timeoutMs: 1_000,
      signal: controller.signal,
    }).then((outcome) => {
      completed = true;
      return outcome;
    });
    try {
      ownedGroupPid = await readiness.ready;

      // when
      if (cancellation !== "timeout") controller.abort();
      if (cancellation !== "abort") t.mock.timers.tick(1_000);
      const outcome = await command;
      t.mock.timers.tick(5_000);

      // then
      assert.notEqual(outcome.status, 0);
      assert.equal(outcome.timedOut, cancellation !== "abort");
      assert.deepEqual(kill.mock.calls.map(({arguments: args}) => args), [[-ownedGroupPid, "SIGTERM"]], "a closed group must receive no delayed escalation");
    } finally {
      if (!completed && ownedGroupPid !== undefined && ownedGroupPid > 1) {
        try { realKill(-ownedGroupPid, "SIGKILL"); } catch { /* The test-owned group already exited. */ }
      }
      t.mock.timers.reset();
      readiness.close();
      await command;
      rmSync(directory, {recursive: true, force: true});
    }
  });
}

test("the exit grace keeps cancellation escalation while a descendant holds output pipes", {timeout: 10_000}, async (t) => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "bugbash-held-pipes-"));
  const readiness = processReadiness(directory);
  const controller = new AbortController();
  const realKill = process.kill.bind(process);
  const kill = t.mock.method(process, "kill");
  t.mock.timers.enable({apis: ["setTimeout"]});
  const timers = t.mock.method(globalThis, "setTimeout");
  const descendant = 'process.on("SIGTERM", () => {}); require("node:fs").writeFileSync(process.argv[1], `${process.argv[2]}\\n`); setInterval(() => {}, 60_000);';
  const program = 'const child = require("node:child_process").spawn(process.execPath, ["-e", process.argv[1], process.argv[2], String(process.pid)], {stdio: ["ignore", 1, 2]}); child.unref();';
  let ownedGroupPid: number | undefined;
  const command = runCommand(process.execPath, ["-e", program, descendant, readiness.path], {
    cwd: directory,
    timeoutMs: 30_000,
    signal: controller.signal,
  });
  try {
    ownedGroupPid = await readiness.ready;
    // Wait for the real leader's exit event to arm its grace before cancellation arms escalation.
    while (!timers.mock.calls.some(({arguments: args}) => args[1] === 5_000)) await nextTurn();

    // when
    t.mock.timers.tick(1_000);
    controller.abort();
    t.mock.timers.tick(4_000);
    const outcome = await command;

    // then
    assert.equal(outcome.status, 0, "the leader's normal exit still resolves at the exit grace");
    assert.deepEqual(kill.mock.calls.map(({arguments: args}) => args), [[-ownedGroupPid, "SIGTERM"]]);
    t.mock.timers.tick(1_000);
    assert.deepEqual(kill.mock.calls.map(({arguments: args}) => args), [[-ownedGroupPid, "SIGTERM"], [-ownedGroupPid, "SIGKILL"]], "open descendant pipes must retain the pending escalation after resolution");
  } finally {
    if (ownedGroupPid !== undefined && ownedGroupPid > 1) {
      try { realKill(-ownedGroupPid, "SIGKILL"); } catch { /* The test-owned group already exited. */ }
    }
    t.mock.timers.reset();
    readiness.close();
    await command;
    rmSync(directory, {recursive: true, force: true});
  }
});

function processReadiness(directory: string): {path: string; ready: Promise<number>; close: () => void} {
  const path = join(directory, "ready");
  const watcher = watch(directory);
  const ready = new Promise<number>((resolve) => {
    watcher.on("change", () => {
      if (!existsSync(path)) return;
      const marker = readFileSync(path, "utf8");
      const pid = Number(marker.trim());
      if (marker.endsWith("\n") && Number.isSafeInteger(pid) && pid > 1) resolve(pid);
    });
  });
  return {path, ready, close: () => watcher.close()};
}

test("source installation keeps each prepared revision immutable across resume", async () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "bugbash-source-revisions-"));
  const git = (...args: string[]) => execFileSync("git", args, {cwd: directory, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"]}).trim();
  git("init", "--quiet");
  git("config", "user.name", "Synthetic Fixture");
  git("config", "user.email", "fixture@example.invalid");
  writeFileSync(join(directory, "install.sh"), '#!/bin/bash\nwhile (($#)); do if [[ $1 == --repo ]]; then printf "%s\\n" "$2"; exit; fi; shift; done\n');
  git("add", "install.sh");
  git("commit", "--quiet", "-m", "test: first installer revision");
  const firstCommit = git("rev-parse", "HEAD");
  writeFileSync(join(directory, "README.md"), "second revision\n");
  git("add", "README.md");
  git("commit", "--quiet", "-m", "test: second installer revision");
  const secondCommit = git("rev-parse", "HEAD");
  const run = new RunRoot(directory, "resumed-source");
  run.create();
  try {
    // when
    const first = await installFromSource(run, firstCommit);
    const second = await installFromSource(run, secondCommit);

    // then
    assert.equal(first.outcome.status, 0, first.outcome.stderr);
    assert.equal(second.outcome.status, 0, second.outcome.stderr);
    const preparedFirst = first.outcome.stdout.trim();
    const preparedSecond = second.outcome.stdout.trim();
    assert.equal(git("--git-dir", preparedFirst, "rev-parse", "refs/heads/live-bugbash-source"), firstCommit, "a resumed revision must not replace the earlier prepared source");
    assert.equal(git("--git-dir", preparedSecond, "rev-parse", "refs/heads/live-bugbash-source"), secondCommit);

    // Verify isolation above before trying unrelated history, so a regression never rewrites it.
    const rebasedCommit = git("commit-tree", git("rev-parse", "HEAD^{tree}"), "-m", "test: unrelated installer revision");
    const rebased = await installFromSource(run, rebasedCommit);
    assert.equal(rebased.outcome.status, 0, rebased.outcome.stderr);
    assert.equal(git("--git-dir", rebased.outcome.stdout.trim(), "rev-parse", "refs/heads/live-bugbash-source"), rebasedCommit);
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
});

test("a command that closes stdin early retains its exit status without an unhandled pipe error", () => {
  // given
  const module = new URL("./symphony.ts", import.meta.url).href;
  const program = `
import {runCommand} from ${JSON.stringify(module)};
const outcome = await runCommand(process.execPath, ["-e", "process.stdin.once('data', () => process.exit(23))"], {
  cwd: process.cwd(), input: "synthetic".repeat(1_000_000), timeoutMs: 5_000,
});
process.stdout.write(JSON.stringify(outcome));
`;

  // when
  const result = spawnSync(process.execPath, ["--input-type=module", "-e", program], {encoding: "utf8", timeout: 10_000});

  // then
  assert.equal(result.status, 0, result.stderr);
  const outcome = JSON.parse(result.stdout) as {status: number; timedOut: boolean};
  assert.equal(outcome.status, 23);
  assert.equal(outcome.timedOut, false);
});
