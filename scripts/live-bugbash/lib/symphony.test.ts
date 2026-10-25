import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import test from "node:test";
import {existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
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
  const childPid = join(directory, "child-pid");
  const stopped = join(directory, "stopped");
  const controller = new AbortController();
  const descendant = `trap 'printf stopped > "$2"; exit 0' TERM; echo $$ > "$1"; sleep 60 & wait`;
  let ownedGroupPid: number | null = null;
  const readyPid = (path: string): number | null => {
    if (!existsSync(path)) return null;
    const pid = Number(readFileSync(path, "utf8"));
    return Number.isSafeInteger(pid) && pid > 0 ? pid : null;
  };
  let completed: Awaited<ReturnType<typeof runCommand>> | undefined;
  const command = runCommand("bash", ["-c", 'echo $$ > "$1"; bash -c "$4" bash "$2" "$3" & wait', "bash", parentPid, childPid, stopped, descendant], {
    cwd: directory,
    timeoutMs: 10_000,
    signal: controller.signal,
  }).then((outcome) => {
    completed = outcome;
    return outcome;
  });
  try {
    ownedGroupPid = await waitFor("installer process group ready", () => readyPid(parentPid), 2_000, 5);
    await waitFor("installer descendant ready", () => readyPid(childPid), 2_000, 5);

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
