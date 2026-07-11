import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";

import {
  type CommitMetadata,
  evaluateCompatibilityMetadata,
  evaluatePullRequestBreakingConsistency,
} from "./check-pr-compatibility-metadata.ts";

const TEMPLATE = readFileSync(
  new URL("../.github/pull_request_template.md", import.meta.url),
  "utf8",
);
const COMPATIBLE = "Compatible: no previously supported, working usage stops working";
const BREAKING = "Breaking: previously supported, working usage stops working";
const UNSURE = "Unsure: maintainer decision required";
const COMBINE = "Combine this pull request into one final commit. The branch commits are review steps and do not\n"
  + "      need to remain separate in `main`. (squash)";
const KEEP = "Keep the individual commits. Each commit is independently meaningful and should remain visible\n"
  + "      in `main`. (rebase)";
const RATIONALE_ERROR = "Provide a non-placeholder `Because ...` compatibility rationale.";
const SELECTION_ERROR = "Select exactly one current option in the Compatibility Decision section.";
const HISTORY_SELECTION_ERROR = "Select exactly one current option in the Commit History in Main section.";

function replaceExactly(source: string, expected: string, replacement: string): string {
  const parts = source.split(expected);
  assert.equal(parts.length, 2, `expected exactly one template occurrence of ${JSON.stringify(expected)}`);
  return `${parts[0]}${replacement}${parts[1]}`;
}

function select(template: string, decision: string): string {
  return replaceExactly(template, `- [ ] ${decision}`, `- [x] ${decision}`);
}

function selectHistory(template: string, choice: string): string {
  return replaceExactly(template, `- [ ] ${choice}`, `- [x] ${choice}`);
}

const KEEP_TEMPLATE = selectHistory(TEMPLATE, KEEP);

function rationale(template: string, value = "Because existing workflows keep their documented behavior."): string {
  return replaceExactly(template, "`Because ...`", `\`${value}\``);
}

function breakingTemplate(history: "combine" | "keep" = "keep"): string {
  const template = history === "combine" ? selectHistory(TEMPLATE, COMBINE) : KEEP_TEMPLATE;
  return replaceExactly(
    replaceExactly(
      replaceExactly(rationale(select(template, BREAKING)), "`Breaks: ...`", "`Breaks: The old key is removed.`"),
      "`Migration: ...`",
      "`Migration: Replace the old key before upgrading.`",
    ),
    "`Alternative: ...`",
    "`Alternative: Stay on the previous release temporarily.`",
  );
}

function assertErrorsInclude(actual: readonly string[], ...expected: readonly string[]): void {
  for (const error of expected) {
    assert.ok(actual.includes(error), `expected errors to include ${JSON.stringify(error)}; got ${JSON.stringify(actual)}`);
  }
}

test("accepts the literal template with Combine selected", () => {
  const body = rationale(select(selectHistory(TEMPLATE, COMBINE), COMPATIBLE));

  assert.deepEqual(evaluateCompatibilityMetadata("feat: preserve behavior", body), []);
});

test("accepts the literal template with Keep selected", () => {
  const body = rationale(select(KEEP_TEMPLATE, COMPATIBLE));

  assert.deepEqual(evaluateCompatibilityMetadata("feat: preserve behavior", body), []);
});

test("rejects a missing commit-history selection", () => {
  const body = rationale(select(TEMPLATE, COMPATIBLE));

  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", body), HISTORY_SELECTION_ERROR);
});

test("rejects multiple commit-history selections", () => {
  const body = rationale(select(selectHistory(selectHistory(TEMPLATE, COMBINE), KEEP), COMPATIBLE));

  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", body), HISTORY_SELECTION_ERROR);
});

test("accepts uppercase commit-history selection and trailing horizontal whitespace", () => {
  const selected = selectHistory(TEMPLATE, COMBINE)
    .replace("- [x] Combine this pull request", "- [X] Combine this pull request")
    .replace("do not\n      need to remain", "do not  \n      need to remain")
    .replace("in `main`. (squash)", "in `main`. (squash)\t");
  const body = rationale(select(selected, COMPATIBLE));

  assert.deepEqual(evaluateCompatibilityMetadata("feat: preserve behavior", body), []);
});

test("ignores a selected commit-history option inside a backtick fence", () => {
  const body = rationale(select(replaceExactly(
    TEMPLATE,
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + "to merge it.",
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + `to merge it.\n\n\`\`\`markdown\n- [x] ${COMBINE}\n\`\`\``,
  ), COMPATIBLE));

  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", body), HISTORY_SELECTION_ERROR);
});

test("ignores a selected commit-history option inside a tilde fence", () => {
  const body = rationale(select(replaceExactly(
    TEMPLATE,
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + "to merge it.",
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + `to merge it.\n\n~~~markdown\n- [x] ${KEEP}\n~~~`,
  ), COMPATIBLE));

  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", body), HISTORY_SELECTION_ERROR);
});

test("ignores a selected commit-history option inside an HTML comment", () => {
  const body = rationale(select(replaceExactly(
    TEMPLATE,
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + "to merge it.",
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + `to merge it.\n\n<!--\n- [x] ${KEEP}\n-->`,
  ), COMPATIBLE));

  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", body), HISTORY_SELECTION_ERROR);
});

test("ignores blockquoted and nested commit-history options", () => {
  const body = rationale(select(replaceExactly(
    TEMPLATE,
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + "to merge it.",
    "Choose one option based on how this pull request should appear in `main`, not on the Git command used\n"
      + `to merge it.\n\n> - [x] ${COMBINE}\n  - [x] ${KEEP}`,
  ), COMPATIBLE));

  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", body), HISTORY_SELECTION_ERROR);
});

test("rejects duplicate visible Commit History in Main sections", () => {
  const body = `${rationale(select(KEEP_TEMPLATE, COMPATIBLE))}\n## Commit History in Main\n\n- [x] ${KEEP}\n`;

  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat: preserve behavior", body),
    "Provide exactly one visible Commit History in Main section.",
  );
});

test("treats CRLF and LF commit-history metadata identically", () => {
  const lf = rationale(select(selectHistory(TEMPLATE, COMBINE), COMPATIBLE));

  assert.deepEqual(
    evaluateCompatibilityMetadata("feat: preserve behavior", lf.replaceAll("\n", "\r\n")),
    evaluateCompatibilityMetadata("feat: preserve behavior", lf),
  );
});

test("rejects invented Git-jargon commit-history choices", () => {
  const invented = replaceExactly(TEMPLATE, `- [ ] ${COMBINE}`, "- [x] Squash merge");
  const body = rationale(select(invented, COMPATIBLE));

  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", body), HISTORY_SELECTION_ERROR);
});

test("accepts the literal Compatible template with uppercase selection and trailing whitespace", () => {
  const body = rationale(select(KEEP_TEMPLATE, COMPATIBLE))
    .replace(`- [x] ${COMPATIBLE}`, `- [X] ${COMPATIBLE}  `);

  assert.deepEqual(evaluateCompatibilityMetadata("feat(setup): improve validation", body), []);
});

test("accepts the literal Breaking template fields without a PR-body breaking footer", () => {
  assert.deepEqual(evaluateCompatibilityMetadata("feat(setup)!: replace configuration", breakingTemplate()), []);
});

test("rejects the literal Unsure template until a maintainer decides", () => {
  assert.deepEqual(evaluateCompatibilityMetadata("feat: consider behavior", rationale(select(KEEP_TEMPLATE, UNSURE))), [
    "Resolve the compatibility decision with a maintainer before the pull request is ready.",
  ]);
});

test("rejects untouched, missing, non-Because, and empty Because rationales", () => {
  const selected = select(KEEP_TEMPLATE, COMPATIBLE);
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: change behavior", selected), RATIONALE_ERROR);
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat: change behavior", replaceExactly(selected, "`Because ...`", "")),
    RATIONALE_ERROR,
  );
  assertErrorsInclude(
    evaluateCompatibilityMetadata(
      "feat: change behavior",
      replaceExactly(selected, "`Because ...`", "`Existing behavior is preserved.`"),
    ),
    RATIONALE_ERROR,
  );
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat: change behavior", replaceExactly(selected, "`Because ...`", "`Because`")),
    RATIONALE_ERROR,
  );
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat: change behavior", replaceExactly(selected, "`Because ...`", "Because ...")),
    RATIONALE_ERROR,
  );
});

test("accepts an unquoted non-placeholder Because rationale", () => {
  const body = replaceExactly(
    select(KEEP_TEMPLATE, COMPATIBLE),
    "`Because ...`",
    "Because the supported contract remains unchanged.",
  );
  assert.deepEqual(evaluateCompatibilityMetadata("feat: preserve behavior", body), []);
});

test("requires the title marker and each authoritative Breaking field", () => {
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat: replace configuration", breakingTemplate()),
    "A breaking pull request title must use the Conventional Commit ! marker.",
  );

  const selected = rationale(select(KEEP_TEMPLATE, BREAKING));
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat!: replace configuration", selected),
    "A breaking pull request must provide non-placeholder `Breaks: ...` details.",
    "A breaking pull request must provide a non-placeholder `Migration: ...` path.",
    "A breaking pull request must provide a non-placeholder `Alternative: ...`.",
  );
});

test("reports each individually untouched Breaking field", () => {
  const valid = breakingTemplate();
  assertErrorsInclude(
    evaluateCompatibilityMetadata(
      "feat!: replace configuration",
      replaceExactly(valid, "`Breaks: The old key is removed.`", "`Breaks: ...`"),
    ),
    "A breaking pull request must provide non-placeholder `Breaks: ...` details.",
  );
  assertErrorsInclude(
    evaluateCompatibilityMetadata(
      "feat!: replace configuration",
      replaceExactly(valid, "`Migration: Replace the old key before upgrading.`", "`Migration: ...`"),
    ),
    "A breaking pull request must provide a non-placeholder `Migration: ...` path.",
  );
  assertErrorsInclude(
    evaluateCompatibilityMetadata(
      "feat!: replace configuration",
      replaceExactly(valid, "`Alternative: Stay on the previous release temporarily.`", "`Alternative: ...`"),
    ),
    "A breaking pull request must provide a non-placeholder `Alternative: ...`.",
  );
});

test("Compatible permits untouched breaking-only fields but rejects a title marker", () => {
  const compatible = rationale(select(KEEP_TEMPLATE, COMPATIBLE));
  assert.deepEqual(evaluateCompatibilityMetadata("feat: preserve behavior", compatible), []);
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat!: preserve behavior", compatible),
    "A compatible pull request title must not use the Conventional Commit ! marker.",
  );
});

test("rejects missing and multiple direct decisions", () => {
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: change behavior", rationale(KEEP_TEMPLATE)), SELECTION_ERROR);
  const multiple = select(select(rationale(KEEP_TEMPLATE), COMPATIBLE), BREAKING);
  assertErrorsInclude(evaluateCompatibilityMetadata("feat!: change behavior", multiple), SELECTION_ERROR);
});

test("ignores selected decisions and complete fake sections inside backtick fences", () => {
  const fencedSelection = replaceExactly(
    rationale(KEEP_TEMPLATE),
    "Choose one option.",
    `Choose one option.\n\n\`\`\`markdown\n- [x] ${COMPATIBLE}\n\`\`\``,
  );
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: change behavior", fencedSelection), SELECTION_ERROR);

  const fakeSection = `\`\`\`markdown\n## Compatibility Decision\n- [x] ${BREAKING}\n\`Because fake.\`\n\`\`\`\n\n`;
  assert.deepEqual(
    evaluateCompatibilityMetadata("feat: preserve behavior", `${fakeSection}${rationale(select(KEEP_TEMPLATE, COMPATIBLE))}`),
    [],
  );
});

test("ignores fake metadata inside tilde fences and HTML comments", () => {
  const valid = rationale(select(KEEP_TEMPLATE, COMPATIBLE));
  const tilde = `~~~markdown\n## Compatibility Decision\n- [x] ${BREAKING}\n~~~\n`;
  const comment = `<!--\n## Compatibility Decision\n- [x] ${BREAKING}\n-->\n`;
  assert.deepEqual(evaluateCompatibilityMetadata("feat: preserve behavior", `${tilde}${comment}${valid}`), []);
});

test("fenced and commented rationale text cannot satisfy the expected field", () => {
  const selected = select(KEEP_TEMPLATE, COMPATIBLE);
  const fenced = replaceExactly(selected, "`Because ...`", "```text\n`Because hidden.`\n```");
  const commented = replaceExactly(selected, "`Because ...`", "<!-- `Because hidden.` -->");
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", fenced), RATIONALE_ERROR);
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", commented), RATIONALE_ERROR);
});

test("fenced and commented Breaking fields cannot satisfy the expected fields", () => {
  let body = rationale(select(KEEP_TEMPLATE, BREAKING));
  body = replaceExactly(body, "`Breaks: ...`", "```text\n`Breaks: Hidden.`\n```");
  body = replaceExactly(body, "`Migration: ...`", "<!-- `Migration: Hidden.` -->");
  body = replaceExactly(body, "`Alternative: ...`", "> `Alternative: Quoted.`");
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat!: replace behavior", body),
    "A breaking pull request must provide non-placeholder `Breaks: ...` details.",
    "A breaking pull request must provide a non-placeholder `Migration: ...` path.",
    "A breaking pull request must provide a non-placeholder `Alternative: ...`.",
  );
});

test("nested-list and blockquote decision examples are not direct metadata", () => {
  const body = replaceExactly(
    rationale(KEEP_TEMPLATE),
    "Choose one option.",
    `Choose one option.\n\n  - [x] ${COMPATIBLE}\n> - [x] ${BREAKING}`,
  );
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: change behavior", body), SELECTION_ERROR);
});

test("rejects duplicate visible Compatibility Decision sections", () => {
  const valid = rationale(select(KEEP_TEMPLATE, COMPATIBLE));
  assert.deepEqual(evaluateCompatibilityMetadata("feat: preserve behavior", `${valid}\n${valid}`), [
    "Provide exactly one visible Compatibility Decision section.",
  ]);
});

test("ignores canonical-looking checkboxes outside the compatibility section", () => {
  const outside = `${rationale(KEEP_TEMPLATE)}\n## Example\n\n- [x] ${COMPATIBLE}\n`;
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: change behavior", outside), SELECTION_ERROR);
});

test("rejects every removed previous-template selection", () => {
  for (const oldSelection of ["No visible change", "Could break existing usage", "Unsure"]) {
    const body = replaceExactly(
      rationale(KEEP_TEMPLATE),
      `- [ ] ${COMPATIBLE}`,
      `- [x] ${oldSelection}\n- [ ] ${COMPATIBLE}`,
    );
    assertErrorsInclude(evaluateCompatibilityMetadata("feat: change behavior", body), SELECTION_ERROR);
  }
});

test("does not accept Compatibility or BREAKING CHANGE substitutes", () => {
  const compatible = replaceExactly(
    select(KEEP_TEMPLATE, COMPATIBLE),
    "`Because ...`",
    "`Compatibility: Existing behavior is preserved.`",
  );
  assertErrorsInclude(evaluateCompatibilityMetadata("feat: preserve behavior", compatible), RATIONALE_ERROR);

  const breaking = `${rationale(select(KEEP_TEMPLATE, BREAKING))}\nBREAKING CHANGE: This is not a template field.\n`;
  assertErrorsInclude(
    evaluateCompatibilityMetadata("feat!: replace behavior", breaking),
    "A breaking pull request must provide non-placeholder `Breaks: ...` details.",
    "A breaking pull request must provide a non-placeholder `Migration: ...` path.",
    "A breaking pull request must provide a non-placeholder `Alternative: ...`.",
  );
});

test("treats CRLF and LF bodies identically", () => {
  const lf = rationale(select(KEEP_TEMPLATE, COMPATIBLE));
  const crlf = lf.replaceAll("\n", "\r\n");
  assert.deepEqual(
    evaluateCompatibilityMetadata("feat: preserve behavior", crlf),
    evaluateCompatibilityMetadata("feat: preserve behavior", lf),
  );
});

const COMPLETE_BREAKING_COMMIT: CommitMetadata = {
  subject: "feat(setup)!: replace configuration",
  body: "BREAKING CHANGE: Replace the old key before upgrading.",
};
const INCOMPLETE_MARKER_ERROR =
  "Each branch commit containing either breaking marker must contain both ! in its subject and a non-placeholder BREAKING CHANGE: footer.";
const KEEP_MARKER_ERROR =
  "A Breaking pull request that keeps the individual commits must contain a branch commit with both ! in its subject and a non-placeholder BREAKING CHANGE: footer.";

test("Breaking Combine mode accepts no retained breaking commit", () => {
  assert.deepEqual(
    evaluatePullRequestBreakingConsistency("feat(setup)!: replace configuration", breakingTemplate("combine"), [
      {subject: "feat(setup): prepare configuration", body: ""},
    ]),
    [],
  );
});

test("Breaking Combine mode accepts a complete marker-bearing branch commit", () => {
  assert.deepEqual(
    evaluatePullRequestBreakingConsistency(
      "feat(setup)!: replace configuration",
      breakingTemplate("combine"),
      [COMPLETE_BREAKING_COMMIT],
    ),
    [],
  );
});

test("Breaking Combine mode rejects a branch commit containing only !", () => {
  assertErrorsInclude(
    evaluatePullRequestBreakingConsistency("feat!: replace behavior", breakingTemplate("combine"), [
      {subject: "feat!: replace behavior", body: ""},
    ]),
    INCOMPLETE_MARKER_ERROR,
  );
});

test("Breaking Combine mode rejects a branch commit containing only a footer", () => {
  assertErrorsInclude(
    evaluatePullRequestBreakingConsistency("feat!: replace behavior", breakingTemplate("combine"), [
      {subject: "feat: replace behavior", body: "BREAKING CHANGE: Replace the old key."},
    ]),
    INCOMPLETE_MARKER_ERROR,
  );
});

test("Compatible Combine mode accepts clean commits", () => {
  const body = rationale(select(selectHistory(TEMPLATE, COMBINE), COMPATIBLE));
  assert.deepEqual(evaluatePullRequestBreakingConsistency("feat: preserve behavior", body, [
    {subject: "feat: preserve behavior", body: ""},
  ]), []);
});

test("Compatible Combine mode rejects a branch breaking marker", () => {
  const body = rationale(select(selectHistory(TEMPLATE, COMBINE), COMPATIBLE));
  assertErrorsInclude(
    evaluatePullRequestBreakingConsistency("feat: preserve behavior", body, [COMPLETE_BREAKING_COMMIT]),
    "A breaking PR title or commit marker requires the PR compatibility decision to be Breaking.",
    "A breaking commit marker requires the PR title to use the Conventional Commit ! marker.",
  );
});

test("Breaking Keep mode rejects a missing complete breaking commit", () => {
  assertErrorsInclude(
    evaluatePullRequestBreakingConsistency("feat!: replace behavior", breakingTemplate(), [
      {subject: "refactor: prepare behavior", body: ""},
    ]),
    KEEP_MARKER_ERROR,
  );
});

test("Breaking Keep mode accepts one commit containing both markers", () => {
  assert.deepEqual(
    evaluatePullRequestBreakingConsistency("feat(setup)!: replace configuration", breakingTemplate(), [
      COMPLETE_BREAKING_COMMIT,
    ]),
    [],
  );
});

test("Breaking Keep mode rejects markers split across two commits", () => {
  const splitMarkers: readonly CommitMetadata[] = [
    {subject: "feat(setup)!: replace configuration", body: ""},
    {subject: "docs: explain migration", body: "BREAKING CHANGE: Replace the old key."},
  ];
  assertErrorsInclude(
    evaluatePullRequestBreakingConsistency("feat(setup)!: replace configuration", breakingTemplate(), splitMarkers),
    INCOMPLETE_MARKER_ERROR,
    KEEP_MARKER_ERROR,
  );
});

test("a marker-bearing branch commit requires a Breaking PR decision", () => {
  assertErrorsInclude(
    evaluatePullRequestBreakingConsistency(
      "feat!: preserve behavior",
      rationale(select(KEEP_TEMPLATE, COMPATIBLE)),
      [COMPLETE_BREAKING_COMMIT],
    ),
    "A breaking PR title or commit marker requires the PR compatibility decision to be Breaking.",
  );
});

test("a marker-bearing branch commit requires ! in the PR title", () => {
  assertErrorsInclude(
    evaluatePullRequestBreakingConsistency("feat: replace behavior", breakingTemplate(), [COMPLETE_BREAKING_COMMIT]),
    "A breaking commit marker requires the PR title to use the Conventional Commit ! marker.",
  );
});

test("Compatible Keep mode accepts clean retained commits", () => {
  const body = rationale(select(KEEP_TEMPLATE, COMPATIBLE));
  assert.deepEqual(evaluatePullRequestBreakingConsistency("feat: preserve behavior", body, [
    {subject: "feat: preserve behavior", body: ""},
    {subject: "refactor: simplify support", body: ""},
  ]), []);
});

test("Keep mode accepts one clean commit when the PR is Compatible", () => {
  const body = rationale(select(KEEP_TEMPLATE, COMPATIBLE));
  assert.deepEqual(evaluatePullRequestBreakingConsistency("feat: preserve behavior", body, [
    {subject: "feat: preserve behavior", body: ""},
  ]), []);
});

test("either incomplete branch marker is rejected in both modes", () => {
  for (const history of ["combine", "keep"] as const) {
    for (const commit of [
      {subject: "feat!: replace behavior", body: ""},
      {subject: "feat: replace behavior", body: "BREAKING CHANGE: Replace the old key."},
      {subject: "feat!: replace behavior", body: "BREAKING CHANGE: ..."},
    ]) {
      assertErrorsInclude(
        evaluatePullRequestBreakingConsistency("feat!: replace behavior", breakingTemplate(history), [commit]),
        INCOMPLETE_MARKER_ERROR,
      );
    }
  }
});

test("unrelated PR-body BREAKING CHANGE text is not a commit marker", () => {
  const body = `${rationale(select(KEEP_TEMPLATE, COMPATIBLE))}\nBREAKING CHANGE: An explanatory example only.\n`;
  assert.deepEqual(
    evaluatePullRequestBreakingConsistency("feat: preserve behavior", body, [
      {subject: "feat: preserve behavior", body: ""},
    ]),
    [],
  );
});
