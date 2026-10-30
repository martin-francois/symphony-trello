import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import test from "node:test";

import {
  type AuditIssue,
  auditIssues,
  type Finding,
  parseIssueNodes,
  renderReport,
  type RuleId,
} from "./audit-issue-labels.ts";
import {createFakeCommandEnvironment} from "./test-support/fake-command-environment.ts";

const script = resolve("scripts/audit-issue-labels.ts");
const REPOSITORY = "SYNTH001/synthetic-repo";
const READY_LABELS = ["enhancement", "priority medium", "size M", "help wanted"];
const UNPICKABLE_LABELS = ["enhancement", "priority medium", "size M"];

function issue(overrides: Partial<AuditIssue> = {}): AuditIssue {
  return {
    number: 1,
    title: "SYNTH001 ready issue",
    assigneeCount: 0,
    labels: READY_LABELS,
    openBlockers: 0,
    totalBlockers: 0,
    ...overrides,
  };
}

function ruleIds(findings: readonly Finding[]): RuleId[] {
  return findings.map(({id}) => id);
}

test("a ready, fully labelled issue has no findings", () => {
  // given
  const issues = [issue()];

  // when
  const findings = auditIssues(issues);

  // then
  assert.deepEqual(findings, []);
});

const CASES: readonly {readonly name: string; readonly issue: AuditIssue; readonly expected: readonly RuleId[]}[] = [
  {
    name: "a missing priority label",
    issue: issue({labels: ["size M", "help wanted"]}),
    expected: ["priority-count"],
  },
  {
    name: "two priority labels",
    issue: issue({labels: [...READY_LABELS, "priority high"]}),
    expected: ["priority-count"],
  },
  {
    name: "a missing size label",
    issue: issue({labels: ["priority low", "help wanted"]}),
    expected: ["size-count"],
  },
  {
    name: "two size labels",
    issue: issue({labels: [...READY_LABELS, "size L"]}),
    expected: ["size-count"],
  },
  {
    name: "`idea` without `not ready`",
    issue: issue({labels: [...UNPICKABLE_LABELS, "idea"]}),
    expected: ["idea-not-ready"],
  },
  {
    name: "`needs human review` without `not ready`",
    issue: issue({labels: [...UNPICKABLE_LABELS, "needs human review"]}),
    expected: ["human-review-not-ready"],
  },
  {
    name: "`help wanted` on a small `idea` issue without `not ready`",
    issue: issue({labels: ["priority low", "size XS", "help wanted", "idea"]}),
    expected: ["idea-not-ready", "gated-pickup"],
  },
  {
    name: "`help wanted` on a `not ready` issue",
    issue: issue({labels: [...READY_LABELS, "not ready"]}),
    expected: ["gated-pickup"],
  },
  {
    name: "`help wanted` on a `blocked` issue",
    issue: issue({labels: [...READY_LABELS, "blocked"]}),
    expected: ["gated-pickup", "blocked-external-wait"],
  },
  {
    name: "`good first issue` on an issue with an open native blocker",
    issue: issue({labels: ["priority low", "size S", "good first issue"], openBlockers: 1, totalBlockers: 1}),
    expected: ["gated-pickup"],
  },
  {
    name: "`help wanted` on a dependency dashboard",
    issue: issue({title: "Dependency Dashboard", labels: ["priority low", "size XS", "help wanted"]}),
    expected: ["gated-pickup"],
  },
  {
    name: "`help wanted` on an assigned issue",
    issue: issue({assigneeCount: 1}),
    expected: ["assigned-help-wanted"],
  },
  {
    name: "a ready, unassigned issue without `help wanted`",
    issue: issue({labels: ["priority medium", "size M"]}),
    expected: ["ready-help-wanted"],
  },
  {
    name: "`blocked` after every native blocker closed",
    issue: issue({labels: ["priority medium", "size M", "blocked"], totalBlockers: 2}),
    expected: ["blocked-closed-blockers"],
  },
  {
    name: "`blocked` with no native dependency",
    issue: issue({labels: ["priority medium", "size M", "blocked"]}),
    expected: ["blocked-external-wait"],
  },
  {
    name: "`blocked` with an open native blocker",
    issue: issue({labels: ["priority medium", "size M", "blocked"], openBlockers: 1, totalBlockers: 2}),
    expected: ["blocked-open-blocker"],
  },
  {
    name: "`already implemented` without `needs human review` and `not ready`",
    issue: issue({labels: [...UNPICKABLE_LABELS, "already implemented"]}),
    expected: ["already-implemented-labels", "already-implemented-verify"],
  },
  {
    name: "`already implemented` with `needs human review` and `not ready`",
    issue: issue({labels: ["priority medium", "size M", "already implemented", "needs human review", "not ready"]}),
    expected: ["already-implemented-verify"],
  },
  {
    name: "a small ready issue without `good first issue`",
    issue: issue({labels: ["priority medium", "size S", "help wanted"]}),
    expected: ["good-first-issue-candidate"],
  },
  {
    name: "`help wanted` on an issue that only mentions the dependency dashboard",
    issue: issue({title: "SYNTH001 fix the dependency dashboard link"}),
    expected: [],
  },
];

for (const {name, issue: audited, expected} of CASES) {
  test(`reports ${name}`, () => {
    // given
    const issues = [audited];

    // when
    const findings = auditIssues(issues);

    // then
    assert.deepEqual(ruleIds(findings), expected);
  });
}

test("lists `blocked` after every native blocker closed for a decision instead of failing", () => {
  // given
  const issues = [issue({labels: ["priority medium", "size M", "blocked"], totalBlockers: 1})];

  // when
  const findings = auditIssues(issues);

  // then
  assert.deepEqual(
    findings.map(({id, severity}) => ({id, severity})),
    [{id: "blocked-closed-blockers", severity: "judgment"}],
  );
});

test("accepts gated issues without pickup labels and small issues already marked `good first issue`", () => {
  // given
  const issues = [
    issue({number: 1, labels: ["priority low", "size S", "not ready"]}),
    issue({number: 2, labels: ["priority low", "size S"], openBlockers: 1, totalBlockers: 1}),
    issue({number: 3, title: "Dependency Dashboard", labels: ["priority low", "size XS"]}),
    issue({number: 4, labels: ["priority low", "size XS", "help wanted", "good first issue"]}),
    issue({number: 5, assigneeCount: 1, labels: ["priority low", "size L"]}),
  ];

  // when
  const findings = auditIssues(issues);

  // then
  assert.deepEqual(findings, []);
});

test("parses paginated issue nodes and treats a missing dependency summary as no blockers", () => {
  // given
  const output = [
    JSON.stringify({
      number: 7,
      title: "SYNTH001 first page",
      assignees: {totalCount: 2},
      labels: {totalCount: 1, nodes: [{name: "priority high"}]},
      issueDependenciesSummary: {blockedBy: 1, totalBlockedBy: 3},
    }),
    JSON.stringify({
      number: 8,
      title: "SYNTH001 second page",
      assignees: {totalCount: 0},
      labels: {totalCount: 0, nodes: []},
      issueDependenciesSummary: null,
    }),
    "",
  ].join("\n");

  // when
  const issues = parseIssueNodes(output);

  // then
  assert.deepEqual(issues, [
    {number: 7, title: "SYNTH001 first page", assigneeCount: 2, labels: ["priority high"], openBlockers: 1, totalBlockers: 3},
    {number: 8, title: "SYNTH001 second page", assigneeCount: 0, labels: [], openBlockers: 0, totalBlockers: 0},
  ]);
});

test("refuses an issue whose labels do not fit in one query page", () => {
  // given
  const output = JSON.stringify({
    number: 9,
    title: "SYNTH001 many labels",
    assignees: {totalCount: 0},
    labels: {totalCount: 101, nodes: [{name: "priority low"}]},
    issueDependenciesSummary: null,
  });

  // when
  const parse = () => parseIssueNodes(output);

  // then
  assert.throws(parse, /issue #9 has more labels than one query page returns/);
});

test("renders failures and judgment items in separate sections", () => {
  // given
  const issues = [
    issue({number: 11, title: "SYNTH001 unprioritized", labels: ["size M", "help wanted"]}),
    issue({number: 12, title: "SYNTH001 small", labels: ["priority low", "size XS", "help wanted"]}),
  ];

  // when
  const report = renderReport(REPOSITORY, issues.length, auditIssues(issues));

  // then
  assert.equal(
    report,
    `Issue label audit of ${REPOSITORY}, 2 open issues. Failures: 1. Judgment items: 1.

Failures (fix the labels):
  needs exactly one priority label (priority high, priority medium, priority low): 1
    #11 SYNTH001 unprioritized

Judgment items (decide each one and report the decision):
  small ready issue without \`good first issue\`: decide whether it meets the 80% one-shot bar: 1
    #12 SYNTH001 small`,
  );
});

function runCli(nodes: readonly object[], args: readonly string[]) {
  const fake = createFakeCommandEnvironment("audit-issue-labels-bin-", {
    gh: `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_COMMAND_LOG"
if [ "$1" = repo ]; then
  printf '%s\\n' "${REPOSITORY}"
else
  cat "$ISSUES_FILE"
fi
`,
  });
  const issuesFile = join(fake.directory, "issues.jsonl");
  writeFileSync(issuesFile, nodes.map((node) => JSON.stringify(node)).join("\n"));
  const result = spawnSync("node", [script, ...args], {
    encoding: "utf8",
    env: fake.environment({ISSUES_FILE: issuesFile}),
  });
  return {result, log: fake.readLog()};
}

function node(number: number, labels: readonly string[]) {
  return {
    number,
    title: `SYNTH001 issue ${number}`,
    assignees: {totalCount: 0},
    labels: {totalCount: labels.length, nodes: labels.map((name) => ({name}))},
    issueDependenciesSummary: {blockedBy: 0, totalBlockedBy: 0},
  };
}

test("the command exits 0 when only judgment items remain", () => {
  // given
  const nodes = [node(21, ["priority low", "size S", "help wanted"])];

  // when
  const {result, log} = runCli(nodes, [REPOSITORY]);

  // then
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Failures: 0\. Judgment items: 1\./);
  assert.match(log, /^api graphql --paginate -f owner=SYNTH001 -f name=synthetic-repo -f query=/m);
});

test("the command exits 1 on a failure and reads the repository from gh when none is given", () => {
  // given
  const nodes = [node(22, ["priority low", "size M"])];

  // when
  const {result, log} = runCli(nodes, []);

  // then
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stdout, /ready, unassigned issue without `help wanted`: 1\n {4}#22 SYNTH001 issue 22/);
  assert.match(log, /^repo view --json nameWithOwner/m);
});
