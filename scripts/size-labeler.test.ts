import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {loadWorkflowScript, withDateNow, withEnvironment} from "./test-support/workflow-script.ts";

interface LabelChanges {
  readonly added: string[];
  readonly removed: string[];
}

interface PullRequestNode {
  readonly number: number;
}

interface ChangedFileNode {
  readonly additions: number;
  readonly deletions: number;
  readonly filename: string;
  readonly previous_filename?: unknown;
  readonly status?: string;
}

interface CandidatePullRequestNode {
  readonly base: {readonly repo: {readonly full_name: string}};
  readonly head: {
    readonly ref: string;
    readonly repo: {readonly full_name: string};
    readonly sha: string;
  };
  readonly number: number;
  readonly state: string;
}

interface WorkflowRunNode {
  readonly head_branch?: string;
  readonly head_repository?: {readonly full_name?: string};
  readonly head_sha?: string;
  readonly pull_requests?: readonly PullRequestNode[];
}

interface SizeLabelerPayload {
  readonly pull_request?: PullRequestNode;
  readonly workflow_run?: WorkflowRunNode;
}

type WorkflowEventName = "pull_request_target" | "schedule" | "workflow_run";

interface RunOptions {
  readonly addFailure?: Error;
  readonly additions?: number;
  readonly candidatePullRequestReadFailure?: Error;
  readonly candidatePullRequests?: readonly CandidatePullRequestNode[];
  readonly changedFiles?: number;
  readonly deletions?: number;
  readonly existingLabels?: readonly string[];
  readonly fileReadFailure?: Error;
  readonly files?: readonly ChangedFileNode[];
  readonly labelReadFailure?: Error;
  readonly nowMs?: number;
  readonly pullRequestReadFailure?: Error;
  readonly pullRequestReadFailureNumbers?: readonly number[];
  readonly removeFailureLabels?: readonly string[];
}

interface RunResult {
  readonly candidatePullRequestReads: number;
  readonly candidateQueries: CandidateQueryParameters[];
  readonly attempts: LabelChanges;
  readonly changes: LabelChanges;
  readonly failures: string[];
  readonly fileQueries: FileQueryParameters[];
  readonly infos: string[];
  readonly labelReads: number;
  readonly operations: string[];
  readonly pullRequestNumbers: number[];
  readonly pullRequestReads: number;
  readonly warnings: string[];
}

interface AddLabelsParameters {
  readonly labels: readonly string[];
}

interface RemoveLabelParameters {
  readonly name: string;
}

interface PullRequestParameters {
  readonly pull_number: number;
}

interface FileQueryParameters {
  readonly owner: string;
  readonly per_page: number;
  readonly pull_number: number;
  readonly repo: string;
}

interface CandidateQueryParameters {
  readonly head?: string;
  readonly owner: string;
  readonly per_page?: number;
  readonly repo: string;
  readonly state: string;
}

const DEFAULT_PAYLOAD = {workflow_run: {pull_requests: [{number: 42}]}};
const DIRECT_PAYLOAD = {pull_request: {number: 42}};
const FORK_PAYLOAD = {
  workflow_run: {
    head_branch: "feature",
    head_repository: {full_name: "fork/repo"},
    head_sha: "abc123",
    pull_requests: [],
  },
};
const MATCHING_FORK_PULL_REQUEST: CandidatePullRequestNode = {
  base: {repo: {full_name: "owner/repo"}},
  head: {
    ref: "feature",
    repo: {full_name: "fork/repo"},
    sha: "abc123",
  },
  number: 84,
  state: "open",
};
const WORKFLOW = readFileSync(
  new URL("../.github/workflows/size-labeler.yml", import.meta.url),
  "utf8",
);
const RESOLVE = loadWorkflowScript("size-labeler.yml", "Resolve pull requests from trusted event metadata");
const SYNC = loadWorkflowScript("size-labeler.yml", "Apply size label from changed lines");

async function runLabeler(
  payload: SizeLabelerPayload = DEFAULT_PAYLOAD,
  options: RunOptions = {},
  eventName: WorkflowEventName = "workflow_run",
): Promise<RunResult> {
  const attempts: LabelChanges = {added: [], removed: []};
  const candidateQueries: CandidateQueryParameters[] = [];
  const changes: LabelChanges = {added: [], removed: []};
  const failures: string[] = [];
  const fileQueries: FileQueryParameters[] = [];
  const infos: string[] = [];
  const operations: string[] = [];
  const pullRequestNumbers: number[] = [];
  const warnings: string[] = [];
  const failedRemovals = new Set(options.removeFailureLabels ?? []);
  let candidatePullRequestReads = 0;
  let labelReads = 0;
  let pullRequestReads = 0;
  const listLabelsOnIssue = async (): Promise<void> => {};
  const listPullRequests = async (): Promise<void> => {};
  const listFiles = async (): Promise<void> => {};
  // One source file holds the lines of a test that lists no files.
  const files = options.files ?? [
    {additions: options.additions ?? 0, deletions: options.deletions ?? 0, filename: "src/Main.java"},
  ];
  const github = {
    paginate: async (
      endpoint: () => Promise<void>,
      parameters: unknown,
    ): Promise<{name: string}[] | readonly CandidatePullRequestNode[] | readonly ChangedFileNode[]> => {
      if (endpoint === listFiles) {
        fileQueries.push(parameters as FileQueryParameters);
        if (options.fileReadFailure) {
          throw options.fileReadFailure;
        }
        return files;
      }
      if (endpoint === listPullRequests) {
        candidatePullRequestReads++;
        candidateQueries.push(parameters as CandidateQueryParameters);
        if (options.candidatePullRequestReadFailure) {
          throw options.candidatePullRequestReadFailure;
        }
        return options.candidatePullRequests ?? [];
      }
      labelReads++;
      if (options.labelReadFailure) {
        throw options.labelReadFailure;
      }
      return (options.existingLabels ?? []).map((name) => ({name}));
    },
    rest: {
      issues: {
        listLabelsOnIssue,
        addLabels: async ({labels}: AddLabelsParameters): Promise<void> => {
          attempts.added.push(...labels);
          operations.push(...labels.map((label) => `add:${label}`));
          if (options.addFailure) {
            throw options.addFailure;
          }
          changes.added.push(...labels);
        },
        removeLabel: async ({name}: RemoveLabelParameters): Promise<void> => {
          attempts.removed.push(name);
          operations.push(`remove:${name}`);
          if (failedRemovals.has(name)) {
            throw new Error(`remove failed for ${name}`);
          }
          changes.removed.push(name);
        },
      },
      pulls: {
        list: listPullRequests,
        listFiles,
        get: async ({pull_number}: PullRequestParameters): Promise<{
          data: {
            additions: number;
            changed_files: number;
            deletions: number;
          };
        }> => {
          pullRequestReads++;
          pullRequestNumbers.push(pull_number);
          const targetedReadFailure = options.pullRequestReadFailureNumbers?.includes(pull_number);
          if (
            targetedReadFailure
            || (!options.pullRequestReadFailureNumbers && options.pullRequestReadFailure)
          ) {
            throw options.pullRequestReadFailure ?? new Error(`pull request read failed for ${pull_number}`);
          }
          return {
            data: {
              additions: options.additions ?? sumOf(files.map(({additions}) => additions)),
              changed_files: options.changedFiles ?? files.length,
              deletions: options.deletions ?? sumOf(files.map(({deletions}) => deletions)),
            },
          };
        },
      },
    },
  };
  const context = {eventName, payload, repo: {owner: "owner", repo: "repo"}};
  let issueNumbersOutput: string | undefined;
  const core = {
    setOutput: (name: string, value: unknown): void => {
      if (name === "issue_numbers") {
        issueNumbersOutput = String(value);
      }
    },
    info: (message: string): void => {
      infos.push(message);
    },
    setFailed: (message: string): void => {
      failures.push(message);
    },
    warning: (message: string): void => {
      warnings.push(message);
    },
  };

  await withDateNow(options.nowMs ?? 0, () => RESOLVE({context, github, core}));
  for (const issueNumber of JSON.parse(issueNumbersOutput ?? "[]") as unknown[]) {
    await withEnvironment({ISSUE_NUMBER: String(issueNumber)}, () => SYNC({context, github, core}));
  }
  return {
    candidatePullRequestReads,
    candidateQueries,
    attempts,
    changes,
    failures,
    fileQueries,
    infos,
    labelReads,
    operations,
    pullRequestNumbers,
    pullRequestReads,
    warnings,
  };
}

function sumOf(counts: readonly number[]): number {
  return counts.reduce((total, count) => total + count, 0);
}

function assertChanges(result: RunResult, changes: LabelChanges): void {
  assert.deepEqual(result.changes, changes);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.warnings, []);
}

test("the workflow combines immediate trusted events with all-path and scheduled fallbacks", () => {
  assert.match(
    WORKFLOW,
    /^  workflow_run:\n    workflows: \[Commitlint\]\n    types: \[completed\]\n    branches-ignore:\n      - release-please--branches--\*\*$/m,
  );
  assert.match(
    WORKFLOW,
    /^  pull_request_target:\n    types: \[opened, reopened, synchronize, edited\]\n    paths-ignore:\n(?:      - .+\n){5}/m,
  );
  assert.match(WORKFLOW, /^  schedule:\n    - cron: "[^"]+"$/m);
});

test("the privileged immediate route executes only trusted metadata API scripts", () => {
  assert.match(WORKFLOW, /^  pull_request_target:/m);
  assert.doesNotMatch(WORKFLOW, /^  pull_request:/m);
  assert.doesNotMatch(WORKFLOW, /^\s+run:/m);
  // Each job checks out only its script, at a base-branch commit, without the token.
  assert.equal(WORKFLOW.match(/uses: actions\/checkout@/g)?.length, 2);
  assert.equal(WORKFLOW.match(/ref: \$\{\{ github\.sha \}\}/g)?.length, 2);
  assert.equal(WORKFLOW.match(/sparse-checkout: scripts\/size-labeler-[\w-]+\.cjs$/gm)?.length, 2);
  assert.equal(WORKFLOW.match(/persist-credentials: false/g)?.length, 2);
});

test("only trusted pull-request routes start the size-label job", () => {
  assert.match(
    WORKFLOW,
    /github\.event_name == 'pull_request_target'/,
  );
  assert.match(WORKFLOW, /github\.event_name == 'schedule'/);
  assert.match(WORKFLOW, /github\.event\.workflow_run\.event == 'pull_request'/);
  assert.doesNotMatch(WORKFLOW, /if:.*pull_requests\[0\]/);
});

test("size-label jobs never overlap their label mutations", () => {
  assert.doesNotMatch(WORKFLOW, /^concurrency:/m);
  assert.match(
    WORKFLOW,
    /^    concurrency:\n      group: size-labeler-\$\{\{ matrix\.issue_number \}\}$/m,
  );
  assert.doesNotMatch(WORKFLOW, /^\s+cancel-in-progress:/m);
  assert.match(WORKFLOW, /^      fail-fast: false$/m);
  assert.match(
    WORKFLOW,
    /issue_number: \$\{\{ fromJSON\(needs\.resolve_pull_requests\.outputs\.issue_numbers\) \}\}/,
  );
  assert.match(
    WORKFLOW,
    /needs\.resolve_pull_requests\.outputs\.issue_numbers != '\[\]'/,
  );
});

test("resolution is read-only and labeling has only its write permission", () => {
  assert.match(
    WORKFLOW,
    /^  resolve_pull_requests:[\s\S]+?^    permissions:\n      pull-requests: read\n/m,
  );
  assert.match(
    WORKFLOW,
    /^  sync-size-label:[\s\S]+?^    permissions:\n      pull-requests: write\n    steps:$/m,
  );
});

test("a direct pull-request-target event labels without consulting a fallback", async () => {
  const result = await runLabeler(
    DIRECT_PAYLOAD,
    {
      additions: 50,
      candidatePullRequestReadFailure: new Error("fallback must not run"),
    },
    "pull_request_target",
  );

  assertChanges(result, {added: ["size S"], removed: []});
  assert.equal(result.candidatePullRequestReads, 0);
  assert.deepEqual(result.pullRequestNumbers, [42]);
});

test("scheduled reconciliation labels every valid open repository pull request", async () => {
  const result = await runLabeler(
    {},
    {
      additions: 200,
      candidatePullRequests: [
        MATCHING_FORK_PULL_REQUEST,
        {...MATCHING_FORK_PULL_REQUEST, number: 85, state: "closed"},
        {
          ...MATCHING_FORK_PULL_REQUEST,
          base: {repo: {full_name: "other/repo"}},
          number: 86,
        },
        {...MATCHING_FORK_PULL_REQUEST, number: 84},
        {...MATCHING_FORK_PULL_REQUEST, number: 42},
        MATCHING_FORK_PULL_REQUEST,
      ],
    },
    "schedule",
  );

  assertChanges(result, {added: ["size M", "size M"], removed: []});
  assert.equal(result.candidatePullRequestReads, 1);
  assert.deepEqual(result.candidateQueries, [
    {owner: "owner", per_page: 100, repo: "repo", state: "open"},
  ]);
  assert.deepEqual(result.pullRequestNumbers, [42, 84]);
});

test("scheduled reconciliation with no open pull requests performs no mutation", async () => {
  const result = await runLabeler({}, {}, "schedule");

  assertChanges(result, {added: [], removed: []});
  assert.equal(result.candidatePullRequestReads, 1);
  assert.equal(result.pullRequestReads, 0);
  assert.equal(result.labelReads, 0);
});

for (const count of [256, 257, 513]) {
  test(`scheduled reconciliation rotates through all ${count} pull requests`, async () => {
    const processed = new Set<number>();
    const runs = Math.ceil(count / 100);
    for (let hour = 0; hour < runs; hour++) {
      const result = await runLabeler(
        {},
        {
          candidatePullRequests: Array.from({length: count}, (_, index) => ({
            ...MATCHING_FORK_PULL_REQUEST,
            number: index + 1,
          })),
          existingLabels: ["size XS"],
          nowMs: hour * 60 * 60 * 1000,
        },
        "schedule",
      );

      assert.deepEqual(result.failures, []);
      assert.deepEqual(result.warnings, []);
      assert.ok(result.pullRequestReads <= 100);
      assert.equal(result.labelReads, result.pullRequestReads);
      result.pullRequestNumbers.forEach((issueNumber) => processed.add(issueNumber));
      assert.deepEqual(result.attempts, {added: [], removed: []});
    }
    assert.deepEqual(
      [...processed].sort((left, right) => left - right),
      Array.from({length: count}, (_, index) => index + 1),
    );
  });
}

test("one scheduled pull-request failure does not suppress later jobs", async () => {
  const count = 100;
  const result = await runLabeler(
    {},
    {
      candidatePullRequests: Array.from({length: count}, (_, index) => ({
        ...MATCHING_FORK_PULL_REQUEST,
        number: index + 1,
      })),
      existingLabels: ["size XS"],
      pullRequestReadFailure: new Error("scheduled pull request read failed"),
      pullRequestReadFailureNumbers: [1],
    },
    "schedule",
  );

  assert.deepEqual(result.failures, []);
  assert.equal(result.warnings.length, 1);
  assert.match(
    result.warnings[0] ?? "",
    /Unable to read changed lines for #1.*scheduled pull request read failed/,
  );
  assert.equal(result.pullRequestReads, count);
  assert.equal(result.labelReads, count - 1);
  assert.deepEqual(result.pullRequestNumbers, Array.from({length: count}, (_, index) => index + 1));
  assert.deepEqual(result.attempts, {added: [], removed: []});
});

test("a scheduled pull-request read failure warns and performs no mutation", async () => {
  const result = await runLabeler(
    {},
    {candidatePullRequestReadFailure: new Error("scheduled read failed")},
    "schedule",
  );

  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.equal(result.pullRequestReads, 0);
  assert.match(result.warnings[0] ?? "", /Unable to list open pull requests.*scheduled read failed/);
});

for (const [changedLines, expectedLabel] of [
  [0, "size XS"],
  [49, "size XS"],
  [50, "size S"],
  [199, "size S"],
  [200, "size M"],
  [499, "size M"],
  [500, "size L"],
  [999, "size L"],
  [1000, "size XL"],
] as const) {
  test(`${changedLines} changed lines selects ${expectedLabel}`, async () => {
    const result = await runLabeler(DEFAULT_PAYLOAD, {additions: changedLines});

    assertChanges(result, {added: [expectedLabel], removed: []});
  });
}

test("changed lines include additions and deletions", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {additions: 125, deletions: 75});

  assertChanges(result, {added: ["size M"], removed: []});
});

test("the current size label is left unchanged", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 50,
    existingLabels: ["size S"],
  });

  assertChanges(result, {added: [], removed: []});
  assert.deepEqual(result.operations, []);
});

test("payload pull-request identity suppresses the candidate-query fallback", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 50,
    candidatePullRequestReadFailure: new Error("fallback must not run"),
  });

  assertChanges(result, {added: ["size S"], removed: []});
  assert.equal(result.candidatePullRequestReads, 0);
  assert.deepEqual(result.pullRequestNumbers, [42]);
});

test("all unique associated pull requests are labeled without a candidate query", async () => {
  const result = await runLabeler(
    {
      workflow_run: {
        pull_requests: [{number: 42}, {number: 84}, {number: 42}],
      },
    },
    {
      additions: 50,
      candidatePullRequestReadFailure: new Error("fallback must not run"),
    },
  );

  assertChanges(result, {added: ["size S", "size S"], removed: []});
  assert.equal(result.candidatePullRequestReads, 0);
  assert.deepEqual(result.pullRequestNumbers, [42, 84]);
});

test("a fork run resolves its pull request from trusted head metadata", async () => {
  const result = await runLabeler(FORK_PAYLOAD, {
    additions: 200,
    candidatePullRequests: [
      {...MATCHING_FORK_PULL_REQUEST, state: "closed"},
      {
        ...MATCHING_FORK_PULL_REQUEST,
        base: {repo: {full_name: "other/repo"}},
      },
      {
        ...MATCHING_FORK_PULL_REQUEST,
        head: {...MATCHING_FORK_PULL_REQUEST.head, ref: "other-feature"},
      },
      {
        ...MATCHING_FORK_PULL_REQUEST,
        head: {...MATCHING_FORK_PULL_REQUEST.head, repo: {full_name: "other/fork"}},
      },
      {
        ...MATCHING_FORK_PULL_REQUEST,
        head: {...MATCHING_FORK_PULL_REQUEST.head, sha: "other-sha"},
      },
      MATCHING_FORK_PULL_REQUEST,
    ],
  });

  assertChanges(result, {added: ["size M"], removed: []});
  assert.equal(result.candidatePullRequestReads, 1);
  assert.deepEqual(result.candidateQueries, [
    {
      head: "fork:feature",
      owner: "owner",
      repo: "repo",
      state: "open",
    },
  ]);
  assert.deepEqual(result.pullRequestNumbers, [84]);
});

test("a fork run without one matching pull request warns and performs no mutation", async () => {
  const result = await runLabeler(FORK_PAYLOAD, {
    candidatePullRequests: [
      {
        ...MATCHING_FORK_PULL_REQUEST,
        head: {...MATCHING_FORK_PULL_REQUEST.head, sha: "stale-sha"},
      },
    ],
  });

  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.equal(result.pullRequestReads, 0);
  assert.match(result.warnings[0] ?? "", /Unable to find an open pull request/);
});

test("a fork run labels every open pull request for the exact trusted head", async () => {
  const result = await runLabeler(FORK_PAYLOAD, {
    additions: 50,
    candidatePullRequests: [
      MATCHING_FORK_PULL_REQUEST,
      {...MATCHING_FORK_PULL_REQUEST, number: 85},
    ],
  });

  assertChanges(result, {added: ["size S", "size S"], removed: []});
  assert.deepEqual(result.pullRequestNumbers, [84, 85]);
});

test("a fork candidate read failure warns and performs no mutation", async () => {
  const result = await runLabeler(FORK_PAYLOAD, {
    candidatePullRequestReadFailure: new Error("candidate read failed"),
  });

  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.equal(result.pullRequestReads, 0);
  assert.match(result.warnings[0] ?? "", /Unable to resolve pull request.*candidate read failed/);
});

for (const [name, workflowRun] of [
  ["missing head metadata", {pull_requests: []}],
  [
    "malformed head repository",
    {
      head_branch: "feature",
      head_repository: {full_name: "fork"},
      head_sha: "abc123",
      pull_requests: [],
    },
  ],
] as const) {
  test(`a run with ${name} warns and performs no mutation`, async () => {
    const result = await runLabeler({workflow_run: workflowRun});

    assert.deepEqual(result.attempts, {added: [], removed: []});
    assert.equal(result.candidatePullRequestReads, 0);
    assert.equal(result.pullRequestReads, 0);
    assert.match(result.warnings[0] ?? "", /missing trusted head repository, branch, or SHA/);
  });
}

test("trusted fallback reconciliation still labels generated release pull requests", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {additions: 200});

  assertChanges(result, {added: ["size M"], removed: []});
});

test("the target is added before stale managed labels are removed", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 200,
    existingLabels: ["bug", "size XS", "size XL", "size XXL"],
  });

  assertChanges(result, {
    added: ["size M"],
    removed: ["size XS", "size XL"],
  });
  assert.deepEqual(result.operations, [
    "add:size M",
    "remove:size XS",
    "remove:size XL",
  ]);
});

test("stale managed labels are removed when the target already exists", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 500,
    existingLabels: ["size XS", "size L", "documentation"],
  });

  assertChanges(result, {added: [], removed: ["size XS"]});
  assert.deepEqual(result.operations, ["remove:size XS"]);
});

test("an event without a pull request does nothing", async () => {
  const result = await runLabeler({});

  assertChanges(result, {added: [], removed: []});
  assert.equal(result.candidatePullRequestReads, 0);
  assert.equal(result.pullRequestReads, 0);
  assert.equal(result.labelReads, 0);
});

test("a pull request read failure warns and does not inspect or mutate labels", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    pullRequestReadFailure: new Error("pull request read failed"),
  });

  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.equal(result.labelReads, 0);
  assert.match(result.warnings[0] ?? "", /Unable to read changed lines.*pull request read failed/);
});

for (const [name, additions, deletions] of [
  ["negative additions", -1, 0],
  ["fractional deletions", 1, 0.5],
  ["unsafe aggregate", Number.MAX_SAFE_INTEGER, 1],
] as const) {
  test(`${name} warns and does not inspect or mutate labels`, async () => {
    const result = await runLabeler(DEFAULT_PAYLOAD, {additions, deletions});

    assert.deepEqual(result.attempts, {added: [], removed: []});
    assert.equal(result.labelReads, 0);
    assert.match(result.warnings[0] ?? "", /Invalid changed-line counts/);
  });
}

test("a paginated label read failure warns and attempts no mutation", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 200,
    labelReadFailure: new Error("label read failed"),
  });

  assert.equal(result.pullRequestReads, 1);
  assert.equal(result.labelReads, 1);
  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.match(result.warnings[0] ?? "", /Unable to read labels.*label read failed/);
});

test("an add failure warns and preserves every existing size label", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    addFailure: new Error("add failed"),
    additions: 200,
    existingLabels: ["size XS", "size XL"],
  });

  assert.deepEqual(result.attempts, {added: ["size M"], removed: []});
  assert.deepEqual(result.changes, {added: [], removed: []});
  assert.deepEqual(result.operations, ["add:size M"]);
  assert.match(result.warnings[0] ?? "", /Unable to add size M label.*add failed/);
});

test("a removal failure warns and does not stop other stale-label removals", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 200,
    existingLabels: ["size S", "size L"],
    removeFailureLabels: ["size S"],
  });

  assert.deepEqual(result.attempts, {
    added: ["size M"],
    removed: ["size S", "size L"],
  });
  assert.deepEqual(result.changes, {
    added: ["size M"],
    removed: ["size L"],
  });
  assert.deepEqual(result.operations, [
    "add:size M",
    "remove:size S",
    "remove:size L",
  ]);
  assert.match(result.warnings[0] ?? "", /Unable to remove size S label.*remove failed for size S/);
});

function sourceFile(lines: number): ChangedFileNode {
  return {additions: lines, deletions: 0, filename: "src/main/java/Example.java"};
}

function adrFile(lines: number, filename = "docs/adr/9999-example.md"): ChangedFileNode {
  return {additions: lines, deletions: 0, filename};
}

test("ADR lines are left out of a pull request that also changes other files", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    files: [sourceFile(30), adrFile(300), {additions: 0, deletions: 20, filename: "docs/adr/0069-old.md"}],
  });

  assertChanges(result, {added: ["size XS"], removed: []});
  assert.deepEqual(result.infos, ["#42: size XS from 30 of 350 changed lines, 320 in docs/adr/"]);
  assert.deepEqual(result.fileQueries, [{owner: "owner", per_page: 100, pull_number: 42, repo: "repo"}]);
});

test("a pull request that changes only ADRs counts every line", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    files: [adrFile(120), {additions: 0, deletions: 10, filename: "docs/adr/0069-old.md"}],
  });

  assertChanges(result, {added: ["size S"], removed: []});
  assert.deepEqual(result.infos, ["#42: size S from 130 of 130 changed lines, 130 in docs/adr/"]);
});

test("an empty file list selects size XS", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {files: []});

  assertChanges(result, {added: ["size XS"], removed: []});
  assert.deepEqual(result.infos, ["#42: size XS from 0 of 0 changed lines, 0 in docs/adr/"]);
});

for (const [name, otherFile] of [
  ["a binary file", {additions: 0, deletions: 0, filename: "docs/images/diagram.png"}],
  [
    "a pure rename",
    {additions: 0, deletions: 0, filename: "src/New.java", previous_filename: "src/Old.java", status: "renamed"},
  ],
] as const) {
  test(`an ADR beside ${name} without changed lines counts every line`, async () => {
    const result = await runLabeler(DEFAULT_PAYLOAD, {files: [otherFile, adrFile(600)]});

    assertChanges(result, {added: ["size L"], removed: []});
    assert.deepEqual(result.infos, ["#42: size L from 600 of 600 changed lines, 600 in docs/adr/"]);
  });
}

test("a docs-only pull request without ADRs counts every line", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    files: [
      {additions: 100, deletions: 0, filename: "README.md"},
      {additions: 40, deletions: 20, filename: "CONTRIBUTING.md"},
      {additions: 30, deletions: 0, filename: "docs/agents/github-issue-triage.md"},
      {additions: 10, deletions: 0, filename: "docs/adr-overview.md"},
    ],
  });

  assertChanges(result, {added: ["size M"], removed: []});
});

for (const [name, files, expectedLabel] of [
  [
    "an ADR renamed inside docs/adr/ is left out",
    [sourceFile(30), {...adrFile(40, "docs/adr/0010-new.md"), previous_filename: "docs/adr/0010-old.md"}],
    "size XS",
  ],
  [
    "an ADR moved out of docs/adr/ counts",
    [sourceFile(30), {...adrFile(60, "docs/decisions/0010.md"), previous_filename: "docs/adr/0010.md"}],
    "size S",
  ],
  [
    "a file moved into docs/adr/ counts",
    [sourceFile(30), {...adrFile(60, "docs/adr/0010.md"), previous_filename: "docs/notes.md"}],
    "size S",
  ],
  [
    "a deleted ADR is left out",
    [sourceFile(30), {additions: 0, deletions: 300, filename: "docs/adr/0010.md"}],
    "size XS",
  ],
  [
    "a pull request that only deletes an ADR counts it",
    [{additions: 0, deletions: 300, filename: "docs/adr/0010.md"}],
    "size M",
  ],
  [
    "an ADR copied inside docs/adr/ is left out",
    [
      sourceFile(30),
      {...adrFile(60, "docs/adr/0011-copy.md"), previous_filename: "docs/adr/0010.md", status: "copied"},
    ],
    "size XS",
  ],
  [
    "an ADR copied out of docs/adr/ counts",
    [
      sourceFile(30),
      {...adrFile(60, "docs/decisions/0010.md"), previous_filename: "docs/adr/0010.md", status: "copied"},
    ],
    "size S",
  ],
  [
    "a file copied into docs/adr/ counts",
    [sourceFile(30), {...adrFile(60, "docs/adr/0010.md"), previous_filename: "docs/notes.md", status: "copied"}],
    "size S",
  ],
  [
    "a pull request that only renames ADRs counts them",
    [{...adrFile(60, "docs/adr/0010-new.md"), previous_filename: "docs/adr/0010-old.md"}],
    "size S",
  ],
] as const) {
  test(name, async () => {
    const result = await runLabeler(DEFAULT_PAYLOAD, {files});

    assertChanges(result, {added: [expectedLabel], removed: []});
  });
}

for (const [countedLines, expectedLabel] of [
  [49, "size XS"],
  [50, "size S"],
  [199, "size S"],
  [200, "size M"],
  [499, "size M"],
  [500, "size L"],
  [999, "size L"],
  [1000, "size XL"],
] as const) {
  test(`${countedLines} lines beside 600 ADR lines selects ${expectedLabel}`, async () => {
    const result = await runLabeler(DEFAULT_PAYLOAD, {
      files: [{additions: countedLines - 1, deletions: 1, filename: "src/Main.java"}, adrFile(600)],
    });

    assertChanges(result, {added: [expectedLabel], removed: []});
  });
}

test("every line counts when GitHub lists fewer files than the pull request changes", async () => {
  // GitHub stops listing a pull request's files at 3000.
  const listedFiles = [
    ...Array.from({length: 2998}, (_, index) => ({additions: 0, deletions: 0, filename: `src/F${index}.java`})),
    sourceFile(30),
    adrFile(600),
  ];
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 990,
    changedFiles: 3001,
    deletions: 10,
    files: listedFiles,
  });

  assertChanges(result, {added: ["size XL"], removed: []});
  assert.deepEqual(result.infos, [
    "#42: GitHub listed 3000 of 3001 changed files, so every line counts",
    "#42: size XL from 1000 of 1000 changed lines, at least 600 in docs/adr/",
  ]);
});

test("a changed-file read failure warns and labels from the pull request totals", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 200,
    fileReadFailure: new Error("file read failed"),
  });

  assert.deepEqual(result.changes, {added: ["size M"], removed: []});
  assert.deepEqual(result.warnings, [
    "Unable to read changed files for #42, so every line counts: Error: file read failed",
  ]);
  assert.deepEqual(result.infos, ["#42: size M from 200 of 200 changed lines, an unknown number in docs/adr/"]);
});

for (const [name, file] of [
  ["a missing path", {additions: 1, deletions: 0}],
  ["a non-string previous path", {additions: 1, deletions: 0, filename: "docs/adr/a.md", previous_filename: 7}],
  ["negative additions", {additions: -1, deletions: 0, filename: "src/Main.java"}],
  ["fractional deletions", {additions: 0, deletions: 0.5, filename: "src/Main.java"}],
] as const) {
  test(`a changed file with ${name} warns and labels from the pull request totals`, async () => {
    const result = await runLabeler(DEFAULT_PAYLOAD, {
      additions: 200,
      deletions: 0,
      files: [sourceFile(0), file as ChangedFileNode],
    });

    assert.deepEqual(result.changes, {added: ["size M"], removed: []});
    assert.deepEqual(result.warnings, ["Invalid changed-file entry for #42, so every line counts"]);
    assert.deepEqual(result.infos, ["#42: size M from 200 of 200 changed lines, an unknown number in docs/adr/"]);
  });
}

test("a repeated changed-file entry warns and labels from the pull request totals", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 200,
    changedFiles: 3,
    deletions: 0,
    files: [sourceFile(30), sourceFile(30), adrFile(300)],
  });

  assert.deepEqual(result.changes, {added: ["size M"], removed: []});
  assert.deepEqual(result.warnings, ["Repeated changed-file entry for #42, so every line counts"]);
  assert.deepEqual(result.infos, ["#42: size M from 200 of 200 changed lines, an unknown number in docs/adr/"]);
});

test("an invalid changed-file count warns and does not list files", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {additions: 1, changedFiles: -1});

  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.deepEqual(result.fileQueries, []);
  assert.match(result.warnings[0] ?? "", /Invalid changed-line counts.*changed_files=-1/);
});

test("an unsafe sum of listed lines warns and does not inspect or mutate labels", async () => {
  const result = await runLabeler(DEFAULT_PAYLOAD, {
    additions: 1,
    deletions: 0,
    files: [
      sourceFile(Number.MAX_SAFE_INTEGER),
      {...sourceFile(Number.MAX_SAFE_INTEGER), filename: "src/main/java/Other.java"},
    ],
  });

  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.equal(result.labelReads, 0);
  assert.match(result.warnings[0] ?? "", /Invalid changed-line counts for #42/);
});

// A push between the two reads can leave the pull request totals out of step with the list.
for (const [name, changedFiles] of [["the same number of files as", 2], ["more files than", 1]] as const) {
  test(`the listed files decide the count when GitHub lists ${name} the totals report`, async () => {
    const result = await runLabeler(DEFAULT_PAYLOAD, {
      additions: 1000,
      changedFiles,
      files: [sourceFile(30), adrFile(300)],
    });

    assertChanges(result, {added: ["size XS"], removed: []});
  });
}
