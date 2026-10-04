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
      readonly [check: string]: PreMergeCheck | readonly CustomCheck[] | boolean | undefined;
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

test("the description check blocks in error mode through the request changes workflow", () => {
  // given
  const checks = Object.values(CONFIG.reviews.pre_merge_checks).flatMap((value) =>
    typeof value === "object" ? [value].flat() : [],
  ) as readonly PreMergeCheck[];

  // when
  const blocking = checks.some((check) => check.mode === "error");

  // then
  assert.ok(blocking, "expected at least one pre-merge check in error mode");
  assert.equal(CONFIG.reviews.pre_merge_checks.description?.mode, "error");
  assert.equal(CONFIG.reviews.request_changes_workflow, true);
});

test("the pull request author cannot clear a failed pre-merge check without fixing the body", () => {
  // given
  const {reviews} = CONFIG;

  // when
  const authorCanOverride = reviews.pre_merge_checks.override_requested_reviewers_only !== true;
  const authorCanApprove = reviews.allow_author_approval !== false;

  // then
  assert.deepEqual({authorCanApprove, authorCanOverride}, {authorCanApprove: false, authorCanOverride: false});
});
