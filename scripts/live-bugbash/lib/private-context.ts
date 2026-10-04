import {existsSync, readdirSync} from "node:fs";
import {join} from "node:path";
import type {PrivateContextScan} from "./report.ts";
import {PUBLIC_FILES, type RunRoot} from "./run-root.ts";
import {runCommand} from "./symphony.ts";

/** Public files the scan covers before the final report exists. */
export function publicFilesBeforeFinalReport(run: RunRoot): string[] {
  const issues = run.path("issues");
  const drafts = existsSync(issues) ? readdirSync(issues).filter((name) => name.endsWith(".md")).map((name) => join("issues", name)) : [];
  return [PUBLIC_FILES.progress, PUBLIC_FILES.ledger, PUBLIC_FILES.cleanupSummary, ...drafts];
}

/** Scans public files with scripts/check-private-context (BetterLeaks plus the repository's private-context rules). */
export async function scanPublicFiles(run: RunRoot, files: readonly string[]): Promise<PrivateContextScan> {
  const present = files.filter((file) => existsSync(run.path(file)));
  if (present.length === 0) {
    return {status: "not-run", detail: "no public files to scan"};
  }
  const outcome = await runCommand(
    join(run.repoRoot, "scripts", "check-private-context"),
    present.flatMap((file) => ["--file", run.path(file)]),
    {cwd: run.repoRoot, timeoutMs: 300_000},
  );
  run.privateEvidence("run", `private-context-scan-${Date.now()}.log`, `${outcome.stdout}\n${outcome.stderr}`);
  if (outcome.status === 0) {
    return {status: "clean", detail: `scripts/check-private-context found nothing in ${present.length} file(s)`};
  }
  if (outcome.status === 1) {
    return {status: "findings", detail: "scripts/check-private-context reported findings; the redacted log is under private-evidence/run"};
  }
  return {status: "unavailable", detail: `scripts/check-private-context could not run (exit ${String(outcome.status)})`};
}
