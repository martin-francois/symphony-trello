import {existsSync, rmSync} from "node:fs";
import {sep} from "node:path";
import {runCodexAuthChanged} from "./codex.ts";
import {isAlive, runOwnedProcesses} from "./processes.ts";
import type {CleanupSummary} from "./report.ts";
import type {RunRoot} from "./run-root.ts";
import {sleep, type CliPaths, type SymphonyCli} from "./symphony.ts";
import type {TrelloApi} from "./trello.ts";

interface RegisteredWorker {
  workflow: string;
  configDir: string;
  stateHome: string;
}

const PROCESS_EXIT_GRACE_MS = 10_000;

/**
 * Stops every registered worker, terminates any process that still belongs to the run, archives
 * every registered Trello board, and deletes registered sensitive copies. Each step acts only on
 * registered or run-root-owned targets, and the whole routine is safe to run again.
 */
export async function cleanupRun(run: RunRoot, cli: SymphonyCli | null, trello: TrelloApi | null): Promise<CleanupSummary> {
  const notes: string[] = [];
  const workers = uniqueWorkers(run.entries<RegisteredWorker>("workers"));
  let workersStopped = 0;
  if (cli !== null) {
    for (const worker of workers) {
      if (!existsSync(worker.workflow)) {
        continue;
      }
      const paths: CliPaths = {...cli.defaultPaths, configDir: worker.configDir, stateHome: worker.stateHome};
      const outcome = await cli.stop(worker.workflow, paths);
      if (outcome.status === 0 && !/already stopped/i.test(outcome.stdout)) {
        workersStopped += 1;
      }
    }
  } else if (workers.length > 0) {
    notes.push("No symphony-trello command was available, so registered workers were not stopped through the CLI.");
  }

  const afterStop = await settledProcesses(run.root);
  if (afterStop.length > 0) {
    notes.push(`${afterStop.length} run-owned process(es) were still alive after stopping workers and were terminated.`);
    for (const owned of afterStop) {
      signal(owned.pid, "SIGTERM");
    }
    await waitForExit(afterStop.map((owned) => owned.pid));
    for (const owned of afterStop.filter((candidate) => isAlive(candidate.pid))) {
      signal(owned.pid, "SIGKILL");
    }
  }
  const remaining = runOwnedProcesses(run.root);

  if (trello !== null) {
    try {
      const swept = await trello.sweepRunBoards("cleanup");
      if (swept > 0) {
        notes.push(`${swept} run-named board(s) were missing from the registry and were registered by cleanup.`);
      }
    } catch {
      notes.push("Cleanup could not list visible boards to look for unregistered run boards.");
    }
  }
  const boards = trello?.registeredBoards() ?? run.entries<{id: string}>("trelloBoards");
  let boardsArchived = 0;
  let boardsStillOpen = 0;
  let boardArchiveFailures = 0;
  if (trello === null && boards.length > 0) {
    notes.push("No Trello client was available, so registered boards could not be archived.");
    boardsStillOpen = boards.length;
  }
  if (trello !== null) {
    for (const board of uniqueById(boards)) {
      try {
        if (!(await trello.boardClosed(board.id))) {
          await trello.archiveBoard(board.id);
          boardsArchived += 1;
        }
        if (!(await trello.boardClosed(board.id))) {
          boardsStillOpen += 1;
        }
      } catch {
        boardArchiveFailures += 1;
      }
    }
  }

  if (runCodexAuthChanged(run)) {
    notes.push("Codex rewrote its login inside the run copy, for example after a token refresh. If the normal Codex login stops working, run codex login again.");
  }
  let sensitivePathsDeleted = 0;
  for (const path of new Set(run.lines("sensitivePaths"))) {
    if (!path.startsWith(run.root + sep)) {
      notes.push("A registered sensitive path outside the run root was left untouched.");
      continue;
    }
    if (existsSync(path)) {
      rmSync(path, {recursive: true, force: true});
      sensitivePathsDeleted += 1;
    }
  }

  return {
    boardsRegistered: uniqueById(boards).length,
    boardsArchived,
    boardsStillOpen,
    boardArchiveFailures,
    workersRegistered: workers.length,
    workersStopped,
    runOwnedProcessesRemaining: remaining.length,
    sensitivePathsDeleted,
    githubSandboxReposLeft: run.entries("githubSandboxRepos").length,
    notes,
  };
}

/** Workers exit asynchronously after `stop`; give them a bounded grace period before counting. */
async function settledProcesses(runRoot: string) {
  const deadline = Date.now() + PROCESS_EXIT_GRACE_MS;
  let owned = runOwnedProcesses(runRoot);
  while (owned.length > 0 && Date.now() < deadline) {
    await sleep(500);
    owned = runOwnedProcesses(runRoot);
  }
  return owned;
}

async function waitForExit(pids: readonly number[]): Promise<void> {
  const deadline = Date.now() + PROCESS_EXIT_GRACE_MS;
  while (pids.some(isAlive) && Date.now() < deadline) {
    await sleep(250);
  }
}

function signal(pid: number, name: NodeJS.Signals): void {
  try {
    process.kill(pid, name);
  } catch {
    // Already gone.
  }
}

function uniqueWorkers(workers: readonly RegisteredWorker[]): RegisteredWorker[] {
  const seen = new Map<string, RegisteredWorker>();
  for (const worker of workers) {
    seen.set(`${worker.workflow}\0${worker.stateHome}`, worker);
  }
  return [...seen.values()];
}

function uniqueById<T extends {id: string}>(entries: readonly T[]): T[] {
  return [...new Map(entries.map((entry) => [entry.id, entry])).values()];
}
