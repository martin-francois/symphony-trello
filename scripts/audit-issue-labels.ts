import {spawnSync} from "node:child_process";
import {pathToFileURL} from "node:url";

// Checks every open issue against the mechanical rules of the Triage labels policy in
// docs/agents/github-issue-triage.md. That page is the authority: when it changes, change RULES to
// match. Rules that need judgment, such as the `good first issue` 80% one-shot bar, are listed for a
// decision instead of failing the run. ADR 0138.

export interface AuditIssue {
  readonly number: number;
  readonly title: string;
  readonly assigneeCount: number;
  readonly labels: readonly string[];
  /** Native `blocked by` issues that are still open. */
  readonly openBlockers: number;
  /** Native `blocked by` issues, open and closed. */
  readonly totalBlockers: number;
}

export type Severity = "failure" | "judgment";

export type RuleId = (typeof RULES)[number]["id"];

export interface Finding {
  readonly id: RuleId;
  readonly severity: Severity;
  readonly rule: string;
  readonly issues: readonly AuditIssue[];
}

interface LabelledIssue {
  readonly has: (label: string) => boolean;
  readonly count: (labels: readonly string[]) => number;
  readonly assigned: boolean;
  /** Not ready to pick up, so `help wanted` and `good first issue` must stay off. */
  readonly gated: boolean;
  readonly openBlockers: number;
  readonly totalBlockers: number;
}

interface Rule {
  readonly id: string;
  readonly severity: Severity;
  readonly rule: string;
  readonly matches: (issue: LabelledIssue) => boolean;
}

const PRIORITY_LABELS = ["priority high", "priority medium", "priority low"];
const SIZE_LABELS = ["size XS", "size S", "size M", "size L", "size XL"];
const SMALL_SIZE_LABELS = ["size XS", "size S"];
const PICKUP_LABELS = ["help wanted", "good first issue"];
// Labels whose issues the policy says must also have `not ready`. Counting them as gated keeps the
// report from asking for `help wanted` on an issue whose fix is to add `not ready`.
const NOT_READY_LABELS = ["not ready", "idea", "needs human review", "already implemented"];

const RULES = [
  {
    id: "priority-count",
    severity: "failure",
    rule: `needs exactly one priority label (${PRIORITY_LABELS.join(", ")})`,
    matches: (issue) => issue.count(PRIORITY_LABELS) !== 1,
  },
  {
    id: "size-count",
    severity: "failure",
    rule: `needs exactly one size label (${SIZE_LABELS.join(", ")})`,
    matches: (issue) => issue.count(SIZE_LABELS) !== 1,
  },
  {
    id: "idea-not-ready",
    severity: "failure",
    rule: "`idea` without `not ready`",
    matches: (issue) => issue.has("idea") && !issue.has("not ready"),
  },
  {
    id: "human-review-not-ready",
    severity: "failure",
    rule: "`needs human review` without `not ready`",
    matches: (issue) => issue.has("needs human review") && !issue.has("not ready"),
  },
  {
    id: "gated-pickup",
    severity: "failure",
    rule:
      "`help wanted` or `good first issue` on an issue that is or must be `not ready`, `blocked`, "
      + "blocked by an open native dependency, or a dependency dashboard",
    matches: (issue) => issue.gated && issue.count(PICKUP_LABELS) > 0,
  },
  {
    id: "assigned-help-wanted",
    severity: "failure",
    rule: "`help wanted` on an assigned issue",
    matches: (issue) => issue.assigned && issue.has("help wanted"),
  },
  {
    id: "ready-help-wanted",
    severity: "failure",
    rule: "ready, unassigned issue without `help wanted`",
    matches: (issue) => !issue.gated && !issue.assigned && !issue.has("help wanted"),
  },
  {
    id: "already-implemented-labels",
    severity: "failure",
    rule: "open `already implemented` issue without both `needs human review` and `not ready`",
    matches: (issue) =>
      issue.has("already implemented") && !(issue.has("needs human review") && issue.has("not ready")),
  },
  {
    id: "blocked-external-wait",
    severity: "judgment",
    rule: "`blocked` with no native dependency: confirm it waits on an external condition",
    matches: (issue) => issue.has("blocked") && issue.totalBlockers === 0,
  },
  {
    id: "blocked-closed-blockers",
    severity: "judgment",
    rule: "`blocked` and every native blocker is closed: remove it unless an external wait remains",
    matches: (issue) => issue.has("blocked") && issue.totalBlockers > 0 && issue.openBlockers === 0,
  },
  {
    id: "blocked-open-blocker",
    severity: "judgment",
    rule: "`blocked` with an open native blocker: confirm an external wait remains besides that issue",
    matches: (issue) => issue.has("blocked") && issue.openBlockers > 0,
  },
  {
    id: "already-implemented-verify",
    severity: "judgment",
    rule:
      "`already implemented`: verify against the code and close with a reference, "
      + "or comment what a maintainer must confirm",
    matches: (issue) => issue.has("already implemented"),
  },
  {
    id: "good-first-issue-candidate",
    severity: "judgment",
    rule: "small ready issue without `good first issue`: decide whether it meets the 80% one-shot bar",
    matches: (issue) => !issue.gated && issue.count(SMALL_SIZE_LABELS) > 0 && !issue.has("good first issue"),
  },
] as const satisfies readonly Rule[];

function isDependencyDashboard(issue: AuditIssue): boolean {
  // Renovate's default dashboard title; renovate.json does not change it.
  return issue.title.trim().toLowerCase() === "dependency dashboard";
}

function labelled(issue: AuditIssue): LabelledIssue {
  const labels = new Set(issue.labels);
  const has = (label: string) => labels.has(label);
  return {
    has,
    count: (candidates) => candidates.filter(has).length,
    assigned: issue.assigneeCount > 0,
    gated:
      NOT_READY_LABELS.some(has) || has("blocked") || issue.openBlockers > 0 || isDependencyDashboard(issue),
    openBlockers: issue.openBlockers,
    totalBlockers: issue.totalBlockers,
  };
}

export function auditIssues(issues: readonly AuditIssue[]): Finding[] {
  const checked = issues.map((issue) => ({issue, context: labelled(issue)}));
  return RULES.map(({id, severity, rule, matches}) => ({
    id,
    severity,
    rule,
    issues: checked.filter(({context}) => matches(context)).map(({issue}) => issue),
  })).filter((finding) => finding.issues.length > 0);
}

function renderSection(heading: string, findings: readonly Finding[]): string[] {
  if (findings.length === 0) {
    return [`${heading}: none`];
  }
  return [
    `${heading}:`,
    ...findings.flatMap(({rule, issues}) => [
      `  ${rule}: ${issues.length}`,
      ...issues.map(({number, title}) => `    #${number} ${title}`),
    ]),
  ];
}

export function renderReport(repository: string, issueCount: number, findings: readonly Finding[]): string {
  const failures = findings.filter(({severity}) => severity === "failure");
  const judgments = findings.filter(({severity}) => severity === "judgment");
  const total = (list: readonly Finding[]) => list.reduce((sum, {issues}) => sum + issues.length, 0);
  return [
    `Issue label audit of ${repository}, ${issueCount} open issues. `
      + `Failures: ${total(failures)}. Judgment items: ${total(judgments)}.`,
    "",
    ...renderSection("Failures (fix the labels)", failures),
    "",
    ...renderSection("Judgment items (decide each one and report the decision)", judgments),
  ].join("\n");
}

interface IssueNode {
  readonly number: number;
  readonly title: string;
  readonly assignees: {readonly totalCount: number};
  readonly labels: {readonly totalCount: number; readonly nodes: readonly {readonly name: string}[]};
  readonly issueDependenciesSummary: {readonly blockedBy: number; readonly totalBlockedBy: number} | null;
}

const OPEN_ISSUES_QUERY = `query($owner: String!, $name: String!, $endCursor: String) {
  repository(owner: $owner, name: $name) {
    issues(states: OPEN, first: 100, after: $endCursor) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number
        title
        assignees(first: 1) { totalCount }
        labels(first: 100) { totalCount nodes { name } }
        issueDependenciesSummary { blockedBy totalBlockedBy }
      }
    }
  }
}`;

/** Parses the issue nodes that `gh api graphql --paginate --jq` prints one per line. */
export function parseIssueNodes(output: string): AuditIssue[] {
  return output
    .split("\n")
    .filter((line) => line.trim().length > 0)
    .map((line) => {
      const node = JSON.parse(line) as IssueNode;
      if (node.labels.nodes.length < node.labels.totalCount) {
        throw new Error(`issue #${node.number} has more labels than one query page returns`);
      }
      return {
        number: node.number,
        title: node.title,
        assigneeCount: node.assignees.totalCount,
        labels: node.labels.nodes.map(({name}) => name),
        openBlockers: node.issueDependenciesSummary?.blockedBy ?? 0,
        totalBlockers: node.issueDependenciesSummary?.totalBlockedBy ?? 0,
      };
    });
}

function gh(args: readonly string[]): string {
  const result = spawnSync("gh", args, {encoding: "utf8", maxBuffer: 256 * 1024 * 1024});
  if (result.status !== 0) {
    const reason = result.error?.message ?? result.stderr.trim();
    throw new Error(`gh ${args[0]} ${args[1] ?? ""} failed: ${reason}`);
  }
  return result.stdout;
}

function fetchOpenIssues(repository: string): AuditIssue[] {
  const [owner, name, ...rest] = repository.split("/");
  if (!owner || !name || rest.length > 0) {
    throw new Error(`expected OWNER/REPO, got ${repository}`);
  }
  return parseIssueNodes(gh([
    "api", "graphql", "--paginate",
    "-f", `owner=${owner}`,
    "-f", `name=${name}`,
    "-f", `query=${OPEN_ISSUES_QUERY}`,
    "--jq", ".data.repository.issues.nodes[]",
  ]));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const usage = "usage: node scripts/audit-issue-labels.ts [OWNER/REPO]";
  const [argument, ...extra] = process.argv.slice(2);
  if (argument === "--help" || argument === "-h") {
    console.log(usage);
    process.exit(0);
  }
  if (extra.length > 0) {
    console.error(usage);
    process.exit(2);
  }
  const repository = argument ?? gh(["repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"]).trim();
  const issues = fetchOpenIssues(repository);
  const findings = auditIssues(issues);
  console.log(renderReport(repository, issues.length, findings));
  if (findings.some(({severity}) => severity === "failure")) {
    process.exitCode = 1;
  }
}
