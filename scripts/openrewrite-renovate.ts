import {readFileSync, writeFileSync} from "node:fs";
import {basename} from "node:path";

export const OPENREWRITE_VERSION_PROPERTIES = [
  "error-prone.version",
  "error-prone-support.version",
  "rewrite-maven-plugin.version",
  "rewrite-maven.version",
  "rewrite-error-prone-support.version",
  "rewrite-migrate-java.version",
  "rewrite-static-analysis.version",
  "rewrite-testing-frameworks.version",
] as const;

export interface VersionChange {
  readonly name: string;
  readonly previous: string;
  readonly next: string;
}

export interface VersionUpdate {
  readonly changes: readonly VersionChange[];
  readonly sourcePullRequest: number;
  readonly sourceSha: string;
}

interface GeneratedPullRequest {
  readonly sourceNumber: number;
  readonly sourceSha: string;
  readonly sourceUrl: string;
  readonly versionChanges: readonly VersionChange[];
  readonly generatedFiles: readonly string[];
}

const VERSION_VALUE = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;
const SOURCE_SHA = /^[0-9a-f]{40}$/;
export const GENERATED_BRANCH_PREFIX =
  process.env.GENERATED_BRANCH_PREFIX ?? "automation/openrewrite/renovate-";

function escapeRegularExpression(value: string): string {
  return value.replaceAll(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function propertyValue(pom: string, propertyName: string, sourceName: string): string {
  const escapedName = escapeRegularExpression(propertyName);
  const matches = [...pom.matchAll(new RegExp(`<${escapedName}>([^<]+)</${escapedName}>`, "g"))];
  if (matches.length !== 1) {
    throw new Error(`${sourceName} must contain exactly one ${propertyName} property`);
  }
  const value = matches[0]?.[1];
  if (!value || !VERSION_VALUE.test(value)) {
    throw new Error(`${sourceName} contains an invalid ${propertyName} value`);
  }
  return value;
}

function replacePropertyValue(pom: string, propertyName: string): string {
  const escapedName = escapeRegularExpression(propertyName);
  return pom.replace(
    new RegExp(`(<${escapedName}>)[^<]+(</${escapedName}>)`, "g"),
    `$1__OPENREWRITE_VERSION__$2`,
  );
}

export function validateVersionOnlyPomChange(
  basePom: string,
  updatedPom: string,
): readonly VersionChange[] {
  const changes = OPENREWRITE_VERSION_PROPERTIES.flatMap((name) => {
    const previous = propertyValue(basePom, name, "base POM");
    const next = propertyValue(updatedPom, name, "updated POM");
    return previous === next ? [] : [{name, previous, next}];
  });
  if (changes.length === 0) {
    throw new Error("the Renovate pull request does not update an OpenRewrite version property");
  }

  const canonicalBase = OPENREWRITE_VERSION_PROPERTIES.reduce(replacePropertyValue, basePom);
  const canonicalUpdate = OPENREWRITE_VERSION_PROPERTIES.reduce(replacePropertyValue, updatedPom);
  if (canonicalBase !== canonicalUpdate) {
    throw new Error("the Renovate pull request changes pom.xml outside OpenRewrite version properties");
  }
  return changes;
}

export function validateVersionUpdate(
  input: unknown,
  expectedSourcePullRequest?: number,
): VersionUpdate {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new Error("OpenRewrite metadata must be an object");
  }
  const record = input as Record<string, unknown>;
  if (
    Object.keys(record).sort().join(",")
    !== ["changes", "sourcePullRequest", "sourceSha"].sort().join(",")
  ) {
    throw new Error("OpenRewrite metadata contains unexpected fields");
  }
  const sourcePullRequest = record.sourcePullRequest;
  if (!Number.isSafeInteger(sourcePullRequest) || Number(sourcePullRequest) <= 0) {
    throw new Error("OpenRewrite metadata contains an invalid source pull request");
  }
  if (
    expectedSourcePullRequest !== undefined
    && sourcePullRequest !== expectedSourcePullRequest
  ) {
    throw new Error("OpenRewrite metadata does not match the expected source pull request");
  }
  const sourceSha = record.sourceSha;
  if (typeof sourceSha !== "string" || !SOURCE_SHA.test(sourceSha)) {
    throw new Error("OpenRewrite metadata contains an invalid source SHA");
  }
  if (!Array.isArray(record.changes) || record.changes.length === 0) {
    throw new Error("OpenRewrite metadata must contain version changes");
  }

  const seen = new Set<string>();
  let previousPropertyIndex = -1;
  const changes = record.changes.map((change): VersionChange => {
    if (!change || typeof change !== "object" || Array.isArray(change)) {
      throw new Error("OpenRewrite metadata contains an invalid version change");
    }
    const fields = change as Record<string, unknown>;
    if (Object.keys(fields).sort().join(",") !== ["name", "next", "previous"].join(",")) {
      throw new Error("OpenRewrite metadata version change contains unexpected fields");
    }
    const {name, next, previous} = fields;
    const propertyIndex = typeof name === "string"
      ? OPENREWRITE_VERSION_PROPERTIES.indexOf(
        name as (typeof OPENREWRITE_VERSION_PROPERTIES)[number],
      )
      : -1;
    if (
      typeof name !== "string"
      || propertyIndex <= previousPropertyIndex
      || seen.has(name)
      || typeof previous !== "string"
      || !VERSION_VALUE.test(previous)
      || typeof next !== "string"
      || !VERSION_VALUE.test(next)
      || previous === next
    ) {
      throw new Error("OpenRewrite metadata contains an invalid version change");
    }
    previousPropertyIndex = propertyIndex;
    seen.add(name);
    return {name, previous, next};
  });

  return {changes, sourcePullRequest: Number(sourcePullRequest), sourceSha};
}

export function generatedBranch(sourceNumber: number): string {
  if (!Number.isSafeInteger(sourceNumber) || sourceNumber <= 0) {
    throw new Error(`invalid source pull request number: ${sourceNumber}`);
  }
  return `${GENERATED_BRANCH_PREFIX}${sourceNumber}`;
}

function versionTable(changes: readonly VersionChange[]): string {
  return changes
    .map(({name, previous, next}) => `| \`${name}\` | \`${previous}\` | \`${next}\` |`)
    .join("\n");
}

function fileList(files: readonly string[]): string {
  return files.map((file) => `- \`${file}\``).join("\n");
}

export function generatedPullRequestBody(input: GeneratedPullRequest): string {
  const sourceSha = input.sourceSha;
  if (!/^[0-9a-f]{40}$/.test(sourceSha)) {
    throw new Error(`invalid source SHA: ${sourceSha}`);
  }
  if (input.versionChanges.length === 0 || input.generatedFiles.length === 0) {
    throw new Error("generated pull request metadata requires version changes and generated files");
  }
  return `## Summary

- Problem: The OpenRewrite toolchain update in [Renovate PR #${input.sourceNumber}](${input.sourceUrl}) changes the curated pipeline output.
- Why it matters: The generated source must be reviewed and committed before the toolchain update can merge.
- What changed: Automation applied OpenRewrite and Spotless for source commit \`${sourceSha}\`.
- What did not change: The active recipe allowlist, recipe options, runtime configuration, and supported application contracts are unchanged.

## Change Type

Choose all that apply.

- [ ] Bug fix
- [ ] Feature
- [x] Documentation
- [x] Refactor required for this change
- [x] Chore / infrastructure

## Linked Issue

- Related #${input.sourceNumber}

## User-Visible Behavior

None. Contributors receive the generated fixed-point source for the updated OpenRewrite toolchain.

## Compatibility Decision

Choose one option.

- [x] Compatible: no previously supported, working usage stops working
- [ ] Breaking: previously supported, working usage stops working
- [ ] Unsure: maintainer decision required

Why did you choose this option?
\`Because the generated changes come from the compatible recurring recipe allowlist and must pass maintainer review before merge.\`

If you choose \`Breaking\`, please fill out the following:

What breaks:
\`Breaks: \`

Migration path:
\`Migration: \`

Alternative:
\`Alternative: \`

If this is not a breaking change, you can leave all three fields blank.

## Commit History in Main

Choose one option based on how this pull request should appear in \`main\`, not on the Git command used
to merge it.

- [x] Combine this pull request into one final commit. The branch commits are review steps and do not
      need to remain separate in \`main\`. (squash)
- [ ] Keep the individual commits. Each commit is independently meaningful and should remain visible
      in \`main\`. (rebase)

## Root Cause And Guardrail

For bug fixes or regressions, explain why the issue happened and what now prevents it from coming
back. For non-bug changes, write \`N/A\`.

- Root cause: N/A.
- Test or guardrail added: The automation reproduced the ordered OpenRewrite-to-Spotless fixed point and ran the full Maven gate before publishing this branch.
- If no test was added, why not: The existing application and maintenance-policy tests validate generated behavior.

## Validation

List the commands, manual checks, or live checks you ran. Include relevant failures that were fixed
during the PR.

- [x] \`./mvnw -q spotless:check verify\`
- [x] Installer or script checks, if touched
- [x] Documentation lint, if Markdown changed
- [x] Manual or live check, if behavior changed

Details:

\`\`\`text
./mvnw -Popenrewrite rewrite:discover
./mvnw -Popenrewrite rewrite:run
./mvnw -q spotless:apply
./mvnw -Popenrewrite rewrite:run
./mvnw -q spotless:apply
./mvnw -q spotless:check verify
\`\`\`

## Human Verification

Describe what you tried manually and what result you saw. If the change cannot be tried manually,
explain why.

\`\`\`text
Review the generated files below before merging.
\`\`\`

## Generated Evidence

| Property | Previous | Updated |
| --- | --- | --- |
${versionTable(input.versionChanges)}

Generated files:

${fileList(input.generatedFiles)}

## Review Checklist

- [x] Docs updated, or N/A
- [x] ADR updated for architecture decisions or tradeoffs, or N/A
- [x] PR title and every commit that will remain in \`main\` use Conventional Commits and are release-note ready
- [x] Compatibility and commit-history choices are complete. For a breaking change, the message reaching \`main\` contains both required breaking markers.
- [x] Live E2E/deployment notes included when behavior or deployment changed, or N/A
- [x] Redaction checked: no Trello credentials, Codex auth files, GitHub tokens, private board links, account names, private host paths, or deployment-specific paths

## AI Assistance (if used)

- [ ] AI-assisted PR
- [ ] I confirm I understand what the code does
`;
}

function readNonemptyLines(path: string): string[] {
  return readFileSync(path, "utf8")
    .split(/\r?\n/)
    .filter((line) => line.length > 0);
}

function parsePositiveInteger(value: string, description: string): number {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`invalid ${description}: ${value}`);
  }
  return parsed;
}

function main(arguments_: readonly string[]): void {
  const [command, ...operands] = arguments_;
  if (command === "validate-update") {
    const [basePath, updatedPath, sourceNumberValue, sourceSha, outputPath] = operands;
    if (!basePath || !updatedPath || !sourceNumberValue || !sourceSha || !outputPath) {
      throw new Error(
        "usage: openrewrite-renovate.ts validate-update BASE_POM UPDATED_POM PR_NUMBER SOURCE_SHA OUTPUT",
      );
    }
    const update = validateVersionUpdate({
      changes: validateVersionOnlyPomChange(
        readFileSync(basePath, "utf8"),
        readFileSync(updatedPath, "utf8"),
      ),
      sourcePullRequest: parsePositiveInteger(sourceNumberValue, "source pull request number"),
      sourceSha,
    });
    writeFileSync(outputPath, `${JSON.stringify(update, undefined, 2)}\n`);
    return;
  }
  if (command === "validate-metadata") {
    const [metadataPath, sourceNumberValue] = operands;
    if (!metadataPath || !sourceNumberValue) {
      throw new Error(
        "usage: openrewrite-renovate.ts validate-metadata METADATA EXPECTED_PR_NUMBER",
      );
    }
    validateVersionUpdate(
      JSON.parse(readFileSync(metadataPath, "utf8")) as unknown,
      parsePositiveInteger(sourceNumberValue, "source pull request number"),
    );
    return;
  }
  if (command === "render-body") {
    const [metadataPath, sourceUrl, generatedFilesPath, outputPath] = operands;
    if (!metadataPath || !sourceUrl || !generatedFilesPath || !outputPath) {
      throw new Error(
        "usage: openrewrite-renovate.ts render-body METADATA SOURCE_URL GENERATED_FILES OUTPUT",
      );
    }
    const metadata = validateVersionUpdate(
      JSON.parse(readFileSync(metadataPath, "utf8")) as unknown,
    );
    writeFileSync(
      outputPath,
      generatedPullRequestBody({
        sourceNumber: metadata.sourcePullRequest,
        sourceSha: metadata.sourceSha,
        sourceUrl,
        versionChanges: metadata.changes,
        generatedFiles: readNonemptyLines(generatedFilesPath),
      }),
    );
    return;
  }
  throw new Error(`unknown command: ${command ?? basename(import.meta.filename)}`);
}

if (process.argv[1] && import.meta.filename === process.argv[1]) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
