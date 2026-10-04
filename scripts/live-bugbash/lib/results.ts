import type {ExpectedStatus, Scenario} from "./manifest.ts";

/**
 * Row statuses. The first six come from the original bug-bash ledger. `failed` marks an unexpected
 * product failure (a new finding to triage), `skipped` marks a row whose opt-in or tool was missing,
 * and `not-yet-automated` marks a manifest row the harness cannot execute yet.
 */
export const RESULT_STATUSES = [
  "covered",
  "covered-with-caveat",
  "known-bug",
  "harness-invalid",
  "interrupted",
  "corrected",
  "failed",
  "skipped",
  "not-yet-automated",
] as const;
export type ResultStatus = (typeof RESULT_STATUSES)[number];

export const PASS_LIKE: ReadonlySet<ResultStatus> = new Set(["covered", "covered-with-caveat", "corrected"]);
export const EXECUTED: ReadonlySet<ResultStatus> = new Set([
  "covered",
  "covered-with-caveat",
  "known-bug",
  "harness-invalid",
  "interrupted",
  "corrected",
  "failed",
]);
export const FAILING: ReadonlySet<ResultStatus> = new Set(["failed", "harness-invalid", "interrupted"]);

export interface ScenarioResult {
  id: string;
  family: string;
  mode: string;
  status: ResultStatus;
  summary: string;
  modeTags: string[];
  linkedIssue: number | null;
  harnessOnly: boolean;
  failedAssertions: string[];
  startedAt: string;
  finishedAt: string;
}

export interface Verdict {
  status: ResultStatus;
  note: string | null;
}

/**
 * Maps assertion results to a row status. A known bug that still reproduces stays `known-bug` and
 * never counts as a clean pass; a known bug that stopped reproducing passes with a caveat that asks
 * for the manifest row to be flipped.
 */
export function verdict(
  expected: ExpectedStatus,
  linkedIssue: number | null,
  failedAssertions: readonly string[],
  caveat: string | null,
): Verdict {
  if (failedAssertions.length === 0) {
    if (expected === "known-bug") {
      return {
        status: "covered-with-caveat",
        note: `known bug #${linkedIssue} no longer reproduces; change the manifest row to expected: covered`,
      };
    }
    return caveat === null ? {status: "covered", note: null} : {status: "covered-with-caveat", note: caveat};
  }
  if (expected === "known-bug") {
    return {status: "known-bug", note: `reproduces known bug #${linkedIssue}`};
  }
  return {status: "failed", note: null};
}

/** A resumed row that previously failed for harness reasons and now passes is `corrected`. */
export function resumedStatus(previous: ResultStatus | undefined, current: ResultStatus): ResultStatus {
  return previous === "harness-invalid" && PASS_LIKE.has(current) ? "corrected" : current;
}

export function emptyResult(scenario: Scenario, status: ResultStatus, summary: string, modeTags: string[]): ScenarioResult {
  const now = new Date().toISOString();
  return {
    id: scenario.id,
    family: scenario.family,
    mode: scenario.mode,
    status,
    summary,
    modeTags,
    linkedIssue: scenario.linked_issue,
    harnessOnly: scenario.harness_only,
    failedAssertions: [],
    startedAt: now,
    finishedAt: now,
  };
}

export interface Tally {
  total: number;
  meaningful: number;
  executedMeaningful: number;
  harnessOnly: number;
  byStatus: Record<ResultStatus, number>;
  passLike: number;
  coveredOrKnown: number;
}

export function tally(results: readonly ScenarioResult[]): Tally {
  const byStatus = Object.fromEntries(RESULT_STATUSES.map((status) => [status, 0])) as Record<ResultStatus, number>;
  for (const result of results) {
    byStatus[result.status] += 1;
  }
  const meaningful = results.filter((result) => !result.harnessOnly);
  const executedMeaningful = meaningful.filter((result) => EXECUTED.has(result.status));
  return {
    total: results.length,
    meaningful: meaningful.length,
    executedMeaningful: executedMeaningful.length,
    harnessOnly: results.length - meaningful.length,
    byStatus,
    passLike: executedMeaningful.filter((result) => PASS_LIKE.has(result.status)).length,
    coveredOrKnown: executedMeaningful.filter((result) => PASS_LIKE.has(result.status) || result.status === "known-bug")
      .length,
  };
}

export function percent(part: number, whole: number): string {
  return whole === 0 ? "n/a" : `${((part / whole) * 100).toFixed(1)}%`;
}
