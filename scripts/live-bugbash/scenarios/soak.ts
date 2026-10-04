import {codexAvailable, realCodexSentinel} from "../lib/codex.ts";
import {runOwnedProcesses} from "../lib/processes.ts";
import {appendProgress} from "../lib/report.ts";
import {SkipScenario, type ScenarioContext, type ScenarioRegistry} from "../lib/scenario.ts";
import {sleep} from "../lib/symphony.ts";
import {cli, endpointArgs} from "./support.ts";

const SENTINEL_TIMEOUT_MS = 180_000;

export interface SoakTotals {
  cycles: number;
  trelloFailures: number;
  boardQueryFailures: number;
  openRunOwnedBoardCycles: number;
  runOwnedProcessCycles: number;
  codexSentinelFailures: number;
  codexSentinel: "run" | "not-run";
}

/**
 * Passive health loop for a finished run: Trello auth through the installed CLI, every registered
 * board closed, no run-owned process alive, and (with --codex real) a one-line Codex sentinel. It
 * stops at --soak-duration, --soak-until, or the run's --time-budget, whichever comes first.
 */
async function passiveSoak(context: ScenarioContext): Promise<string> {
  const {durationMs, until} = context.options.soak;
  if (durationMs === null && until === null) {
    throw new SkipScenario("passive soak needs --soak-duration or --soak-until");
  }
  const soakEnd = until?.getTime() ?? Date.now() + (durationMs ?? 0);
  const end = context.deadline === null ? soakEnd : Math.min(soakEnd, context.deadline);
  const sentinel = context.options.codex === "real" && codexAvailable();
  const totals: SoakTotals = {
    cycles: 0,
    trelloFailures: 0,
    boardQueryFailures: 0,
    openRunOwnedBoardCycles: 0,
    runOwnedProcessCycles: 0,
    codexSentinelFailures: 0,
    codexSentinel: sentinel ? "run" : "not-run",
  };
  const trello = context.trello();
  do {
    totals.cycles += 1;
    const cycle = {trelloAuth: "ok", openBoards: 0, boardQueryFailures: 0, processes: 0, codexSentinel: sentinel ? "ok" : "not-run"};
    const auth = await cli(context, `cycle-${totals.cycles}-list-workspaces`, ["list-workspaces", ...(await endpointArgs(context))]);
    if (auth.status !== 0) {
      totals.trelloFailures += 1;
      cycle.trelloAuth = "failed";
    }
    for (const board of trello.registeredBoards()) {
      try {
        if (!(await trello.boardClosed(board.id))) {
          cycle.openBoards += 1;
        }
      } catch {
        cycle.boardQueryFailures += 1;
      }
    }
    totals.boardQueryFailures += cycle.boardQueryFailures;
    if (cycle.openBoards > 0) {
      totals.openRunOwnedBoardCycles += 1;
    }
    cycle.processes = runOwnedProcesses(context.run.root).length;
    if (cycle.processes > 0) {
      totals.runOwnedProcessCycles += 1;
    }
    if (sentinel) {
      const result = await realCodexSentinel(context.run, `${context.scenario.id}-cycle-${totals.cycles}`, SENTINEL_TIMEOUT_MS);
      if (!result.ok) {
        totals.codexSentinelFailures += 1;
        cycle.codexSentinel = "failed";
      }
    }
    appendProgress(
      context.run,
      `soak cycle ${totals.cycles}: trello auth ${cycle.trelloAuth}, open run-owned boards ${cycle.openBoards}, board query failures ${cycle.boardQueryFailures}, run-owned processes ${cycle.processes}, codex sentinel ${cycle.codexSentinel}`,
    );
    const wait = Math.min(context.options.soak.intervalMs, end - Date.now());
    if (wait > 0) {
      await sleep(wait);
    }
  } while (Date.now() < end);
  context.evidence("soak-summary.json", JSON.stringify(totals, null, 2));
  context.check.equal(totals.trelloFailures, 0, "no cycle had a Trello auth failure");
  context.check.equal(totals.boardQueryFailures, 0, "no board query failed");
  context.check.equal(totals.openRunOwnedBoardCycles, 0, "no cycle found an open run-owned board");
  context.check.equal(totals.runOwnedProcessCycles, 0, "no cycle found a run-owned process");
  context.check.equal(totals.codexSentinelFailures, 0, "no Codex sentinel failed");
  return `${totals.cycles} soak cycle(s): Trello failures ${totals.trelloFailures}, board query failures ${totals.boardQueryFailures}, open run-owned board cycles ${totals.openRunOwnedBoardCycles}, run-owned process cycles ${totals.runOwnedProcessCycles}, Codex sentinel ${totals.codexSentinel} with ${totals.codexSentinelFailures} failure(s)`;
}

export const SOAK_SCENARIOS: ScenarioRegistry = {
  "passive-soak-trello-auth-board-process-codex-sentinel": passiveSoak,
};
