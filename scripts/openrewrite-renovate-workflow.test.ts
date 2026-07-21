import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {parse} from "yaml";

interface PullRequest {
  readonly base: {readonly ref: string; readonly repo: {readonly full_name: string}};
  readonly head: {
    readonly ref: string;
    readonly repo: {readonly full_name: string};
    readonly sha: string;
  };
  readonly html_url: string;
  readonly labels: readonly {readonly name: string}[];
  readonly number: number;
  readonly state: string;
  readonly user: {readonly login: string};
}

interface CommitStatus {
  readonly context: string;
  readonly description: string;
  readonly owner: string;
  readonly repo: string;
  readonly sha: string;
  readonly state: string;
  readonly target_url: string;
}

interface WorkflowDocument {
  readonly jobs?: Readonly<
    Record<
      string,
      {
        readonly steps?: readonly {
          readonly name?: unknown;
          readonly with?: {readonly script?: unknown};
        }[];
      }
    >
  >;
}

type ExecutableAsyncFunction = (...arguments_: readonly unknown[]) => Promise<unknown>;
type AsyncFunctionConstructor = new (...arguments_: readonly string[]) => ExecutableAsyncFunction;

const AsyncFunction = Object.getPrototypeOf(async function (): Promise<void> {}).constructor as AsyncFunctionConstructor;
const WORKFLOW = readFileSync(
  new URL("../.github/workflows/openrewrite-renovate.yml", import.meta.url),
  "utf8",
);
const CI_WORKFLOW = readFileSync(new URL("../.github/workflows/ci.yml", import.meta.url), "utf8");
const DEPENDENCY_SUBMISSION_WORKFLOW = readFileSync(
  new URL("../.github/workflows/dependency-submission.yml", import.meta.url),
  "utf8",
);
const RENOVATE = readFileSync(new URL("../renovate.json", import.meta.url), "utf8");
const RENOVATE_CONFIG = JSON.parse(RENOVATE) as {
  readonly branchConcurrentLimit?: number;
  readonly dependencyDashboardApproval?: boolean;
  readonly extends?: readonly string[];
  readonly internalChecksFilter?: string;
  readonly minimumReleaseAge?: string;
  readonly minimumReleaseAgeBehaviour?: string;
  readonly packageRules: readonly {
    readonly automerge?: boolean;
    readonly branchTopic?: string;
    readonly dependencyDashboardApproval?: boolean;
    readonly groupName?: string;
    readonly matchDepTypes?: readonly string[];
    readonly matchPackageNames?: readonly string[];
    readonly matchUpdateTypes?: readonly string[];
    readonly minimumReleaseAge?: string;
    readonly platformAutomerge?: boolean;
  }[];
  readonly prConcurrentLimit?: number;
};
const CANDIDATE: PullRequest = {
  base: {ref: "main", repo: {full_name: "owner/repo"}},
  head: {
    ref: "renovate/openrewrite-toolchain",
    repo: {full_name: "owner/repo"},
    sha: "1111111111111111111111111111111111111111",
  },
  html_url: "https://github.com/owner/repo/pull/42",
  labels: [{name: "dependencies"}, {name: "openrewrite"}],
  number: 42,
  state: "open",
  user: {login: "renovate[bot]"},
};

function workflowScript(stepName: string, workflow = WORKFLOW): string {
  const document = parse(workflow) as WorkflowDocument;
  assert.ok(document.jobs, "workflow jobs must exist");
  const matchingSteps = Object.values(document.jobs)
    .flatMap(({steps}) => steps ?? [])
    .filter(({name}) => name === stepName);
  assert.equal(matchingSteps.length, 1, `${stepName} step must exist exactly once`);
  const script = matchingSteps[0]?.with?.script;
  assert.ok(typeof script === "string", `${stepName} script block must exist`);
  return script;
}

test("workflow script extraction follows YAML structure across indentation styles", () => {
  const workflow = `jobs:
  verify:
    steps:
    - name: Execute workflow script
      uses: actions/github-script@pinned
      with:
        script: |
          return "parsed";
`;

  assert.equal(workflowScript("Execute workflow script", workflow), 'return "parsed";\n');
});

test("workflow script extraction reports missing structure clearly", () => {
  const workflow = `jobs:
  verify:
    steps:
      - name: Checkout source
        uses: actions/checkout@pinned
`;

  assert.throws(
    () => workflowScript("Absent", workflow),
    /Absent step must exist exactly once/,
  );
  assert.throws(
    () => workflowScript("Checkout source", workflow),
    /Checkout source script block must exist/,
  );
});

async function resolve(
  eventName: "pull_request_target" | "schedule" | "workflow_dispatch",
  pullRequest: PullRequest | undefined,
  openPullRequests: readonly PullRequest[] = [],
  requestedPullRequest = "",
  payloadAction = "",
  payloadLabel = "",
): Promise<{readonly failures: readonly string[]; readonly numbers: readonly number[]}> {
  const failures: string[] = [];
  let output = "[]";
  const listPullRequests = async (): Promise<void> => {};
  const github = {
    paginate: async (): Promise<readonly PullRequest[]> => openPullRequests,
    rest: {
      pulls: {
        get: async (): Promise<{readonly data: PullRequest}> => {
          assert.ok(pullRequest);
          return {data: pullRequest};
        },
        list: listPullRequests,
      },
    },
  };
  const core = {
    setFailed: (message: string): void => {
      failures.push(message);
    },
    setOutput: (name: string, value: unknown): void => {
      if (name === "pull_request_numbers") {
        output = String(value);
      }
    },
  };
  const context = {
    eventName,
    payload: {action: payloadAction, label: {name: payloadLabel}, pull_request: pullRequest},
    repo: {owner: "owner", repo: "repo"},
  };

  await new AsyncFunction(
    "context",
    "github",
    "core",
    "process",
    workflowScript("Resolve OpenRewrite Renovate pull requests"),
  )(context, github, core, {
    env: {
      GENERATED_BRANCH_PREFIX: "automation/openrewrite/renovate-",
      REQUESTED_PULL_REQUEST: requestedPullRequest,
    },
  });
  return {failures, numbers: JSON.parse(output) as number[]};
}

async function runFreshnessScript(
  stepName:
    | "Invalidate stale generated source"
    | "Invalidate generated result before reconciliation"
    | "Verify current Renovate source",
  payload: PullRequest,
  source: PullRequest,
  generatedPullRequests: readonly PullRequest[],
  generatedParentSha: string,
  provenanceStatuses: readonly unknown[] = [
    {
      context: "OpenRewrite source provenance",
      creator: {login: "github-actions[bot]"},
      description: "Source SHA delivered by Renovate",
      state: "success",
      target_url: source.html_url,
    },
  ],
  associatedPullRequests?: readonly PullRequest[],
): Promise<{readonly failures: readonly string[]; readonly statuses: readonly CommitStatus[]}> {
  const failures: string[] = [];
  const statuses: CommitStatus[] = [];
  const listPullRequests = async (): Promise<void> => {};
  const listCommitStatusesForRef = async (): Promise<void> => {};
  const listPullRequestsAssociatedWithCommit = async (): Promise<void> => {};
  const github = {
    paginate: async (method: unknown): Promise<readonly unknown[]> => {
      if (method === listPullRequests) {
        return generatedPullRequests;
      }
      if (method === listCommitStatusesForRef) {
        return provenanceStatuses;
      }
      return associatedPullRequests ?? [payload];
    },
    rest: {
      pulls: {
        get: async (): Promise<{readonly data: PullRequest}> => ({data: source}),
        list: listPullRequests,
      },
      repos: {
        createCommitStatus: async (status: CommitStatus): Promise<void> => {
          statuses.push(status);
        },
        listCommitStatusesForRef,
        getCommit: async (): Promise<{
          readonly data: {readonly parents: readonly {readonly sha: string}[]};
        }> => ({data: {parents: [{sha: generatedParentSha}]}}),
        listPullRequestsAssociatedWithCommit,
      },
    },
  };
  const core = {
    setFailed: (message: string): void => {
      failures.push(message);
    },
  };
  const context = {
    payload: {pull_request: payload},
    repo: {owner: "owner", repo: "repo"},
  };

  await new AsyncFunction("context", "github", "core", "process", workflowScript(stepName))(
    context,
    github,
    core,
    {
      env: {
        GENERATED_BRANCH_PREFIX: "automation/openrewrite/renovate-",
        SOURCE_PULL_REQUEST: String(source.number),
      },
    },
  );
  return {failures, statuses};
}

async function attestSource(sender: string): Promise<readonly CommitStatus[]> {
  const statuses: CommitStatus[] = [];
  const github = {
    rest: {
      repos: {
        createCommitStatus: async (status: CommitStatus): Promise<void> => {
          statuses.push(status);
        },
      },
    },
  };
  const context = {
    payload: {
      action: "synchronize",
      pull_request: CANDIDATE,
      sender: {login: sender},
    },
    repo: {owner: "owner", repo: "repo"},
  };

  await new AsyncFunction(
    "context",
    "github",
    "core",
    workflowScript("Attest the Renovate source sender"),
  )(context, github, {});
  return statuses;
}

async function reportSourceValidation(
  source: PullRequest,
  environment: Readonly<Record<string, string>>,
): Promise<readonly CommitStatus[]> {
  const statuses: CommitStatus[] = [];
  const github = {
    rest: {
      pulls: {
        get: async (): Promise<{readonly data: PullRequest}> => ({data: source}),
      },
      repos: {
        createCommitStatus: async (status: CommitStatus): Promise<void> => {
          statuses.push(status);
        },
      },
    },
  };
  const context = {repo: {owner: "owner", repo: "repo"}};

  await new AsyncFunction(
    "context",
    "github",
    "core",
    "process",
    workflowScript("Report source validation"),
  )(context, github, {}, {env: environment});
  return statuses;
}

test("direct reconciliation accepts only the exact same-repository Renovate candidate", async () => {
  assert.deepEqual(await resolve("pull_request_target", CANDIDATE), {failures: [], numbers: [42]});
  assert.deepEqual(
    await resolve("pull_request_target", {...CANDIDATE, user: {login: "contributor"}}),
    {failures: [], numbers: []},
  );
  assert.deepEqual(
    await resolve("pull_request_target", {
      ...CANDIDATE,
      head: {...CANDIDATE.head, repo: {full_name: "fork/repo"}},
    }),
    {failures: [], numbers: []},
  );
});

test("scheduled reconciliation filters and orders open candidates", async () => {
  const otherPackage = {
    ...CANDIDATE,
    head: {...CANDIDATE.head, ref: "renovate/all"},
    number: 44,
    labels: [{name: "dependencies"}],
  };
  const laterCandidate = {...CANDIDATE, number: 43};
  const labelRemovedManagedSource = {
    ...CANDIDATE,
    labels: [{name: "dependencies"}],
    number: 46,
  };
  const generated = {
    ...otherPackage,
    head: {...otherPackage.head, ref: "automation/openrewrite/renovate-41"},
    number: 45,
    user: {login: "openrewrite-automation[bot]"},
  };

  const result = await resolve(
    "schedule",
    undefined,
    [otherPackage, generated, laterCandidate, labelRemovedManagedSource, CANDIDATE],
  );

  assert.deepEqual(result, {failures: [], numbers: [41, 42, 43, 46]});
});

test("an explicitly removed OpenRewrite label still schedules cleanup", async () => {
  const unlabeled = {...CANDIDATE, labels: [{name: "dependencies"}]};

  const result = await resolve(
    "pull_request_target",
    unlabeled,
    [],
    "",
    "unlabeled",
    "openrewrite",
  );

  assert.deepEqual(result, {failures: [], numbers: [42]});
});

test("editing a Renovate pull request away from main still schedules cleanup", async () => {
  const retargeted = {...CANDIDATE, base: {...CANDIDATE.base, ref: "maintenance"}};

  const result = await resolve("pull_request_target", retargeted, [], "", "edited");

  assert.deepEqual(result, {failures: [], numbers: [42]});
});

test("manual reconciliation rejects an invalid pull request number", async () => {
  const result = await resolve("workflow_dispatch", undefined, [], "not-a-number");

  assert.deepEqual(result.failures, ["Invalid pull request number: not-a-number"]);
  assert.deepEqual(result.numbers, []);
});

test("a source update immediately makes the previous generated head pending", async () => {
  const generated = {
    ...CANDIDATE,
    head: {
      ...CANDIDATE.head,
      ref: "automation/openrewrite/renovate-42",
      sha: "2222222222222222222222222222222222222222",
    },
    number: 43,
    user: {login: "openrewrite-automation[bot]"},
  };

  const result = await runFreshnessScript(
    "Invalidate stale generated source",
    CANDIDATE,
    CANDIDATE,
    [generated],
    "0000000000000000000000000000000000000000",
  );

  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.statuses, [
    {
      context: "OpenRewrite update validation",
      description: "Validating Renovate PR #42",
      owner: "owner",
      repo: "repo",
      sha: CANDIDATE.head.sha,
      state: "pending",
      target_url: CANDIDATE.html_url,
    },
    {
      context: "OpenRewrite source freshness",
      description: "Waiting for current Renovate PR #42",
      owner: "owner",
      repo: "repo",
      sha: generated.head.sha,
      state: "pending",
      target_url: CANDIDATE.html_url,
    },
  ]);
});

test("source provenance follows the webhook sender rather than commit authorship", async () => {
  const renovate = await attestSource("renovate[bot]");
  assert.equal(renovate[0]?.context, "OpenRewrite source provenance");
  assert.equal(renovate[0]?.state, "success");
  assert.equal(renovate[0]?.sha, CANDIDATE.head.sha);

  const impersonator = await attestSource("maintainer");
  assert.equal(impersonator[0]?.state, "failure");
  assert.equal(impersonator[0]?.description, "Source SHA was not delivered by Renovate");
});

test("scheduled reconciliation makes a stale generated head pending before generation", async () => {
  const generated = {
    ...CANDIDATE,
    head: {
      ...CANDIDATE.head,
      ref: "automation/openrewrite/renovate-42",
      sha: "2222222222222222222222222222222222222222",
    },
    number: 43,
    user: {login: "openrewrite-automation[bot]"},
  };

  const result = await runFreshnessScript(
    "Invalidate generated result before reconciliation",
    CANDIDATE,
    CANDIDATE,
    [generated],
    "0000000000000000000000000000000000000000",
  );

  assert.deepEqual(result.failures, []);
  assert.equal(result.statuses[0]?.context, "OpenRewrite update validation");
  assert.equal(result.statuses[0]?.sha, CANDIDATE.head.sha);
  assert.equal(result.statuses[1]?.state, "pending");
  assert.equal(result.statuses[1]?.sha, generated.head.sha);
});

test("scheduled reconciliation fails before generation without trusted provenance", async () => {
  const generated = {
    ...CANDIDATE,
    head: {
      ...CANDIDATE.head,
      ref: "automation/openrewrite/renovate-42",
      sha: "2222222222222222222222222222222222222222",
    },
    number: 43,
    user: {login: "openrewrite-automation[bot]"},
  };
  const result = await runFreshnessScript(
    "Invalidate generated result before reconciliation",
    CANDIDATE,
    CANDIDATE,
    [generated],
    "0000000000000000000000000000000000000000",
    [],
  );

  assert.deepEqual(result.failures, ["Pull request #42 lacks trusted Renovate provenance"]);
  assert.equal(result.statuses[0]?.context, "OpenRewrite update validation");
  assert.equal(result.statuses[0]?.state, "failure");
  assert.equal(result.statuses[1]?.context, "OpenRewrite source freshness");
  assert.equal(result.statuses[1]?.state, "failure");
  assert.equal(result.statuses[1]?.sha, generated.head.sha);
});

test("a not-applicable status cannot substitute for Renovate sender provenance", async () => {
  const result = await runFreshnessScript(
    "Invalidate generated result before reconciliation",
    CANDIDATE,
    CANDIDATE,
    [],
    CANDIDATE.head.sha,
    [
      {
        context: "OpenRewrite source provenance",
        creator: {login: "github-actions[bot]"},
        description: "Not an OpenRewrite Renovate source",
        state: "success",
        target_url: CANDIDATE.html_url,
      },
    ],
  );

  assert.deepEqual(result.failures, ["Pull request #42 lacks trusted Renovate provenance"]);
  assert.equal(result.statuses[0]?.context, "OpenRewrite update validation");
  assert.equal(result.statuses[0]?.state, "failure");
});

test("ineligible source cleanup does not require trusted provenance", async () => {
  const ineligible = {...CANDIDATE, labels: [{name: "dependencies"}]};
  const result = await runFreshnessScript(
    "Invalidate generated result before reconciliation",
    ineligible,
    ineligible,
    [],
    ineligible.head.sha,
    [],
  );

  assert.deepEqual(result.failures, []);
  assert.equal(result.statuses[0]?.context, "OpenRewrite update validation");
  assert.equal(result.statuses[0]?.state, "failure");
});

test("only a current fully validated no-result source receives a successful gate", async () => {
  const successfulEnvironment = {
    GENERATED_CHANGES: "false",
    SOURCE_PULL_REQUEST: "42",
    SOURCE_SHA: CANDIDATE.head.sha,
    VALIDATION_PASSED: "true",
  };

  const successful = await reportSourceValidation(CANDIDATE, successfulEnvironment);
  assert.equal(successful[0]?.context, "OpenRewrite update validation");
  assert.equal(successful[0]?.state, "success");
  assert.equal(successful[0]?.sha, CANDIDATE.head.sha);

  const generated = await reportSourceValidation(CANDIDATE, {
    ...successfulEnvironment,
    GENERATED_CHANGES: "true",
  });
  assert.equal(generated[0]?.state, "failure");
  assert.equal(generated[0]?.description, "Generated changes require the derived pull request");

  const failed = await reportSourceValidation(CANDIDATE, {
    ...successfulEnvironment,
    VALIDATION_PASSED: "false",
  });
  assert.equal(failed[0]?.state, "failure");
  assert.equal(failed[0]?.description, "OpenRewrite update validation failed");

  const stale = await reportSourceValidation(
    {...CANDIDATE, head: {...CANDIDATE.head, sha: "2".repeat(40)}},
    successfulEnvironment,
  );
  assert.equal(stale[0]?.state, "failure");
});

test("a generated head passes only when its parent is the current eligible source", async () => {
  const generated = {
    ...CANDIDATE,
    head: {
      ...CANDIDATE.head,
      ref: "automation/openrewrite/renovate-42",
      sha: "2222222222222222222222222222222222222222",
    },
    number: 43,
    user: {login: "openrewrite-automation[bot]"},
  };

  const current = await runFreshnessScript(
    "Verify current Renovate source",
    generated,
    CANDIDATE,
    [],
    CANDIDATE.head.sha,
  );
  assert.deepEqual(current.failures, []);
  assert.equal(current.statuses[0]?.context, "OpenRewrite source provenance");
  assert.equal(current.statuses[0]?.state, "success");
  assert.equal(current.statuses[1]?.context, "OpenRewrite update validation");
  assert.equal(current.statuses[1]?.state, "success");
  assert.equal(current.statuses[2]?.context, "OpenRewrite source freshness");
  assert.equal(current.statuses[2]?.state, "success");

  const stale = await runFreshnessScript(
    "Verify current Renovate source",
    generated,
    CANDIDATE,
    [],
    "0000000000000000000000000000000000000000",
  );
  assert.deepEqual(stale.failures, [
    "Generated pull request does not match current Renovate PR #42",
  ]);
  assert.equal(stale.statuses[2]?.state, "failure");
});

test("an unrelated Renovate pull request receives not-applicable successes", async () => {
  const unrelatedRenovate = {
    ...CANDIDATE,
    head: {...CANDIDATE.head, ref: "renovate/all"},
  };

  const result = await runFreshnessScript(
    "Verify current Renovate source",
    unrelatedRenovate,
    CANDIDATE,
    [],
    CANDIDATE.head.sha,
  );

  assert.deepEqual(result.failures, []);
  assert.equal(result.statuses[0]?.state, "success");
  assert.equal(result.statuses[0]?.description, "Not an OpenRewrite Renovate source");
  assert.equal(result.statuses[1]?.state, "success");
  assert.equal(result.statuses[1]?.description, "Not an OpenRewrite Renovate source");
  assert.equal(result.statuses[2]?.state, "success");
  assert.equal(result.statuses[2]?.description, "Not a generated OpenRewrite pull request");
});

test("an unrelated pull request cannot overwrite statuses on a shared governed commit", async () => {
  const unrelated = {
    ...CANDIDATE,
    head: {...CANDIDATE.head, ref: "contributor/change"},
    user: {login: "contributor"},
  };
  const generated = {
    ...CANDIDATE,
    head: {
      ...CANDIDATE.head,
      ref: "automation/openrewrite/renovate-42",
      sha: unrelated.head.sha,
    },
    number: 43,
    user: {login: "openrewrite-automation[bot]"},
  };

  const result = await runFreshnessScript(
    "Verify current Renovate source",
    unrelated,
    CANDIDATE,
    [],
    CANDIDATE.head.sha,
    [],
    [unrelated, generated],
  );

  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.statuses, []);
});

test("a fork branch cannot impersonate a generated pull request", async () => {
  const fork = {
    ...CANDIDATE,
    head: {
      ...CANDIDATE.head,
      ref: "automation/openrewrite/renovate-42",
      repo: {full_name: "fork/repo"},
    },
    user: {login: "contributor"},
  };

  const result = await runFreshnessScript(
    "Verify current Renovate source",
    fork,
    CANDIDATE,
    [],
    CANDIDATE.head.sha,
  );

  assert.deepEqual(result.failures, []);
  assert.equal(result.statuses.length, 3);
  assert.ok(result.statuses.every(({state}) => state === "success"));
});

test("write credentials are isolated from recipe execution", () => {
  const invalidationStart = WORKFLOW.indexOf("\n  invalidate-derived-source:");
  const verificationStart = WORKFLOW.indexOf("\n  verify-derived-source:");
  const generateStart = WORKFLOW.indexOf("\n  generate:");
  const reportStart = WORKFLOW.indexOf("\n  report-source-validation:");
  const publishStart = WORKFLOW.indexOf("\n  publish:");
  assert.ok(
    invalidationStart > 0
      && verificationStart > invalidationStart
      && generateStart > verificationStart
      && reportStart > generateStart
      && publishStart > reportStart,
  );
  const invalidationJob = WORKFLOW.slice(invalidationStart, verificationStart);
  const generateJob = WORKFLOW.slice(generateStart, reportStart);
  const reportJob = WORKFLOW.slice(reportStart, publishStart);
  const publishJob = WORKFLOW.slice(publishStart);

  assert.match(invalidationJob, /contents: read/);
  assert.match(invalidationJob, /pull-requests: read/);
  assert.match(invalidationJob, /statuses: write/);
  assert.match(
    WORKFLOW,
    /invalidate-reconciled-source:\n    needs: \[resolve, attest-source, invalidate-derived-source\]/,
  );
  assert.doesNotMatch(generateJob, /OPENREWRITE_AUTOMATION_(?:APP_ID|PRIVATE_KEY)/);
  assert.match(generateJob, /persist-credentials: false/g);
  assert.match(generateJob, /contents: read/);
  assert.doesNotMatch(generateJob, /statuses: write/);
  assert.doesNotMatch(generateJob, /context: "OpenRewrite update validation"/);
  assert.doesNotMatch(generateJob, /ref: \$\{\{ steps\.source\.outputs\.source_sha \}\}/);
  assert.match(generateJob, /name: Materialize the exact Renovate POM/);
  assert.match(generateJob, /commits\[0\]\?\.parents\[0\]\?\.sha !== pullRequest\.base\.sha/);
  assert.match(
    generateJob,
    /commit --no-gpg-sign -m "chore\(deps\): establish validated OpenRewrite source"/,
  );
  assert.match(
    generateJob,
    /docker\.io\/library\/maven:3\.9\.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c/,
  );
  assert.match(generateJob, /--user "\$\(id -u\):\$\(id -g\)"/);
  assert.match(generateJob, /--cap-drop ALL/);
  assert.match(generateJob, /--security-opt no-new-privileges/);
  assert.match(generateJob, /--env GIT_OPTIONAL_LOCKS=0/);
  assert.match(generateJob, /--env MAVEN_CONFIG=\/cache\/home\/\.m2/);
  assert.match(
    generateJob,
    /--env "MAVEN_OPTS=-Duser\.home=\/cache\/home -Dmaven\.repo\.local=\/cache\/m2"/,
  );
  assert.match(generateJob, /--env RUNNER_TEMP=\/output/);
  assert.match(generateJob, /--volume "\$PWD:\/workspace"/);
  assert.match(generateJob, /--volume "\$PWD\/\.git:\/workspace\/\.git:ro"/);
  assert.match(generateJob, /--volume "\$container_cache:\/cache"/);
  assert.match(generateJob, /--volume "\$container_output:\/output"/);
  assert.doesNotMatch(generateJob, /--volume "\$RUNNER_TEMP:/);
  assert.match(generateJob, /--entrypoint bash/);
  assert.doesNotMatch(generateJob, /--privileged/);
  assert.doesNotMatch(generateJob, /\/var\/run\/docker\.sock/);
  assert.doesNotMatch(generateJob, /github\.token|GITHUB_TOKEN/);
  assert.match(generateJob, /find "\$container_output" -mindepth 1 -maxdepth 1 -printf '%f\\0'/);
  assert.match(generateJob, /\[ -L "\$container_output\/generated-files\.txt" \]/);
  assert.match(generateJob, /\[ -L "\$container_output\/openrewrite\.patch" \]/);
  assert.match(
    generateJob,
    /group: openrewrite-renovate-generate-\$\{\{ matrix\.pull_request_number \}\}/,
  );
  assert.equal(generateJob.match(/check_untracked_output/g)?.length, 4);
  assert.equal(
    generateJob.match(/git diff --name-only HEAD > "\$RUNNER_TEMP\/generated-files\.txt"/g)
      ?.length,
    2,
  );
  assert.match(generateJob, /The full Maven gate changed the verified fixed-point result/);
  assert.match(reportJob, /statuses: write/);
  assert.match(reportJob, /context: "OpenRewrite update validation"/);
  assert.doesNotMatch(reportJob, /(?:rewrite:run|spotless:apply|OPENREWRITE_AUTOMATION_)/);
  assert.match(
    publishJob,
    /^ {8}uses: actions\/create-github-app-token@[0-9a-f]{40} # v[0-9]+$/m,
  );
  assert.match(publishJob, /if: \$\{\{ steps\.publication\.outputs\.required == 'true' \}\}/g);
  assert.match(publishJob, /permission-contents: write/);
  assert.match(publishJob, /permission-pull-requests: write/);
  assert.match(publishJob, /contents: read/);
  assert.match(publishJob, /statuses: write/);
  assert.doesNotMatch(publishJob, /\n      contents: write/);
  assert.doesNotMatch(publishJob, /needs\.generate\.result == 'success'/);
  assert.match(
    publishJob,
    /matching-refs\/heads\/\$branch" \|[\s\S]*--arg ref "refs\/heads\/\$branch"[\s\S]*select\(\.ref == \$ref\)/,
  );
  assert.match(publishJob, /closed\|ineligible\) ;;/);
  assert.match(
    publishJob,
    /Source state is unknown; preserving any previous derived pull request/,
  );
  assert.match(
    publishJob,
    /group: openrewrite-renovate-publish-\$\{\{ matrix\.pull_request_number \}\}/,
  );
  assert.doesNotMatch(publishJob, /gh pr list/);
  assert.equal(
    publishJob.match(/-f head="\$GITHUB_REPOSITORY_OWNER:\$branch"/g)?.length,
    2,
  );
  assert.equal(
    publishJob.match(/\.head\.repo\.full_name == \$repository/g)?.length,
    3,
  );
  assert.match(
    publishJob,
    /pom\.xml\|src\/main\/java\/\*\.java\|src\/test\/java\/\*\.java/,
  );
  assert.match(publishJob, /git ls-files --stage/);
  const metadataValidation = publishJob.indexOf("validate-metadata");
  const sourceFetch = publishJob.indexOf('git fetch origin "$source_sha"');
  assert.ok(
    metadataValidation > 0 && metadataValidation < sourceFetch,
    "artifact metadata must be validated before its source SHA reaches Git",
  );
  const idempotenceCheck = publishJob.indexOf('generated_tree="$(git write-tree)"');
  const firstEligibilityCheck = publishJob.indexOf("if ! source_is_current; then");
  const finalEligibilityCheck = publishJob.indexOf(
    "if ! source_is_current; then",
    firstEligibilityCheck + 1,
  );
  const generatedCommit = publishJob.indexOf(
    'git commit -m "refactor(build): apply OpenRewrite toolchain update"',
  );
  const publicationPush = publishJob.indexOf('git push origin "HEAD:refs/heads/$branch"');
  assert.ok(
    firstEligibilityCheck > 0
      && firstEligibilityCheck < idempotenceCheck
      && idempotenceCheck < generatedCommit
      && generatedCommit < finalEligibilityCheck
      && finalEligibilityCheck < publicationPush,
    "source eligibility must be revalidated before idempotence and immediately before publication",
  );
  assert.match(
    publishJob.slice(idempotenceCheck, generatedCommit),
    /remote_parent[\s\S]*remote_tree[\s\S]*-n "\$existing_pr"[\s\S]*"\$remote_parent" = "\$source_sha"[\s\S]*"\$remote_tree" = "\$generated_tree"[\s\S]*exit 0/,
  );
  assert.match(
    publishJob.slice(finalEligibilityCheck, publicationPush),
    /fail_stale_generated[\s\S]*Renovate source became ineligible/,
  );
  assert.match(publishJob, /GH_TOKEN="\$STATUS_TOKEN" gh api[\s\S]*OpenRewrite source freshness/);
  assert.match(publishJob, /--force-with-lease=refs\/heads\/\$branch:\$remote_sha/);
  assert.match(publishJob, /--reviewer "martinfrancois"/);
  assert.match(publishJob, /--add-reviewer "martinfrancois"/);
});

test("the generated branch prefix has one workflow source of truth", () => {
  assert.equal(
    WORKFLOW.match(/automation\/openrewrite\/renovate-/g)?.length,
    1,
  );
  assert.match(
    WORKFLOW,
    /GENERATED_BRANCH_PREFIX: automation\/openrewrite\/renovate-/,
  );
  assert.match(
    WORKFLOW,
    /const branch = `\$\{process\.env\.GENERATED_BRANCH_PREFIX\}\$\{source\.number\}`/,
  );
  assert.match(
    WORKFLOW,
    /branch="\$\{GENERATED_BRANCH_PREFIX\}\$\{SOURCE_PULL_REQUEST\}"/,
  );
});

test("updated OpenRewrite artifacts remain isolated in pull request CI", () => {
  assert.match(
    CI_WORKFLOW,
    /github\.head_ref == 'renovate\/openrewrite-toolchain'/g,
  );
  assert.match(
    CI_WORKFLOW,
    /startsWith\(github\.head_ref, 'automation\/openrewrite\/renovate-'\)/g,
  );
  assert.match(CI_WORKFLOW, /persist-credentials: false/);
  assert.match(CI_WORKFLOW, /name: Test the OpenRewrite update in isolation/);
  assert.match(
    CI_WORKFLOW,
    /docker\.io\/library\/maven:3\.9\.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c/,
  );
  assert.match(CI_WORKFLOW, /--cap-drop ALL/);
  assert.match(CI_WORKFLOW, /--security-opt no-new-privileges/);
  assert.match(CI_WORKFLOW, /--env GIT_OPTIONAL_LOCKS=0/);
  assert.match(CI_WORKFLOW, /--volume "\$PWD\/\.git:\/workspace\/\.git:ro"/);
  assert.doesNotMatch(CI_WORKFLOW, /--volume "\$RUNNER_TEMP:/);
  assert.doesNotMatch(CI_WORKFLOW, /\/var\/run\/docker\.sock/);
  assert.match(
    DEPENDENCY_SUBMISSION_WORKFLOW,
    /github\.head_ref != 'renovate\/openrewrite-toolchain'/,
  );
  assert.match(
    DEPENDENCY_SUBMISSION_WORKFLOW,
    /!startsWith\(github\.head_ref, 'automation\/openrewrite\/renovate-'\)/,
  );
});

test("the OpenRewrite rule selects exact toolchain packages across Maven dependency types", () => {
  const rule = RENOVATE_CONFIG.packageRules.find(
    ({groupName}) => groupName === "OpenRewrite toolchain",
  );

  assert.equal(rule?.matchDepTypes, undefined);
  for (const packageName of [
    "com.google.errorprone:error_prone_core",
    "tech.picnic.error-prone-support:error-prone-contrib",
    "tech.picnic.error-prone-support:refaster-runner",
  ]) {
    assert.ok(rule?.matchPackageNames?.includes(packageName), `${packageName} must remain grouped`);
  }
  assert.equal(rule?.branchTopic, "openrewrite-toolchain");
  assert.equal(rule?.platformAutomerge, false);
});

test("major update pull requests require manual merge", () => {
  const openRewriteRule = RENOVATE.indexOf('"groupName": "OpenRewrite toolchain"');
  const majorRule = RENOVATE.indexOf('"matchUpdateTypes": ["major"]');
  const majorUpdateRule = RENOVATE_CONFIG.packageRules.find(
    ({matchUpdateTypes}) => matchUpdateTypes?.includes("major"),
  );

  assert.ok(openRewriteRule > 0);
  assert.ok(majorRule > openRewriteRule);
  assert.equal(majorUpdateRule?.automerge, false);
});

test("Renovate never requires dependency dashboard approval", () => {
  assert.equal(RENOVATE_CONFIG.dependencyDashboardApproval, false);
  for (const rule of RENOVATE_CONFIG.packageRules) {
    assert.equal(rule.dependencyDashboardApproval, undefined);
  }
});

test("Renovate enforces the repository-wide seven-day dependency cooldown", () => {
  assert.equal(RENOVATE_CONFIG.minimumReleaseAge, "7 days");
  assert.equal(RENOVATE_CONFIG.minimumReleaseAgeBehaviour, "timestamp-required");
  assert.equal(RENOVATE_CONFIG.internalChecksFilter, "strict");
  assert.ok(RENOVATE_CONFIG.extends?.includes("group:all"));
  for (const rule of RENOVATE_CONFIG.packageRules) {
    assert.equal(rule.minimumReleaseAge, undefined);
  }
});

test("Renovate permits unlimited concurrent branches and pull requests", () => {
  assert.equal(RENOVATE_CONFIG.branchConcurrentLimit, 0);
  assert.equal(RENOVATE_CONFIG.prConcurrentLimit, 0);
});
