import assert from "node:assert/strict";
import {spawn, spawnSync, type ChildProcess} from "node:child_process";
import {mkdtempSync, readFileSync, symlinkSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {waitFor} from "./symphony.ts";
import {runOwnedProcesses} from "./processes.ts";

const LINUX_ONLY = {skip: process.platform !== "linux"};
const SHELL = spawnSync("sh", ["-c", "command -v sh"], {encoding: "utf8"}).stdout.trim();

/**
 * Starts a shell under the given program name whose command line ends with a run-root path. The
 * trailing `:` keeps the shell from replacing itself with `sleep`.
 */
async function sleeper(program: string, runRoot: string): Promise<ChildProcess> {
  const child = spawn(program, ["-c", "sleep 30; :", join(runRoot, "progress.md")]);
  await waitFor("the child command line", () => commandLine(child.pid).includes(runRoot), 5_000, 20);
  return child;
}

function commandLine(pid: number | undefined): string {
  try {
    return readFileSync(`/proc/${pid}/cmdline`, "utf8");
  } catch {
    return "";
  }
}

test("a Java program whose command line mentions the run root is run-owned", LINUX_ONLY, async () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "live-bugbash-proc-"));
  const runRoot = join(directory, "run");
  const java = join(directory, "java");
  symlinkSync(SHELL, java);
  const child = await sleeper(java, runRoot);

  try {
    // when
    const owned = runOwnedProcesses(runRoot);

    // then
    assert.deepEqual(owned, [{pid: child.pid, reason: "command line"}]);
  } finally {
    child.kill("SIGKILL");
  }
});

test("another program that mentions the run root, such as an operator's tail, is not run-owned", LINUX_ONLY, async () => {
  // given
  const runRoot = join(mkdtempSync(join(tmpdir(), "live-bugbash-proc-")), "run");
  const child = await sleeper(SHELL, runRoot);

  try {
    // when
    const owned = runOwnedProcesses(runRoot);

    // then
    assert.deepEqual(owned, []);
  } finally {
    child.kill("SIGKILL");
  }
});
