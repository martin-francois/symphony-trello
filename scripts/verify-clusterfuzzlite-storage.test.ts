import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

const script = resolve("scripts/verify-clusterfuzzlite-storage");

function validCoverageReport() {
  return {
    type: "oss-fuzz.java.coverage.json.export",
    version: "1.0.0",
    data: [
      {
        files: [
          "repository/RepositorySourceResolver.java",
          "tracker/TrelloCardReferenceParser.java",
          "tracker/TrelloChecklistClassifier.java",
          "workflow/WorkflowLoader.java",
        ].map((filename) => ({
          filename: `src/main/java/ch/fmartin/symphony/trello/${filename}`,
          summary: {lines: {covered: 42}},
        })),
        totals: {lines: {covered: 42}},
      },
    ],
  };
}

function run(
  args: string[],
  failingPath = "",
  coverageReport = validCoverageReport(),
  emptyPath = "",
  malformedXml = false,
) {
  const fakeBin = mkdtempSync(join(tmpdir(), "clusterfuzzlite-storage-bin-"));
  const log = join(fakeBin, "gh.log");
  const gh = join(fakeBin, "gh");
  writeFileSync(log, "");
  writeFileSync(
    gh,
    `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$GH_LOG"
if [[ -n "\${FAILING_PATH:-}" && "$*" == *"$FAILING_PATH"* ]]; then
  exit 1
fi
if [[ "$*" == *"application/vnd.github.raw+json"* ]]; then
  if [[ -n "\${EMPTY_PATH:-}" && "$*" == *"$EMPTY_PATH"* ]]; then
    exit 0
  elif [[ "$*" == *"/index.html"* ]]; then
    printf '%s\\n' "$HTML_REPORT"
  elif [[ "$*" == *"/jacoco.xml"* ]]; then
    printf '%s\\n' "$XML_REPORT"
  else
    printf '%s\\n' "$COVERAGE_REPORT"
  fi
fi
`,
  );
  chmodSync(gh, 0o755);

  const result = spawnSync("bash", [script, ...args], {
    encoding: "utf8",
    env: {
      ...process.env,
      CFL_STORAGE_REPOSITORY: "owner/fuzz-storage",
      COVERAGE_REPORT: JSON.stringify(coverageReport),
      EMPTY_PATH: emptyPath,
      FAILING_PATH: failingPath,
      GH_LOG: log,
      HTML_REPORT: "<html><body>coverage</body></html>",
      PATH: `${fakeBin}:${process.env.PATH}`,
      XML_REPORT: malformedXml ? "<report>" : "<report/>",
    },
  });

  return {log: readFileSync(log, "utf8"), result};
}

test("verifies every requested target on the corpus branch", () => {
  const {log, result} = run(["corpus", "FirstFuzzer", "SecondFuzzer"]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /repos\/owner\/fuzz-storage\/contents\/corpus\/FirstFuzzer -f ref=main/);
  assert.match(log, /repos\/owner\/fuzz-storage\/contents\/corpus\/SecondFuzzer -f ref=main/);
});

test("fails when a requested target was not persisted", () => {
  const {result} = run(["corpus", "MissingFuzzer"], "corpus/MissingFuzzer");

  assert.equal(result.status, 1);
  assert.match(result.stderr, /did not persist corpus\/MissingFuzzer/);
});

test("verifies complete nonempty Java coverage on the Pages branch", () => {
  const {log, result} = run(["coverage"]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /contents\/coverage\/latest\/report\/linux\/index\.html -f ref=gh-pages/);
  assert.match(log, /contents\/coverage\/latest\/report\/linux\/jacoco\.xml -f ref=gh-pages/);
  assert.match(log, /contents\/coverage\/latest\/report\/linux\/summary\.json -f ref=gh-pages/);
  assert.match(log, /contents\/coverage\/latest\/fuzzer_stats\/RepositorySourceFuzzer\.json/);
  assert.match(log, /contents\/coverage\/latest\/fuzzer_stats\/WorkflowLoaderFuzzer\.json/);
  assert.match(log, /application\/vnd\.github\.raw\+json.*index\.html -f ref=gh-pages/);
  assert.match(log, /application\/vnd\.github\.raw\+json.*jacoco\.xml -f ref=gh-pages/);
  assert.doesNotMatch(log, /--jq \.content/);
});

test("rejects a coverage upload without covered production files", () => {
  const emptyReport = {
    type: "oss-fuzz.java.coverage.json.export",
    version: "1.0.0",
    data: [{files: [], totals: {lines: {covered: 0}}}],
  };
  const {result} = run(["coverage"], "", emptyReport);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /empty or malformed Java coverage summary/);
});

test("rejects an empty stored HTML report", () => {
  const {result} = run(["coverage"], "", validCoverageReport(), "index.html");

  assert.equal(result.status, 1);
  assert.match(result.stderr, /did not generate report\/linux\/index\.html/);
});

test("rejects malformed stored JaCoCo XML", () => {
  const {result} = run(["coverage"], "", validCoverageReport(), "", true);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /generated malformed JaCoCo XML/);
});

test("rejects incomplete verification requests", () => {
  assert.equal(run(["corpus"]).result.status, 2);
  assert.equal(run(["coverage", "UnexpectedFuzzer"]).result.status, 2);
  assert.equal(run(["unknown"]).result.status, 2);
});
