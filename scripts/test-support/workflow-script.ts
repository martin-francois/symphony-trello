import assert from "node:assert/strict";
import {readdirSync, readFileSync} from "node:fs";
import {createRequire} from "node:module";
import {parse} from "yaml";

/**
 * Shared by the workflow tests: the parsed workflows under .github/workflows, what makes a job
 * privileged (docs/agents/github-actions-security.md), and the module a github-script step runs,
 * found through the step's one `require` call so a test exercises the same file the workflow does
 * (docs/agents/github-actions.md).
 */

export interface WorkflowStep {
  readonly id?: unknown;
  readonly name?: unknown;
  readonly run?: unknown;
  readonly uses?: unknown;
  readonly with?: Readonly<Record<string, unknown>>;
}

export interface WorkflowJob {
  readonly environment?: unknown;
  readonly permissions?: unknown;
  readonly steps?: readonly WorkflowStep[];
}

export interface WorkflowDocument {
  readonly jobs?: Readonly<Record<string, WorkflowJob>>;
  readonly on?: unknown;
  readonly permissions?: unknown;
}

export interface Workflow {
  readonly document: WorkflowDocument;
  readonly file: string;
  readonly source: string;
}

export interface GithubScriptArguments {
  readonly context: unknown;
  readonly core: unknown;
  readonly github: unknown;
}

export type WorkflowScript = (arguments_: GithubScriptArguments) => Promise<unknown>;

/** The whole script block of a step that loads its code from the repository. */
export const REQUIRE_CALL = /^await require\("\.\/(scripts\/[\w.-]+\.cjs)"\)\(\{ github, context, core \}\);\n?$/;
/** Events whose runs hold base-repository privileges; see docs/agents/github-actions-security.md. */
export const PRIVILEGED_TRIGGERS: ReadonlySet<string> = new Set(["issue_comment", "pull_request_target", "workflow_run"]);
/**
 * Events on which github.sha is a base-branch commit, the default branch, or a ref a write-access
 * user chose, never a pull request's head or merge commit. On pull_request it is the merge commit
 * and on push whatever was pushed.
 */
export const TRIGGERS_WITH_A_TRUSTED_SHA: ReadonlySet<string> = new Set([
  "issue_comment",
  "issues",
  "pull_request_target",
  "schedule",
  "workflow_dispatch",
  "workflow_run",
]);
/** The checkout ref that names the commit the workflow file was read from. */
export const WORKFLOW_COMMIT_REF = /^\$\{\{\s*github\.sha\s*\}\}$/;
export const WORKFLOW_DIRECTORY = new URL("../../.github/workflows/", import.meta.url);
const REPOSITORY = new URL("../../", import.meta.url);

const require = createRequire(import.meta.url);

/** Every workflow file, sorted by name. */
export const WORKFLOWS: readonly Workflow[] = readdirSync(WORKFLOW_DIRECTORY)
  .filter((file) => /\.ya?ml$/.test(file))
  .sort()
  .map((file) => {
    const source = readFileSync(new URL(file, WORKFLOW_DIRECTORY), "utf8");
    return {document: parse(source) as WorkflowDocument, file, source};
  });

export function triggers({on}: WorkflowDocument): readonly string[] {
  if (typeof on === "string") {
    return [on];
  }
  if (Array.isArray(on)) {
    return on.map(String);
  }
  return on && typeof on === "object" ? Object.keys(on) : [];
}

export function hasPrivilegedTrigger(document: WorkflowDocument): boolean {
  return triggers(document).some((trigger) => PRIVILEGED_TRIGGERS.has(trigger));
}

function holdsIdTokenWrite(job: WorkflowJob): boolean {
  const permissions = job.permissions;
  return Boolean(permissions && typeof permissions === "object" && Reflect.get(permissions, "id-token") === "write");
}

function usesRepositorySecret(job: WorkflowJob): boolean {
  return /secrets\.(?!GITHUB_TOKEN\b)\w+/.test(JSON.stringify(job));
}

/** What makes a job privileged, as docs/agents/github-actions-security.md defines it; empty for an ordinary job. */
export function privilegeReasons(workflow: Workflow, job: WorkflowJob): readonly string[] {
  return [
    hasPrivilegedTrigger(workflow.document) ? "a privileged trigger" : "",
    holdsIdTokenWrite(job) ? "id-token: write" : "",
    usesRepositorySecret(job) ? "a repository secret" : "",
    job.environment !== undefined ? "a deployment environment" : "",
  ].filter(Boolean);
}

export function privilegedJobs(workflow: Workflow): readonly (readonly [string, WorkflowJob, readonly string[]])[] {
  return jobs(workflow).flatMap(([jobName, job]) => {
    const reasons = privilegeReasons(workflow, job);
    return reasons.length > 0 ? [[jobName, job, reasons] as const] : [];
  });
}

/** Whether github.sha can never name pull request code in this workflow: every trigger is a trusted one. */
export function shaIsTrusted(document: WorkflowDocument): boolean {
  const events = triggers(document);
  return events.length > 0 && events.every((trigger) => TRIGGERS_WITH_A_TRUSTED_SHA.has(trigger));
}

export function jobs(workflow: Workflow): readonly (readonly [string, WorkflowJob])[] {
  return Object.entries(workflow.document.jobs ?? {});
}

export function steps(job: WorkflowJob): readonly WorkflowStep[] {
  return job.steps ?? [];
}

export function usesAction(step: WorkflowStep, action: string): boolean {
  return typeof step.uses === "string" && step.uses.startsWith(`${action}@`);
}

export function stepKey(workflow: Workflow, jobName: string, step: WorkflowStep): string {
  return `${workflow.file}:${jobName}:${String(step.name ?? step.uses ?? "run")}`;
}

export function isWorkflowCommitRef(ref: unknown): boolean {
  return WORKFLOW_COMMIT_REF.test(String(ref));
}

/** The repository path of the module a github-script step loads, or undefined for a step that keeps its code inline. */
export function requiredModule(step: WorkflowStep): string | undefined {
  const script = step.with?.["script"];
  return typeof script === "string" ? REQUIRE_CALL.exec(script)?.[1] : undefined;
}

/** The file a repository path such as scripts/x.cjs names. */
export function repositoryFile(path: string): URL {
  return new URL(path, REPOSITORY);
}

/** The repository path of the module that the named step of the parsed workflow loads. */
export function scriptPathOf({document, file}: Pick<Workflow, "document" | "file">, stepName: string): string {
  const matches = Object.values(document.jobs ?? {})
    .flatMap((job) => steps(job))
    .filter(({name}) => name === stepName);
  assert.equal(matches.length, 1, `${stepName} step must exist exactly once in ${file}`);
  const script = matches[0]?.with?.["script"];
  assert.ok(typeof script === "string", `${stepName} script block must exist in ${file}`);
  const path = REQUIRE_CALL.exec(script)?.[1];
  assert.ok(path, `${stepName} in ${file} must load its script with one require call, not ${JSON.stringify(script)}`);
  return path;
}

/** The repository path of the module that the named step of the workflow file loads. */
export function workflowScriptPath(workflowFile: string, stepName: string): string {
  const workflow = WORKFLOWS.find(({file}) => file === workflowFile);
  assert.ok(workflow, `${workflowFile} must exist under .github/workflows`);
  return scriptPathOf(workflow, stepName);
}

export function loadWorkflowScript(workflowFile: string, stepName: string): WorkflowScript {
  return require(`../../${workflowScriptPath(workflowFile, stepName)}`) as WorkflowScript;
}

/** Runs the action with these variables in process.env, then restores the previous values. */
export async function withEnvironment<T>(
  variables: Readonly<Record<string, string>>,
  action: () => Promise<T>,
): Promise<T> {
  const previous = new Map(Object.keys(variables).map((name) => [name, process.env[name]]));
  Object.assign(process.env, variables);
  try {
    return await action();
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) {
        delete process.env[name];
      } else {
        process.env[name] = value;
      }
    }
  }
}

/** Runs the action with Date.now pinned to the given time, then restores the clock. */
export async function withDateNow<T>(nowMs: number, action: () => Promise<T>): Promise<T> {
  const previous = Date.now;
  Date.now = (): number => nowMs;
  try {
    return await action();
  } finally {
    Date.now = previous;
  }
}
