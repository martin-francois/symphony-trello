import assert from "node:assert/strict";
import {join, resolve} from "node:path";
import test from "node:test";
import {SCENARIOS} from "../scenarios/index.ts";
import {FAMILIES, loadManifest, ManifestError, parseManifest} from "./manifest.ts";

const MANIFEST = loadManifest(resolve("scripts/live-bugbash/manifest.yml"));

const VALID_ROW = `
  - id: sample-row
    family: setup
    profiles: [quick]
    requires: {trello: false, codex: false, github: false, network: false}
    mode: deterministic-cli
    description: d
    setup: s
    action: a
    assertions: [x]
    cleanup: c
    expected: covered
`;

function manifestWith(row: string): string {
  return `version: 1\ndefaults:\n  evidence: {public: p, private: q}\nscenarios:\n${row}`;
}

test("the repository manifest loads and every row carries the full scenario contract", () => {
  // given
  const rows = MANIFEST.scenarios;

  // when
  const incomplete = rows.filter(
    (scenario) =>
      !scenario.description ||
      !scenario.setup ||
      !scenario.action ||
      !scenario.cleanup ||
      scenario.assertions.length === 0 ||
      !scenario.evidence.public ||
      !scenario.evidence.private,
  );

  // then
  assert.deepEqual(ids(incomplete), [], "rows missing a description, setup, action, assertion, cleanup, or evidence path");
});

test("the manifest covers every family the issue names", () => {
  // given
  const rows = MANIFEST.scenarios;

  // when
  const families = [...new Set(rows.map((scenario) => scenario.family))].sort();

  // then
  assert.deepEqual(families, [...FAMILIES].sort());
});

test("every automated scenario has a manifest row", () => {
  // given
  const manifestIds = new Set(ids(MANIFEST.scenarios));

  // when
  const unknown = Object.keys(SCENARIOS).filter((id) => !manifestIds.has(id));

  // then
  assert.deepEqual(unknown, [], "automation registered for an id that the manifest does not list");
});

test("known-bug rows link an issue and real-Codex rows declare real Codex", () => {
  // given
  const rows = MANIFEST.scenarios;

  // when
  const unlinkedKnownBugs = rows.filter((scenario) => scenario.expected === "known-bug" && scenario.linked_issue === null);
  const realCodexModes = rows.filter((scenario) => scenario.mode === "service-real-codex" || scenario.mode === "direct-real-codex");

  // then
  assert.deepEqual(ids(unlinkedKnownBugs), []);
  assert.deepEqual(ids(realCodexModes.filter((scenario) => scenario.requires.codex !== "real")), []);
});

test("rows without a profile, with an unknown family, or with duplicate ids are rejected", () => {
  // given
  const withoutProfile = manifestWith(VALID_ROW.replace("profiles: [quick]", "profiles: []"));
  const unknownFamily = manifestWith(VALID_ROW.replace("family: setup", "family: other"));
  const duplicate = manifestWith(VALID_ROW + VALID_ROW);

  // when / then
  assert.throws(() => parseManifest(withoutProfile), ManifestError);
  assert.throws(() => parseManifest(unknownFamily), /family must be one of/);
  assert.throws(() => parseManifest(duplicate), /duplicate scenario id sample-row/);
});

test("a known-bug row without a linked issue is rejected", () => {
  // given
  const knownBug = manifestWith(VALID_ROW.replace("expected: covered", "expected: known-bug"));

  // when / then
  assert.throws(() => parseManifest(knownBug), /known bug and must link its GitHub issue/);
});

test("defaults fill the evidence paths and a row may override them", () => {
  // given
  const overridden = manifestWith(`${VALID_ROW}    evidence: {public: own public, private: ${join("own", "private")}}\n`);
  const defaulted = manifestWith(VALID_ROW);

  // when
  const own = parseManifest(overridden).scenarios[0]?.evidence;
  const fromDefaults = parseManifest(defaulted).scenarios[0]?.evidence;

  // then
  assert.equal(own?.public, "own public");
  assert.equal(fromDefaults?.private, "q");
});

function ids(scenarios: ReadonlyArray<{id: string}>): string[] {
  return scenarios.map((scenario) => scenario.id);
}
