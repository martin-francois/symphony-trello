import {execFileSync, spawnSync} from "node:child_process";
import {readFileSync} from "node:fs";
import {pathToFileURL} from "node:url";

import {CommitParser} from "conventional-commits-parser";

// Release Please's `github` changelog type builds notes from GitHub's pull request list and never
// reads commit footers. `pull-request` adds every `BREAKING CHANGE:` footer since the last release to
// the release pull request body and to the changelog on its branch, then checks both. `release`
// checks that a draft release lists every footer before it is published. ADR 0135.

export interface BreakingChange {
  readonly sha: string;
  readonly scope: string | undefined;
  readonly text: string;
}

export interface CommitMessage {
  readonly sha: string;
  readonly message: string;
}

export interface RepositoryFile {
  readonly content: string;
  readonly sha: string;
}

export interface ReleaseGitHub {
  pullRequestBody(number: number): string;
  setPullRequestBody(number: number, body: string): void;
  file(path: string, branch: string): RepositoryFile | undefined;
  /** Creates or replaces the file and returns its GitHub web URL. */
  writeFile(path: string, branch: string, content: string, sha: string | undefined, message: string): string;
  /** Points the branch at the head of another branch, creating it when it does not exist. */
  resetBranch(branch: string, fromBranch: string): void;
  releaseBody(tag: string): string;
}

export interface ReleasePullRequest {
  readonly number: number;
  readonly headBranchName: string;
  readonly baseBranchName: string;
}

export const SECTION_HEADING = "## Breaking Changes";
// Release Please's limits and file names for release notes that do not fit in a pull request body,
// from src/util/pull-request-overflow-handler.ts.
export const MAX_PULL_REQUEST_BODY_LENGTH = 65536;
export const OVERFLOW_MESSAGE =
  "This release is too large to preview in the pull request body. View the full release notes here:";
const RELEASE_NOTES_FILE = "release-notes.md";
// Release tags are `v<version>` because release-please-config.json sets include-v-in-tag and turns
// off include-component-in-tag.
const RELEASE_TAG_PATTERN = "v[0-9]*";
// The commit becomes the head of the release branch, so it carries the release title's `[skip ci]`.
const COMMIT_MESSAGE = "chore: add breaking changes to the release notes [skip ci]";
// The first release entry starts at its version heading. GitHub-generated notes write
// `## 1.2.3 (date)`, the default changelog writes `## [1.2.3](compare) (date)`.
const VERSION_HEADING = /^## \[?\d+\.\d+\.\d+/;
// renderBreakingChange writes `* ` entries with two-space continuation lines; the first other line
// is where the generated notes resume.
const isSectionLine = (line: string) => line === "" || line.startsWith("* ") || line.startsWith("  ");
const BLOCK_START = /^([-*+] |\d+[.)] |#{1,6} |```|~~~)/;
// The header patterns of the conventionalcommits preset that commitlint's config-conventional uses,
// so `type!:` parses and marks the commit as breaking.
const commitParser = new CommitParser({
  headerPattern: /^(\w*)(?:\((.*)\))?!?: (.*)$/,
  breakingHeaderPattern: /^(\w*)(?:\((.*)\))?!: (.*)$/,
});

export function breakingChanges(commits: readonly CommitMessage[]): BreakingChange[] {
  return commits.flatMap(({sha, message}) => {
    const commit = commitParser.parse(message);
    return commit.notes.map((note) => ({sha, scope: commit.scope ?? undefined, text: note.text.trim()}));
  });
}

function escapeHtml(text: string): string {
  // GitHub drops unknown tags such as `<version>` from rendered notes; keep code spans unchanged.
  return text.replace(/``[^`].*?[^`]``|`[^`]*`|<|>/g, (match) => {
    if (match === "<") {
      return "&lt;";
    }
    return match === ">" ? "&gt;" : match;
  });
}

// Footers are wrapped at commit-message width. GitHub renders single newlines in pull request and
// release bodies as line breaks, so join wrapped lines while keeping lists, headings, and fences.
function unwrap(text: string): string[] {
  const lines: string[] = [];
  let joinable = false;
  let inFence = false;
  for (const rawLine of text.split("\n")) {
    const line = rawLine.trimEnd();
    const fence = /^\s*(```|~~~)/.test(line);
    if (inFence || fence) {
      lines.push(line);
      inFence = fence ? !inFence : inFence;
      joinable = false;
    } else if (line.trim() === "") {
      lines.push("");
      joinable = false;
    } else if (joinable && !BLOCK_START.test(line.trim())) {
      lines[lines.length - 1] = `${lines[lines.length - 1]} ${line.trim()}`;
    } else {
      lines.push(line);
      joinable = true;
    }
  }
  return lines;
}

export function renderBreakingChange(change: BreakingChange, commitUrl: string): string {
  const [first = "", ...rest] = unwrap(escapeHtml(change.text));
  const scope = change.scope ? `**${change.scope}:** ` : "";
  const link = ` ([${change.sha.slice(0, 7)}](${commitUrl}))`;
  return [`* ${scope}${first}${link}`, ...rest.map((line) => (line === "" ? "" : `  ${line}`))].join("\n");
}

export function renderSection(changes: readonly BreakingChange[], commitUrl: (sha: string) => string) {
  if (changes.length === 0) {
    return undefined;
  }
  return [SECTION_HEADING, "", ...changes.map((change) => renderBreakingChange(change, commitUrl(change.sha)))]
    .join("\n");
}

function firstIndex(lines: readonly string[], from: number, predicate: (line: string) => boolean): number {
  const index = lines.slice(from).findIndex(predicate);
  return index < 0 ? lines.length : from + index;
}

/** Replaces the breaking-change section of the first release entry, or removes it without a section. */
export function withBreakingChanges(document: string, section: string | undefined): string {
  const lines = document.replace(/\r\n/g, "\n").split("\n");
  const heading = lines.findIndex((line) => VERSION_HEADING.test(line));
  if (heading < 0) {
    throw new Error("release notes have no `## <version>` heading for the new release");
  }
  const entryEnd = firstIndex(lines, heading + 1, (line) => VERSION_HEADING.test(line));
  const existing = firstIndex(lines, heading + 1, (line) => line === SECTION_HEADING);
  if (existing >= entryEnd && section === undefined) {
    return document;
  }
  const withoutSection =
    existing < entryEnd
      ? [...lines.slice(0, existing), ...lines.slice(firstIndex(lines, existing + 1, (line) => !isSectionLine(line)))]
      : lines;
  const rest = withoutSection.slice(heading + 1);
  const restStart = rest.findIndex((line) => line.trim() !== "");
  const inserted = section === undefined ? [] : [...section.split("\n"), ""];
  return [...withoutSection.slice(0, heading + 1), "", ...inserted, ...rest.slice(restStart < 0 ? rest.length : restStart)]
    .join("\n");
}

export function missingBreakingChanges(
  changes: readonly BreakingChange[],
  document: string,
  commitUrl: (sha: string) => string,
): BreakingChange[] {
  const normalized = document.replace(/\r\n/g, "\n");
  return changes.filter((change) => !normalized.includes(renderBreakingChange(change, commitUrl(change.sha))));
}

function updateFile(github: ReleaseGitHub, path: string, branch: string, section: string | undefined): void {
  const file = github.file(path, branch);
  if (!file) {
    throw new Error(`${path} does not exist on ${branch}`);
  }
  const updated = withBreakingChanges(file.content, section);
  if (updated !== file.content) {
    github.writeFile(path, branch, updated, file.sha, COMMIT_MESSAGE);
    console.log(`Updated the breaking changes in ${path} on ${branch}.`);
  }
}

function readFile(github: ReleaseGitHub, path: string, branch: string): string {
  const file = github.file(path, branch);
  if (!file) {
    throw new Error(`${path} does not exist on ${branch}`);
  }
  return file.content;
}

/** Returns the location that holds the release notes Release Please will publish. */
function updateReleaseNotes(
  github: ReleaseGitHub,
  pullRequest: ReleasePullRequest,
  section: string | undefined,
): () => string {
  const notesBranch = `${pullRequest.headBranchName}--release-notes`;
  const readNotesFile = () => readFile(github, RELEASE_NOTES_FILE, notesBranch);
  const body = github.pullRequestBody(pullRequest.number);
  if (body.includes(OVERFLOW_MESSAGE)) {
    updateFile(github, RELEASE_NOTES_FILE, notesBranch, section);
    return readNotesFile;
  }
  const updated = withBreakingChanges(body, section);
  if (updated.length > MAX_PULL_REQUEST_BODY_LENGTH) {
    // Store the notes the way Release Please does when its own notes overflow; it reads them back
    // from this file when it creates the release.
    github.resetBranch(notesBranch, pullRequest.baseBranchName);
    const existing = github.file(RELEASE_NOTES_FILE, notesBranch);
    const url = github.writeFile(RELEASE_NOTES_FILE, notesBranch, updated, existing?.sha, "Saving release notes");
    github.setPullRequestBody(pullRequest.number, `${OVERFLOW_MESSAGE} ${url}`);
    console.log(`Moved the release notes to ${url} because they exceed the pull request body limit.`);
    return readNotesFile;
  }
  if (updated !== body) {
    github.setPullRequestBody(pullRequest.number, updated);
    console.log(`Updated the breaking changes in pull request #${pullRequest.number}.`);
  }
  return () => github.pullRequestBody(pullRequest.number);
}

export function updateReleasePullRequest(options: {
  readonly github: ReleaseGitHub;
  readonly pullRequest: ReleasePullRequest;
  readonly changelogPath: string;
  readonly changes: readonly BreakingChange[];
  readonly commitUrl: (sha: string) => string;
}): void {
  const {github, pullRequest, changelogPath, changes, commitUrl} = options;
  const section = renderSection(changes, commitUrl);
  updateFile(github, changelogPath, pullRequest.headBranchName, section);
  const readNotes = updateReleaseNotes(github, pullRequest, section);

  const missing = [
    ...missingBreakingChanges(changes, readFile(github, changelogPath, pullRequest.headBranchName), commitUrl)
      .map((change) => `${changelogPath}: ${change.sha}`),
    ...missingBreakingChanges(changes, readNotes(), commitUrl)
      .map((change) => `release notes of #${pullRequest.number}: ${change.sha}`),
  ];
  if (missing.length > 0) {
    throw new Error(`BREAKING CHANGE footers missing after the update:\n${missing.join("\n")}`);
  }
  console.log(`Release pull request #${pullRequest.number} lists ${changes.length} breaking change(s).`);
}

export function verifyRelease(options: {
  readonly github: ReleaseGitHub;
  readonly tag: string;
  readonly changes: readonly BreakingChange[];
  readonly commitUrl: (sha: string) => string;
}): void {
  const {github, tag, changes, commitUrl} = options;
  const missing = missingBreakingChanges(changes, github.releaseBody(tag), commitUrl);
  if (missing.length > 0) {
    throw new Error(
      `Draft release ${tag} is missing BREAKING CHANGE footers from ${missing.map(({sha}) => sha).join(", ")}. `
        + "Add them to the draft release notes, then publish the draft by hand.",
    );
  }
  console.log(`Release ${tag} lists ${changes.length} breaking change(s).`);
}

function git(cwd: string, ...args: readonly string[]): string {
  // A release range can hold hundreds of commits with long dependency-update bodies.
  return execFileSync("git", args, {cwd, encoding: "utf8", maxBuffer: 256 * 1024 * 1024});
}

/** Fetches the release branch and every tag, including one Release Please created after checkout. */
export function fetchReleaseBranch(branch: string, cwd = process.cwd()): string {
  const ref = `refs/remotes/origin/${branch}`;
  git(cwd, "fetch", "--quiet", "--tags", "origin", `+refs/heads/${branch}:${ref}`);
  return git(cwd, "rev-parse", ref).trim();
}

export function commitMessages(range: string, cwd = process.cwd()): CommitMessage[] {
  return git(cwd, "log", "--reverse", "--format=%x1e%H%x00%B", range)
    .split("\x1e")
    .filter((record) => record.includes("\0"))
    .map((record) => {
      const separator = record.indexOf("\0");
      return {sha: record.slice(0, separator), message: record.slice(separator + 1)};
    });
}

export function previousReleaseTag(ref: string, cwd = process.cwd()): string {
  return git(cwd, "describe", "--tags", "--abbrev=0", "--match", RELEASE_TAG_PATTERN, ref).trim();
}

function gh(args: readonly string[], input?: unknown) {
  return spawnSync("gh", ["api", ...args, ...(input === undefined ? [] : ["--input", "-"])], {
    encoding: "utf8",
    input: input === undefined ? undefined : JSON.stringify(input),
  });
}

/** Returns undefined when GitHub answers 404 and fails on any other error. */
function ghOptionalJson(args: readonly string[]): unknown {
  const result = gh(args);
  if (result.status !== 0 && result.stderr.includes("HTTP 404")) {
    return undefined;
  }
  if (result.status !== 0) {
    throw new Error(`gh api ${args.join(" ")} failed: ${result.stderr.trim()}`);
  }
  return JSON.parse(result.stdout);
}

function ghJson(args: readonly string[], input?: unknown): unknown {
  const result = gh(args, input);
  if (result.status !== 0) {
    throw new Error(`gh api ${args.join(" ")} failed: ${result.stderr.trim()}`);
  }
  return JSON.parse(result.stdout);
}

export function ghReleaseGitHub(repository: string): ReleaseGitHub {
  const contentsPath = (path: string) => `repos/${repository}/contents/${path}`;
  return {
    pullRequestBody(number) {
      return ((ghJson([`repos/${repository}/pulls/${number}`]) as {body: string | null}).body ?? "");
    },
    setPullRequestBody(number, body) {
      ghJson(["--method", "PATCH", `repos/${repository}/pulls/${number}`], {body});
    },
    file(path, branch) {
      const file = ghOptionalJson([`${contentsPath(path)}?ref=${encodeURIComponent(branch)}`]) as
        | {content: string; sha: string}
        | undefined;
      return file && {content: Buffer.from(file.content, "base64").toString("utf8"), sha: file.sha};
    },
    writeFile(path, branch, content, sha, message) {
      const response = ghJson(["--method", "PUT", contentsPath(path)], {
        branch,
        content: Buffer.from(content, "utf8").toString("base64"),
        message,
        ...(sha === undefined ? {} : {sha}),
      }) as {content: {html_url: string}};
      return response.content.html_url;
    },
    resetBranch(branch, fromBranch) {
      const {object} = ghJson([`repos/${repository}/git/ref/heads/${fromBranch}`]) as {object: {sha: string}};
      if (ghOptionalJson([`repos/${repository}/git/ref/heads/${branch}`]) !== undefined) {
        ghJson(["--method", "PATCH", `repos/${repository}/git/refs/heads/${branch}`], {sha: object.sha, force: true});
      } else {
        ghJson(["--method", "POST", `repos/${repository}/git/refs`], {ref: `refs/heads/${branch}`, sha: object.sha});
      }
    },
    releaseBody(tag) {
      // The releases/tags endpoint does not return drafts; `gh release view` finds them.
      const result = spawnSync("gh", ["release", "view", tag, "--repo", repository, "--json", "body"], {
        encoding: "utf8",
      });
      if (result.status !== 0) {
        throw new Error(`gh release view ${tag} failed: ${result.stderr.trim()}`);
      }
      return (JSON.parse(result.stdout) as {body: string}).body;
    },
  };
}

function requiredEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} must be set`);
  }
  return value;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const mode = process.argv[2];
  const repository = requiredEnvironment("GITHUB_REPOSITORY");
  const serverUrl = process.env.GITHUB_SERVER_URL ?? "https://github.com";
  const github = ghReleaseGitHub(repository);
  const commitUrl = (sha: string) => `${serverUrl}/${repository}/commit/${sha}`;
  const changesIn = (range: string) => {
    const changes = breakingChanges(commitMessages(range));
    console.log(`Found ${changes.length} breaking change(s) in ${range}.`);
    return changes;
  };
  if (mode === "pull-request") {
    const pullRequest = JSON.parse(requiredEnvironment("RELEASE_PR")) as ReleasePullRequest;
    const config = JSON.parse(readFileSync("release-please-config.json", "utf8")) as {
      packages: Record<string, {"changelog-path"?: string}>;
    };
    // The release pull request branch is the default branch at the commit Release Please used.
    const head = fetchReleaseBranch(pullRequest.headBranchName);
    updateReleasePullRequest({
      github,
      pullRequest,
      changelogPath: config.packages["."]?.["changelog-path"] ?? "CHANGELOG.md",
      changes: changesIn(`${previousReleaseTag(head)}..${head}`),
      commitUrl,
    });
  } else if (mode === "release") {
    const tag = requiredEnvironment("RELEASE_TAG");
    git(process.cwd(), "fetch", "--quiet", "--tags", "origin");
    verifyRelease({github, tag, changes: changesIn(`${previousReleaseTag(`${tag}^`)}..${tag}`), commitUrl});
  } else {
    throw new Error("usage: release-breaking-changes.ts pull-request|release");
  }
}
