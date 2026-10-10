import {spawnSync} from "node:child_process";
import {existsSync, readdirSync, readFileSync, readlinkSync} from "node:fs";
import {basename, sep} from "node:path";

export interface RunOwnedProcess {
  pid: number;
  reason: "command line" | "working directory";
}

/**
 * Programs the harness starts: managed workers and the fake app-server run on Java, real Codex runs
 * as `codex`. Only these can be run-owned, so an operator's shell, editor, or `tail` that mentions
 * a run-root path is never stopped by cleanup.
 */
const RUN_PROGRAMS = new Set(["java", "codex"]);
/**
 * Codex commands the harness and its workers start. An interactive Codex session that an operator
 * opened inside a run workspace has neither subcommand and is left alone.
 */
const HARNESS_CODEX = /\bcodex\b.*\b(app-server|exec)\b/;

/**
 * Finds processes that belong to a run: Java workers and fake app-servers carry run-root paths in
 * their command line, and a real `codex app-server` runs with its working directory inside the run's
 * per-card workspaces. On Linux this reads /proc; elsewhere it falls back to `ps` command lines.
 */
export function runOwnedProcesses(runRoot: string): RunOwnedProcess[] {
  const mentionsRunRoot = (commandLine: string) => `${commandLine} `.includes(runRoot + sep) || `${commandLine} `.includes(`${runRoot} `);
  const owned: RunOwnedProcess[] = [];
  if (existsSync("/proc/self/cmdline")) {
    for (const entry of readdirSync("/proc")) {
      const pid = Number(entry);
      if (!Number.isInteger(pid) || pid === process.pid) {
        continue;
      }
      try {
        const argv = readFileSync(`/proc/${pid}/cmdline`, "utf8").split("\0").filter((part) => part !== "");
        if (!isRunProgram(argv)) {
          continue;
        }
        const commandLine = argv.join(" ");
        if (mentionsRunRoot(commandLine)) {
          owned.push({pid, reason: "command line"});
          continue;
        }
        const cwd = readlinkSync(`/proc/${pid}/cwd`);
        if (HARNESS_CODEX.test(commandLine) && (cwd === runRoot || cwd.startsWith(runRoot + sep))) {
          owned.push({pid, reason: "working directory"});
        }
      } catch {
        // The process exited or belongs to another user; neither can be run-owned.
      }
    }
    return owned;
  }
  const ps = spawnSync("ps", ["-axo", "pid=,command="], {encoding: "utf8"});
  for (const line of ps.stdout.split("\n")) {
    const match = /^\s*(\d+)\s+(.*)$/.exec(line);
    const commandLine = match?.[2] ?? "";
    if (match?.[1] !== undefined && Number(match[1]) !== process.pid && isRunProgram(commandLine.split(" ")) && mentionsRunRoot(commandLine)) {
      owned.push({pid: Number(match[1]), reason: "command line"});
    }
  }
  return owned;
}

/** True for `java ...`, `codex ...`, and `node .../codex ...` (the npm-installed Codex launcher). */
function isRunProgram(argv: readonly string[]): boolean {
  const program = basename(argv[0] ?? "");
  if (RUN_PROGRAMS.has(program)) {
    return true;
  }
  return program === "node" && basename(argv[1] ?? "") === "codex";
}

export function isAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}
