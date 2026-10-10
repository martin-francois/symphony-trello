import assert from "node:assert/strict";
import test from "node:test";
import {cleanupIsClean, exitCodeFor, mergeCleanup, type CleanupSummary} from "./report.ts";
import {percent, resumedStatus, tally, verdict, type ResultStatus, type ScenarioResult} from "./results.ts";

function result(status: ResultStatus, harnessOnly = false): ScenarioResult {
  return {
    id: status,
    family: "setup",
    mode: "deterministic-cli",
    status,
    summary: "",
    modeTags: [],
    linkedIssue: null,
    harnessOnly,
    failedAssertions: [],
    startedAt: "",
    finishedAt: "",
  };
}

const CLEAN: CleanupSummary = {
  boardsRegistered: 2,
  boardsArchived: 2,
  boardsStillOpen: 0,
  boardArchiveFailures: 0,
  workersRegistered: 1,
  workersStopped: 1,
  runOwnedProcessesRemaining: 0,
  sensitivePathsDeleted: 1,
  githubSandboxReposLeft: 0,
  notes: [],
};

test("a reproducing known bug stays known-bug and never counts as a clean pass", () => {
  // given
  const failedAssertions = ["tool missing"];

  // when
  const outcome = verdict("known-bug", 493, failedAssertions, null);
  const counts = tally([result(outcome.status), result("covered")]);

  // then
  assert.deepEqual(outcome, {status: "known-bug", note: "reproduces known bug #493"});
  assert.equal(counts.passLike, 1);
  assert.equal(counts.coveredOrKnown, 2);
});

test("a known bug that stopped reproducing passes with a caveat that asks for the manifest flip", () => {
  // given
  const failedAssertions: string[] = [];

  // when
  const outcome = verdict("known-bug", 493, failedAssertions, null);

  // then
  assert.deepEqual(outcome, {
    status: "covered-with-caveat",
    note: "known bug #493 no longer reproduces; change the manifest row to expected: covered",
  });
});

test("an unexpected assertion failure is a failed row and a caveat keeps a pass", () => {
  // given
  const failedAssertions = ["exit code"];

  // when
  const failed = verdict("covered", null, failedAssertions, null);
  const caveated = verdict("covered", null, [], "fake Trello only");

  // then
  assert.equal(failed.status, "failed");
  assert.deepEqual(caveated, {status: "covered-with-caveat", note: "fake Trello only"});
});

test("a resumed row that passes after a harness-invalid result is corrected", () => {
  // given
  const previousStatuses = ["harness-invalid", "failed", undefined] as const;

  // when
  const statuses = previousStatuses.map((previous) => resumedStatus(previous, "covered"));

  // then
  assert.deepEqual(statuses, ["corrected", "covered", "covered"]);
});

test("tallies exclude harness-only and unexecuted rows from the percentages", () => {
  // given
  const results = [result("covered"), result("failed"), result("skipped"), result("not-yet-automated"), result("covered", true)];

  // when
  const counts = tally(results);

  // then
  assert.deepEqual(
    {total: counts.total, meaningful: counts.meaningful, executed: counts.executedMeaningful, harnessOnly: counts.harnessOnly},
    {total: 5, meaningful: 4, executed: 2, harnessOnly: 1},
  );
  assert.equal(percent(counts.passLike, counts.executedMeaningful), "50.0%");
  assert.equal(percent(0, 0), "n/a");
});

test("the exit code fails on failed, harness-invalid, or unclean cleanup and reports interruption", () => {
  // given
  const clean = [result("covered"), result("known-bug"), result("skipped")];

  // when
  const codes = [
    exitCodeFor(clean, CLEAN),
    exitCodeFor([result("harness-invalid")], CLEAN),
    exitCodeFor([result("interrupted")], CLEAN),
    exitCodeFor([result("covered")], {...CLEAN, boardsStillOpen: 1}),
  ];

  // then
  assert.deepEqual(codes, [0, 1, 130, 1]);
});

test("cleanup passes add up archive counts and keep the latest open state", () => {
  // given
  const second = {...CLEAN, boardsArchived: 0, workersStopped: 0, sensitivePathsDeleted: 0, runOwnedProcessesRemaining: 1};

  // when
  const merged = mergeCleanup(CLEAN, second);

  // then
  assert.deepEqual(
    {archived: merged.boardsArchived, stopped: merged.workersStopped, remaining: merged.runOwnedProcessesRemaining},
    {archived: 2, stopped: 1, remaining: 1},
  );
  assert.equal(cleanupIsClean(merged), false);
});
