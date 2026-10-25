import {mkdtempSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {cleanupRun} from "../lib/cleanup.ts";
import {FakeTrelloApi, FAKE_TRELLO_CREDENTIALS} from "../lib/fake-trello-api.ts";
import {loadManifest} from "../lib/manifest.ts";
import {parseOptions} from "../lib/options.ts";
import {RunRoot} from "../lib/run-root.ts";
import {Checks, type ScenarioContext} from "../lib/scenario.ts";
import {SymphonyCli} from "../lib/symphony.ts";
import {TrelloApi} from "../lib/trello.ts";
import {fileURLToPath} from "node:url";

export async function withScenario(id: string, body: (context: ScenarioContext, cli: SymphonyCli, fixtures: Map<string, unknown>) => Promise<void>): Promise<void> {
  const directory = mkdtempSync(join(tmpdir(), "bugbash-scenario-"));
  const run = new RunRoot(directory, "scenario-run");
  run.create();
  const fake = new FakeTrelloApi();
  const endpoint = await fake.start();
  const trello = new TrelloApi(run, endpoint, FAKE_TRELLO_CREDENTIALS);
  await trello.captureInitialBoards();
  const cli = new SymphonyCli(run, process.execPath, {endpoint, credentials: FAKE_TRELLO_CREDENTIALS});
  const fixtures = new Map<string, unknown>();
  const scenario = loadManifest(fileURLToPath(new URL("../manifest.yml", import.meta.url))).scenarios.find((row) => row.id === id);
  if (scenario === undefined) throw new Error(`cannot load test scenario "${id}": select a manifest row`);
  const context: ScenarioContext = {
    run, scenario, options: parseOptions(["--scenario", id]), targetCommit: "synthetic", deadline: null, signal: new AbortController().signal,
    cli: async () => cli,
    sourceInstall: () => null,
    trello: () => trello,
    fakeTrello: null,
    async fixture<T>(key: string, setup: () => Promise<T>): Promise<T> {
      if (!fixtures.has(key)) fixtures.set(key, await setup());
      return fixtures.get(key) as T;
    },
    cleanup: () => cleanupRun(run, cli, trello),
    flushReports() {},
    check: new Checks(), selected: new Set([id]),
    evidence: (name, content) => run.privateEvidence(id, name, content),
    caveat() {},
  };
  try {
    await body(context, cli, fixtures);
  } finally {
    await fake.stop();
    rmSync(directory, {recursive: true, force: true});
  }
}
