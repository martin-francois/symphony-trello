import {spawn} from "node:child_process";
import {readFileSync} from "node:fs";
import {realCodexSentinel, runScopedCodexHome} from "../lib/codex.ts";
import {runOwnedProcesses} from "../lib/processes.ts";
import type {ScenarioContext, ScenarioRegistry} from "../lib/scenario.ts";
import {sleep, waitFor} from "../lib/symphony.ts";
import {
  addCard,
  cardList,
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
const SENTINEL_TIMEOUT_MS = 180_000;
const REAL_CODEX_CARD_TIMEOUT_MS = 300_000;

interface FailureCase {
  steps: Parameters<typeof script>[2];
  error: RegExp;
}

const FAILURE_CASES: Readonly<Record<string, FailureCase>> = {
  "malformed-app-server-protocol": {steps: [{kind: "malformed-json"}], error: /codex_protocol_error: app-server reader failed/},
  "turn-cancelled-failure-path": {steps: [{kind: "turn-cancelled"}], error: /turn_cancelled/},
  "stalled-codex-worker-cleanup-and-retry": {steps: [{kind: "stall"}], error: /stall/i},
};

interface FailureRun {
  service: ServiceBoard;
  cardId: string;
  error: string;
  listAfterRetry: string | undefined;
}

/** One worker with a card per selected failure case; each card must land in the retry queue. */
async function failureFixture(context: ScenarioContext): Promise<Map<string, FailureRun>> {
  return context.fixture("codex-failures", async () => {
    const cases = Object.entries(FAILURE_CASES).filter(([id]) => context.selected.has(id));
    const service = await serviceBoard(context, "codex-failures", {
      edits: [
        [["agent", "max_concurrent_agents"], 4],
        [["codex", "stall_timeout_ms"], 4_000],
      ],
    });
    const cards = new Map<string, string>();
    for (const [id, entry] of cases) {
      script(service, id, entry.steps);
      cards.set(id, await addCard(context, service, id, QUESTION));
    }
    await startWorker(context, service);
    const runs = new Map<string, FailureRun>();
    try {
      for (const [id] of cases) {
        const cardId = cards.get(id) as string;
        const entry = await waitFor(
          `${id} to enter the retry queue`,
          async () => stateEntries(await state(service), "retrying").find((candidate) => candidate["card_id"] === cardId),
          60_000,
        );
        runs.set(id, {service, cardId, error: String(entry["error"] ?? ""), listAfterRetry: await cardList(context, service, cardId)});
      }
    } finally {
      await stopWorker(context, service);
    }
    return runs;
  });
}

async function failureCase(context: ScenarioContext): Promise<string> {
  const id = context.scenario.id;
  const run = (await failureFixture(context)).get(id);
  const expected = FAILURE_CASES[id];
  if (run === undefined || expected === undefined) {
    throw new Error(`the codex-failures fixture did not run ${id}`);
  }
  context.evidence("retry-error.txt", run.error);
  context.check.that(expected.error.test(run.error), `the state API shows the card retrying with an error matching ${expected.error.source}`);
  if (id === "malformed-app-server-protocol") {
    context.check.equal(run.listAfterRetry, "Ready for Codex", "the card returns to Ready for Codex");
  }
  await sleep(1_000);
  const leftovers = runOwnedProcesses(context.run.root).filter((owned) => commandLine(owned.pid).includes(run.service.codex.scriptDir));
  context.check.equal(leftovers.length, 0, "no fake app-server process remains after the worker stops");
  return `the card entered the retry queue with "${run.error.split(":")[0] ?? ""}" and no app-server process remained`;
}

function commandLine(pid: number): string {
  try {
    return readFileSync(`/proc/${pid}/cmdline`, "utf8").replaceAll("\0", " ");
  } catch {
    return "";
  }
}

async function directSentinel(context: ScenarioContext): Promise<string> {
  const result = await realCodexSentinel(context.run, context.scenario.id, SENTINEL_TIMEOUT_MS);
  context.check.that(result.ok, result.detail);
  return result.detail;
}

async function protocolProbe(context: ScenarioContext): Promise<string> {
  const codexHome = runScopedCodexHome(context.run);
  const child = spawn("codex", ["app-server"], {
    cwd: context.run.dir("workspaces", context.scenario.id),
    env: {...process.env, CODEX_HOME: codexHome},
    stdio: ["pipe", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (chunk: string) => (stdout += chunk));
  child.stderr.setEncoding("utf8").on("data", (chunk: string) => (stderr += chunk));
  const exited = new Promise<number | null>((resolve) => child.on("close", resolve));
  child.stdin.write(`${JSON.stringify({id: 1, method: "initialize", params: {clientInfo: {name: "symphony-bugbash", version: "0"}}})}\n`);
  try {
    const line = await waitFor("the initialize response", () => stdout.split("\n").find((candidate) => candidate.includes("\"id\":1")), 30_000, 100);
    const response = JSON.parse(line) as {id?: number; result?: {userAgent?: string}};
    context.check.equal(response.id, 1, "the response carries the request id");
    context.check.that(typeof response.result?.userAgent === "string", "the response has a userAgent");
  } finally {
    child.stdin.end();
    const timeout = sleep(10_000).then(() => "timeout" as const);
    const result = await Promise.race([exited, timeout]);
    context.check.that(result !== "timeout", "codex app-server exits after stdin closes");
    if (result === "timeout") {
      child.kill("SIGKILL");
    }
    context.evidence("app-server.log", `${stdout}\n--- stderr ---\n${stderr}`);
  }
  return "codex app-server answered initialize with a userAgent and exited when stdin closed";
}

async function installedRealCodexSmoke(context: ScenarioContext): Promise<string> {
  const service = await serviceBoard(context, context.scenario.id, {realCodex: true});
  const cardId = await addCard(context, service, context.scenario.id, "What does HTTP status 418 mean? Answer in one or two sentences as a Trello comment. This is a question only: no repository, code change, or pull request is needed.");
  const outcome = await service.cli.start(service.workflow, service.paths, {CODEX_HOME: runScopedCodexHome(context.run)});
  context.evidence("start.txt", `${outcome.stdout}\n${outcome.stderr}`);
  context.check.exitCode(outcome, 0, "start exits 0");
  try {
    await waitForCardIn(context, service, cardId, "Human Review", REAL_CODEX_CARD_TIMEOUT_MS);
    const comments = await context.trello().comments(cardId);
    context.evidence("comments.txt", comments.join("\n---\n"));
    context.check.that(comments.length > 0, "the card has a Codex workpad or handoff comment");
    await waitFor(
      "the worker to drain",
      async () => {
        const current = await state(service);
        return stateEntries(current, "running").length === 0 && stateEntries(current, "retrying").length === 0;
      },
      60_000,
    );
  } finally {
    await stopWorker(context, service);
  }
  return "real Codex answered a question-only card through the installed service and handed it to Human Review";
}

export const CODEX_ACCESS_SCENARIOS: ScenarioRegistry = {
  ...Object.fromEntries(Object.keys(FAILURE_CASES).map((id) => [id, failureCase])),
  "direct-real-codex-sentinel": directSentinel,
  "direct-codex-app-server-json-rpc-protocol-probe": protocolProbe,
  "installed-real-codex-trello-smoke": installedRealCodexSmoke,
};
