import assert from "node:assert/strict";
import {readdirSync, readFileSync} from "node:fs";
import test from "node:test";
import {parse} from "yaml";

// Enforces the mechanical rules in docs/agents/github-actions-security.md for every workflow.

interface WorkflowStep {
  readonly name?: unknown;
  readonly run?: unknown;
  readonly uses?: unknown;
  readonly with?: Readonly<Record<string, unknown>>;
}

interface WorkflowJob {
  readonly environment?: unknown;
  readonly permissions?: unknown;
  readonly steps?: readonly WorkflowStep[];
}

interface WorkflowStepWithId extends WorkflowStep {
  readonly id?: unknown;
}

interface WorkflowDocument {
  readonly jobs?: Readonly<Record<string, WorkflowJob>>;
  readonly on?: unknown;
  readonly permissions?: unknown;
}

interface Workflow {
  readonly document: WorkflowDocument;
  readonly file: string;
  readonly source: string;
}

const WORKFLOW_DIRECTORY = new URL("../.github/workflows/", import.meta.url);
const PRIVILEGED_TRIGGERS = new Set(["issue_comment", "pull_request_target", "workflow_run"]);
const FULL_SHA_PIN = /^\s*(?:- )?uses: \S+@[0-9a-f]{40} # v?\d+(?:\.\d+)*/;
// A privileged job may check out a base SHA an earlier step resolved from the pull request's
// base, or the release ref the Release Please job resolved from its own tag.
const TRUSTED_CHECKOUT_REF =
  /^\$\{\{\s*((?:steps|needs)\.[\w-]+\.outputs\.(?:base_sha|checkout_ref)|github\.event\.pull_request\.base\.sha)\s*\}\}$/;
const BASE_SHA_OUTPUT = /core\.setOutput\(\s*"base_sha",\s*pullRequest\.base\.sha\s*\)/;
// A step that keeps the checkout token, keyed as workflow:job:step, with the reason the page
// gives. Every other checkout must set persist-credentials: false.
const CHECKOUTS_THAT_KEEP_THE_TOKEN = new Map([
  [
    "openrewrite-renovate.yml:publish:Checkout trusted publication source",
    "pushes the generated branch with the GitHub App token",
  ],
  [
    "release-please.yml:release-please:Checkout release workflow source",
    "trusted push to main; Release Please holds the same token in env",
  ],
  [
    "release-please.yml:release-please:Checkout release source tag",
    "trusted release tag; the job holds the same token in env",
  ],
]);

const WORKFLOWS: readonly Workflow[] = readdirSync(WORKFLOW_DIRECTORY)
  .filter((file) => /\.ya?ml$/.test(file))
  .sort()
  .map((file) => {
    const source = readFileSync(new URL(file, WORKFLOW_DIRECTORY), "utf8");
    return {document: parse(source) as WorkflowDocument, file, source};
  });

function triggers(document: WorkflowDocument): readonly string[] {
  const on = document.on;
  if (typeof on === "string") {
    return [on];
  }
  if (Array.isArray(on)) {
    return on.map(String);
  }
  return on && typeof on === "object" ? Object.keys(on) : [];
}

function hasPrivilegedTrigger(document: WorkflowDocument): boolean {
  return triggers(document).some((trigger) => PRIVILEGED_TRIGGERS.has(trigger));
}

function jobs(workflow: Workflow): readonly (readonly [string, WorkflowJob])[] {
  return Object.entries(workflow.document.jobs ?? {});
}

function steps(job: WorkflowJob): readonly WorkflowStep[] {
  return job.steps ?? [];
}

function usesAction(step: WorkflowStep, action: string): boolean {
  return typeof step.uses === "string" && step.uses.startsWith(`${action}@`);
}

function permissionValues(permissions: unknown): readonly string[] {
  if (typeof permissions === "string") {
    return [permissions];
  }
  return permissions && typeof permissions === "object" ? Object.values(permissions).map(String) : [];
}

function holdsIdTokenWrite(job: WorkflowJob): boolean {
  const permissions = job.permissions;
  return Boolean(permissions && typeof permissions === "object" && Reflect.get(permissions, "id-token") === "write");
}

function usesRepositorySecret(job: WorkflowJob): boolean {
  return /secrets\.(?!GITHUB_TOKEN\b)\w+/.test(JSON.stringify(job));
}

// What makes a job privileged, as the page defines it; empty for an ordinary job.
function privilegeReasons(workflow: Workflow, job: WorkflowJob): readonly string[] {
  return [
    hasPrivilegedTrigger(workflow.document) ? "a privileged trigger" : "",
    holdsIdTokenWrite(job) ? "id-token: write" : "",
    usesRepositorySecret(job) ? "a repository secret" : "",
    job.environment !== undefined ? "a deployment environment" : "",
  ].filter(Boolean);
}

function privilegedJobs(workflow: Workflow): readonly (readonly [string, WorkflowJob, readonly string[]])[] {
  return jobs(workflow).flatMap(([jobName, job]) => {
    const reasons = privilegeReasons(workflow, job);
    return reasons.length > 0 ? [[jobName, job, reasons] as const] : [];
  });
}

// Any cache action, and any input that names a cache on any action, such as setup-java's
// `cache`, `cache-dependency-path`, or a `cache-from` or `cache-read-only` input, whatever its
// value: a privileged job has no business configuring a cache at all.
function restoresCache(step: WorkflowStep): boolean {
  const cacheAction = typeof step.uses === "string" && /cache/i.test(step.uses.split("@")[0] ?? "");
  const cacheInput = Object.keys(step.with ?? {}).some((name) => /cache/i.test(name));
  return cacheAction || cacheInput;
}

function stepKey(workflow: Workflow, jobName: string, step: WorkflowStep): string {
  return `${workflow.file}:${jobName}:${String(step.name ?? step.uses ?? "run")}`;
}

function checkouts(workflow: Workflow): readonly (readonly [string, WorkflowJob, WorkflowStep])[] {
  return jobs(workflow).flatMap(([jobName, job]) =>
    steps(job)
      .filter((step) => usesAction(step, "actions/checkout"))
      .map((step) => [jobName, job, step] as const),
  );
}

function stepById(job: WorkflowJob, id: string): WorkflowStepWithId | undefined {
  return (steps(job) as readonly WorkflowStepWithId[]).find((step) => step.id === id);
}

test("every action is pinned to a full commit SHA with a version comment", () => {
  for (const {file, source} of WORKFLOWS) {
    for (const line of source.split("\n").filter((candidate) => /^\s*(?:- )?uses:/.test(candidate))) {
      assert.match(line, FULL_SHA_PIN, `${file}: ${line.trim()}`);
    }
  }
});

test("every workflow declares read-only default permissions", () => {
  for (const {document, file} of WORKFLOWS) {
    assert.notEqual(document.permissions, undefined, `${file} must declare top-level permissions`);
    for (const value of permissionValues(document.permissions)) {
      assert.match(value, /^(read|read-all|none)$/, `${file} grants ${value} to every job by default`);
    }
  }
});

test("no run step interpolates an expression into its shell script", () => {
  for (const workflow of WORKFLOWS) {
    for (const [jobName, job] of jobs(workflow)) {
      for (const step of steps(job)) {
        if (typeof step.run === "string") {
          assert.doesNotMatch(step.run, /\$\{\{/, `${stepKey(workflow, jobName, step)} must pass values through env`);
        }
      }
    }
  }
});

test("every checkout drops the token unless the test lists the step that keeps it", () => {
  const keepers = new Set(CHECKOUTS_THAT_KEEP_THE_TOKEN.keys());
  for (const workflow of WORKFLOWS) {
    for (const [jobName, , step] of checkouts(workflow)) {
      const key = stepKey(workflow, jobName, step);
      if (keepers.delete(key)) {
        assert.notEqual(step.with?.["persist-credentials"], false, `${key} is listed as keeping the token`);
        continue;
      }
      assert.equal(step.with?.["persist-credentials"], false, `${key} must set persist-credentials: false`);
    }
  }
  assert.deepEqual([...keepers], [], "every listed checkout must still exist");
});

test("a privileged job checks out only this repository at its default ref, a base SHA, or a release ref", () => {
  for (const workflow of WORKFLOWS) {
    const privileged = new Map(privilegedJobs(workflow).map(([jobName, , reasons]) => [jobName, reasons]));
    for (const [jobName, job, step] of checkouts(workflow)) {
      const reasons = privileged.get(jobName);
      if (!reasons) {
        continue;
      }
      const key = `${stepKey(workflow, jobName, step)} (${reasons.join(", ")})`;
      assert.equal(step.with?.["repository"], undefined, `${key} must check out this repository`);
      const ref = step.with?.["ref"];
      if (ref === undefined) {
        continue;
      }
      assert.match(String(ref), TRUSTED_CHECKOUT_REF, `${key} must check out a base SHA or release ref, not ${String(ref)}`);
      const producer = /steps\.([\w-]+)\.outputs\.base_sha/.exec(String(ref))?.[1];
      if (producer) {
        const script = stepById(job, producer)?.with?.["script"];
        assert.match(String(script), BASE_SHA_OUTPUT, `${key}: step ${producer} must set base_sha from the pull request's base`);
      }
    }
  }
});

test("no privileged workflow declares a cache-mode", () => {
  for (const {file, source} of WORKFLOWS.filter(({document}) => hasPrivilegedTrigger(document))) {
    assert.doesNotMatch(source, /^\s*cache-mode:/m, `${file} must not change the read-only cache default`);
  }
});

test("no cache is restored or saved where it could reach a privileged run, a secret, or an OIDC token", () => {
  for (const workflow of WORKFLOWS) {
    for (const [jobName, job, reasons] of privilegedJobs(workflow)) {
      for (const step of steps(job).filter(restoresCache)) {
        assert.fail(`${stepKey(workflow, jobName, step)} uses a cache in a job with ${reasons.join(", ")}`);
      }
    }
  }
});

test("the audit table covers every workflow", () => {
  const page = readFileSync(new URL("../docs/agents/github-actions-security.md", import.meta.url), "utf8");
  for (const {file} of WORKFLOWS) {
    assert.match(page, new RegExp(`^\\| \`${file.replaceAll(".", "\\.")}\` \\|`, "m"), `${file} is missing from the audit table`);
  }
});
