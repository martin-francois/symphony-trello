import {existsSync, readdirSync, writeFileSync} from "node:fs";
import {join} from "node:path";
import type {RunOptions} from "./options.ts";
import {
  EXECUTED,
  FAILING,
  RESULT_STATUSES,
  percent,
  tally,
  type ResultStatus,
  type ScenarioResult,
} from "./results.ts";
import {tableCell} from "./redact.ts";
import {PUBLIC_FILES, type RunRoot} from "./run-root.ts";

export interface RunHeader {
  runId: string;
  targetCommit: string;
  startedAt: string;
  options: RunOptions;
  cliSource: string;
}

export interface CleanupSummary {
  boardsRegistered: number;
  boardsArchived: number;
  boardsStillOpen: number;
  boardArchiveFailures: number;
  workersRegistered: number;
  workersStopped: number;
  runOwnedProcessesRemaining: number;
  sensitivePathsDeleted: number;
  githubSandboxReposLeft: number;
  notes: string[];
}

export interface PrivateContextScan {
  status: "clean" | "findings" | "unavailable" | "not-run";
  detail: string;
}

const STATUS_MEANING: Readonly<Record<ResultStatus, string>> = {
  covered: "assertions passed",
  "covered-with-caveat": "assertions passed with a caveat recorded in the row",
  "known-bug": "reproduced a linked known bug; not a clean pass",
  "harness-invalid": "the harness could not produce a trustworthy result",
  interrupted: "stopped by the time budget or a signal before finishing",
  corrected: "passed on resume after an earlier harness-invalid result",
  failed: "unexpected product failure; triage it and draft an issue after reproducing it twice",
  skipped: "requirement or opt-in missing",
  "not-yet-automated": "listed in the manifest, automation not implemented yet",
};

export function modeSummary(options: RunOptions): string {
  return `trello=${options.trello} codex=${options.codex} github=${options.github} host=${options.hostProfile} network=${options.network ? "allowed" : "off"}`;
}

export function writeProgressHeader(run: RunRoot, header: RunHeader): void {
  const {options} = header;
  const selection = [
    ...options.selection.profiles.map((profile) => `profile:${profile}`),
    ...options.selection.families.map((family) => `family:${family}`),
    ...options.selection.scenarioIds.map((id) => `scenario:${id}`),
  ].join(" ");
  run.writePublic(
    PUBLIC_FILES.progress,
    `# Live bug bash progress

- Run id: ${header.runId}
- Target commit: ${header.targetCommit}
- Started: ${header.startedAt}
- Modes: ${modeSummary(options)}
- Real-service opt-in: ${realOptIn(options)}
- Selection: ${selection}
- Symphony command: ${header.cliSource}
- Time budget: ${options.timeBudgetMs === null ? "none" : `${Math.round(options.timeBudgetMs / 1000)}s`}

## Log

`,
  );
}

export function appendProgress(run: RunRoot, line: string): void {
  run.appendPublic(PUBLIC_FILES.progress, `- ${new Date().toISOString()} ${line}\n`);
}

export function writeLedger(run: RunRoot, results: readonly ScenarioResult[]): void {
  const rows = results.map(
    (result) =>
      `| ${result.id} | ${result.status} | ${tableCell(run.publicText(result.summary))} | ${tableCell(result.modeTags.join(" "))} |`,
  );
  run.writePublic(
    PUBLIC_FILES.ledger,
    ["# Coverage ledger", "", "| scenario id | status | public-safe evidence summary | mode tags |", "| --- | --- | --- | --- |", ...rows, ""].join(
      "\n",
    ),
  );
}

export function writeCleanupSummary(run: RunRoot, summary: CleanupSummary): void {
  run.writePublic(
    PUBLIC_FILES.cleanupSummary,
    `# Cleanup summary

- Run-owned Trello boards registered: ${summary.boardsRegistered}
- Archived by cleanup during this run: ${summary.boardsArchived}
- Still open after cleanup: ${summary.boardsStillOpen}
- Archive failures: ${summary.boardArchiveFailures}
- Run-owned workers registered: ${summary.workersRegistered}
- Workers stopped by cleanup during this run: ${summary.workersStopped}
- Run-owned processes remaining: ${summary.runOwnedProcessesRemaining}
- Sensitive run-scoped paths deleted: ${summary.sensitivePathsDeleted}
- GitHub sandbox repositories left for manual cleanup: ${summary.githubSandboxReposLeft}
${summary.notes.map((note) => `- ${note}`).join("\n")}
`,
  );
}

/**
 * Combines cleanup passes: archive, stop, and delete counts add up across passes, while what is
 * still open or running comes from the latest pass.
 */
export function mergeCleanup(previous: CleanupSummary | null, latest: CleanupSummary): CleanupSummary {
  if (previous === null) {
    return latest;
  }
  return {
    ...latest,
    boardsArchived: previous.boardsArchived + latest.boardsArchived,
    boardArchiveFailures: latest.boardArchiveFailures,
    workersStopped: previous.workersStopped + latest.workersStopped,
    sensitivePathsDeleted: previous.sensitivePathsDeleted + latest.sensitivePathsDeleted,
    notes: [...new Set([...previous.notes, ...latest.notes])],
  };
}

export function cleanupIsClean(summary: CleanupSummary): boolean {
  return summary.boardsStillOpen === 0 && summary.boardArchiveFailures === 0 && summary.runOwnedProcessesRemaining === 0;
}

export function writeFinalReport(
  run: RunRoot,
  header: RunHeader,
  results: readonly ScenarioResult[],
  stopReason: string,
  cleanup: CleanupSummary | null,
  scan: PrivateContextScan,
): void {
  const counts = tally(results);
  const statusLines = RESULT_STATUSES.map(
    (status) => `| ${status} | ${counts.byStatus[status]} | ${STATUS_MEANING[status]} |`,
  );
  const findings = results.filter((result) => result.status === "failed");
  const invalid = results.filter((result) => result.status === "harness-invalid" || result.status === "interrupted");
  const known = results.filter((result) => result.status === "known-bug");
  const skipped = results.filter((result) => result.status === "skipped");
  const skipReasons = new Map<string, string[]>();
  for (const result of skipped) {
    skipReasons.set(result.summary, [...(skipReasons.get(result.summary) ?? []), result.id]);
  }
  const drafts = issueDrafts(run);
  run.writePublic(
    PUBLIC_FILES.finalReport,
    `# Live bug bash final report

- Run id: ${header.runId}
- Target commit: ${header.targetCommit}
- Started: ${header.startedAt}
- Finished: ${new Date().toISOString()}
- Stop reason: ${stopReason}
- Modes: ${modeSummary(header.options)}
- Real-service opt-in: ${realOptIn(header.options)}
- Symphony command: ${header.cliSource}

## Coverage

${liveCoverageNote(header.options)}- Rows: ${counts.total} (meaningful ${counts.meaningful}, harness-only ${counts.harnessOnly})
- Executed meaningful rows: ${counts.executedMeaningful}
- Pass-like: ${counts.passLike} (${percent(counts.passLike, counts.executedMeaningful)})
- Covered or known bug: ${counts.coveredOrKnown} (${percent(counts.coveredOrKnown, counts.executedMeaningful)})

| status | rows | meaning |
| --- | --- | --- |
${statusLines.join("\n")}

## Findings to triage

${listOrNone(findings.map((result) => `${result.id}: ${result.failedAssertions.join("; ") || result.summary}. Rerun with \`scripts/live-bugbash/run.sh --scenario ${result.id}\`.`))}

## Known bugs reproduced

${listOrNone(known.map((result) => `${result.id}: #${result.linkedIssue}`))}

## Harness-invalid or interrupted rows

${listOrNone(invalid.map((result) => `${result.id} (${result.status}): ${result.summary}`))}

## Skipped rows

${listOrNone([...skipReasons.entries()].map(([reason, ids]) => `${reason}: ${ids.join(", ")}`))}

## Cleanup

${cleanup === null ? "Cleanup did not run." : `${cleanupIsClean(cleanup) ? "Clean" : "Not clean"}; see cleanup-summary.md.`}

## Private-context scan

${scan.status}: ${scan.detail}

## Issue drafts

${listOrNone(drafts)}

## Recommended next runs

${listOrNone(recommendations(header.options, results))}
`,
  );
}

export function exitCodeFor(results: readonly ScenarioResult[], cleanup: CleanupSummary | null): number {
  if (results.some((result) => result.status === "interrupted")) {
    return 130;
  }
  if (results.some((result) => FAILING.has(result.status))) {
    return 1;
  }
  return cleanup !== null && !cleanupIsClean(cleanup) ? 1 : 0;
}

export function writeResults(run: RunRoot, results: readonly ScenarioResult[]): void {
  writeFileSync(run.path("state", "results.json"), `${JSON.stringify(results, null, 2)}\n`);
}

function issueDrafts(run: RunRoot): string[] {
  const directory = run.path("issues");
  return existsSync(directory) ? readdirSync(directory).filter((name) => name.endsWith(".md")).map((name) => join("issues", name)) : [];
}

const LIVE_PROFILES = ["trello-live", "real-codex", "github-live"] as const;

/** A live profile run against the fake Trello API is a rehearsal; say so before the counts. */
function liveCoverageNote(options: RunOptions): string {
  const live = options.selection.profiles.filter((profile) => (LIVE_PROFILES as readonly string[]).includes(profile));
  if (live.length === 0 || options.trello === "real") {
    return "";
  }
  return `- Live coverage: none. The ${live.join(", ")} profile ran against the local fake Trello API; repeat it with --trello real before claiming live Trello coverage.\n`;
}

function realOptIn(options: RunOptions): string {
  const flags = [
    options.trello === "real" ? "--trello real" : null,
    options.codex === "real" ? "--codex real" : null,
    options.github === "real-sandbox" ? "--github real-sandbox" : null,
    options.hostProfile === "hardened" ? "--host-profile hardened" : null,
    options.network ? "--network" : null,
  ].filter((flag) => flag !== null);
  return flags.length === 0 ? "none (fakes only)" : flags.join(" ");
}

function recommendations(options: RunOptions, results: readonly ScenarioResult[]): string[] {
  const next: string[] = [];
  if (results.some((result) => result.status === "failed")) {
    next.push("Reproduce each failed row twice, then draft an issue under issues/ with the live-bugbash skill.");
  }
  if (options.trello === "fake" && results.some((result) => EXECUTED.has(result.status) && result.modeTags.includes("trello:fake"))) {
    next.push("Repeat the Trello rows with --trello real before claiming live Trello coverage.");
  }
  if (results.some((result) => result.status === "skipped" && result.summary.includes("--codex real"))) {
    next.push("Run --profile real-codex --codex real after the fake-Codex rows pass.");
  }
  if (results.some((result) => result.status === "not-yet-automated")) {
    next.push("Automate the not-yet-automated rows or cover them manually with the live-bugbash skill.");
  }
  return next;
}

function listOrNone(lines: readonly string[]): string {
  return lines.length === 0 ? "None." : lines.map((line) => `- ${line}`).join("\n");
}
