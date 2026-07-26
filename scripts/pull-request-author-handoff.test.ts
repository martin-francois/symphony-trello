import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";

interface UserNode {
  readonly id?: number;
  readonly login: string;
}

interface PullRequestNode {
  readonly labels?: readonly {readonly name: string}[];
  readonly number: number;
  readonly state: string;
  readonly updated_at?: string;
  readonly user?: UserNode;
}

interface HandoffPayload {
  readonly action?: string;
  readonly after?: string;
  readonly comment?: {
    readonly body?: string;
    readonly created_at?: string;
    readonly id?: number;
    readonly user?: UserNode;
  };
  readonly issue?: {
    readonly labels?: readonly {readonly name: string}[];
    readonly number: number;
    readonly pull_request?: object;
  };
  readonly label?: {readonly name: string};
  readonly pull_request?: PullRequestNode;
  readonly requested_reviewer?: UserNode;
  readonly sender?: UserNode;
}

interface RunOptions {
  readonly addFailure?: Error;
  readonly commentFailure?: Error;
  readonly commentReadFailure?: Error;
  readonly existingComments?: readonly {readonly body: string; readonly user: UserNode}[];
  readonly existingLabels?: readonly string[];
  readonly labelReadFailure?: Error;
  readonly timelineReadFailure?: Error;
  readonly timeline?: readonly TimelineEventNode[];
  readonly pullRequest?: PullRequestNode;
  readonly pullRequestReadFailure?: Error;
  readonly removeFailureLabels?: readonly string[];
}

interface RunResult {
  readonly added: string[];
  readonly attemptedAdds: string[];
  readonly attemptedRemovals: string[];
  readonly commentReads: number;
  readonly comments: string[];
  readonly labelReads: number;
  readonly timelineReads: number;
  readonly pullRequestReads: number;
  readonly removed: string[];
  readonly warnings: string[];
}

interface AddLabelsParameters {
  readonly labels: readonly string[];
}

interface CommentParameters {
  readonly body: string;
}

interface RemoveLabelParameters {
  readonly name: string;
}

interface TimelineEventNode {
  readonly actor?: UserNode;
  readonly commit_id?: string;
  readonly created_at?: string;
  readonly event: string;
  readonly id?: number;
  readonly label?: {readonly name: string};
  readonly node_id?: string;
  readonly requested_reviewer?: UserNode;
  readonly sha?: string;
}

type ExecutableAsyncFunction = (...arguments_: readonly unknown[]) => Promise<unknown>;
type AsyncFunctionConstructor = new (...arguments_: readonly string[]) => ExecutableAsyncFunction;

const AsyncFunction = Object.getPrototypeOf(async function (): Promise<void> {}).constructor as AsyncFunctionConstructor;
const WORKFLOW = readFileSync(
  new URL("../.github/workflows/pull-request-author-handoff.yml", import.meta.url),
  "utf8",
);
const AUTHOR = {login: "octocat"};
const WAITING_LABEL = "waiting-for-author";
const REVIEW_LABEL = "needs-maintainer-review";
const LABEL_TIMESTAMP = "2026-07-22T18:00:00Z";
const WAITING_CYCLE_TOKEN = "LE_waiting_cycle";
const PUSH_SHA = "abc123";
const READY_COMMENT_ID = 73;
const REVIEWER = {id: 19, login: "reviewer"};
const DEFAULT_TIMELINE: readonly TimelineEventNode[] = [
  {
    created_at: LABEL_TIMESTAMP,
    event: "labeled",
    label: {name: WAITING_LABEL},
    node_id: WAITING_CYCLE_TOKEN,
  },
  {event: "committed", sha: PUSH_SHA},
  {actor: AUTHOR, created_at: LABEL_TIMESTAMP, event: "ready_for_review"},
  {
    actor: AUTHOR,
    created_at: LABEL_TIMESTAMP,
    event: "review_requested",
    requested_reviewer: REVIEWER,
  },
  {event: "commented", id: READY_COMMENT_ID},
];

function workflowScript(): string {
  const lines = WORKFLOW.split("\n");
  const scriptStart = lines.findIndex((line) => line.trim() === "script: |");
  assert.notEqual(scriptStart, -1, "author handoff script block must exist");

  const scriptLines: string[] = [];
  for (const line of lines.slice(scriptStart + 1)) {
    if (line && !line.startsWith("            ")) {
      break;
    }
    scriptLines.push(line.slice(12));
  }
  return scriptLines.join("\n");
}

async function runHandoff(
  eventName: "issue_comment" | "pull_request_target",
  payload: HandoffPayload,
  options: RunOptions = {},
): Promise<RunResult> {
  const added: string[] = [];
  const attemptedAdds: string[] = [];
  const attemptedRemovals: string[] = [];
  const comments: string[] = [];
  const removed: string[] = [];
  const warnings: string[] = [];
  const removeFailures = new Set(options.removeFailureLabels ?? []);
  let commentReads = 0;
  let timelineReads = 0;
  let labelReads = 0;
  let pullRequestReads = 0;
  const listLabelsOnIssue = async (): Promise<void> => {};
  const listComments = async (): Promise<void> => {};
  const listEventsForTimeline = async (): Promise<void> => {};
  const github = {
    paginate: async (
      endpoint: () => Promise<void>,
    ): Promise<({body?: string; name?: string; user?: UserNode} | TimelineEventNode)[]> => {
      if (endpoint === listComments) {
        commentReads++;
        if (options.commentReadFailure) {
          throw options.commentReadFailure;
        }
        return [...(options.existingComments ?? [])];
      }
      if (endpoint === listEventsForTimeline) {
        timelineReads++;
        if (options.timelineReadFailure) {
          throw options.timelineReadFailure;
        }
        return [...(options.timeline ?? DEFAULT_TIMELINE)];
      }
      labelReads++;
      if (options.labelReadFailure) {
        throw options.labelReadFailure;
      }
      return (options.existingLabels ?? []).map((name) => ({name}));
    },
    rest: {
      issues: {
        addLabels: async ({labels}: AddLabelsParameters): Promise<void> => {
          attemptedAdds.push(...labels);
          if (options.addFailure) {
            throw options.addFailure;
          }
          added.push(...labels);
        },
        createComment: async ({body}: CommentParameters): Promise<void> => {
          if (options.commentFailure) {
            throw options.commentFailure;
          }
          comments.push(body);
        },
        listLabelsOnIssue,
        listComments,
        listEventsForTimeline,
        removeLabel: async ({name}: RemoveLabelParameters): Promise<void> => {
          attemptedRemovals.push(name);
          if (removeFailures.has(name)) {
            throw new Error(`remove failed for ${name}`);
          }
          removed.push(name);
        },
      },
      pulls: {
        get: async (): Promise<{data: PullRequestNode}> => {
          pullRequestReads++;
          if (options.pullRequestReadFailure) {
            throw options.pullRequestReadFailure;
          }
          return {
            data: options.pullRequest ?? {number: 42, state: "open", user: AUTHOR},
          };
        },
      },
    },
  };
  const context = {eventName, payload, repo: {owner: "owner", repo: "repo"}};
  const core = {
    warning: (message: string): void => {
      warnings.push(message);
    },
  };

  await new AsyncFunction("context", "github", "core", workflowScript())(context, github, core);
  return {
    added,
    attemptedAdds,
    attemptedRemovals,
    commentReads,
    comments,
    timelineReads,
    labelReads,
    pullRequestReads,
    removed,
    warnings,
  };
}

function authorEvent(action: string, overrides: Partial<HandoffPayload> = {}): HandoffPayload {
  return {
    action,
    after: PUSH_SHA,
    pull_request: {
      labels: [{name: WAITING_LABEL}],
      number: 42,
      state: "open",
      updated_at: LABEL_TIMESTAMP,
      user: AUTHOR,
    },
    requested_reviewer: REVIEWER,
    sender: AUTHOR,
    ...overrides,
  };
}

function assertTransition(result: RunResult): void {
  assert.deepEqual(result.added, [REVIEW_LABEL]);
  assert.deepEqual(result.removed, [WAITING_LABEL]);
  assert.deepEqual(result.warnings, []);
}

test("the privileged workflow uses only trusted metadata and least-privilege API access", () => {
  assert.match(WORKFLOW, /^  pull_request_target:\n    types: \[labeled, synchronize, ready_for_review, review_requested\]$/m);
  assert.match(WORKFLOW, /^  issue_comment:\n    types: \[created\]$/m);
  assert.match(WORKFLOW, /^permissions: read-all$/m);
  assert.match(
    WORKFLOW,
    /^    permissions:\n      issues: write\n      pull-requests: read$/m,
  );
  assert.match(WORKFLOW, /github\.event\.label\.name == 'waiting-for-author'/);
  assert.match(WORKFLOW, /github\.event\.sender\.login == github\.event\.pull_request\.user\.login/);
  assert.match(
    WORKFLOW,
    /contains\(github\.event\.pull_request\.labels\.\*\.name, 'waiting-for-author'\)/,
  );
  assert.match(WORKFLOW, /github\.event\.comment\.user\.login == github\.event\.issue\.user\.login/);
  assert.match(WORKFLOW, /github\.event\.comment\.body == '\/ready'/);
  assert.match(
    WORKFLOW,
    /contains\(github\.event\.issue\.labels\.\*\.name, 'waiting-for-author'\)/,
  );
  assert.match(
    WORKFLOW,
    /^    concurrency:\n      group: pull-request-author-handoff-.+\n(?:      # .+\n)?      queue: max$/m,
  );
  assert.doesNotMatch(WORKFLOW, /^      cancel-in-progress:/m);
  assert.doesNotMatch(WORKFLOW, /actions\/checkout|^\s+run:/m);
});

test("applying the waiting label removes the review label and explains the handoff", async () => {
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    {existingLabels: [WAITING_LABEL, REVIEW_LABEL]},
  );

  assert.deepEqual(result.added, []);
  assert.deepEqual(result.removed, [REVIEW_LABEL]);
  assert.equal(result.comments.length, 1);
  assert.match(result.comments[0] ?? "", /top-level `\/ready` comment/);
  assert.match(result.comments[0] ?? "", /Replies in review threads and other comments do not change/);
  assert.doesNotMatch(result.comments[0] ?? "", /\n {4}/);
  assert.deepEqual(result.warnings, []);
});

test("other labels and closed pull requests do not create reminders", async () => {
  const otherLabel = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: "documentation"}}),
  );
  const closed = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {
      label: {name: WAITING_LABEL},
      pull_request: {number: 42, state: "closed", user: AUTHOR},
    }),
  );

  assert.equal(otherLabel.labelReads, 0);
  assert.deepEqual(otherLabel.comments, []);
  assert.equal(closed.labelReads, 0);
  assert.deepEqual(closed.comments, []);
});

test("a queued event is inert after the pull request closes", async () => {
  for (const payload of [
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    authorEvent("synchronize"),
  ]) {
    const result = await runHandoff(
      "pull_request_target",
      payload,
      {
        existingLabels: [WAITING_LABEL],
        pullRequest: {number: 42, state: "closed", user: AUTHOR},
      },
    );

    assert.equal(result.pullRequestReads, 1);
    assert.equal(result.labelReads, 0);
    assert.deepEqual(result.comments, []);
    assert.deepEqual(result.added, []);
    assert.deepEqual(result.removed, []);
  }
});

test("each author-owned pull request event returns waiting work for review", async () => {
  for (const action of ["synchronize", "ready_for_review", "review_requested"]) {
    assertTransition(await runHandoff(
      "pull_request_target",
      authorEvent(action),
      {existingLabels: [WAITING_LABEL]},
    ));
  }
});

test("a force-push back to an existing commit returns waiting work for review", async () => {
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize"),
    {
      existingLabels: [WAITING_LABEL],
      timeline: [
        {event: "committed", sha: PUSH_SHA},
        {
          created_at: LABEL_TIMESTAMP,
          event: "labeled",
          label: {name: WAITING_LABEL},
          node_id: WAITING_CYCLE_TOKEN,
        },
        {
          actor: AUTHOR,
          commit_id: PUSH_SHA,
          created_at: LABEL_TIMESTAMP,
          event: "head_ref_force_pushed",
        },
      ],
    },
  );

  assertTransition(result);
});

test("a maintainer or bot event does not return waiting work for review", async () => {
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize", {sender: {login: "maintainer"}}),
    {existingLabels: [WAITING_LABEL]},
  );

  assert.equal(result.labelReads, 0);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.removed, []);
});

test("an author top-level ready command returns waiting work for review", async () => {
  const result = await runHandoff(
    "issue_comment",
    {
      action: "created",
      comment: {
        body: "/ready",
        created_at: LABEL_TIMESTAMP,
        id: READY_COMMENT_ID,
        user: AUTHOR,
      },
      issue: {labels: [{name: WAITING_LABEL}], number: 42, pull_request: {}},
    },
    {existingLabels: [WAITING_LABEL]},
  );

  assert.equal(result.pullRequestReads, 1);
  assertTransition(result);
});

test("ordinary comments, inline replies, and another user's ready command are inert", async () => {
  for (const payload of [
    {comment: {body: "I'll update this next week.", user: AUTHOR}, issue: {number: 42, pull_request: {}}},
    {comment: {body: "That CodeRabbit finding is a false positive.", user: AUTHOR}, issue: {number: 42}},
    {comment: {body: "/ready", user: {login: "maintainer"}}, issue: {number: 42, pull_request: {}}},
    {comment: {body: "/ready after one more check", user: AUTHOR}, issue: {number: 42, pull_request: {}}},
  ]) {
    const result = await runHandoff("issue_comment", payload, {existingLabels: [WAITING_LABEL]});
    assert.deepEqual(result.added, []);
    assert.deepEqual(result.removed, []);
  }
});

test("a ready signal is inert unless the pull request is waiting", async () => {
  const result = await runHandoff(
    "issue_comment",
    {
      comment: {body: "/ready", user: AUTHOR},
      issue: {labels: [], number: 42, pull_request: {}},
    },
    {existingLabels: [WAITING_LABEL]},
  );

  assert.equal(result.labelReads, 0);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.removed, []);
});

test("an event emitted before the waiting state cannot consume a later waiting label", async () => {
  const pullRequestEvent = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize", {
      pull_request: {
        labels: [],
        number: 42,
        state: "open",
        updated_at: LABEL_TIMESTAMP,
        user: AUTHOR,
      },
    }),
    {existingLabels: [WAITING_LABEL]},
  );
  const commentEvent = await runHandoff(
    "issue_comment",
    {
      comment: {body: "/ready", user: AUTHOR},
      issue: {labels: [], number: 42, pull_request: {}},
    },
    {existingLabels: [WAITING_LABEL]},
  );

  assert.equal(pullRequestEvent.labelReads, 0);
  assert.equal(commentEvent.labelReads, 0);
  assert.deepEqual(pullRequestEvent.added, []);
  assert.deepEqual(commentEvent.added, []);
});

test("a signal from an earlier waiting cycle cannot consume the current cycle", async () => {
  const timeline: readonly TimelineEventNode[] = [
    {event: "committed", sha: PUSH_SHA},
    {event: "commented", id: READY_COMMENT_ID},
    {
      created_at: LABEL_TIMESTAMP,
      event: "labeled",
      label: {name: WAITING_LABEL},
      node_id: "LE_current_cycle",
    },
  ];
  const pullRequestEvent = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize"),
    {existingLabels: [WAITING_LABEL], timeline},
  );
  const commentEvent = await runHandoff(
    "issue_comment",
    {
      comment: {
        body: "/ready",
        created_at: LABEL_TIMESTAMP,
        id: READY_COMMENT_ID,
        user: AUTHOR,
      },
      issue: {labels: [{name: WAITING_LABEL}], number: 42, pull_request: {}},
    },
    {existingLabels: [WAITING_LABEL], timeline},
  );

  assert.equal(pullRequestEvent.timelineReads, 1);
  assert.equal(commentEvent.timelineReads, 1);
  assert.deepEqual(pullRequestEvent.added, []);
  assert.deepEqual(commentEvent.added, []);
  assert.deepEqual(pullRequestEvent.removed, []);
  assert.deepEqual(commentEvent.removed, []);
});

test("same-second review signals before the cycle remain stale", async () => {
  for (const [action, signal] of [
    ["ready_for_review", {actor: AUTHOR, created_at: LABEL_TIMESTAMP, event: "ready_for_review"}],
    ["review_requested", {
      actor: AUTHOR,
      created_at: LABEL_TIMESTAMP,
      event: "review_requested",
      requested_reviewer: REVIEWER,
    }],
  ] as const) {
    const result = await runHandoff(
      "pull_request_target",
      authorEvent(action),
      {
        existingLabels: [WAITING_LABEL],
        timeline: [
          signal,
          {
            created_at: LABEL_TIMESTAMP,
            event: "labeled",
            label: {name: WAITING_LABEL},
            node_id: "LE_current_cycle",
          },
        ],
      },
    );

    assert.deepEqual(result.added, []);
    assert.deepEqual(result.removed, []);
  }
});

test("a stale waiting-label delivery does not override a completed transition", async () => {
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    {existingLabels: [REVIEW_LABEL]},
  );

  assert.deepEqual(result.removed, []);
  assert.deepEqual(result.comments, []);
  assert.equal(result.commentReads, 0);
});

test("a delayed labeled delivery reconciles the current waiting cycle exactly once", async () => {
  const currentCycleToken = "LE_current_cycle";
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    {
      existingLabels: [WAITING_LABEL, REVIEW_LABEL],
      timeline: [{
        created_at: "2026-07-22T18:01:00Z",
        event: "labeled",
        label: {name: WAITING_LABEL},
        node_id: currentCycleToken,
      }],
    },
  );

  assert.deepEqual(result.removed, [REVIEW_LABEL]);
  assert.equal(result.comments.length, 1);
  assert.ok(result.comments[0]?.includes(currentCycleToken));
  assert.equal(result.timelineReads, 1);
  assert.equal(result.commentReads, 1);
});

test("a repeated waiting-label delivery does not duplicate its reminder", async () => {
  const marker = `<!-- symphony-trello:waiting-for-author:${WAITING_CYCLE_TOKEN} -->`;
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    {
      existingComments: [{body: `${marker}\nEarlier reminder.`, user: {login: "github-actions[bot]"}}],
      existingLabels: [WAITING_LABEL],
    },
  );

  assert.equal(result.commentReads, 1);
  assert.deepEqual(result.comments, []);
  assert.deepEqual(result.warnings, []);
});

test("an author cannot suppress the reminder by copying its internal marker", async () => {
  const marker = `<!-- symphony-trello:waiting-for-author:${WAITING_CYCLE_TOKEN} -->`;
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    {
      existingComments: [{body: marker, user: AUTHOR}],
      existingLabels: [WAITING_LABEL],
    },
  );

  assert.equal(result.comments.length, 1);
  assert.ok(result.comments[0]?.includes(marker));
});

test("an existing review label is retained while the waiting label is removed", async () => {
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize"),
    {existingLabels: [WAITING_LABEL, REVIEW_LABEL]},
  );

  assert.deepEqual(result.attemptedAdds, []);
  assert.deepEqual(result.removed, [WAITING_LABEL]);
  assert.deepEqual(result.warnings, []);
});

test("a failed review-label addition preserves the waiting label", async () => {
  const result = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize"),
    {addFailure: new Error("add failed"), existingLabels: [WAITING_LABEL]},
  );

  assert.deepEqual(result.attemptedAdds, [REVIEW_LABEL]);
  assert.deepEqual(result.attemptedRemovals, []);
  assert.match(result.warnings[0] ?? "", /Unable to add needs-maintainer-review.*add failed/);
});

test("API failures warn without inventing successful state changes", async () => {
  const labelRead = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize"),
    {existingLabels: [WAITING_LABEL], labelReadFailure: new Error("labels failed")},
  );
  assert.deepEqual(labelRead.attemptedAdds, []);
  assert.deepEqual(labelRead.attemptedRemovals, []);
  assert.match(labelRead.warnings[0] ?? "", /Unable to read labels.*labels failed/);

  const pullRead = await runHandoff(
    "issue_comment",
    {
      comment: {body: "/ready", user: AUTHOR},
      issue: {labels: [{name: WAITING_LABEL}], number: 42, pull_request: {}},
    },
    {pullRequestReadFailure: new Error("pull failed")},
  );
  assert.equal(pullRead.labelReads, 0);
  assert.match(pullRead.warnings[0] ?? "", /Unable to read pull request.*pull failed/);

  const eventRead = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize"),
    {existingLabels: [WAITING_LABEL], timelineReadFailure: new Error("timeline failed")},
  );
  assert.deepEqual(eventRead.attemptedAdds, []);
  assert.deepEqual(eventRead.attemptedRemovals, []);
  assert.match(eventRead.warnings[0] ?? "", /Unable to read the timeline.*timeline failed/);
});

test("reminder and removal failures remain visible", async () => {
  const reminder = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    {commentFailure: new Error("comment failed"), existingLabels: [WAITING_LABEL]},
  );
  assert.match(reminder.warnings[0] ?? "", /Unable to post the author handoff reminder.*comment failed/);

  const commentRead = await runHandoff(
    "pull_request_target",
    authorEvent("labeled", {label: {name: WAITING_LABEL}}),
    {commentReadFailure: new Error("comments failed"), existingLabels: [WAITING_LABEL]},
  );
  assert.deepEqual(commentRead.comments, []);
  assert.match(commentRead.warnings[0] ?? "", /Unable to read comments.*comments failed/);

  const transition = await runHandoff(
    "pull_request_target",
    authorEvent("synchronize"),
    {existingLabels: [WAITING_LABEL], removeFailureLabels: [WAITING_LABEL]},
  );
  assert.deepEqual(transition.added, [REVIEW_LABEL]);
  assert.deepEqual(transition.removed, []);
  assert.match(transition.warnings[0] ?? "", /Unable to remove waiting-for-author.*remove failed/);
});
