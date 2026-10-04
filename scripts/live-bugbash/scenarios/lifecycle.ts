import {existsSync, readFileSync, writeFileSync} from "node:fs";
import {join} from "node:path";
import {runOwnedProcesses, isAlive} from "../lib/processes.ts";
import {outputOf, ProductFailure, type ScenarioContext, type ScenarioRegistry} from "../lib/scenario.ts";
import {sleep, waitFor, workerState, type CliPaths} from "../lib/symphony.ts";
import {
  addCard,
  cli,
  filesIn,
  script,
  serviceBoard,
  startWorker,
  state,
  stateEntries,
  stopWorker,
  waitForCardIn,
  type ServiceBoard,
} from "./support.ts";

const QUESTION = "Answer with one sentence. No repository is needed.";

interface Pair {
  paths: CliPaths;
  first: ServiceBoard;
  second: ServiceBoard;
}

/** Two boards connected into one config dir, so start --all and stop act on both. */
async function pair(context: ScenarioContext): Promise<Pair> {
  return context.fixture("lifecycle-pair", async () => {
    const first = await serviceBoard(context, "lifecycle-pair-a");
    const second = await serviceBoard(context, "lifecycle-pair-b", {paths: first.paths});
    return {paths: first.paths, first, second};
  });
}

async function single(context: ScenarioContext): Promise<ServiceBoard> {
  return context.fixture("lifecycle-single", () => serviceBoard(context, "lifecycle-single"));
}

async function lifecycle(context: ScenarioContext, name: string, args: readonly string[], paths: CliPaths) {
  return cli(context, name, [...args, "--config-dir", paths.configDir, "--state-home", paths.stateHome], {paths});
}

async function startAll(context: ScenarioContext, current: Pair): Promise<void> {
  for (const service of [current.first, current.second]) {
    context.run.register("workers", {workflow: service.workflow, configDir: current.paths.configDir, stateHome: current.paths.stateHome});
  }
  const outcome = await lifecycle(context, "start-all", ["start", "--all"], current.paths);
  context.check.exitCode(outcome, 0, "start --all exits 0");
  for (const service of [current.first, current.second]) {
    await waitFor(`the ${service.fixture} state endpoint`, () => workerState(service.port), 60_000);
  }
}

async function stopAll(context: ScenarioContext, current: Pair) {
  return lifecycle(context, "stop-all", ["stop"], current.paths);
}

function statusLine(output: string, boardName: string): string {
  return output.split("\n").find((line) => line.includes(`"${boardName}"`)) ?? "";
}

async function startAllStopAll(context: ScenarioContext): Promise<string> {
  const current = await pair(context);
  await startAll(context, current);
  const stopped = await stopAll(context, current);
  context.check.exitCode(stopped, 0, "stop exits 0");
  const status = await lifecycle(context, "status", ["status"], current.paths);
  for (const service of [current.first, current.second]) {
    context.check.that(statusLine(status.stdout, service.board.name).startsWith("stopped"), `status reports ${service.fixture} stopped`);
    context.check.equal(await workerState(service.port), null, `the ${service.fixture} state endpoint is closed`);
  }
  const leftovers = runOwnedProcesses(context.run.root).filter((owned) => owned.reason === "command line");
  context.check.equal(leftovers.length, 0, "no run-owned worker process remains");
  return "start --all started both workers; stop drained both and left no process";
}

async function startAllStopOne(context: ScenarioContext): Promise<string> {
  const current = await pair(context);
  await startAll(context, current);
  try {
    const stopOne = await lifecycle(context, "stop-one", ["stop", "--board", current.first.board.name], current.paths);
    context.check.exitCode(stopOne, 0, "stop --board exits 0");
    const status = await lifecycle(context, "status", ["status"], current.paths);
    context.check.that(statusLine(status.stdout, current.first.board.name).startsWith("stopped"), "the selected board is stopped");
    context.check.that(statusLine(status.stdout, current.second.board.name).startsWith("running"), "the sibling board keeps running");
    const cardId = await addCard(context, current.second, context.scenario.id, QUESTION);
    await waitForCardIn(context, current.second, cardId, "Human Review");
  } finally {
    await stopAll(context, current);
  }
  return "stopping one board left the sibling running, and the sibling finished a new card";
}

async function startAllTwoWorkflows(context: ScenarioContext): Promise<string> {
  const current = await pair(context);
  await startAll(context, current);
  try {
    const cards = [
      {service: current.first, id: await addCard(context, current.first, context.scenario.id, QUESTION, "Ready for Codex", "first board")},
      {service: current.second, id: await addCard(context, current.second, context.scenario.id, QUESTION, "Ready for Codex", "second board")},
    ];
    for (const card of cards) {
      await waitForCardIn(context, card.service, card.id, "Human Review");
      const local = await fetch(`http://127.0.0.1:${card.service.port}/api/v1/local-status`).then((response) => response.json() as Promise<Record<string, unknown>>);
      context.check.that(
        local["configuredBoardId"] === card.service.board.id || local["configuredBoardId"] === card.service.board.shortLink || local["boardId"] === card.service.board.id,
        `the ${card.service.fixture} worker reports its own board`,
      );
    }
  } finally {
    await stopAll(context, current);
  }
  return "two workflows started together each finished their own card";
}

async function idempotentStart(context: ScenarioContext): Promise<string> {
  const service = await single(context);
  await startWorker(context, service);
  try {
    const before = pidOf(service);
    const again = await service.cli.start(service.workflow, service.paths);
    context.evidence("second-start.txt", outputOf(again));
    context.check.exitCode(again, 0, "the second start exits 0");
    context.check.contains(outputOf(again), "already running", "the second start says the worker is already running");
    context.check.equal(pidOf(service), before, "the worker pid does not change");
  } finally {
    await stopWorker(context, service);
  }
  return "starting a running workflow again was a no-op";
}

function pidFile(service: ServiceBoard): string | undefined {
  const prefix = `${service.workflow.split("/").pop() ?? ""}.`;
  const file = filesIn(service.paths.stateHome).find((name) => name.startsWith(prefix) && name.endsWith(".pid"));
  return file === undefined ? undefined : join(service.paths.stateHome, file);
}

function pidOf(service: ServiceBoard): number | null {
  const file = pidFile(service);
  return file === undefined || !existsSync(file) ? null : Number(readFileSync(file, "utf8").trim());
}

async function managedLifecycle(context: ScenarioContext): Promise<string> {
  const service = await single(context);
  const args = (command: string) => [command, "--workflow", service.workflow];
  await startWorker(context, service);
  try {
    const running = await lifecycle(context, "status-running", args("status"), service.paths);
    context.check.that(running.stdout.trim().startsWith("running"), "status reports running after start");
    const pid = pidOf(service);
    await service.cli.start(service.workflow, service.paths);
    context.check.equal(pidOf(service), pid, "a duplicate start does not create a second process");
  } finally {
    const stop = await stopWorker(context, service);
    context.check.exitCode(stop, 0, "stop exits 0");
  }
  const stopped = await lifecycle(context, "status-stopped", args("status"), service.paths);
  context.check.that(stopped.stdout.trim().startsWith("stopped"), "status reports stopped after stop");
  return "start, status, duplicate start, stop, and status agreed";
}

async function logsForStoppedWorker(context: ScenarioContext): Promise<string> {
  const service = await single(context);
  await startWorker(context, service);
  await stopWorker(context, service);
  const logs = await lifecycle(context, "logs", ["logs", "--workflow", service.workflow], service.paths);
  context.check.exitCode(logs, 0, "logs exits 0 for a stopped worker");
  context.check.contains(logs.stdout, "started in", "logs prints the worker startup line");
  context.check.contains(logs.stdout, "stopped in", "logs prints the worker shutdown line");
  return "logs printed the stopped worker's startup and shutdown lines";
}

async function missingWorkflow(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const missing = join(paths.configDir, "missing.WORKFLOW.md");
  const before = [...filesIn(paths.configDir), ...filesIn(paths.stateHome)].length;
  for (const command of ["status", "logs", "stop"]) {
    const outcome = await lifecycle(context, command, [command, "--workflow", missing], paths);
    context.check.failedExit(outcome, `${command} fails for a missing workflow`);
    context.check.contains(outputOf(outcome), "setup_failed code=setup_invalid_arguments", `${command} reports setup_invalid_arguments`);
  }
  context.check.equal([...filesIn(paths.configDir), ...filesIn(paths.stateHome)].length, before, "no file is created in the config dir or state home");
  return "status, logs, and stop rejected the missing workflow with setup_invalid_arguments and created nothing";
}

async function stalePid(context: ScenarioContext): Promise<string> {
  const service = await single(context);
  await startWorker(context, service);
  const pid = pidOf(service);
  if (pid === null || !runOwnedProcesses(context.run.root).some((owned) => owned.pid === pid)) {
    await stopWorker(context, service);
    throw new ProductFailure("the started worker has no run-owned pid file");
  }
  process.kill(pid, "SIGKILL");
  await waitFor("the killed worker to exit", () => !isAlive(pid), 10_000);
  const status = await lifecycle(context, "status-after-kill", ["status", "--workflow", service.workflow], service.paths);
  context.check.that(status.stdout.trim().startsWith("stopped"), "status reports the killed worker stopped");
  try {
    await startWorker(context, service);
    context.check.that(pidOf(service) !== pid, "the restarted worker has a new pid");
  } finally {
    await stopWorker(context, service);
  }
  return "after SIGKILL, status reported stopped and start brought the worker back";
}

async function boardUrlSelector(context: ScenarioContext): Promise<string> {
  const service = await single(context);
  await startWorker(context, service);
  try {
    const status = await lifecycle(context, "status-by-url", ["status", "--board", service.board.url], service.paths);
    context.check.exitCode(status, 0, "status --board <url> exits 0");
    const lines = status.stdout.trim().split("\n").filter((line) => line.trim() !== "");
    context.check.that(lines.length === 1 && lines[0]?.startsWith("running") === true, "status reports exactly the selected board as running");
  } finally {
    await stopWorker(context, service);
  }
  return "status accepted the board URL selector";
}

async function duplicateBoardName(context: ScenarioContext): Promise<string> {
  const id = context.scenario.id;
  const first = await serviceBoard(context, `${id}-first`, {boardScenarioId: id, boardNameSuffix: "-shared"});
  await serviceBoard(context, `${id}-second`, {boardScenarioId: id, boardNameSuffix: "-shared", paths: first.paths});
  const start = await lifecycle(context, "start-ambiguous", ["start", "--board", first.board.name], first.paths);
  context.check.failedExit(start, "start fails for an ambiguous board name");
  context.check.that(/setup_failed code=\S*ambiguous/.test(outputOf(start)), "the failure code names the ambiguous selector");
  context.check.that(!filesIn(first.paths.stateHome).some((file) => file.endsWith(".pid")), "no pid file is created");
  return `start --board with a shared name failed with ${/setup_failed code=(\S+)/.exec(outputOf(start))?.[1] ?? "no code"} and started nothing`;
}

async function unicodeBoardName(context: ScenarioContext): Promise<string> {
  const service = await serviceBoard(context, context.scenario.id, {boardNameSuffix: "-ünïcødé ✓"});
  const byName = (command: string) => [command, "--board", service.board.name];
  context.run.register("workers", {workflow: service.workflow, configDir: service.paths.configDir, stateHome: service.paths.stateHome});
  const start = await lifecycle(context, "start", byName("start"), service.paths);
  context.check.exitCode(start, 0, "start --board <unicode name> exits 0");
  const status = await lifecycle(context, "status", byName("status"), service.paths);
  context.check.that(status.status === 0 && status.stdout.trim().startsWith("running"), "status --board <unicode name> reports running");
  const stop = await lifecycle(context, "stop", byName("stop"), service.paths);
  context.check.exitCode(stop, 0, "stop --board <unicode name> exits 0");
  return "start, status, and stop accepted a Unicode board-name selector";
}

async function invalidReload(context: ScenarioContext): Promise<string> {
  const service = await single(context);
  const original = readFileSync(service.workflow, "utf8");
  await startWorker(context, service);
  try {
    writeFileSync(service.workflow, original.replace(/^---\n/, "---\ntracker: [unclosed\n"));
    await sleep(4_000);
    context.check.that((await workerState(service.port)) !== null, "the worker keeps answering /api/v1/state after the bad edit");
    const cardId = await addCard(context, service, context.scenario.id, QUESTION);
    await waitForCardIn(context, service, cardId, "Human Review");
    const logs = filesIn(service.paths.stateHome)
      .filter((file) => file.endsWith(".log"))
      .map((file) => readFileSync(join(service.paths.stateHome, file), "utf8"))
      .join("\n");
    context.evidence("worker.log", logs);
    context.check.contains(logs, "outcome=reload_failed", "the worker log records the rejected reload");
  } finally {
    writeFileSync(service.workflow, original);
    await stopWorker(context, service);
  }
  return "an invalid edit was rejected, the last known good config kept serving, and a new card still finished";
}

async function telemetry(context: ScenarioContext): Promise<string> {
  const service = await single(context);
  script(service, context.scenario.id, [{kind: "telemetry"}, {kind: "handoff"}]);
  await startWorker(context, service);
  try {
    const cardId = await addCard(context, service, context.scenario.id, QUESTION);
    await waitForCardIn(context, service, cardId, "Human Review");
    const current = await state(service);
    context.evidence("state.json", JSON.stringify(current, null, 2));
    const totals = current["codex_totals"] as Record<string, number> | undefined;
    context.check.that((totals?.["total_tokens"] ?? 0) >= 150, "codex_totals counts the reported tokens");
    context.check.that(JSON.stringify(current["rate_limits"] ?? null).includes("42"), "rate_limits shows the reported primary window");
  } finally {
    await stopWorker(context, service);
  }
  return "token usage and rate-limit telemetry from fake Codex appeared in /api/v1/state";
}

async function externalMove(context: ScenarioContext, destination: string): Promise<string> {
  const service = await serviceBoard(context, context.scenario.id);
  script(service, context.scenario.id, [{kind: "stall"}]);
  await startWorker(context, service);
  try {
    const cardId = await addCard(context, service, context.scenario.id, QUESTION);
    await waitFor("the card to run", async () => stateEntries(await state(service), "running").some((entry) => entry["card_id"] === cardId), 60_000);
    await context.trello().moveCard(service.board, cardId, destination);
    await waitFor(
      "the worker to drop the moved card",
      async () => !stateEntries(await state(service), "running").some((entry) => entry["card_id"] === cardId),
      30_000,
    );
    await sleep(3_000);
    const current = await state(service);
    context.check.that(!stateEntries(current, "retrying").some((entry) => entry["card_id"] === cardId), "the moved card is not retried");
    const fakes = runOwnedProcesses(context.run.root).filter((owned) => owned.reason === "command line" && commandLine(owned.pid).includes(service.codex.scriptDir));
    context.check.equal(fakes.length, 0, "no fake app-server process for the card remains");
  } finally {
    await stopWorker(context, service);
  }
  return `moving a running card to ${destination} stopped its agent without a retry or leftover app-server`;
}

function commandLine(pid: number): string {
  try {
    return readFileSync(`/proc/${pid}/cmdline`, "utf8").replaceAll("\0", " ");
  } catch {
    return "";
  }
}

export const LIFECYCLE_SCENARIOS: ScenarioRegistry = {
  "start-all-stop-all": startAllStopAll,
  "start-all-stop-one-board": startAllStopOne,
  "start-all-two-workflows": startAllTwoWorkflows,
  "start-running-workflow-idempotent": idempotentStart,
  "managed-lifecycle-start-status-duplicate-start-stop": managedLifecycle,
  "managed-lifecycle-logs-for-stopped-worker": logsForStoppedWorker,
  "lifecycle-commands-missing-workflow": missingWorkflow,
  "stale-pid-restart-recovery": stalePid,
  "board-url-selector-live": boardUrlSelector,
  "duplicate-board-name-selector": duplicateBoardName,
  "board-name-selector-unicode": unicodeBoardName,
  "invalid-workflow-reload-keeps-last-known-good": invalidReload,
  "state-api-codex-usage-and-rate-limit-telemetry": telemetry,
  "external-terminal-move-while-worker-running": (context) => externalMove(context, "Done"),
  "external-non-active-move-while-worker-running": (context) => externalMove(context, "Human Review"),
};
