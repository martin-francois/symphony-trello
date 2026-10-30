import assert from "node:assert/strict";
import {existsSync, readdirSync} from "node:fs";
import {createRequire} from "node:module";
import test from "node:test";
import {
  isWorkflowCommitRef,
  jobs,
  privilegeReasons,
  repositoryFile,
  requiredModule,
  shaIsTrusted,
  scriptPathOf,
  stepKey,
  steps,
  usesAction,
  WORKFLOWS,
  type Workflow,
  type WorkflowJob,
  type WorkflowStep,
} from "./test-support/workflow-script.ts";

// Enforces the rules in docs/agents/github-actions.md: step code longer than a few lines lives in
// scripts/, a github-script step loads its module with one require call, and a privileged job
// checks that module out from the commit the workflow file came from, without the token.

/** The most non-blank, non-comment lines a run or script block may keep inline. */
const INLINE_LINE_LIMIT = 10;
const FUNCTION_DEFINITIONS = [
  // JavaScript, PowerShell and Bash: function name
  /^\s*(?:async\s+)?function\b/m,
  // Bash: name() {
  /^\s*[A-Za-z_][\w-]*\s*\(\s*\)\s*\{?\s*$/m,
  // JavaScript: a named arrow function
  /^\s*(?:const|let|var)\s+\w+\s*=\s*(?:async\s*)?(?:\([^)]*\)|\w+)\s*=>/m,
];
const DEPENDENCY_INSTALL = /\b(?:pnpm|npm|yarn)\s+(?:install|ci|add|i)\b/;
const SCRIPTS = new URL("./", import.meta.url);
const require = createRequire(import.meta.url);

function codeLines(block: string): readonly string[] {
  return block
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line !== "" && !line.startsWith("#") && !line.startsWith("//"));
}

function inlineBlocks(step: WorkflowStep): readonly (readonly [string, string])[] {
  const blocks: (readonly [string, string])[] = [];
  if (typeof step.run === "string") {
    blocks.push(["run", step.run]);
  }
  if (typeof step.with?.["script"] === "string") {
    blocks.push(["script", step.with["script"]]);
  }
  return blocks;
}

function sparseCheckoutList(checkout: WorkflowStep): readonly string[] | undefined {
  const patterns = checkout.with?.["sparse-checkout"];
  return typeof patterns === "string" ? patterns.split("\n").map((line) => line.trim()).filter(Boolean) : undefined;
}

function runsRepositoryScript(step: WorkflowStep): boolean {
  return typeof step.run === "string" && /(?:^|\s)scripts\//.test(step.run);
}

function eachStep(action: (workflow: Workflow, jobName: string, step: WorkflowStep) => void): void {
  for (const workflow of WORKFLOWS) {
    for (const [jobName, job] of jobs(workflow)) {
      for (const step of steps(job)) {
        action(workflow, jobName, step);
      }
    }
  }
}

test(`every run or script block that stays inline has at most ${INLINE_LINE_LIMIT} lines of code and no function`, () => {
  eachStep((workflow, jobName, step) => {
    for (const [kind, block] of inlineBlocks(step)) {
      const key = `${stepKey(workflow, jobName, step)} ${kind}`;
      const lines = codeLines(block);
      assert.ok(
        lines.length <= INLINE_LINE_LIMIT,
        `${key} has ${lines.length} lines of code; move the code into a file under scripts/`,
      );
      for (const definition of FUNCTION_DEFINITIONS) {
        assert.doesNotMatch(block, definition, `${key} defines a function; move the code into a file under scripts/`);
      }
    }
  });
});

test("a script block that loads repository code is one require call of a CommonJS module exporting a function", () => {
  eachStep((workflow, jobName, step) => {
    const script = step.with?.["script"];
    if (typeof script !== "string" || !script.includes("require(")) {
      return;
    }
    const key = stepKey(workflow, jobName, step);
    const module = requiredModule(step);
    assert.ok(module, `${key} must be exactly: await require("./scripts/<name>.cjs")({ github, context, core });`);
    assert.ok(existsSync(repositoryFile(module)), `${key} loads ${module}, which does not exist`);
    assert.equal(typeof require(repositoryFile(module).pathname), "function", `${module} must export one async function`);
  });
});

test("every CommonJS module under scripts/ is loaded by a workflow step", () => {
  const loaded = new Set<string | undefined>();
  eachStep((_workflow, _jobName, step) => loaded.add(requiredModule(step)));
  for (const file of readdirSync(SCRIPTS).filter((name) => name.endsWith(".cjs"))) {
    assert.ok(loaded.has(`scripts/${file}`), `scripts/${file} is not loaded by any workflow; delete it or load it`);
  }
});

test("a job that loads a module checks it out first without the token, and a privileged job only from the workflow's commit", () => {
  for (const workflow of WORKFLOWS) {
    for (const [jobName, job] of jobs(workflow)) {
      const privileged = privilegeReasons(workflow, job).length > 0;
      const jobSteps = steps(job);
      const modules = jobSteps.map(requiredModule).filter((module) => module !== undefined);
      if (modules.length === 0) {
        continue;
      }
      const firstLoad = jobSteps.findIndex((step) => requiredModule(step) !== undefined);
      const checkout = jobSteps
        .slice(0, firstLoad)
        .findLast((step) => usesAction(step, "actions/checkout") && step.with?.["path"] === undefined);
      const key = `${workflow.file}:${jobName}`;
      assert.ok(checkout, `${key} must check out this repository before it loads ${modules.join(", ")}`);
      const checkoutKey = stepKey(workflow, jobName, checkout);
      assert.equal(checkout.with?.["persist-credentials"], false, `${checkoutKey} must set persist-credentials: false`);
      const sparse = sparseCheckoutList(checkout);
      if (sparse === undefined) {
        assert.ok(
          jobSteps.some(runsRepositoryScript),
          `${checkoutKey} must list the loaded modules in sparse-checkout unless the job runs other scripts/ files`,
        );
      } else {
        assert.equal(checkout.with?.["sparse-checkout-cone-mode"], false, `${checkoutKey} must list files, not cone directories`);
        assert.deepEqual([...sparse].sort(), [...new Set(modules)].sort(), `${checkoutKey} must check out exactly the modules the job loads`);
      }
      if (!privileged) {
        continue;
      }
      assert.ok(
        shaIsTrusted(workflow.document),
        `${key} loads a module in a privileged job but has a trigger on which github.sha can be pull request code`,
      );
      assert.ok(isWorkflowCommitRef(checkout.with?.["ref"]), `${checkoutKey} must check out \${{ github.sha }}, the commit the workflow file was read from`);
      for (const step of jobSteps) {
        for (const [kind, block] of inlineBlocks(step)) {
          assert.doesNotMatch(block, DEPENDENCY_INSTALL, `${stepKey(workflow, jobName, step)} ${kind} installs dependencies in a privileged job`);
        }
      }
    }
  }
});

test("the module lookup reports a missing step, a duplicate step, and a block that is not the require call", () => {
  const load = (script: string): WorkflowJob => ({steps: [{name: "Load", uses: "actions/github-script@pinned", with: {script}}]});
  const requireCall = 'await require("./scripts/x.cjs")({ github, context, core });\n';

  assert.equal(scriptPathOf({document: {jobs: {one: load(requireCall)}}, file: "one.yml"}, "Load"), "scripts/x.cjs");
  assert.throws(
    () => scriptPathOf({document: {jobs: {one: load(requireCall)}}, file: "one.yml"}, "Absent"),
    /Absent step must exist exactly once in one\.yml/,
  );
  assert.throws(
    () => scriptPathOf({document: {jobs: {one: load(requireCall), two: load(requireCall)}}, file: "two.yml"}, "Load"),
    /Load step must exist exactly once in two\.yml/,
  );
  assert.throws(
    () => scriptPathOf(
      {document: {jobs: {one: load('const run = require("./scripts/x.cjs");\nawait run({ github, context, core });\n')}}, file: "one.yml"},
      "Load",
    ),
    /Load in one\.yml must load its script with one require call/,
  );
});
