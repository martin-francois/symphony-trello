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
