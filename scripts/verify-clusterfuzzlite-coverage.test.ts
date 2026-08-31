import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

const script = resolve("scripts/verify-clusterfuzzlite-coverage");
const targetSources = new Map([
  ["RepositorySourceFuzzer", "repository/RepositorySourceResolver.java"],
  ["TrelloCardReferenceParserFuzzer", "tracker/TrelloCardReferenceParser.java"],
  ["TrelloChecklistClassifierFuzzer", "tracker/TrelloChecklistClassifier.java"],
  ["WorkflowLoaderFuzzer", "workflow/WorkflowLoader.java"],
]);

function report(source: string, coveredLines = 42) {
  return JSON.stringify({
    type: "oss-fuzz.java.coverage.json.export",
    version: "1.0.0",
    data: [
      {
        files: [
          {
            filename: `src/main/java/ch/fmartin/symphony/trello/${source}`,
            summary: {lines: {covered: coveredLines}},
          },
        ],
        totals: {lines: {covered: coveredLines}},
      },
    ],
  });
}

function fixture(overrides = new Map<string, string>()) {
  const coverage = mkdtempSync(join(tmpdir(), "clusterfuzzlite-coverage-report-"));
  mkdirSync(join(coverage, "report", "linux"), {recursive: true});
  mkdirSync(join(coverage, "fuzzer_stats"));
  writeFileSync(join(coverage, "report", "linux", "index.html"), "report\n");
  writeFileSync(join(coverage, "report", "linux", "jacoco.xml"), "<report/>\n");
  writeFileSync(
    join(coverage, "report", "linux", "summary.json"),
    overrides.get("summary.json") ?? report("workflow/WorkflowLoader.java"),
  );
  for (const [target, source] of targetSources) {
    writeFileSync(
      join(coverage, "fuzzer_stats", `${target}.json`),
      overrides.get(target) ?? report(source),
    );
  }
  return coverage;
}

function verify(coverage: string) {
  return spawnSync("bash", [script, "verify-directory", coverage], {encoding: "utf8"});
}

test("accepts reports that reach every intended production boundary", () => {
  const result = verify(fixture());

  assert.equal(result.status, 0, result.stderr);
});

test("rejects an aggregate report without covered files", () => {
  const result = verify(
    fixture(new Map([["summary.json", report("workflow/WorkflowLoader.java", 0)]])),
  );

  assert.equal(result.status, 1);
  assert.match(result.stderr, /empty or malformed Java coverage summary/);
});

for (const scenario of [
  {description: "a malformed", report: "{"},
  {
    description: "an empty",
    report: JSON.stringify({
      type: "oss-fuzz.java.coverage.json.export",
      version: "1.0.0",
      data: [{files: []}],
    }),
  },
  {description: "a wrong-target", report: report("workflow/WorkflowLoader.java")},
]) {
  test(`rejects ${scenario.description} per-target report`, () => {
    const result = verify(fixture(new Map([["RepositorySourceFuzzer", scenario.report]])));

    assert.equal(result.status, 1);
    assert.match(result.stderr, /RepositorySourceFuzzer coverage is malformed or does not reach/);
  });
}
