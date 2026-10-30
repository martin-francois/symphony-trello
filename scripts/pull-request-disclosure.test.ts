import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {parse} from "yaml";
import {loadWorkflowScript, withDateNow} from "./test-support/workflow-script.ts";

interface UserNode {
  readonly login: string;
}

interface PullRequestNode {
  readonly body?: string | null;
  readonly head?: {readonly ref: string};
  readonly labels?: readonly {readonly name: string}[];
  readonly merged_at?: string | null;
  readonly number: number;
  readonly state: string;
  readonly updated_at?: string;
  readonly user?: UserNode;
}

interface CommentNode {
  readonly body: string;
  readonly id: number;
  readonly user: UserNode;
}

interface IssueEventNode {
  readonly actor: UserNode;
  readonly event: string;
  readonly label?: {readonly name: string};
}

interface RunOptions {
  readonly action?: string;
  readonly addFailure?: Error;
  readonly closedPullRequests?: readonly PullRequestNode[];
  readonly commentFailure?: Error;
  readonly commentReadFailure?: Error;
  readonly eventReadFailure?: Error;
  readonly existingComments?: readonly CommentNode[];
  readonly existingLabels?: readonly string[];
  readonly issueEvents?: readonly IssueEventNode[];
  readonly labelExists?: boolean;
  readonly labelLookupFailure?: Error;
  readonly labelReadFailure?: Error;
  readonly now?: string;
  readonly openPullRequests?: readonly PullRequestNode[];
  readonly pageSize?: number;
  readonly pullRequest?: PullRequestNode;
  readonly pullRequestReadFailure?: Error;
  readonly removeFailureLabels?: readonly string[];
  readonly updateFailure?: Error;
}

interface RunResult {
  readonly added: string[];
  readonly closed: number[];
  readonly comments: string[];
  readonly createdLabels: string[];
  /** One entry per page of pull requests read, named by the listed state. */
  readonly listed: string[];
  readonly removed: string[];
  readonly reopened: number[];
  readonly updatedComments: {readonly body: string; readonly id: number}[];
  readonly warnings: string[];
}

interface WorkflowDocument {
  readonly jobs?: Readonly<Record<string, {
    readonly concurrency?: {readonly group?: string};
    readonly if?: unknown;
    readonly permissions?: Readonly<Record<string, string>>;
    readonly steps?: readonly {readonly uses?: unknown; readonly with?: Readonly<Record<string, unknown>>}[];
  }>>;
  readonly on?: {
    readonly pull_request_target?: {readonly types?: readonly string[]};
    readonly schedule?: readonly {readonly cron: string}[];
  };
  readonly permissions?: unknown;
}

const WORKFLOW = readFileSync(new URL("../.github/workflows/pull-request-disclosure.yml", import.meta.url), "utf8");
const DISCLOSURE = loadWorkflowScript(
  "pull-request-disclosure.yml",
  "Flag, resolve, close, or reopen pull requests with an incomplete description",
);
const TEMPLATE = readFileSync(new URL("../.github/pull_request_template.md", import.meta.url), "utf8");
const AUTHOR = {login: "octocat"};
const BOT = {login: "github-actions[bot]"};
const MAINTAINER = {login: "maintainer"};
const MISSING_LABEL = "missing-confirmation";
const WAITING_LABEL = "waiting-for-author";
const REVIEW_LABEL = "needs-maintainer-review";
const NO_AI_ASSISTANCE = "No AI assistance beyond short completion, search, formatting, or typo fixes";
const AI_ASSISTED = "AI-assisted PR";
const UNDERSTOOD = "I confirm I understand what the code does";
const EVENT_TIME = "2026-10-30T12:00:00Z";
// The first daily run at or after EVENT_TIME plus 14 days.
const CLOSE_DATE = "2026-11-13 UTC";
const DUE = "2026-11-13T13:17:00Z";
const FLAG_MARKER = flagMarker("boxUnticked");
const RESOLVED_MARKER = "<!-- symphony-trello:missing-confirmation resolved -->";
const CLOSED_MARKER = `<!-- symphony-trello:missing-confirmation closed flagged-at=${EVENT_TIME} -->`;
const REOPEN_FAILED_MARKER = "<!-- symphony-trello:missing-confirmation reopen-failed -->";
const FORCE_PUSH_WARNING = "Don't force-push or rebase this branch until it's reopened.";
const CLOSED_BY_BOT: IssueEventNode = {actor: BOT, event: "closed"};
const CLOSED_BY_MAINTAINER: IssueEventNode = {actor: MAINTAINER, event: "closed"};
const REOPENED_BY_BOT: IssueEventNode = {actor: BOT, event: "reopened"};

function flagMarker(problem: string, flaggedAt = EVENT_TIME): string {
  return `<!-- symphony-trello:missing-confirmation flagged-at=${flaggedAt} added-waiting=true restore-review=false problem=${problem} -->`;
}

function unlabeled(actor: UserNode): IssueEventNode {
  return {actor, event: "unlabeled", label: {name: MISSING_LABEL}};
}

function tick(body: string, label: string): string {
  const parts = body.split(`- [ ] ${label}`);
  assert.equal(parts.length, 2, `expected exactly one unticked ${JSON.stringify(label)}`);
  return `${parts[0]}- [x] ${label}${parts[1]}`;
}

function drop(body: string, text: string): string {
  const parts = body.split(text);
  assert.equal(parts.length, 2, `expected exactly one ${JSON.stringify(text)}`);
  return `${parts[0]}${parts[1]}`;
}

const VALID_BODY = tick(tick(TEMPLATE, NO_AI_ASSISTANCE), UNDERSTOOD);
const UNTICKED_BODY = tick(TEMPLATE, NO_AI_ASSISTANCE);

function pullRequest(body: string | null, overrides: Partial<PullRequestNode> = {}): PullRequestNode {
  return {body, head: {ref: "feature"}, number: 42, state: "open", updated_at: EVENT_TIME, user: AUTHOR, ...overrides};
}

function flagComment(body: string, id = 7): CommentNode {
  return {body, id, user: BOT};
}

function closedComment(id = 9, marker = CLOSED_MARKER): CommentNode {
  return {body: `${marker}\nClosing`, id, user: BOT};
}

function pages<T>(items: readonly T[], size: number): T[][] {
  const result: T[][] = [];
  for (let start = 0; start === 0 || start < items.length; start += size) {
    result.push(items.slice(start, start + size));
  }
  return result;
}

async function run(eventName: "pull_request_target" | "schedule", options: RunOptions = {}): Promise<RunResult> {
  const added: string[] = [];
  const closed: number[] = [];
  const comments: string[] = [];
  const createdLabels: string[] = [];
  const listed: string[] = [];
  const removed: string[] = [];
  const reopened: number[] = [];
  const updatedComments: {body: string; id: number}[] = [];
  const warnings: string[] = [];
  const removeFailures = new Set(options.removeFailureLabels ?? []);
  const listLabelsOnIssue = async (): Promise<void> => {};
  const listComments = async (): Promise<void> => {};
  const listEvents = async (): Promise<void> => {};
  const list = async (): Promise<void> => {};
  const allPullRequests = [...(options.openPullRequests ?? []), ...(options.closedPullRequests ?? [])];
  const github = {
    // Like Octokit's paginate: a map function sees one page at a time, and calling done() stops
    // the reading after that page.
    paginate: async (
      endpoint: () => Promise<void>,
      parameters: {readonly state?: string},
      mapPage?: (response: {readonly data: PullRequestNode[]}, done: () => void) => unknown[],
    ): Promise<unknown[]> => {
      if (endpoint === listComments) {
        if (options.commentReadFailure) {
          throw options.commentReadFailure;
        }
        return [...(options.existingComments ?? [])];
      }
      if (endpoint === listEvents) {
        if (options.eventReadFailure) {
          throw options.eventReadFailure;
        }
        return [...(options.issueEvents ?? [CLOSED_BY_BOT])];
      }
      if (endpoint === list) {
        const listedPullRequests = parameters.state === "closed" ? options.closedPullRequests : options.openPullRequests;
        const read: unknown[] = [];
        let stopped = false;
        for (const page of pages(listedPullRequests ?? [], options.pageSize ?? 100)) {
          listed.push(parameters.state ?? "");
          read.push(...(mapPage ? mapPage({data: page}, () => {
            stopped = true;
          }) : page));
          if (stopped) {
            break;
          }
        }
        return read;
      }
      if (options.labelReadFailure) {
        throw options.labelReadFailure;
      }
      return (options.existingLabels ?? []).map((name) => ({name}));
    },
    rest: {
      issues: {
        addLabels: async ({labels}: {readonly labels: readonly string[]}): Promise<void> => {
          if (options.addFailure) {
            throw options.addFailure;
          }
          added.push(...labels);
        },
        createComment: async ({body}: {readonly body: string}): Promise<void> => {
          if (options.commentFailure) {
            throw options.commentFailure;
          }
          comments.push(body);
        },
        createLabel: async ({name}: {readonly name: string}): Promise<void> => {
          createdLabels.push(name);
        },
        getLabel: async (): Promise<void> => {
          if (options.labelLookupFailure) {
            throw options.labelLookupFailure;
          }
          if (options.labelExists === false) {
            throw Object.assign(new Error("Not Found"), {status: 404});
          }
        },
        listComments,
        listEvents,
        listLabelsOnIssue,
        removeLabel: async ({name}: {readonly name: string}): Promise<void> => {
          if (removeFailures.has(name)) {
            throw new Error(`remove failed for ${name}`);
          }
          removed.push(name);
        },
        updateComment: async ({body, comment_id}: {readonly body: string; readonly comment_id: number}): Promise<void> => {
          updatedComments.push({body, id: comment_id});
        },
      },
      pulls: {
        get: async ({pull_number}: {readonly pull_number: number}): Promise<{data: PullRequestNode}> => {
          if (options.pullRequestReadFailure) {
            throw options.pullRequestReadFailure;
          }
          const fromList = allPullRequests.find(({number}) => number === pull_number);
          return {data: options.pullRequest ?? fromList ?? pullRequest(UNTICKED_BODY)};
        },
        list,
        update: async ({pull_number, state}: {readonly pull_number: number; readonly state: string}): Promise<void> => {
          if (options.updateFailure) {
            throw options.updateFailure;
          }
          (state === "closed" ? closed : reopened).push(pull_number);
        },
      },
    },
  };
  const payload = eventName === "pull_request_target"
    ? {action: options.action ?? "opened", pull_request: {number: 42}}
    : {schedule: "17 13 * * *"};
  const context = {eventName, payload, repo: {owner: "owner", repo: "repo"}};
  const core = {warning: (message: string): void => {
    warnings.push(message);
  }};

  await withDateNow(Date.parse(options.now ?? EVENT_TIME), () => DISCLOSURE({context, github, core}));
  return {added, closed, comments, createdLabels, listed, removed, reopened, updatedComments, warnings};
}

const NOTHING: RunResult = {
  added: [], closed: [], comments: [], createdLabels: [], listed: [], removed: [], reopened: [], updatedComments: [], warnings: [],
};

test("the workflow is metadata-only, least-privilege, pinned, and scheduled outside the maintenance window", () => {
  const document = parse(WORKFLOW) as WorkflowDocument;
  assert.deepEqual(document.on?.pull_request_target?.types, ["opened", "edited", "reopened", "synchronize", "ready_for_review"]);
  assert.equal(document.permissions, "read-all");
  const cron = document.on?.schedule?.[0]?.cron ?? "";
  assert.notEqual(Number(cron.split(" ")[1]), 4, `the daily run must stay out of 04:00 to 05:00 UTC, got ${cron}`);
  const [job] = Object.values(document.jobs ?? {});
  assert.deepEqual(job?.permissions, {issues: "write", "pull-requests": "write"});
  assert.equal(
    job?.concurrency?.group,
    "pull-request-disclosure-${{ github.event.pull_request.number || 'sweep' }}",
    "one group per pull request, so a burst of events on other pull requests drops no run",
  );
  assert.match(String(job?.if), /github\.event\.pull_request\.base\.repo\.full_name == github\.repository/);
  assert.match(String(job?.if), /!endsWith\(github\.event\.pull_request\.user\.login, '\[bot\]'\)/);
  assert.match(String(job?.if), /github\.event\.pull_request\.user\.login != 'martinfrancois'/);
  assert.doesNotMatch(String(job?.if), /head\.ref|release-please/, "a fork can reuse any branch name");
  const steps = job?.steps ?? [];
  assert.equal(steps.length, 2);
  assert.match(String(steps[0]?.uses), /^actions\/checkout@[0-9a-f]{40}$/);
  assert.equal(steps[0]?.with?.["sparse-checkout"], "scripts/pull-request-disclosure.cjs");
  assert.equal(steps[0]?.with?.["persist-credentials"], false);
  assert.match(String(steps[1]?.uses), /^actions\/github-script@[0-9a-f]{40}$/);
  assert.doesNotMatch(WORKFLOW, /actions\/cache|cache:|cache-mode|secrets\./);
});

test("an unticked box is flagged once with labels and the approved comment", async () => {
  const result = await run("pull_request_target");

  assert.deepEqual(result.added, [MISSING_LABEL, WAITING_LABEL]);
  assert.deepEqual(result.removed, []);
  assert.deepEqual(result.warnings, []);
  assert.deepEqual(result.comments, [[
    FLAG_MARKER,
    `Thanks for the pull request, @octocat! Please tick \`${UNDERSTOOD}\` in the description. Until then it has the \`${WAITING_LABEL}\` label and won't be reviewed.`,
    "",
    `If it's still unticked on ${CLOSE_DATE}, it closes automatically. Ticking the box later reopens it.`,
  ].join("\n")]);
});

test("the close date is the date of the first daily run at or after 14 days, at the workflow's cron time", async () => {
  const cron = (parse(WORKFLOW) as WorkflowDocument).on?.schedule?.[0]?.cron ?? "";
  const [minute, hour] = cron.split(" ").map(Number);
  const sweep = Date.UTC(2026, 10, 13, hour, minute);
  const flaggedAt = (offsetMinutes: number): string => new Date(sweep - 14 * 24 * 60 * 60 * 1000 + offsetMinutes * 60 * 1000).toISOString();
  for (const [offsetMinutes, date] of [[-1, "2026-11-13"], [0, "2026-11-13"], [1, "2026-11-14"]] as const) {
    const result = await run("pull_request_target", {pullRequest: pullRequest(UNTICKED_BODY, {updated_at: flaggedAt(offsetMinutes)})});
    assert.match(
      result.comments[0] ?? "",
      new RegExp(`still unticked on ${date} UTC, it closes`),
      `14 days end ${offsetMinutes} minute(s) from the ${cron} run on 2026-11-13, so the run that closes it is on ${date}`,
    );
  }
});

test("the close warning is its own paragraph in every flag comment", async () => {
  for (const [name, body] of [["unticked", UNTICKED_BODY], ["neither choice", tick(TEMPLATE, UNDERSTOOD)]] as const) {
    const result = await run("pull_request_target", {pullRequest: pullRequest(body)});
    const paragraphs = (result.comments[0] ?? "").split("\n").slice(1).join("\n").split("\n\n");
    assert.equal(paragraphs.length, 2, name);
    assert.match(paragraphs[1] ?? "", /^If it's still (unticked|incomplete) on .* UTC, it closes automatically\. /, name);
  }
});

test("the missing label is created when the repository does not have it yet", async () => {
  const result = await run("pull_request_target", {labelExists: false});

  assert.deepEqual(result.createdLabels, [MISSING_LABEL]);
  assert.deepEqual(result.added, [MISSING_LABEL, WAITING_LABEL]);
});

const PROBLEMS: readonly (readonly [string, string, string, RegExp, RegExp])[] = [
  ["unticked box", "boxUnticked", UNTICKED_BODY, /Please tick `I confirm I understand what the code does` in the description\./, /^Closing: `I confirm I understand what the code does` has been unticked for 14 days\. Ticking it reopens this pull request automatically\. /],
  ["neither choice", "neitherChoice", tick(TEMPLATE, UNDERSTOOD), /Please tick one AI assistance choice in the description/, /^Closing: neither AI assistance choice has been ticked for 14 days\. Ticking one reopens this pull request automatically\. /],
  ["both choices", "bothChoices", tick(VALID_BODY, AI_ASSISTED), /Please tick only one of the two AI assistance choices/, /^Closing: both AI assistance choices have been ticked for 14 days\. Ticking only one reopens this pull request automatically\. /],
  ["AI section missing", "aiSectionMissing", VALID_BODY.split("## AI Assistance (if used)")[0] ?? "", /Please add the `## AI Assistance \(if used\)` section/, /^Closing: the AI Assistance section has been missing from the description for 14 days\. Adding it back reopens this pull request automatically\. /],
  ["AI line missing", "aiLinesMissing", drop(VALID_BODY, `- [ ] ${AI_ASSISTED}\n`), /Please keep the two AI assistance checkboxes/, /^Closing: the AI assistance checkboxes have been missing from the description for 14 days\. Adding them back reopens this pull request automatically\. /],
  ["checklist missing", "checklistMissing", drop(VALID_BODY, "## Review Checklist\n"), /Please add the `## Review Checklist` section/, /^Closing: the Review Checklist section has been missing from the description for 14 days\. Adding it back reopens this pull request automatically\. /],
  ["box line missing", "boxLineMissing", drop(tick(TEMPLATE, NO_AI_ASSISTANCE), `- [ ] ${UNDERSTOOD}\n`), /Please keep the `- \[ \] I confirm I understand what the code does` line/, /^Closing: the `I confirm I understand what the code does` line has been missing from the description for 14 days\. Adding it back reopens this pull request automatically\. /],
  ["empty body", "aiSectionMissing", "", /Please add the `## AI Assistance \(if used\)` section/, /^Closing: the AI Assistance section has been missing/],
];

test("each problem gets its own fix sentence in the flag comment", async () => {
  for (const [name, , body, fix] of PROBLEMS) {
    const result = await run("pull_request_target", {pullRequest: pullRequest(body)});
    assert.equal(result.comments.length, 1, name);
    assert.match(result.comments[0] ?? "", fix, name);
  }
  const nullBody = await run("pull_request_target", {pullRequest: pullRequest(null)});
  assert.equal(nullBody.comments.length, 1);
});

test("each problem flagged and still present at close time gets its own closing comment, ending with the force-push warning", async () => {
  for (const [name, key, body, , closing] of PROBLEMS) {
    const result = await run("schedule", {
      existingComments: [flagComment(`${flagMarker(key)}\nold text`)],
      now: DUE,
      openPullRequests: [pullRequest(body, {labels: [{name: MISSING_LABEL}]})],
    });
    assert.deepEqual(result.closed, [42], name);
    assert.equal(result.comments.length, 1, name);
    const [, text] = (result.comments[0] ?? "").split("\n");
    assert.match(text ?? "", closing, name);
    assert.ok(text?.endsWith(FORCE_PUSH_WARNING), `${name}: ${text ?? ""}`);
  }
});

test("a later edit that is still incomplete keeps the flag time and flagged problem and edits the comment in place", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(drop(UNTICKED_BODY, "## Review Checklist\n"), {updated_at: "2026-11-02T08:00:00Z"}),
  });

  assert.deepEqual(result.comments, []);
  assert.deepEqual(result.added, []);
  assert.equal(result.updatedComments.length, 1);
  assert.ok(result.updatedComments[0]?.body.startsWith(`${FLAG_MARKER}\n`), result.updatedComments[0]?.body ?? "no update");
  assert.equal(result.updatedComments[0]?.id, 7);
  assert.match(result.updatedComments[0]?.body ?? "", /Please add the `## Review Checklist` section/);
  assert.match(result.updatedComments[0]?.body ?? "", new RegExp(`still incomplete on ${CLOSE_DATE}`));
});

test("an unchanged comment is not rewritten", async () => {
  const first = await run("pull_request_target");
  const second = await run("pull_request_target", {
    existingComments: [flagComment(first.comments[0] ?? "")],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
  });

  assert.deepEqual(second.comments, []);
  assert.deepEqual(second.updatedComments, []);
  assert.deepEqual(second.added, []);
});

test("a flagged pull request that the author pushed to keeps waiting and loses the review label", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [MISSING_LABEL, REVIEW_LABEL],
  });

  assert.deepEqual(result.added, [WAITING_LABEL]);
  assert.deepEqual(result.removed, [REVIEW_LABEL]);
});

test("a pull request taken out of the review queue by the flag returns to it when fixed", async () => {
  const flagged = await run("pull_request_target", {existingLabels: [REVIEW_LABEL]});
  assert.deepEqual(flagged.removed, [REVIEW_LABEL]);
  assert.ok(flagged.comments[0]?.includes("restore-review=true"), flagged.comments[0] ?? "no comment");

  const fixed = await run("pull_request_target", {
    existingComments: [flagComment(flagged.comments[0] ?? "")],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY),
  });
  assert.deepEqual(fixed.removed, [MISSING_LABEL, WAITING_LABEL]);
  assert.deepEqual(fixed.added, [REVIEW_LABEL]);
});

test("a maintainer's existing waiting label is recorded as not added by the automation", async () => {
  const result = await run("pull_request_target", {existingLabels: [WAITING_LABEL]});

  assert.deepEqual(result.added, [MISSING_LABEL]);
  assert.ok(result.comments[0]?.includes("added-waiting=false"), result.comments[0] ?? "no comment");
});

test("a fixed description removes the labels the automation added and marks the comment resolved", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY),
  });

  assert.deepEqual(result.removed, [MISSING_LABEL, WAITING_LABEL]);
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.comments, []);
  assert.deepEqual(result.updatedComments, [{
    body: `${RESOLVED_MARKER}\nThanks, @octocat, the description is complete now.`,
    id: 7,
  }]);
});

test("a fixed description keeps a waiting label the automation did not add and says in one sentence how to hand off", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(FLAG_MARKER.replace("added-waiting=true", "added-waiting=false"))],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY),
  });

  assert.deepEqual(result.removed, [MISSING_LABEL]);
  assert.deepEqual(result.updatedComments, [{
    body: `${RESOLVED_MARKER}\nThanks, @octocat, the description is complete now. \`${WAITING_LABEL}\` stays for the earlier feedback; comment \`/ready\` once that's addressed.`,
    id: 7,
  }]);
});

test("a valid description with no earlier flag changes nothing", async () => {
  const result = await run("pull_request_target", {pullRequest: pullRequest(VALID_BODY)});

  assert.deepEqual(result, NOTHING);
});

test("a resolved comment is not resolved twice", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${RESOLVED_MARKER}\nThanks`)],
    pullRequest: pullRequest(VALID_BODY),
  });

  assert.deepEqual(result.removed, []);
  assert.deepEqual(result.updatedComments, []);
});

test("a pull request that becomes incomplete again gets a new flag time, even after a maintainer opted it out", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${RESOLVED_MARKER}\nThanks`)],
    issueEvents: [unlabeled(MAINTAINER)],
    pullRequest: pullRequest(UNTICKED_BODY, {updated_at: "2026-12-01T09:30:00Z"}),
  });

  assert.deepEqual(result.added, [MISSING_LABEL, WAITING_LABEL]);
  assert.ok(result.updatedComments[0]?.body.includes("flagged-at=2026-12-01T09:30:00Z"), result.updatedComments[0]?.body ?? "no update");
  assert.match(result.updatedComments[0]?.body ?? "", /still unticked on 2026-12-15 UTC, it closes/);
});

test("a flagged pull request whose missing-confirmation label a maintainer removed is left alone on the next edit", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [WAITING_LABEL],
    issueEvents: [unlabeled(MAINTAINER)],
  });

  assert.deepEqual(result, NOTHING);
});

test("the daily run does not close a pull request whose missing-confirmation label a maintainer removed after the list", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [WAITING_LABEL],
    issueEvents: [unlabeled(MAINTAINER)],
    now: "2026-12-01T00:00:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
  });

  assert.deepEqual(result, {...NOTHING, listed: ["open", "closed"]});
});

test("a missing-confirmation label the automation removed itself is added back on the next incomplete edit", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [WAITING_LABEL],
    issueEvents: [unlabeled(MAINTAINER), {actor: BOT, event: "labeled", label: {name: MISSING_LABEL}}, unlabeled(BOT)],
  });

  assert.deepEqual(result.added, [MISSING_LABEL]);
  assert.ok(result.updatedComments[0]?.body.startsWith(`${FLAG_MARKER}\n`), result.updatedComments[0]?.body ?? "no update");
});

test("an event read failure stops the run before any write", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    eventReadFailure: new Error("events down"),
  });

  assert.deepEqual({...result, warnings: []}, NOTHING);
  assert.match(result.warnings[0] ?? "", /Unable to read events for #42/);
});

test("an author's own copy of the marker is ignored", async () => {
  const result = await run("pull_request_target", {existingComments: [{body: FLAG_MARKER, id: 3, user: AUTHOR}]});

  assert.equal(result.comments.length, 1);
  assert.deepEqual(result.updatedComments, []);
});

test("a fork branch named like Release Please is checked like any other", async () => {
  const result = await run("pull_request_target", {
    pullRequest: pullRequest(UNTICKED_BODY, {head: {ref: "release-please--branches--main"}}),
  });

  assert.deepEqual(result.added, [MISSING_LABEL, WAITING_LABEL]);
});

test("bots and martinfrancois are skipped", async () => {
  for (const user of [{login: "renovate[bot]"}, {login: "github-actions[bot]"}, {login: "martinfrancois"}]) {
    const result = await run("pull_request_target", {pullRequest: pullRequest(UNTICKED_BODY, {user})});
    assert.deepEqual(result, NOTHING, user.login);
  }
});

test("the daily run closes a pull request flagged 14 days ago that is still unticked with the approved comment", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    now: DUE,
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}, {name: WAITING_LABEL}]})],
  });

  assert.deepEqual(result.closed, [42]);
  assert.deepEqual(result.comments, [[
    CLOSED_MARKER,
    `Closing: \`${UNDERSTOOD}\` has been unticked for 14 days. Ticking it reopens this pull request automatically. ${FORCE_PUSH_WARNING}`,
  ].join("\n")]);
  assert.deepEqual(result.updatedComments, []);
});

test("a close whose problem changed since the flag says the description was incomplete, not that the new problem lasted 14 days", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    now: DUE,
    openPullRequests: [pullRequest(drop(UNTICKED_BODY, "## Review Checklist\n"), {labels: [{name: MISSING_LABEL}]})],
  });

  assert.deepEqual(result.closed, [42]);
  assert.deepEqual(result.comments, [[
    CLOSED_MARKER,
    `Closing: the description has been incomplete for 14 days. Please add the \`## Review Checklist\` section from the pull request template back to the description. Adding it back reopens this pull request automatically. ${FORCE_PUSH_WARNING}`,
  ].join("\n")]);
});

test("the daily run leaves a pull request alone before the first run at or after the 14 days", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    now: "2026-11-13T13:16:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}, {name: WAITING_LABEL}]})],
  });

  assert.deepEqual(result.closed, []);
  assert.deepEqual(result.comments, []);
});

test("a late daily run on the day the 14 days end does not close before the date in the comment", async () => {
  const lateRun = await run("schedule", {
    existingComments: [flagComment(`${flagMarker("boxUnticked", "2026-10-30T13:30:00Z")}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    now: "2026-11-13T13:45:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
  });
  assert.deepEqual(lateRun.closed, [], "the comment promised 2026-11-14 UTC");

  const nextRun = await run("schedule", {
    existingComments: [flagComment(`${flagMarker("boxUnticked", "2026-10-30T13:30:00Z")}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    now: "2026-11-14T13:17:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
  });
  assert.deepEqual(nextRun.closed, [42]);
});

test("the daily run counts from the flag time, not from later activity", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    now: "2026-11-20T00:00:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}], updated_at: "2026-11-19T00:00:00Z"})],
  });

  assert.deepEqual(result.closed, [42]);
});

test("the daily run re-reads the pull request before closing and resolves a fix that landed since the list", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    now: "2026-12-01T00:00:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
    pullRequest: pullRequest(VALID_BODY),
  });

  assert.deepEqual(result.closed, []);
  assert.deepEqual(result.removed, [MISSING_LABEL, WAITING_LABEL]);
});

test("a close that failed after its comment is retried without a second comment", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    now: "2026-12-01T00:00:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
  });

  assert.deepEqual(result.closed, [42]);
  assert.deepEqual(result.comments, []);
});

test("the daily run touches only pull requests that carry the label", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    now: "2026-12-01T00:00:00Z",
    openPullRequests: [
      pullRequest(UNTICKED_BODY, {labels: [{name: WAITING_LABEL}]}),
      pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}], number: 43, user: {login: "martinfrancois"}}),
    ],
  });

  assert.deepEqual(result, {...NOTHING, listed: ["open", "closed"]});
});

test("a flagged pull request whose comment was deleted is flagged again instead of closed", async () => {
  const result = await run("schedule", {
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    now: "2026-12-01T00:00:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}, {name: WAITING_LABEL}]})],
  });

  assert.deepEqual(result.closed, []);
  assert.equal(result.comments.length, 1);
  assert.ok(result.comments[0]?.includes("flagged-at=2026-12-01T00:00:00.000Z"), result.comments[0] ?? "no comment");
});

test("a closed pull request whose description was fixed is reopened and resolved on the edited event", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
  });

  assert.deepEqual(result.reopened, [42]);
  assert.deepEqual(result.removed, [MISSING_LABEL, WAITING_LABEL]);
  assert.match(result.updatedComments[0]?.body ?? "", /the description is complete now/);
});

test("a closed pull request that is still incomplete is left closed", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    pullRequest: pullRequest(UNTICKED_BODY, {state: "closed"}),
  });

  assert.deepEqual(result, NOTHING);
});

test("a pull request a person closed after this automation reopened it is not reopened", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${RESOLVED_MARKER}\nThanks`), closedComment()],
    issueEvents: [CLOSED_BY_BOT, REOPENED_BY_BOT, CLOSED_BY_MAINTAINER],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
  });

  assert.deepEqual(result, NOTHING);
});

test("a pull request closed during a later flag cycle is not reopened on an earlier cycle's closed marker", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${flagMarker("boxUnticked", "2026-12-01T09:30:00Z")}\nold text`), closedComment()],
    issueEvents: [CLOSED_BY_BOT, REOPENED_BY_BOT, CLOSED_BY_BOT],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
  });

  assert.deepEqual(result, NOTHING);
});

test("a pull request a person closed after this automation's close in the same flag cycle is not reopened", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    issueEvents: [CLOSED_BY_BOT, {actor: MAINTAINER, event: "reopened"}, CLOSED_BY_MAINTAINER],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
  });

  assert.deepEqual(result, NOTHING);
});

test("a pull request a maintainer or the author closed is never reopened", async () => {
  const fixedNoMarker = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
  });
  assert.deepEqual(fixedNoMarker, NOTHING);

  const reopenFailedBefore = await run("pull_request_target", {
    action: "edited",
    existingComments: [closedComment(), {body: `${REOPEN_FAILED_MARKER}\nSorry`, id: 11, user: BOT}],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
  });
  assert.deepEqual(reopenFailedBefore, NOTHING);
});

test("a reopen GitHub refuses gets one comment asking for a new pull request and loses the labels the automation added", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
    updateFailure: Object.assign(new Error("Validation Failed"), {status: 422}),
  });

  assert.deepEqual(result.reopened, []);
  assert.deepEqual(result.removed, [MISSING_LABEL, WAITING_LABEL]);
  assert.deepEqual(result.comments, [
    `${REOPEN_FAILED_MARKER}\nThanks, the description is complete now, but GitHub can't reopen this pull request because its branch changed or was deleted. Please open a new one.`,
  ]);
  assert.deepEqual(result.warnings, []);
});

test("the daily run skips a pull request whose reopen GitHub refused", async () => {
  const reopenFailed = {body: `${REOPEN_FAILED_MARKER}\nSorry`, id: 11, user: BOT};
  const result = await run("schedule", {
    closedPullRequests: [pullRequest(VALID_BODY, {labels: [{name: MISSING_LABEL}], number: 43, state: "closed", updated_at: "2026-11-30T00:00:00Z"})],
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment(), reopenFailed],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    now: "2026-12-01T00:00:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
  });

  assert.deepEqual(result, {...NOTHING, listed: ["open", "closed"]});
});

test("the daily run reopens a fixed closed pull request this automation closed", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    now: "2026-12-01T00:00:00Z",
    closedPullRequests: [pullRequest(VALID_BODY, {labels: [{name: MISSING_LABEL}], state: "closed", updated_at: "2026-11-20T00:00:00Z"})],
  });

  assert.deepEqual(result.reopened, [42]);
  assert.deepEqual(result.removed, [MISSING_LABEL, WAITING_LABEL]);
});

test("the daily run leaves an unfixed closed pull request, a maintainer-closed one, a merged one, and one past the cutoff alone", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    now: "2026-12-01T00:00:00Z",
    closedPullRequests: [
      pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}], state: "closed", updated_at: "2026-11-25T00:00:00Z"}),
      pullRequest(VALID_BODY, {labels: [], number: 43, state: "closed", updated_at: "2026-11-24T00:00:00Z"}),
      pullRequest(VALID_BODY, {labels: [{name: MISSING_LABEL}], merged_at: "2026-11-23T00:00:00Z", number: 44, state: "closed", updated_at: "2026-11-23T00:00:00Z"}),
      pullRequest(VALID_BODY, {labels: [{name: MISSING_LABEL}], number: 45, state: "closed", updated_at: "2026-08-01T00:00:00Z"}),
    ],
  });

  assert.deepEqual(result.reopened, []);
  assert.deepEqual(result.comments, []);
});

test("the daily run stops reading closed pull requests at the 90-day cutoff", async () => {
  const insideWindow = pullRequest(VALID_BODY, {labels: [{name: MISSING_LABEL}], number: 46, state: "closed", updated_at: "2026-09-03T00:00:00Z"});
  const pastWindow = pullRequest(VALID_BODY, {labels: [{name: MISSING_LABEL}], number: 45, state: "closed", updated_at: "2026-09-01T00:00:00Z"});
  const nextPage = pullRequest(VALID_BODY, {labels: [{name: MISSING_LABEL}], number: 44, state: "closed", updated_at: "2026-08-01T00:00:00Z"});
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    now: "2026-12-01T00:00:00Z",
    closedPullRequests: [insideWindow, pastWindow, nextPage],
    pageSize: 2,
  });

  assert.deepEqual(result.reopened, [46]);
  assert.deepEqual(result.listed, ["open", "closed"], "the page after the one that crosses the cutoff must not be fetched");
});

test("a label read failure stops the flag before any write", async () => {
  const result = await run("pull_request_target", {labelReadFailure: new Error("labels down")});
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.comments, []);
  assert.match(result.warnings[0] ?? "", /Unable to read labels/);
});

test("a comment read failure stops the run before any write", async () => {
  const result = await run("pull_request_target", {commentReadFailure: new Error("comments down")});
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.comments, []);
  assert.match(result.warnings[0] ?? "", /Unable to read comments/);
});

test("a pull request read failure stops the run", async () => {
  const result = await run("pull_request_target", {pullRequestReadFailure: new Error("pulls down")});
  assert.deepEqual(result.added, []);
  assert.match(result.warnings[0] ?? "", /Unable to read pull request #42/);
});

test("a label lookup error other than not found stops the flag", async () => {
  const result = await run("pull_request_target", {labelLookupFailure: new Error("labels api down")});
  assert.deepEqual(result.added, []);
  assert.deepEqual(result.createdLabels, []);
  assert.match(result.warnings[0] ?? "", /Unable to read the missing-confirmation label/);
});

test("a failed label addition skips the comment", async () => {
  const result = await run("pull_request_target", {addFailure: new Error("add down")});
  assert.deepEqual(result.comments, []);
  assert.match(result.warnings[0] ?? "", /Unable to add/);
});

test("a failed comment keeps the labels and warns", async () => {
  const result = await run("pull_request_target", {commentFailure: new Error("comment down")});
  assert.deepEqual(result.added, [MISSING_LABEL, WAITING_LABEL]);
  assert.match(result.warnings[0] ?? "", /Unable to post the disclosure comment/);
});

test("a failed close warns and leaves the pull request open", async () => {
  const result = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    now: "2026-12-01T00:00:00Z",
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
    updateFailure: new Error("close down"),
  });
  assert.deepEqual(result.closed, []);
  assert.match(result.warnings[0] ?? "", /Unable to close/);
});

test("a reopen that fails for another reason warns and posts nothing", async () => {
  const result = await run("pull_request_target", {
    action: "edited",
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`), closedComment()],
    pullRequest: pullRequest(VALID_BODY, {state: "closed"}),
    updateFailure: new Error("reopen down"),
  });
  assert.deepEqual(result.comments, []);
  assert.match(result.warnings[0] ?? "", /Unable to reopen #42/);
});

test("a failed label removal leaves the comment unresolved", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY),
    removeFailureLabels: [MISSING_LABEL],
  });
  assert.deepEqual(result.updatedComments, []);
  assert.match(result.warnings[0] ?? "", /Unable to remove/);
});

test("a marker with an unreadable flag time is treated as a fresh flag", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(FLAG_MARKER.replace(EVENT_TIME, "yesterday"))],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
  });
  assert.ok(result.updatedComments[0]?.body.includes(`flagged-at=${EVENT_TIME}`), result.updatedComments[0]?.body ?? "no update");
});

test("a fixed pull request with an unreadable marker still loses the flag label", async () => {
  const result = await run("pull_request_target", {
    existingComments: [flagComment(FLAG_MARKER.replace(EVENT_TIME, "yesterday"))],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY),
  });
  assert.deepEqual(result.removed, [MISSING_LABEL]);
  assert.match(result.updatedComments[0]?.body ?? "", /resolved/);
});

test("no contributor-facing text contains an em dash", async () => {
  const flagged = await run("pull_request_target");
  const resolved = await run("pull_request_target", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    existingLabels: [MISSING_LABEL, WAITING_LABEL],
    pullRequest: pullRequest(VALID_BODY),
  });
  const closed = await run("schedule", {
    existingComments: [flagComment(`${FLAG_MARKER}\nold text`)],
    now: DUE,
    openPullRequests: [pullRequest(UNTICKED_BODY, {labels: [{name: MISSING_LABEL}]})],
  });
  for (const text of [...flagged.comments, ...resolved.updatedComments.map(({body}) => body), ...closed.comments]) {
    assert.doesNotMatch(text, /—|–/);
  }
});
