import {existsSync, readdirSync} from "node:fs";
import {join} from "node:path";
import {fakeCodex, type FakeCodex, type FakeCodexStep, writeFakeCodexScript, capturedPrompts} from "../lib/codex.ts";
import {freePort} from "../lib/ports.ts";
import {commandEvidence, ProductFailure, type ScenarioContext} from "../lib/scenario.ts";
import {waitFor, workerState, type CliPaths, type CommandOutcome, type SymphonyCli, type WorkerState} from "../lib/symphony.ts";
import {STANDARD_LISTS, type CreatedBoard} from "../lib/trello.ts";
import {patchWorkflow, readFrontMatter, type WorkflowEdit} from "../lib/workflow.ts";

/** Generous bounds: polling is the behavior under test, and CI-class hosts can be slow. */
export const CARD_TIMEOUT_MS = 90_000;
export const WORKER_START_TIMEOUT_MS = 60_000;
/** Poll interval written into every service workflow so scenarios settle in seconds. */
const POLL_INTERVAL_MS = 1_000;

/** Runs a CLI command and stores the full private transcript as scenario evidence. */
export async function cli(
  context: ScenarioContext,
  name: string,
  args: readonly string[],
  options: {paths?: CliPaths; env?: Record<string, string>; timeoutMs?: number; cwd?: string} = {},
): Promise<CommandOutcome> {
  const symphony = await context.cli();
  const outcome = await symphony.exec(args, options);
  context.evidence(`${name}.txt`, commandEvidence(args, outcome));
  return outcome;
}

export async function endpointArgs(context: ScenarioContext): Promise<string[]> {
  return ["--endpoint", (await context.cli()).endpoint];
}

/** Board names the product creates must start with the run id so cleanup can find them. */
export function runBoardName(context: ScenarioContext, suffix = ""): string {
  return `${context.run.runId}-${context.scenario.id}${suffix}`;
}

/** Registers boards the product itself created (setup-local, new-board) right after the command. */
export async function registerProductBoards(context: ScenarioContext): Promise<number> {
  return context.trello().sweepRunBoards(context.scenario.id);
}

export interface ServiceBoard {
  fixture: string;
  board: CreatedBoard;
  paths: CliPaths;
  workflow: string;
  codex: FakeCodex;
  port: number;
  cli: SymphonyCli;
}

export interface ServiceBoardOptions {
  lists?: readonly string[];
  importArgs?: readonly string[];
  edits?: readonly WorkflowEdit[];
  envFile?: Record<string, string>;
  reviewList?: string;
  sleepMs?: number;
  boardNameSuffix?: string;
  /** Names the board after this scenario id instead of the fixture id, so two fixtures can share a board name. */
  boardScenarioId?: string;
  /** Connect into existing run-scoped paths (one manifest for several boards) instead of fresh ones. */
  paths?: CliPaths;
  /** Use real Codex instead of the fake app-server (service-real-codex rows). */
  realCodex?: boolean;
}

/**
 * Creates a registered disposable board, connects it with import-board, and points the generated
 * workflow at the selected Trello endpoint and at a per-fixture fake Codex app-server.
 */
export async function serviceBoard(context: ScenarioContext, fixture: string, options: ServiceBoardOptions = {}): Promise<ServiceBoard> {
  const symphony = await context.cli();
  const board = await context
    .trello()
    .createBoard(options.boardScenarioId ?? fixture, options.lists ?? STANDARD_LISTS, context.options.trelloWorkspaceId, options.boardNameSuffix ?? "");
  const paths = options.paths ?? symphony.paths(fixture);
  symphony.writeEnvFile(paths, options.envFile ?? {});
  const port = await freePort(paths.manifest);
  const workflow = join(paths.configDir, `${fixture}.WORKFLOW.md`);
  const args = [
    "import-board",
    "--board",
    board.id,
    "--endpoint",
    symphony.endpoint,
    "--no-github",
    "--force",
    "--workflow",
    workflow,
    "--env",
    paths.envFile,
    "--manifest",
    paths.manifest,
    "--workspace-root",
    paths.workspaceRoot,
    "--server-port",
    String(port),
    ...(options.importArgs ?? []),
  ];
  const outcome = await symphony.exec(args, {paths});
  context.run.privateEvidence(fixture, "import-board.txt", commandEvidence(args, outcome));
  if (outcome.status !== 0) {
    throw new ProductFailure(`import-board failed while preparing fixture ${fixture} (exit ${String(outcome.status)})`);
  }
  const codex = fakeCodex(context.run, fixture, {
    ...(options.reviewList === undefined ? {} : {reviewList: options.reviewList}),
    ...(options.sleepMs === undefined ? {} : {sleepMs: options.sleepMs}),
  });
  const edits: WorkflowEdit[] = [
    [["tracker", "endpoint"], symphony.endpoint],
    [["polling", "interval_ms"], POLL_INTERVAL_MS],
    [["codex", "read_timeout_ms"], 15_000],
    ...(options.realCodex === true ? [] : ([[["codex", "command"], codex.command]] as WorkflowEdit[])),
    ...(options.edits ?? []),
  ];
  patchWorkflow(workflow, edits);
  return {fixture, board, paths, workflow, codex, port, cli: symphony};
}

/** Wraps a workflow that setup-local wrote so the service helpers can drive it with fake Codex. */
export async function adoptService(context: ScenarioContext, fixture: string, workflow: string, paths: CliPaths, boardId: string): Promise<ServiceBoard> {
  const symphony = await context.cli();
  const codex = fakeCodex(context.run, fixture);
  patchWorkflow(workflow, [
    [["tracker", "endpoint"], symphony.endpoint],
    [["polling", "interval_ms"], POLL_INTERVAL_MS],
    [["codex", "read_timeout_ms"], 15_000],
    [["codex", "command"], codex.command],
  ]);
  const lists = await context.trello().request<Array<{id: string; name: string}>>("GET", `boards/${boardId}/lists`, {filter: "open"});
  const name = context.trello().registeredBoards().find((board) => board.id === boardId)?.name ?? fixture;
  return {
    fixture,
    board: {id: boardId, name, shortLink: "", url: "", lists: Object.fromEntries(lists.map((list) => [list.name, list.id]))},
    paths,
    workflow,
    codex,
    port: Number((readFrontMatter(workflow)["server"] as {port?: unknown} | undefined)?.port),
    cli: symphony,
  };
}

export function script(service: ServiceBoard, scenarioId: string, steps: readonly FakeCodexStep[]): void {
  writeFakeCodexScript(service.codex, scenarioId, steps);
}

export async function addCard(context: ScenarioContext, service: ServiceBoard, scenarioId: string, desc: string, list = "Ready for Codex", title = "bug bash card"): Promise<string> {
  const card = await context.trello().createCard(service.board, list, `${scenarioId}: ${title}`, desc, scenarioId);
  return card.id;
}

export async function startWorker(context: ScenarioContext, service: ServiceBoard): Promise<void> {
  const outcome = await service.cli.start(service.workflow, service.paths);
  context.run.privateEvidence(service.fixture, `start-${Date.now()}.txt`, commandEvidence(["start", "--workflow", "<workflow>"], outcome));
  if (outcome.status !== 0) {
    throw new ProductFailure(`start failed for fixture ${service.fixture} (exit ${String(outcome.status)})`);
  }
  await waitFor(`the ${service.fixture} worker state endpoint`, () => workerState(service.port), WORKER_START_TIMEOUT_MS);
}

export async function stopWorker(context: ScenarioContext, service: ServiceBoard): Promise<CommandOutcome> {
  const outcome = await service.cli.stop(service.workflow, service.paths);
  context.run.privateEvidence(service.fixture, `stop-${Date.now()}.txt`, commandEvidence(["stop", "--workflow", "<workflow>"], outcome));
  return outcome;
}

export async function cardList(context: ScenarioContext, service: ServiceBoard, cardId: string): Promise<string | undefined> {
  const card = await context.trello().card(cardId);
  return Object.entries(service.board.lists).find(([, id]) => id === card.idList)?.[0];
}

export async function waitForCardIn(context: ScenarioContext, service: ServiceBoard, cardId: string, list: string, timeoutMs = CARD_TIMEOUT_MS): Promise<void> {
  await waitFor(`the card to reach ${list}`, async () => (await cardList(context, service, cardId)) === list, timeoutMs);
}

export async function waitForPrompt(service: ServiceBoard, marker: string, timeoutMs = CARD_TIMEOUT_MS): Promise<string> {
  return waitFor(
    `fake Codex to receive the prompt for ${marker}`,
    () => capturedPrompts(service.codex).find((prompt) => prompt.includes(marker)),
    timeoutMs,
  );
}

export async function state(service: ServiceBoard): Promise<WorkerState> {
  const current = await workerState(service.port);
  if (current === null) {
    throw new ProductFailure(`the ${service.fixture} worker stopped answering /api/v1/state`);
  }
  return current;
}

export function stateEntries(current: WorkerState, key: "running" | "retrying"): Array<Record<string, unknown>> {
  return Array.isArray(current[key]) ? current[key] : [];
}

export function frontMatter(path: string): Record<string, unknown> {
  return readFrontMatter(path);
}

export function filesIn(directory: string): string[] {
  return existsSync(directory) ? readdirSync(directory) : [];
}

/** Lines of the Repository Source Context section of a prompt. */
export function repositorySourceSection(prompt: string): string {
  const start = prompt.lastIndexOf("## Repository Source Context");
  if (start < 0) {
    return "";
  }
  const end = prompt.indexOf("\n## ", start + 1);
  return prompt.slice(start, end < 0 ? undefined : end);
}
