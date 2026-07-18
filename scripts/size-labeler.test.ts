import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";

interface LabelChanges {
  readonly added: string[];
  readonly removed: string[];
}

interface PullRequestNode {
  readonly number: number;
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
  readonly deletions?: number;
  readonly existingLabels?: readonly string[];
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

interface CandidateQueryParameters {
  readonly head?: string;
  readonly owner: string;
  readonly per_page?: number;
  readonly repo: string;
  readonly state: string;
}

type ExecutableAsyncFunction = (...arguments_: readonly unknown[]) => Promise<unknown>;
type AsyncFunctionConstructor = new (...arguments_: readonly string[]) => ExecutableAsyncFunction;

const AsyncFunction = Object.getPrototypeOf(async function (): Promise<void> {}).constructor as AsyncFunctionConstructor;
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

function workflowScript(stepName: string): string {
  const lines = WORKFLOW.split("\n");
  const stepStart = lines.findIndex((line) => line.trim() === `- name: ${stepName}`);
  assert.notEqual(stepStart, -1, `${stepName} step must exist`);
  const relativeScriptStart = lines
    .slice(stepStart + 1)
    .findIndex((line) => line.trim() === "script: |");
  assert.notEqual(relativeScriptStart, -1, `${stepName} script block must exist`);
  const scriptStart = stepStart + relativeScriptStart + 1;

  const scriptLines: string[] = [];
  for (const line of lines.slice(scriptStart + 1)) {
    if (line && !line.startsWith("            ")) {
      break;
    }
    scriptLines.push(line.slice(12));
  }
  return scriptLines.join("\n");
}

async function runLabeler(
  payload: SizeLabelerPayload = DEFAULT_PAYLOAD,
  options: RunOptions = {},
  eventName: WorkflowEventName = "workflow_run",
): Promise<RunResult> {
  const attempts: LabelChanges = {added: [], removed: []};
  const candidateQueries: CandidateQueryParameters[] = [];
  const changes: LabelChanges = {added: [], removed: []};
  const failures: string[] = [];
  const operations: string[] = [];
  const pullRequestNumbers: number[] = [];
  const warnings: string[] = [];
  const failedRemovals = new Set(options.removeFailureLabels ?? []);
  let candidatePullRequestReads = 0;
  let labelReads = 0;
  let pullRequestReads = 0;
  const listLabelsOnIssue = async (): Promise<void> => {};
  const listPullRequests = async (): Promise<void> => {};
  const github = {
    paginate: async (
      endpoint: () => Promise<void>,
      parameters: unknown,
    ): Promise<{name: string}[] | readonly CandidatePullRequestNode[]> => {
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
        get: async ({pull_number}: PullRequestParameters): Promise<{
          data: {
            additions: number;
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
              additions: options.additions ?? 0,
              deletions: options.deletions ?? 0,
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
    setFailed: (message: string): void => {
      failures.push(message);
    },
    warning: (message: string): void => {
      warnings.push(message);
    },
  };

  await new AsyncFunction(
    "context",
    "github",
    "core",
    "Date",
    workflowScript("Resolve pull requests from trusted event metadata"),
  )(context, github, core, {now: (): number => options.nowMs ?? 0});
  for (const issueNumber of JSON.parse(issueNumbersOutput ?? "[]") as unknown[]) {
    await new AsyncFunction(
      "context",
      "github",
      "core",
      "process",
      workflowScript("Apply size label from changed lines"),
    )(context, github, core, {env: {ISSUE_NUMBER: String(issueNumber)}});
  }
  return {
    candidatePullRequestReads,
    candidateQueries,
    attempts,
    changes,
    failures,
    labelReads,
    operations,
    pullRequestNumbers,
    pullRequestReads,
    warnings,
  };
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
  assert.doesNotMatch(WORKFLOW, /actions\/checkout|^\s+run:/m);
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
