import {execFileSync} from "node:child_process";
import {pathToFileURL} from "node:url";

type CompatibilityKind = "breaking" | "compatible" | "unsure";
export type CommitHistoryMode = "combine" | "keep";

interface Fence {
  readonly length: number;
  readonly marker: "`" | "~";
}

export interface CommitMetadata {
  readonly body: string;
  readonly subject: string;
}

const COMPATIBILITY_HEADING = "## Compatibility Decision";
const COMMIT_HISTORY_HEADING = "## Commit History in Main";
const DECISIONS = new Map<string, CompatibilityKind>([
  ["- [x] Compatible: no previously supported, working usage stops working", "compatible"],
  ["- [x] Breaking: previously supported, working usage stops working", "breaking"],
  ["- [x] Unsure: maintainer decision required", "unsure"],
]);
const COMMIT_HISTORY_CHOICES = new Map<string, {readonly continuation: string; readonly mode: CommitHistoryMode}>([
  [
    "- [x] Combine this pull request into one final commit. The branch commits are review steps and do not",
    {continuation: "      need to remain separate in `main`. (squash)", mode: "combine"},
  ],
  [
    "- [x] Keep the individual commits. Each commit is independently meaningful and should remain visible",
    {continuation: "      in `main`. (rebase)", mode: "keep"},
  ],
]);
const SELECTION_ERROR = "Select exactly one current option in the Compatibility Decision section.";
const COMMIT_HISTORY_SELECTION_ERROR =
  "Select exactly one current option in the Commit History in Main section.";

function stripHtmlComments(line: string, commentOpen: boolean): readonly [string, boolean] {
  let visible = "";
  let cursor = 0;
  let inComment = commentOpen;
  while (cursor < line.length) {
    if (inComment) {
      const end = line.indexOf("-->", cursor);
      if (end < 0) {
        return [visible, true];
      }
      cursor = end + 3;
      inComment = false;
      continue;
    }
    const start = line.indexOf("<!--", cursor);
    if (start < 0) {
      visible += line.slice(cursor);
      break;
    }
    visible += line.slice(cursor, start);
    cursor = start + 4;
    inComment = true;
  }
  return [visible, inComment];
}

function openingFence(line: string): Fence | undefined {
  const match = /^ {0,3}(`{3,}|~{3,})/.exec(line);
  const delimiter = match?.[1];
  if (!delimiter) {
    return undefined;
  }
  const marker = delimiter[0];
  if (marker !== "`" && marker !== "~") {
    return undefined;
  }
  return {marker, length: delimiter.length};
}

function closesFence(line: string, fence: Fence): boolean {
  const match = /^ {0,3}(`+|~+)[ \t]*$/.exec(line);
  const delimiter = match?.[1];
  return delimiter?.[0] === fence.marker && delimiter.length >= fence.length;
}

function visibleMarkdownLines(body: string): readonly string[] {
  const visible: string[] = [];
  let commentOpen = false;
  let fence: Fence | undefined;
  for (const rawLine of body.replaceAll("\r\n", "\n").replaceAll("\r", "\n").split("\n")) {
    if (fence) {
      if (closesFence(rawLine, fence)) {
        fence = undefined;
      }
      continue;
    }
    const [withoutComments, nextCommentOpen] = stripHtmlComments(rawLine, commentOpen);
    commentOpen = nextCommentOpen;
    const nextFence = openingFence(withoutComments);
    if (nextFence) {
      fence = nextFence;
      continue;
    }
    visible.push(withoutComments);
  }
  return visible;
}

function visibleSection(lines: readonly string[], heading: string): readonly string[] | undefined {
  const headingIndexes = lines.flatMap((line, index) =>
    line.trimEnd() === heading ? [index] : [],
  );
  if (headingIndexes.length !== 1) {
    return undefined;
  }
  const start = (headingIndexes[0] ?? -1) + 1;
  const relativeEnd = lines.slice(start).findIndex((line) => /^##[ \t]+\S/.test(line));
  return relativeEnd < 0 ? lines.slice(start) : lines.slice(start, start + relativeEnd);
}

function compatibilitySection(lines: readonly string[]): readonly string[] | undefined {
  return visibleSection(lines, COMPATIBILITY_HEADING);
}

function commitHistorySection(lines: readonly string[]): readonly string[] | undefined {
  return visibleSection(lines, COMMIT_HISTORY_HEADING);
}

function selectedDecision(section: readonly string[]): CompatibilityKind | undefined {
  const selectedLines = section
    .filter((line) => /^- \[[xX]\] .+[ \t]*$/.test(line))
    .map((line) => line.trimEnd().replace("[X]", "[x]"));
  if (selectedLines.length !== 1) {
    return undefined;
  }
  return DECISIONS.get(selectedLines[0] ?? "");
}

function selectedCommitHistoryMode(section: readonly string[]): CommitHistoryMode | undefined {
  const selectedIndexes = section.flatMap((line, index) =>
    /^- \[[xX]\] .+[ \t]*$/.test(line) ? [index] : [],
  );
  if (selectedIndexes.length !== 1) {
    return undefined;
  }
  const selectedIndex = selectedIndexes[0] ?? -1;
  const selectedLine = section[selectedIndex]?.trimEnd().replace("[X]", "[x]");
  const choice = selectedLine ? COMMIT_HISTORY_CHOICES.get(selectedLine) : undefined;
  if (!choice || section[selectedIndex + 1]?.trimEnd() !== choice.continuation) {
    return undefined;
  }
  return choice.mode;
}

function valueAfter(section: readonly string[], prompt: string): string | undefined {
  const promptIndexes = section.flatMap((line, index) => (line === prompt ? [index] : []));
  if (promptIndexes.length !== 1) {
    return undefined;
  }
  return section.slice((promptIndexes[0] ?? -1) + 1).find((line) => line.trim().length > 0);
}

function inlineCodeContents(line: string | undefined): string | undefined {
  if (!line || line !== line.trimStart()) {
    return undefined;
  }
  const trimmed = line.trimEnd();
  if (trimmed.startsWith("`") || trimmed.endsWith("`")) {
    if (!(trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length > 2)) {
      return undefined;
    }
    return trimmed.slice(1, -1).trim();
  }
  return trimmed;
}

function nonPlaceholderField(line: string | undefined, prefix: string): boolean {
  const contents = inlineCodeContents(line);
  if (!contents) {
    return false;
  }
  const pattern = prefix === "Because"
    ? /^Because[ \t]+(.+)$/
    : new RegExp(`^${prefix}:[ \\t]+(.+)$`);
  const value = pattern.exec(contents)?.[1]?.trim();
  return Boolean(value && value !== "...");
}

function titleDeclaresBreaking(title: string): boolean {
  return /^[a-z][a-z0-9-]*(?:\([^)]+\))?!:/i.test(title);
}

function bodyDecision(body: string): CompatibilityKind | undefined {
  const section = compatibilitySection(visibleMarkdownLines(body));
  return section ? selectedDecision(section) : undefined;
}

function bodyCommitHistoryMode(body: string): CommitHistoryMode | undefined {
  const section = commitHistorySection(visibleMarkdownLines(body));
  return section ? selectedCommitHistoryMode(section) : undefined;
}

function commitFooterDeclaresBreaking(body: string): boolean {
  const match = /^BREAKING CHANGE:[ \t]+(.+)[ \t]*$/m.exec(body);
  const value = match?.[1]?.trim();
  return Boolean(value && value !== "...");
}

function commitFooterMarkerPresent(body: string): boolean {
  return /^BREAKING CHANGE:/m.test(body);
}

export function evaluatePullRequestBreakingConsistency(
  title: string,
  body: string,
  commits: readonly CommitMetadata[],
): string[] {
  const titleBreaking = titleDeclaresBreaking(title);
  const bodyBreaking = bodyDecision(body) === "breaking";
  const commitHistoryMode = bodyCommitHistoryMode(body);
  const markers = commits.map((commit) => ({
    footerComplete: commitFooterDeclaresBreaking(commit.body),
    footerPresent: commitFooterMarkerPresent(commit.body),
    subject: titleDeclaresBreaking(commit.subject),
  }));
  const anyCommitMarker = markers.some(({footerPresent, subject}) => footerPresent || subject);
  const completeBreakingCommit = markers.some(({footerComplete, subject}) => footerComplete && subject);
  const incompleteBreakingCommit = markers.some(
    ({footerComplete, footerPresent, subject}) =>
      (footerPresent || subject) && !(footerComplete && subject),
  );
  const errors: string[] = [];

  if ((titleBreaking || anyCommitMarker) && !bodyBreaking) {
    errors.push("A breaking PR title or commit marker requires the PR compatibility decision to be Breaking.");
  }
  if (anyCommitMarker && !titleBreaking) {
    errors.push("A breaking commit marker requires the PR title to use the Conventional Commit ! marker.");
  }
  if (incompleteBreakingCommit) {
    errors.push(
      "Each branch commit containing either breaking marker must contain both ! in its subject and a non-placeholder BREAKING CHANGE: footer.",
    );
  }
  if (bodyBreaking && commitHistoryMode === "keep" && !completeBreakingCommit) {
    errors.push(
      "A Breaking pull request that keeps the individual commits must contain a branch commit with both ! in its subject and a non-placeholder BREAKING CHANGE: footer.",
    );
  }
  return errors;
}

function commitsInRange(base: string, head: string): readonly CommitMetadata[] {
  const revisionRange = `${base}..${head}`;
  const shas = execFileSync("git", ["rev-list", "--reverse", revisionRange], {encoding: "utf8"})
    .trim()
    .split("\n")
    .filter(Boolean);
  return shas.map((sha) => {
    const message = execFileSync("git", ["show", "-s", "--format=%s%x00%b", sha], {encoding: "utf8"});
    const separator = message.indexOf("\0");
    if (separator < 0) {
      throw new Error(`Unable to parse commit message for ${sha}.`);
    }
    return {subject: message.slice(0, separator).trimEnd(), body: message.slice(separator + 1).trimEnd()};
  });
}

export function evaluateCompatibilityMetadata(title: string, body: string): string[] {
  const lines = visibleMarkdownLines(body);
  const section = compatibilitySection(lines);
  if (!section) {
    return ["Provide exactly one visible Compatibility Decision section."];
  }
  const decision = selectedDecision(section);
  if (!decision) {
    return [SELECTION_ERROR];
  }

  const errors: string[] = [];
  if (!nonPlaceholderField(valueAfter(section, "Why did you choose this option?"), "Because")) {
    errors.push("Provide a non-placeholder `Because ...` compatibility rationale.");
  }

  const breakingTitle = titleDeclaresBreaking(title);
  if (decision === "unsure") {
    errors.push("Resolve the compatibility decision with a maintainer before the pull request is ready.");
  } else if (decision === "breaking") {
    if (!breakingTitle) {
      errors.push("A breaking pull request title must use the Conventional Commit ! marker.");
    }
    if (!nonPlaceholderField(valueAfter(section, "What breaks:"), "Breaks")) {
      errors.push("A breaking pull request must provide non-placeholder `Breaks: ...` details.");
    }
    if (!nonPlaceholderField(valueAfter(section, "Migration path:"), "Migration")) {
      errors.push("A breaking pull request must provide a non-placeholder `Migration: ...` path.");
    }
    if (!nonPlaceholderField(valueAfter(section, "Alternative:"), "Alternative")) {
      errors.push("A breaking pull request must provide a non-placeholder `Alternative: ...`.");
    }
  } else if (breakingTitle) {
    errors.push("A compatible pull request title must not use the Conventional Commit ! marker.");
  }

  const historySection = commitHistorySection(lines);
  if (!historySection) {
    errors.push("Provide exactly one visible Commit History in Main section.");
  } else if (!selectedCommitHistoryMode(historySection)) {
    errors.push(COMMIT_HISTORY_SELECTION_ERROR);
  }
  return errors;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const title = process.env.PR_TITLE ?? "";
  const body = process.env.PR_BODY ?? "";
  const base = process.env.PR_BASE_SHA ?? "";
  const head = process.env.PR_HEAD_SHA ?? "";
  if (Boolean(base) !== Boolean(head)) {
    throw new Error("PR_BASE_SHA and PR_HEAD_SHA must be provided together.");
  }
  const commits = base && head ? commitsInRange(base, head) : [];
  const errors = [
    ...evaluateCompatibilityMetadata(title, body),
    ...evaluatePullRequestBreakingConsistency(title, body, commits),
  ];
  for (const error of errors) {
    console.error(`compatibility metadata: ${error}`);
  }
  if (errors.length > 0) {
    process.exitCode = 1;
  }
}
