import {spawnSync} from "node:child_process";
import {existsSync, readFileSync, rmSync} from "node:fs";
import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {cleanupRun} from "./lib/cleanup.ts";
import {codexAvailable} from "./lib/codex.ts";
import {FAKE_TRELLO_CREDENTIALS, FakeTrelloApi} from "./lib/fake-trello-api.ts";
import {loadManifest, type Scenario} from "./lib/manifest.ts";
import {parseOptions, USAGE, UsageError, type RunOptions} from "./lib/options.ts";
import {planRun, selectScenarios, type Capabilities, type PlanEntry} from "./lib/plan.ts";
import {
  appendProgress,
  exitCodeFor,
  mergeCleanup,
  writeCleanupSummary,
  writeFinalReport,
  writeLedger,
  writeProgressHeader,
  writeResults,
  type CleanupSummary,
  type RunHeader,
} from "./lib/report.ts";
import {emptyResult, PASS_LIKE, resumedStatus, verdict, type ScenarioResult} from "./lib/results.ts";
import {publicFilesBeforeFinalReport, scanPublicFiles} from "./lib/private-context.ts";
import {PUBLIC_FILES, RunRoot} from "./lib/run-root.ts";
import {Checks, ProductFailure, SkipScenario, type HarnessServices, type ScenarioContext, type SourceInstall} from "./lib/scenario.ts";
import {installFromSource, SymphonyCli, TimeoutError, type TrelloTarget} from "./lib/symphony.ts";
import {REAL_TRELLO_ENDPOINT, TrelloApi, type TrelloCredentials} from "./lib/trello.ts";
import {SCENARIOS} from "./scenarios/index.ts";

export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
export const MANIFEST_PATH = join(REPO_ROOT, "scripts", "live-bugbash", "manifest.yml");
/** Upper bound for one scenario; the longest automated row (a real-Codex card) waits five minutes. */
const SCENARIO_TIMEOUT_MS = 10 * 60_000;

async function main(argv: readonly string[]): Promise<number> {
  let options: RunOptions;
  try {
    options = parseOptions(argv);
  } catch (error) {
    if (error instanceof UsageError) {
      process.stderr.write(`live-bugbash: ${error.message}\n\n${USAGE}`);
      return 2;
    }
    throw error;
  }
  if (options.command === "help") {
    process.stdout.write(USAGE);
    return 0;
  }
  const manifest = loadManifest(MANIFEST_PATH);
  if (options.command === "cleanup-only") {
    return cleanupOnly(options);
  }
  const selected = selectScenarios(manifest, options.selection);
  const automated = new Set(Object.keys(SCENARIOS));
  if (options.command === "list") {
    printList(selected, automated);
    return 0;
  }
  const plan = planRun(selected, options, capabilities(options), automated);
  if (options.command === "dry-run") {
    printPlan(plan, options);
    return 0;
  }
  return execute(plan, options);
}

function printList(scenarios: readonly Scenario[], automated: ReadonlySet<string>): void {
  const rows = scenarios.map((scenario) =>
    [
      scenario.id,
      scenario.family,
      scenario.mode,
      scenario.profiles.join(","),
      scenario.expected + (scenario.linked_issue === null ? "" : ` #${scenario.linked_issue}`),
      automated.has(scenario.id) ? "automated" : "not-yet-automated",
    ].join("\t"),
  );
  process.stdout.write(`id\tfamily\tmode\tprofiles\texpected\tautomation\n${rows.join("\n")}\n`);
  process.stdout.write(`${scenarios.length} scenario(s)\n`);
}

function printPlan(plan: readonly PlanEntry[], options: RunOptions): void {
  process.stdout.write(`Dry run for ${options.runId}; nothing is created.\n`);
  for (const entry of plan) {
    process.stdout.write(`${entry.decision.padEnd(18)} ${entry.scenario.id} (${entry.scenario.mode}): ${entry.reason}\n`);
  }
  const counts = new Map<string, number>();
  for (const entry of plan) {
    counts.set(entry.decision, (counts.get(entry.decision) ?? 0) + 1);
  }
  process.stdout.write(`${[...counts.entries()].map(([decision, count]) => `${decision}=${count}`).join(" ")}\n`);
}

function capabilities(options: RunOptions): Capabilities {
  return {
    codexCli: options.codex === "real" && codexAvailable(),
    ghCli: options.github === "real-sandbox" && spawnSync("gh", ["--version"], {encoding: "utf8"}).status === 0,
    trelloCredentials: options.trello === "real" ? realTrelloCredentials() !== null : true,
  };
}

/** TRELLO_API_KEY and TRELLO_API_TOKEN from the environment, then from the ignored project `.env`. */
export function realTrelloCredentials(): TrelloCredentials | null {
  const fromFile = readDotenv(join(REPO_ROOT, ".env"));
  const key = process.env["TRELLO_API_KEY"] ?? fromFile["TRELLO_API_KEY"];
  const token = process.env["TRELLO_API_TOKEN"] ?? fromFile["TRELLO_API_TOKEN"];
  return key !== undefined && key !== "" && token !== undefined && token !== "" ? {key, token} : null;
}

function readDotenv(path: string): Record<string, string> {
  if (!existsSync(path)) {
    return {};
  }
  const values: Record<string, string> = {};
  for (const line of readFileSync(path, "utf8").split("\n")) {
    const match = /^\s*(?:export\s+)?([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (match?.[1] !== undefined && match[2] !== undefined) {
      values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, "$2");
    }
  }
  return values;
}

function targetCommit(): string {
  return spawnSync("git", ["rev-parse", "HEAD"], {cwd: REPO_ROOT, encoding: "utf8"}).stdout.trim();
}

function worktreeDirty(): boolean {
  return spawnSync("git", ["status", "--porcelain", "--untracked-files=no"], {cwd: REPO_ROOT, encoding: "utf8"}).stdout.trim() !== "";
}

interface TrelloSetup {
  target: TrelloTarget;
  fake: FakeTrelloApi | null;
}

async function trelloSetup(run: RunRoot, options: RunOptions): Promise<TrelloSetup> {
  if (options.trello === "fake") {
    const fake = new FakeTrelloApi({stateFile: run.path("state", "fake-trello.json")});
    const endpoint = await fake.start();
    return {target: {endpoint, credentials: FAKE_TRELLO_CREDENTIALS}, fake};
  }
  const credentials = realTrelloCredentials();
  if (credentials === null) {
    throw new UsageError("--trello real needs TRELLO_API_KEY and TRELLO_API_TOKEN in the environment or .env");
  }
  return {target: {endpoint: REAL_TRELLO_ENDPOINT, credentials}, fake: null};
}

async function execute(plan: readonly PlanEntry[], options: RunOptions): Promise<number> {
  const run = new RunRoot(REPO_ROOT, options.runId);
  if (options.resume && !run.exists) {
    process.stderr.write(`live-bugbash: no earlier run ${options.runId} to resume\n`);
    return 2;
  }
  if (!options.resume && run.exists) {
    process.stderr.write(`live-bugbash: run ${options.runId} already exists; use --resume to continue it\n`);
    return 2;
  }
  if (options.trello === "real" && realTrelloCredentials() === null) {
    process.stderr.write("live-bugbash: --trello real needs TRELLO_API_KEY and TRELLO_API_TOKEN in the environment or .env\n");
    return 2;
  }
  run.create();
  const commit = targetCommit();
  const previous = options.resume ? previousResults(run) : new Map<string, ScenarioResult>();
  const trello = await trelloSetup(run, options);
  const trelloApi = new TrelloApi(run, trello.target.endpoint, trello.target.credentials);
  if (options.trello === "real") {
    try {
      run.privateEvidence("run", "preexisting-trello-boards.json", JSON.stringify(await trelloApi.visibleBoards(), null, 2));
    } catch (error) {
      process.stderr.write(`live-bugbash: the real Trello API rejected the credentials or was unreachable: ${error instanceof Error ? error.message : String(error)}\n`);
      if (!options.resume) {
        rmSync(run.root, {recursive: true, force: true});
      }
      return 2;
    }
  }

  let sourceInstall: Promise<SourceInstall> | null = null;
  let cliPromise: Promise<SymphonyCli> | null = null;
  const fixtures = new Map<string, Promise<unknown>>();
  const results: ScenarioResult[] = [];
  const header: RunHeader = {
    runId: options.runId,
    targetCommit: commit.slice(0, 12),
    startedAt: new Date().toISOString(),
    options,
    cliSource: options.symphonyCommand === null ? `source install of ${commit.slice(0, 12)}` : "--symphony-command",
  };
  let lastCleanup: CleanupSummary | null = null;
  const deadline = options.timeBudgetMs === null ? null : Date.now() + options.timeBudgetMs;

  const services: HarnessServices = {
    run,
    options,
    targetCommit: commit,
    deadline,
    fakeTrello: trello.fake,
    sourceInstall() {
      if (options.symphonyCommand !== null) {
        return null;
      }
      sourceInstall ??= installFromSource(run, commit);
      return sourceInstall;
    },
    cli() {
      cliPromise ??= (async () => {
        if (options.symphonyCommand !== null) {
          return new SymphonyCli(run, resolve(options.symphonyCommand), trello.target);
        }
        const installed = await services.sourceInstall();
        if (installed === null) {
          throw new Error("no source install and no --symphony-command");
        }
        if (installed.outcome.status !== 0) {
          throw new Error("the source install failed, so no symphony-trello command is available");
        }
        return new SymphonyCli(run, installed.command, trello.target);
      })();
      return cliPromise;
    },
    trello: () => trelloApi,
    fixture<T>(id: string, setup: () => Promise<T>): Promise<T> {
      if (!fixtures.has(id)) {
        fixtures.set(id, setup());
      }
      return fixtures.get(id) as Promise<T>;
    },
    async cleanup() {
      const cli = cliPromise === null ? null : await cliPromise.catch(() => null);
      const pass = await cleanupRun(run, cli, trelloApi);
      lastCleanup = mergeCleanup(lastCleanup, pass);
      writeCleanupSummary(run, lastCleanup);
      return pass;
    },
    flushReports() {
      writeLedger(run, results);
      writeResults(run, results);
    },
  };

  writeProgressHeader(run, header);
  if (worktreeDirty()) {
    appendProgress(run, "The checkout has uncommitted changes; a source install builds the committed HEAD only.");
  }
  appendProgress(run, `Selected ${plan.length} scenario(s).`);

  let interrupted = false;
  let stopReason = "all selected scenarios finished";
  let abortCurrent: (() => void) | null = null;
  const onSignal = () => {
    if (interrupted) {
      process.exit(130);
    }
    interrupted = true;
    stopReason = "interrupted by a signal";
    abortCurrent?.();
  };
  process.on("SIGINT", onSignal);
  process.on("SIGTERM", onSignal);
  const selectedIds = new Set(plan.filter((entry) => entry.decision === "run").map((entry) => entry.scenario.id));

  try {
    for (const entry of plan) {
      const earlier = previous.get(entry.scenario.id);
      if (earlier !== undefined && (PASS_LIKE.has(earlier.status) || earlier.status === "known-bug")) {
        results.push(earlier);
        continue;
      }
      if (deadline !== null && Date.now() >= deadline && !interrupted) {
        interrupted = true;
        stopReason = "time budget exhausted";
      }
      let result: ScenarioResult;
      if (interrupted) {
        result = emptyResult(entry.scenario, "interrupted", `not started: ${stopReason}`, modeTags(entry.scenario, options));
      } else if (entry.decision === "skip") {
        result = emptyResult(entry.scenario, "skipped", entry.reason, modeTags(entry.scenario, options));
      } else if (entry.decision === "not-yet-automated") {
        result = emptyResult(entry.scenario, "not-yet-automated", "not automated yet; cover it manually with the live-bugbash skill", modeTags(entry.scenario, options));
      } else {
        appendProgress(run, `start ${entry.scenario.id}`);
        const limitMs = Math.min(SCENARIO_TIMEOUT_MS, deadline === null ? SCENARIO_TIMEOUT_MS : Math.max(0, deadline - Date.now()));
        const stopped = new Promise<string>((resolve) => {
          const timer = setTimeout(() => resolve(`did not finish within ${Math.round(limitMs / 1000)}s`), limitMs);
          abortCurrent = () => {
            clearTimeout(timer);
            resolve("interrupted by a signal");
          };
        });
        const outcome = await Promise.race([runScenario(services, entry.scenario, selectedIds), stopped]);
        abortCurrent = null;
        if (typeof outcome === "string") {
          // The abandoned scenario keeps running in the background and may still touch shared
          // fixtures, so no further scenario starts. Cleanup stops its processes, and the process
          // exits after the reports are written.
          result = emptyResult(entry.scenario, "interrupted", `stopped while running: ${outcome}`, modeTags(entry.scenario, options));
          if (!interrupted) {
            interrupted = true;
            stopReason =
              deadline !== null && Date.now() >= deadline
                ? "time budget exhausted"
                : `${entry.scenario.id} did not finish; run --cleanup-only ${options.runId} once it has stopped`;
          }
        } else {
          result = guardUnregisteredBoards(run, trello.fake, trelloApi, outcome);
          if (interrupted) {
            result = {...result, status: "interrupted", summary: `interrupted while running: ${result.summary}`};
          }
        }
      }
      result.status = resumedStatus(earlier?.status, result.status);
      results.push(result);
      if (entry.decision === "run") {
        appendProgress(run, `${result.status} ${entry.scenario.id}`);
      }
      services.flushReports();
    }
  } finally {
    process.off("SIGINT", onSignal);
    process.off("SIGTERM", onSignal);
    await services.cleanup();
    appendProgress(run, "final cleanup finished");
    services.flushReports();
    await trello.fake?.stop();
  }

  const scan = await scanPublicFiles(run, publicFilesBeforeFinalReport(run));
  writeFinalReport(run, header, results, stopReason, lastCleanup, scan);
  const finalScan = await scanPublicFiles(run, [PUBLIC_FILES.finalReport]);
  const exitCode = Math.max(exitCodeFor(results, lastCleanup), scan.status === "findings" || finalScan.status === "findings" ? 1 : 0);
  process.stdout.write(
    `${run.publicText(readFileSync(run.path(PUBLIC_FILES.finalReport), "utf8").split("\n## Findings")[0] ?? "")}\n` +
      `Final report private-context scan: ${finalScan.status}\nReports: target/live-bugbash/${options.runId}/\n`,
  );
  return exitCode;
}

async function runScenario(
  services: HarnessServices,
  scenario: Scenario,
  selected: ReadonlySet<string>,
): Promise<ScenarioResult> {
  const startedAt = new Date().toISOString();
  const check = new Checks();
  const caveats: string[] = [];
  const context: ScenarioContext = {
    ...services,
    scenario,
    check,
    selected,
    evidence: (name, content) => services.run.privateEvidence(scenario.id, name, content),
    caveat: (text) => caveats.push(text),
  };
  const runner = SCENARIOS[scenario.id];
  let summary: string;
  let status: ScenarioResult["status"];
  try {
    if (runner === undefined) {
      throw new Error(`no automation registered for ${scenario.id}`);
    }
    summary = await runner(context);
    const outcome = verdict(scenario.expected, scenario.linked_issue, check.failures, caveats.length === 0 ? null : caveats.join("; "));
    status = outcome.status;
    summary = outcome.note === null ? summary : `${summary} (${outcome.note})`;
    if (outcome.status === "failed") {
      summary = `${summary}; failed: ${check.failures.join("; ")}`;
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (error instanceof SkipScenario) {
      status = "skipped";
      summary = message;
    } else if (error instanceof ProductFailure || error instanceof TimeoutError) {
      // A known bug reproduces through its assertions. A product failure that stops the scenario
      // before them is a new finding even on a known-bug row.
      check.failures.push(message);
      status = "failed";
      summary = message;
    } else {
      status = "harness-invalid";
      summary = `harness error: ${message}`;
      services.run.privateEvidence(scenario.id, "harness-error.txt", error instanceof Error ? (error.stack ?? message) : message);
    }
  }
  return {
    id: scenario.id,
    family: scenario.family,
    mode: scenario.mode,
    status,
    summary: services.run.publicText(summary),
    modeTags: modeTags(scenario, services.options),
    linkedIssue: scenario.linked_issue,
    harnessOnly: scenario.harness_only,
    failedAssertions: check.failures.map((failure) => services.run.publicText(failure)),
    startedAt,
    finishedAt: new Date().toISOString(),
  };
}

/**
 * Against the fake Trello API every board is visible, so a rehearsal can prove that a scenario
 * registered everything it created. A leak marks the row harness-invalid and registers the board so
 * cleanup still archives it; fixing the scenario before a real run keeps real boards accounted for.
 */
function guardUnregisteredBoards(run: RunRoot, fake: FakeTrelloApi | null, trello: TrelloApi, result: ScenarioResult): ScenarioResult {
  if (fake === null) {
    return result;
  }
  const registered = new Set(trello.registeredBoards().map((board) => board.id));
  const leaked = Object.values(fake.state.boards).filter((board) => !registered.has(board.id));
  if (leaked.length === 0) {
    return result;
  }
  for (const board of leaked) {
    run.register("trelloBoards", {id: board.id, name: board.name, scenario: result.id, url: board.url});
  }
  return {
    ...result,
    status: "harness-invalid",
    summary: `${result.summary}; the scenario created ${leaked.length} Trello board(s) without registering them`,
  };
}

export function modeTags(scenario: Scenario, options: RunOptions): string[] {
  const tags = [`mode:${scenario.mode}`];
  if (scenario.requires.trello) {
    tags.push(`trello:${options.trello}`);
  }
  if (scenario.requires.codex !== false) {
    tags.push(`codex:${scenario.requires.codex}`);
  }
  if (scenario.requires.github) {
    tags.push(`github:${options.github}`);
  }
  tags.push(`host:${options.hostProfile}`);
  if (scenario.linked_issue !== null) {
    tags.push(`issue:#${scenario.linked_issue}`);
  }
  return tags;
}

function previousResults(run: RunRoot): Map<string, ScenarioResult> {
  const path = run.path("state", "results.json");
  if (!existsSync(path)) {
    return new Map();
  }
  const results = JSON.parse(readFileSync(path, "utf8")) as ScenarioResult[];
  return new Map(results.map((result) => [result.id, result]));
}

async function cleanupOnly(options: RunOptions): Promise<number> {
  const run = new RunRoot(REPO_ROOT, options.runId);
  if (!run.exists) {
    process.stderr.write(`live-bugbash: no run ${options.runId} to clean up\n`);
    return 2;
  }
  const trello = await trelloSetup(run, options);
  const trelloApi = new TrelloApi(run, trello.target.endpoint, trello.target.credentials);
  const installed = run.path("install", "bin", "symphony-trello");
  const command = options.symphonyCommand ?? (existsSync(installed) ? installed : null);
  const cli = command === null ? null : new SymphonyCli(run, resolve(command), trello.target);
  try {
    const summary = await cleanupRun(run, cli, trelloApi);
    writeCleanupSummary(run, summary);
    process.stdout.write(readFileSync(run.path(PUBLIC_FILES.cleanupSummary), "utf8"));
    return exitCodeFor([], summary);
  } finally {
    await trello.fake?.stop();
  }
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(
    (code) => process.exit(code),
    (error: unknown) => {
      process.stderr.write(`live-bugbash: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}\n`);
      process.exit(2);
    },
  );
}
