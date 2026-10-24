import assert from "node:assert/strict";
import test from "node:test";
import {parseManifest, type Manifest} from "./manifest.ts";
import {orderForExecution, planRun, selectScenarios, SelectionError} from "./plan.ts";

function row(id: string, family: string, profiles: string, mode: string, codex = "false"): string {
  return `
  - id: ${id}
    family: ${family}
    profiles: [${profiles}]
    requires: {trello: true, codex: ${codex}, github: false, network: false}
    mode: ${mode}
    description: d
    setup: s
    action: a
    assertions: [x]
    cleanup: c
    expected: covered
`;
}

const MANIFEST: Manifest = parseManifest(`version: 1
defaults:
  evidence: {public: p, private: q}
scenarios:
${row("real-codex-row", "codex-access", "real-codex", "service-real-codex", "real")}
${row("fake-codex-row", "codex-access", "release", "service-fake-codex", "fake")}
${row("cli-row", "setup", "quick, release", "deterministic-cli")}
${row("cleanup-row", "cleanup", "quick", "cleanup")}
`);

const FAKE_MODES = {trello: "fake", codex: "fake", github: "fake", hostProfile: "standard", network: false} as const;
const ALL_TOOLS = {codexCli: true, ghCli: true, trelloCredentials: true};
const AUTOMATED = new Set(MANIFEST.scenarios.map((scenario) => scenario.id));

function ids(scenarios: ReadonlyArray<{id: string}>): string[] {
  return scenarios.map((scenario) => scenario.id);
}

test("a profile selects its rows and full selects every row", () => {
  // given
  const quick = {profiles: ["quick" as const], families: [], scenarioIds: []};
  const full = {profiles: ["full" as const], families: [], scenarioIds: []};

  // when
  const quickRows = selectScenarios(MANIFEST, quick);
  const fullRows = selectScenarios(MANIFEST, full);

  // then
  assert.deepEqual(ids(quickRows), ["cli-row", "cleanup-row"]);
  assert.equal(fullRows.length, 4);
});

test("a family alone selects that family and narrows a profile", () => {
  // given
  const family = {profiles: [], families: ["codex-access" as const], scenarioIds: []};
  const narrowed = {profiles: ["release" as const], families: ["setup" as const], scenarioIds: []};

  // when
  const familyRows = selectScenarios(MANIFEST, family);
  const narrowedRows = selectScenarios(MANIFEST, narrowed);

  // then
  assert.deepEqual(ids(familyRows), ["fake-codex-row", "real-codex-row"]);
  assert.deepEqual(ids(narrowedRows), ["cli-row"]);
});

test("fake-Codex rows run before real-Codex rows and cleanup runs last", () => {
  // given
  const manifestOrder = MANIFEST.scenarios;

  // when
  const ordered = orderForExecution(manifestOrder);

  // then
  assert.deepEqual(ids(ordered), ["cli-row", "fake-codex-row", "real-codex-row", "cleanup-row"]);
});

test("an unknown scenario id is a selection error", () => {
  // given
  const criteria = {profiles: [], families: [], scenarioIds: ["nope"]};

  // when / then
  assert.throws(() => selectScenarios(MANIFEST, criteria), SelectionError);
});

test("real-Codex rows are skipped without the opt-in and without the codex CLI", () => {
  // given
  const optedIn = {...FAKE_MODES, codex: "real"} as const;
  const noCodexCli = {...ALL_TOOLS, codexCli: false};

  // when
  const withoutOptIn = planRun(MANIFEST.scenarios, FAKE_MODES, ALL_TOOLS, AUTOMATED);
  const withoutCli = planRun(MANIFEST.scenarios, optedIn, noCodexCli, AUTOMATED);

  // then
  assert.deepEqual(withoutOptIn.find((entry) => entry.scenario.id === "real-codex-row"), {
    scenario: MANIFEST.scenarios[0],
    decision: "skip",
    reason: "real-Codex row; pass --codex real to opt in",
  });
  assert.equal(withoutCli.find((entry) => entry.scenario.id === "real-codex-row")?.reason, "real-Codex row; the codex CLI is not on PATH");
});

test("real Trello needs credentials and a row without automation is not-yet-automated", () => {
  // given
  const realTrello = {...FAKE_MODES, trello: "real"} as const;
  const noCredentials = {...ALL_TOOLS, trelloCredentials: false};
  const onlyCliRow = new Set(["cli-row"]);

  // when
  const realPlan = planRun(MANIFEST.scenarios, realTrello, noCredentials, onlyCliRow);
  const fakePlan = planRun(MANIFEST.scenarios, FAKE_MODES, ALL_TOOLS, onlyCliRow);

  // then
  assert.ok(realPlan.every((entry) => entry.decision === "skip"), "every Trello row is skipped without credentials");
  assert.equal(fakePlan.find((entry) => entry.scenario.id === "cleanup-row")?.decision, "not-yet-automated");
  assert.equal(fakePlan.find((entry) => entry.scenario.id === "cli-row")?.decision, "run");
});
