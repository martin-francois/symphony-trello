import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {parse} from "yaml";

interface PreMergeCheck {
  readonly mode?: string;
}

interface CustomCheck extends PreMergeCheck {
  readonly instructions: string;
  readonly name: string;
}

interface CodeRabbitConfig {
  readonly reviews: {
    readonly allow_author_approval?: boolean;
    readonly pre_merge_checks: {
      readonly custom_checks: readonly CustomCheck[];
      readonly description?: PreMergeCheck;
      readonly override_requested_reviewers_only?: boolean;
    };
    readonly request_changes_workflow?: boolean;
  };
}

const CONFIG = parse(
  readFileSync(new URL("../.coderabbit.yaml", import.meta.url), "utf8"),
) as CodeRabbitConfig;
const TEMPLATE = readFileSync(
  new URL("../.github/pull_request_template.md", import.meta.url),
  "utf8",
);
const CUSTOM_CHECKS = CONFIG.reviews.pre_merge_checks.custom_checks;
// Every custom check opens with the same rules for reading the body, up to and including this line.
const SHARED_PREAMBLE_END = "Fail when the body is empty.";

test("custom pre-merge checks quote only text the pull request template contains", () => {
  // given
  const quotedExcerpts = CUSTOM_CHECKS.map((check) => ({
    check: check.name,
    excerpts: [...check.instructions.replaceAll(/\s+/gu, " ").matchAll(/"([^"]+)"/gu)].map(
      (match) => match[1] ?? "",
    ),
  }));

  // when
  const missingExcerpts = quotedExcerpts.flatMap(({check, excerpts}) =>
    excerpts.filter((excerpt) => !TEMPLATE.includes(excerpt)).map((excerpt) => `${check}: ${excerpt}`),
  );

  // then
  assert.ok(
    quotedExcerpts.every(({excerpts}) => excerpts.length > 0),
    "every custom check must quote the template text it looks for",
  );
  assert.deepEqual(missingExcerpts, []);
});

test("custom pre-merge checks share one preamble for reading the body", () => {
  // given
  const preambleEnds = CUSTOM_CHECKS.map((check) => check.instructions.indexOf(SHARED_PREAMBLE_END));

  // when
  const checksWithoutPreamble = CUSTOM_CHECKS.filter((_, index) => preambleEnds[index] === -1).map(
    (check) => check.name,
  );
  const preambles = CUSTOM_CHECKS.map((check, index) =>
    check.instructions.slice(0, (preambleEnds[index] ?? 0) + SHARED_PREAMBLE_END.length),
  );

  // then
  assert.deepEqual(
    checksWithoutPreamble,
    [],
    `every custom check must end its shared preamble with "${SHARED_PREAMBLE_END}"`,
  );
  assert.equal(
    new Set(preambles).size,
    1,
    "edit the shared preamble in every custom check, so all checks read the body the same way",
  );
});

test("the description check blocks in error mode through the request changes workflow", () => {
  // given
  const {reviews} = CONFIG;

  // when
  const descriptionMode = reviews.pre_merge_checks.description?.mode;

  // then
  assert.equal(descriptionMode, "error", "the built-in description check must stay in error mode");
  assert.equal(
    reviews.request_changes_workflow,
    true,
    "error mode blocks a merge only through the request changes workflow",
  );
});

test("the pull request author cannot clear a failed pre-merge check without fixing the body", () => {
  // given
  const {reviews} = CONFIG;

  // when
  const {override_requested_reviewers_only: overrideByReviewersOnly} = reviews.pre_merge_checks;
  const {allow_author_approval: allowAuthorApproval} = reviews;

  // then
  assert.equal(
    overrideByReviewersOnly,
    true,
    "only requested reviewers may ignore failed pre-merge checks, otherwise the author can skip them",
  );
  assert.equal(
    allowAuthorApproval,
    false,
    "the author must not run @coderabbitai approve, which approves even with failing checks",
  );
});

test("assistance disclosure requires exactly one explicit choice", () => {
  // given
  const check = CUSTOM_CHECKS.find(({name}) => name === "AI assistance disclosed");

  // when
  const instructions = check?.instructions.replaceAll(/\s+/gu, " ") ?? "";

  // then
  assert.equal(check?.mode, "error");
  assert.ok(instructions.includes("Fail when neither disclosure choice is ticked or both choices are ticked."));
  assert.ok(instructions.includes("exactly one must be ticked"));
  assert.ok(instructions.includes('Fail when "AI-assisted PR" is ticked and the confirmation is not.'));
  assert.ok(instructions.includes("No AI assistance beyond short completion, search, formatting, or typo fixes"));
});

test("redaction acknowledgement is required in error mode", () => {
  // given
  const check = CUSTOM_CHECKS.find(({name}) => name === "Redaction checked");

  // when
  const instructions = check?.instructions ?? "";

  // then
  assert.equal(check?.mode, "error");
  assert.ok(instructions.includes('"Redaction checked:"'));
  assert.ok(instructions.includes("checkbox is not ticked"));
});

interface GuardWorkflow {
  readonly on: {readonly pull_request_target: {readonly types: readonly string[]; readonly paths?: readonly string[]; readonly "paths-ignore"?: readonly string[]}};
  readonly permissions: Record<string, never>;
  readonly jobs: {
    readonly "coderabbit-config-guard": {
      readonly if?: string;
      readonly permissions: {readonly "pull-requests": string};
      readonly steps: readonly {readonly uses: string; readonly with: {readonly script: string}}[];
    };
  };
}

const GUARD = parse(readFileSync(new URL("../.github/workflows/coderabbit-config-guard.yml", import.meta.url), "utf8")) as GuardWorkflow;

test("the configuration guard executes trusted base code without a checkout", () => {
  // when
  const job = GUARD.jobs["coderabbit-config-guard"];

  // then
  assert.deepEqual(GUARD.on.pull_request_target.types, ["opened", "synchronize", "reopened"]);
  assert.equal(GUARD.on.pull_request_target.paths, undefined);
  assert.equal(GUARD.on.pull_request_target["paths-ignore"], undefined);
  assert.equal(job.if, undefined);
  assert.deepEqual(GUARD.permissions, {});
  assert.deepEqual(job.permissions, {"pull-requests": "read"});
  assert.equal(job.steps.length, 1);
  assert.ok(job.steps[0]?.uses.startsWith("actions/github-script@"));
});

for (const scenario of [
  {name: "GitHub file limit", author: "contributor", files: Array.from({length: 3000}, () => ({filename: "other.txt"})), fails: true, message: "Cannot verify CodeRabbit configuration at GitHub's 3000-file limit. Split this PR."},
  {name: "owner changes config", author: "martinfrancois", files: [{filename: ".coderabbit.yaml"}], fails: false},
  {name: "contributor changes another file", author: "contributor", files: [{filename: "README.md"}], fails: false},
  {name: "release bot changes config", author: "github-actions[bot]", files: [{filename: ".coderabbit.yaml"}], fails: true},
  {name: "contributor changes config", author: "contributor", files: [{filename: ".coderabbit.yaml"}], fails: true},
  {name: "contributor renames config away", author: "contributor", files: [{filename: "config.yaml", previous_filename: ".coderabbit.yaml"}], fails: true},
  {name: "contributor renames a file into config", author: "contributor", files: [{filename: ".coderabbit.yaml", previous_filename: "config.yaml"}], fails: true},
]) {
  test(`configuration guard: ${scenario.name}`, async () => {
    // given
    const failures: string[] = [];
    const script = GUARD.jobs["coderabbit-config-guard"].steps[0]?.with.script ?? "";
    const run = new Function("context", "github", "core", `return (async () => {${script}})();`);
    const context = {repo: {owner: "martin-francois", repo: "symphony-trello"}, payload: {pull_request: {number: 1, user: {login: scenario.author}}}};
    const github = {rest: {pulls: {listFiles: "listFiles"}}, paginate: async () => scenario.files};

    // when
    await run(context, github, {setFailed: (message: string) => failures.push(message)});

    // then
    const expectedFailure = "message" in scenario ? scenario.message : "Only martinfrancois may change .coderabbit.yaml.";
    assert.deepEqual(failures, scenario.fails ? [expectedFailure] : []);
  });
}
