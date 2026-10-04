import {publicFilesBeforeFinalReport, scanPublicFiles} from "../lib/private-context.ts";
import {runOwnedProcesses} from "../lib/processes.ts";
import {SkipScenario, type ScenarioContext, type ScenarioRegistry} from "../lib/scenario.ts";

async function interimBoardCleanup(context: ScenarioContext): Promise<string> {
  const trello = context.trello();
  const board = await trello.createBoard(context.scenario.id, ["Ready for Codex"], context.options.trelloWorkspaceId);
  await trello.archiveBoard(board.id);
  context.check.that(await trello.boardClosed(board.id), "the board is closed after the first archive");
  let secondArchive = true;
  try {
    await trello.archiveBoard(board.id);
  } catch {
    secondArchive = false;
  }
  context.check.that(secondArchive, "archiving the same board again succeeds");
  let refused = false;
  try {
    await trello.archiveBoard("000000000000000000000000");
  } catch (error) {
    refused = error instanceof Error && error.message.includes("did not register");
  }
  context.check.that(refused, "archiving an unregistered board id is refused");
  return "a registered board was archived mid-run, archiving again was safe, and an unregistered id was refused";
}

async function finalBoardCleanup(context: ScenarioContext): Promise<string> {
  const pass = await context.cleanup();
  context.check.equal(pass.boardsStillOpen, 0, "every registered board is closed");
  context.check.equal(pass.boardArchiveFailures, 0, "no archive request failed");
  context.check.equal(pass.runOwnedProcessesRemaining, 0, "no run-owned process remains");
  return `cleanup archived ${pass.boardsArchived} of ${pass.boardsRegistered} registered board(s) and stopped ${pass.workersStopped} worker(s)`;
}

async function finalCleanupVerification(context: ScenarioContext): Promise<string> {
  const pass = await context.cleanup();
  context.check.equal(pass.boardsArchived, 0, "the second pass archives no board");
  context.check.equal(pass.workersStopped, 0, "the second pass stops no worker");
  context.check.equal(pass.boardsStillOpen, 0, "every registered board is still closed");
  context.check.equal(runOwnedProcesses(context.run.root).length, 0, "no run-owned process remains");
  return `a second cleanup pass changed nothing for ${pass.boardsRegistered} registered board(s) and ${pass.workersRegistered} worker(s)`;
}

async function privateContextScan(context: ScenarioContext): Promise<string> {
  context.flushReports();
  const files = publicFilesBeforeFinalReport(context.run);
  const scan = await scanPublicFiles(context.run, files);
  if (scan.status === "unavailable" || scan.status === "not-run") {
    throw new SkipScenario(scan.detail);
  }
  context.check.equal(scan.status, "clean", "scripts/check-private-context finds nothing");
  return scan.detail;
}

export const CLEANUP_SCENARIOS: ScenarioRegistry = {
  "interim-real-trello-board-cleanup": interimBoardCleanup,
  "final-trello-board-cleanup": finalBoardCleanup,
  "final-cleanup-verification": finalCleanupVerification,
  "private-context-scan-public-reports-and-drafts": privateContextScan,
};
