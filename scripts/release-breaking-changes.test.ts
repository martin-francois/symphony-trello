import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdtempSync, readFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";

import {parse} from "yaml";

import {
  type BreakingChange,
  breakingChanges,
  commitMessages,
  MAX_PULL_REQUEST_BODY_LENGTH,
  OVERFLOW_MESSAGE,
  fetchReleaseBranch,
  previousReleaseTag,
  type ReleaseGitHub,
  renderBreakingChange,
  renderSection,
  type RepositoryFile,
  SECTION_HEADING,
  updateReleasePullRequest,
  verifyRelease,
  withBreakingChanges,
} from "./release-breaking-changes.ts";

const commitUrl = (sha: string) => `https://github.com/owner/project/commit/${sha}`;
const INSTALLER_SHA = "e2a3433d373f0c0a89783ae84bf9102960e87a33";
const WORKFLOW_SHA = "7ec1cf05810bdeae1768e192dc52ec58eeb04d26";
const INSTALLER_MESSAGE = `fix(installer)!: reject unproven update inputs

Require a supported installed version before stopping workers.

BREAKING CHANGE: Existing installs with unreadable versions now stop
before replacement. Repair Java before retrying.

Refs #566
`;
const WORKFLOW_MESSAGE = `feat(workflow)!: document branch-only handoff compatibility

BREAKING CHANGE: Generated workflows use the No PR label for
branch-only handoff. Rename the label to keep prior behavior.
`;
const INSTALLER: BreakingChange = {
  sha: INSTALLER_SHA,
  scope: "installer",
  text: "Existing installs with unreadable versions now stop\nbefore replacement. Repair Java before retrying.",
};
const WORKFLOW: BreakingChange = {
  sha: WORKFLOW_SHA,
  scope: "workflow",
  text: "Generated workflows use the No PR label for\nbranch-only handoff. Rename the label to keep prior behavior.",
};
const RELEASE_NOTES = `## 2.0.0 (2026-10-30)

## What's Changed
* fix(installer)!: reject unproven update inputs by @martinfrancois in https://github.com/owner/project/pull/798

## New Contributors
* @someone made their first contribution in https://github.com/owner/project/pull/594

**Full Changelog**: https://github.com/owner/project/compare/v1.2.0...v2.0.0`;
const PULL_REQUEST_BODY = `:robot: I have created a release *beep* *boop*
---


${RELEASE_NOTES}

---
This PR was generated with [Release Please](https://github.com/googleapis/release-please).`;
const OLDER_ENTRY = `## [1.2.0](https://github.com/owner/project/compare/v1.1.1...v1.2.0) (2026-07-16)

${SECTION_HEADING}

* **cli:** an older break that stays as released ([1111111](https://github.com/owner/project/commit/1111111))`;
const CHANGELOG = `# Changelog

${RELEASE_NOTES}

${OLDER_ENTRY}
`;
const PULL_REQUEST = {number: 589, headBranchName: "release-please--branches--main", baseBranchName: "main"};
const NOTES_BRANCH = `${PULL_REQUEST.headBranchName}--release-notes`;

class FakeGitHub implements ReleaseGitHub {
  body: string;
  release = "";
  readonly files = new Map<string, RepositoryFile>();
  readonly writes: string[] = [];
  readonly commitMessages: string[] = [];
  readonly resets: string[] = [];
  dropWrites = false;

  constructor(body: string, files: Readonly<Record<string, string>> = {}) {
    this.body = body;
    for (const [key, content] of Object.entries(files)) {
      this.files.set(key, {content, sha: "initial"});
    }
  }

  pullRequestBody(number: number): string {
    assert.equal(number, PULL_REQUEST.number);
    return this.body;
  }

  setPullRequestBody(number: number, body: string): void {
    assert.equal(number, PULL_REQUEST.number);
    this.writes.push(`pull request #${number}`);
    if (!this.dropWrites) {
      this.body = body;
    }
  }

  file(path: string, branch: string): RepositoryFile | undefined {
    return this.files.get(`${branch}:${path}`);
  }

  writeFile(path: string, branch: string, content: string, sha: string | undefined, message: string): string {
    assert.equal(sha, this.files.get(`${branch}:${path}`)?.sha, `write to ${path} must name the current blob`);
    this.writes.push(`${branch}:${path}`);
    this.commitMessages.push(message);
    if (!this.dropWrites) {
      this.files.set(`${branch}:${path}`, {content, sha: `written-${this.writes.length}`});
    }
    return `https://github.com/owner/project/blob/${branch}/${path}`;
  }

  resetBranch(branch: string, fromBranch: string): void {
    this.resets.push(`${branch} from ${fromBranch}`);
  }

  releaseBody(tag: string): string {
    assert.equal(tag, "v2.0.0");
    return this.release;
  }

  content(branch: string, path: string): string {
    const file = this.files.get(`${branch}:${path}`);
    assert.ok(file, `${branch}:${path} must exist`);
    return file.content;
  }
}

function releaseBranchGitHub(body = PULL_REQUEST_BODY): FakeGitHub {
  return new FakeGitHub(body, {[`${PULL_REQUEST.headBranchName}:CHANGELOG.md`]: CHANGELOG});
}

function update(github: FakeGitHub, changes: readonly BreakingChange[]) {
  updateReleasePullRequest({github, pullRequest: PULL_REQUEST, changelogPath: "CHANGELOG.md", changes, commitUrl});
}

function gitIn(directory: string) {
  return (...args: readonly string[]) =>
    execFileSync("git", ["-c", "user.name=Test", "-c", "user.email=test@test.invalid", ...args], {
      cwd: directory,
      encoding: "utf8",
    });
}

test("breaking changes come from BREAKING CHANGE footers and from ! headers without one", () => {
  // given
  const commits = [
    {sha: INSTALLER_SHA, message: INSTALLER_MESSAGE},
    {sha: WORKFLOW_SHA, message: WORKFLOW_MESSAGE},
    {sha: "a".repeat(40), message: "fix: tidy\n\nBREAKING-CHANGE: the hyphen form counts too\n"},
    {sha: "b".repeat(40), message: "feat(cli)!: drop the legacy flag\n\nNo footer here.\n"},
  ];

  // when
  const changes = breakingChanges(commits);

  // then
  assert.deepEqual(changes, [
    INSTALLER,
    WORKFLOW,
    {sha: "a".repeat(40), scope: undefined, text: "the hyphen form counts too"},
    {sha: "b".repeat(40), scope: "cli", text: "drop the legacy flag"},
  ]);
});

test("mentions of breaking changes outside a footer are not breaking changes", () => {
  // given
  const message = `chore(deps): update all dependencies (#622)

##### BREAKING CHANGES

- Upstream dropped Node 18.

- [x] For a breaking change, the message reaching \`main\` contains both breaking markers.
`;

  // when
  const changes = breakingChanges([{sha: "c".repeat(40), message}]);

  // then
  assert.deepEqual(changes, []);
});

test("an entry joins wrapped footer lines, keeps lists, and escapes tags outside code", () => {
  // given
  const change = {
    sha: WORKFLOW_SHA,
    scope: "workflow",
    text: "Set `<name>` or <version>\nbefore retrying.\n- first step\n- second\nstep",
  };

  // when
  const entry = renderBreakingChange(change, commitUrl(WORKFLOW_SHA));

  // then
  assert.equal(
    entry,
    `* **workflow:** Set \`<name>\` or &lt;version&gt; before retrying. ([7ec1cf0](${commitUrl(WORKFLOW_SHA)}))
  - first step
  - second step`,
  );
});

test("the section goes between the version heading and the generated notes", () => {
  // given
  const section = renderSection([INSTALLER, WORKFLOW], commitUrl);

  // when
  const updated = withBreakingChanges(PULL_REQUEST_BODY, section);

  // then
  assert.equal(
    updated,
    PULL_REQUEST_BODY.replace("## 2.0.0 (2026-10-30)\n\n", `## 2.0.0 (2026-10-30)\n\n${section}\n\n`),
  );
  assert.ok(
    updated.includes("* **installer:** Existing installs with unreadable versions now stop before replacement. "
      + "Repair Java before retrying. ([e2a3433]("),
    "the entry joins the footer's wrapped lines",
  );
});

test("updating the section again replaces it and touches only the new release entry", () => {
  // given
  const stale = withBreakingChanges(CHANGELOG, renderSection([WORKFLOW], commitUrl));
  const section = renderSection([INSTALLER, WORKFLOW], commitUrl);

  // when
  const updated = withBreakingChanges(stale, section);

  // then
  assert.equal(updated, withBreakingChanges(CHANGELOG, section));
  assert.equal(withBreakingChanges(updated, section), updated);
  assert.equal(withBreakingChanges(updated, undefined), CHANGELOG);
  assert.ok(updated.endsWith(`${OLDER_ENTRY}\n`), "older entries keep their released breaking changes");
  assert.equal(withBreakingChanges(CHANGELOG, undefined), CHANGELOG);
});

test("replacing the section keeps generated notes that have no pull request list", () => {
  // given
  const notes = "## 2.0.0 (2026-10-30)\n\n**Full Changelog**: https://github.com/owner/project/compare/v1.2.0...v2.0.0";
  const section = renderSection([INSTALLER], commitUrl);

  // when
  const twice = withBreakingChanges(withBreakingChanges(notes, section), section);

  // then
  assert.equal(twice, notes.replace("\n\n", `\n\n${section}\n\n`));
});

test("notes without a version heading are rejected", () => {
  assert.throws(() => withBreakingChanges("# Changelog\n", undefined), /no `## <version>` heading/);
});

test("the release pull request body and changelog get every breaking change", () => {
  // given
  const github = releaseBranchGitHub();
  const section = renderSection([INSTALLER, WORKFLOW], commitUrl);

  // when
  update(github, [INSTALLER, WORKFLOW]);

  // then
  assert.equal(github.body, withBreakingChanges(PULL_REQUEST_BODY, section));
  assert.equal(github.content(PULL_REQUEST.headBranchName, "CHANGELOG.md"), withBreakingChanges(CHANGELOG, section));
  assert.deepEqual(
    github.commitMessages,
    ["chore: add breaking changes to the release notes [skip ci]"],
    "the release branch head keeps skipping CI after a fast-forward merge",
  );
});

test("a second run with the same footers writes nothing", () => {
  // given
  const github = releaseBranchGitHub();
  update(github, [INSTALLER, WORKFLOW]);
  github.writes.length = 0;

  // when
  update(github, [INSTALLER, WORKFLOW]);

  // then
  assert.deepEqual(github.writes, []);
});

test("the update fails when a footer is missing from the stored notes", () => {
  // given
  const github = releaseBranchGitHub();
  github.dropWrites = true;

  // when
  const run = () => update(github, [INSTALLER]);

  // then
  assert.throws(run, new RegExp(`CHANGELOG\\.md: ${INSTALLER_SHA}\\nrelease notes of #589: ${INSTALLER_SHA}`));
});

test("release notes that already overflow the body are updated in Release Please's notes file", () => {
  // given
  const overflowBody = `${OVERFLOW_MESSAGE} https://github.com/owner/project/blob/${NOTES_BRANCH}/release-notes.md`;
  const github = releaseBranchGitHub(overflowBody);
  github.files.set(`${NOTES_BRANCH}:release-notes.md`, {content: PULL_REQUEST_BODY, sha: "initial"});

  // when
  update(github, [INSTALLER]);

  // then
  assert.equal(github.body, overflowBody);
  assert.equal(
    github.content(NOTES_BRANCH, "release-notes.md"),
    withBreakingChanges(PULL_REQUEST_BODY, renderSection([INSTALLER], commitUrl)),
  );
});

test("notes that no longer fit in the body move to Release Please's notes file", () => {
  // given
  const filler = `* ${"x".repeat(MAX_PULL_REQUEST_BODY_LENGTH - 50 - PULL_REQUEST_BODY.length)}\n`;
  const fittingBody = PULL_REQUEST_BODY.replace("## What's Changed\n", `## What's Changed\n${filler}`);
  assert.ok(fittingBody.length < MAX_PULL_REQUEST_BODY_LENGTH, "Release Please's own notes still fit");
  const github = releaseBranchGitHub(fittingBody);

  // when
  update(github, [INSTALLER]);

  // then
  assert.deepEqual(github.resets, [`${NOTES_BRANCH} from main`]);
  assert.equal(github.body, `${OVERFLOW_MESSAGE} https://github.com/owner/project/blob/${NOTES_BRANCH}/release-notes.md`);
  assert.equal(
    github.content(NOTES_BRANCH, "release-notes.md"),
    withBreakingChanges(fittingBody, renderSection([INSTALLER], commitUrl)),
  );
});

test("a draft release passes only when it lists every breaking change", () => {
  // given
  const github = releaseBranchGitHub();
  github.release = withBreakingChanges(RELEASE_NOTES, renderSection([INSTALLER], commitUrl));
  const verify = (changes: readonly BreakingChange[]) =>
    verifyRelease({github, tag: "v2.0.0", changes, commitUrl});

  // when
  verify([INSTALLER]);

  // then
  assert.throws(() => verify([INSTALLER, WORKFLOW]), new RegExp(`v2\\.0\\.0 is missing .*${WORKFLOW_SHA}`));
});

test("the range starts at the last release tag, including one created after checkout", () => {
  // given
  const origin = mkdtempSync(join(tmpdir(), "release-breaking-changes-origin-"));
  const clone = mkdtempSync(join(tmpdir(), "release-breaking-changes-clone-"));
  const inOrigin = gitIn(origin);
  inOrigin("init", "--quiet", "--initial-branch=main");
  inOrigin("commit", "--quiet", "--allow-empty", "-m", "feat!: first\n\nBREAKING CHANGE: released in 1.0.0");
  inOrigin("tag", "v1.0.0");
  inOrigin("tag", "review-v9.0.0");
  inOrigin("commit", "--quiet", "--allow-empty", "-m", "feat!: second\n\nBREAKING CHANGE: released in 2.0.0");
  gitIn(tmpdir())("clone", "--quiet", origin, clone);
  inOrigin("tag", "v2.0.0");
  inOrigin("commit", "--quiet", "--allow-empty", "-m", INSTALLER_MESSAGE);
  inOrigin("commit", "--quiet", "--allow-empty", "-m", "fix: nothing breaks");
  inOrigin("branch", "release-please--branches--main");

  // when
  const head = fetchReleaseBranch("release-please--branches--main", clone);
  const tag = previousReleaseTag(head, clone);
  const changes = breakingChanges(commitMessages(`${tag}..${head}`, clone));

  // then
  assert.equal(tag, "v2.0.0");
  assert.deepEqual(changes.map(({scope, text}) => ({scope, text})), [{scope: INSTALLER.scope, text: INSTALLER.text}]);
});

test("release tags and the release workflow match what the script expects", () => {
  // given
  const config = JSON.parse(readFileSync("release-please-config.json", "utf8")) as Record<string, unknown>;
  const workflow = parse(readFileSync(".github/workflows/release-please.yml", "utf8")) as {
    concurrency?: {group?: string; "cancel-in-progress"?: boolean};
    jobs: {"release-please": {steps: {id?: string; name?: string; run?: string; with?: Record<string, unknown>; env?: Record<string, string>}[]}};
  };

  // when
  const steps = workflow.jobs["release-please"].steps;
  const index = (predicate: (step: (typeof steps)[number]) => boolean) => steps.findIndex(predicate);
  const releasePlease = index((step) => step.id === "release");
  const install = index((step) => step.run === "pnpm install --frozen-lockfile");
  const verifyStep = index((step) => step.run === "node scripts/release-breaking-changes.ts release");
  const publish = index((step) => step.name === "Publish release");
  const pullRequestStep = index((step) => step.run === "node scripts/release-breaking-changes.ts pull-request");

  // then
  assert.equal(config["include-v-in-tag"], true, "previousReleaseTag matches v-prefixed tags");
  assert.equal(config["include-component-in-tag"], false, "previousReleaseTag matches tags without a component");
  assert.equal(steps[0]?.with?.["fetch-depth"], 0, "the script needs history back to the last release tag");
  assert.ok(releasePlease >= 0 && install > releasePlease, "script dependencies install after Release Please");
  assert.ok(install < verifyStep && verifyStep < publish, "the release check runs before the draft is published");
  assert.ok(pullRequestStep > publish, "a failed pull request update never holds back a release");
  assert.equal(steps[pullRequestStep]?.env?.RELEASE_PR, "${{ steps.release.outputs.pr }}");
  assert.equal(workflow.concurrency?.["cancel-in-progress"], false, "runs queue instead of interleaving");
});
