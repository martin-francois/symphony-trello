import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {
  hasPrivilegedTrigger,
  jobs,
  isWorkflowCommitRef,
  privilegedJobs,
  repositoryFile,
  requiredModule,
  shaIsTrusted,
  stepKey,
  steps,
  usesAction,
  WORKFLOWS,
  type Workflow,
  type WorkflowJob,
  type WorkflowStep,
} from "./test-support/workflow-script.ts";

// Enforces the mechanical rules in docs/agents/github-actions-security.md for every workflow.

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

function permissionValues(permissions: unknown): readonly string[] {
  if (typeof permissions === "string") {
    return [permissions];
  }
  return permissions && typeof permissions === "object" ? Object.values(permissions).map(String) : [];
}

// Any cache action, and any input that names a cache on any action, such as setup-java's
// `cache`, `cache-dependency-path`, or a `cache-from` or `cache-read-only` input, whatever its
// value: a privileged job has no business configuring a cache at all.
function restoresCache(step: WorkflowStep): boolean {
  const cacheAction = typeof step.uses === "string" && /cache/i.test(step.uses.split("@")[0] ?? "");
  const cacheInput = Object.keys(step.with ?? {}).some((name) => /cache/i.test(name));
  return cacheAction || cacheInput;
}

function checkouts(workflow: Workflow): readonly (readonly [string, WorkflowJob, WorkflowStep])[] {
  return jobs(workflow).flatMap(([jobName, job]) =>
    steps(job)
      .filter((step) => usesAction(step, "actions/checkout"))
      .map((step) => [jobName, job, step] as const),
  );
}

function stepById(job: WorkflowJob, id: string): WorkflowStep | undefined {
  return steps(job).find((step) => step.id === id);
}

/** A github-script step's code: its block, or the module the block loads from scripts/. */
function scriptCode(step: WorkflowStep | undefined): string {
  const module = step && requiredModule(step);
  return module ? readFileSync(repositoryFile(module), "utf8") : String(step?.with?.["script"]);
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
      // Where every trigger is one on which github.sha cannot name pull request code, the workflow's
      // own commit is a base SHA too; see docs/agents/github-actions.md.
      assert.ok(
        TRUSTED_CHECKOUT_REF.test(String(ref)) || (shaIsTrusted(workflow.document) && isWorkflowCommitRef(ref)),
        `${key} must check out a base SHA or release ref, not ${String(ref)}`,
      );
      const producer = /steps\.([\w-]+)\.outputs\.base_sha/.exec(String(ref))?.[1];
      if (producer) {
        assert.match(scriptCode(stepById(job, producer)), BASE_SHA_OUTPUT, `${key}: step ${producer} must set base_sha from the pull request's base`);
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
