import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";

interface LabelChanges {
  readonly added: string[];
  readonly removed: string[];
}

interface LabelNode {
  readonly body: string;
  readonly number: number;
}

interface LabelerPayload {
  readonly issue?: LabelNode;
  readonly pull_request?: LabelNode;
}

interface RunOptions {
  readonly addFailure?: Error;
  readonly existingLabels?: readonly string[];
  readonly listFailure?: Error;
  readonly removeFailure?: Error;
}

interface RunResult {
  readonly attempts: LabelChanges;
  readonly changes: LabelChanges;
  readonly warnings: string[];
}

interface AddLabelsParameters {
  readonly labels: readonly string[];
}

interface RemoveLabelParameters {
  readonly name: string;
}

type ExecutableAsyncFunction = (...arguments_: readonly unknown[]) => Promise<unknown>;
type AsyncFunctionConstructor = new (...arguments_: readonly string[]) => ExecutableAsyncFunction;

const AsyncFunction = Object.getPrototypeOf(async function (): Promise<void> {}).constructor as AsyncFunctionConstructor;
const TEMPLATE = readFileSync(
  new URL("../.github/pull_request_template.md", import.meta.url),
  "utf8",
);
const COMPATIBLE = "Compatible: no previously supported, working usage stops working";
const BREAKING = "Breaking: previously supported, working usage stops working";
const UNSURE = "Unsure: maintainer decision required";
const COMBINE = "Combine this pull request into one final commit. The branch commits are review steps and do not\n"
  + "      need to remain separate in `main`. (squash)";
const KEEP = "Keep the individual commits. Each commit is independently meaningful and should remain visible\n"
  + "      in `main`. (rebase)";

function replaceExactly(source: string, expected: string, replacement: string): string {
  const parts = source.split(expected);
  assert.equal(parts.length, 2, `expected exactly one template occurrence of ${JSON.stringify(expected)}`);
  return `${parts[0]}${replacement}${parts[1]}`;
}

function select(decision: string, template = TEMPLATE): string {
  return replaceExactly(template, `- [ ] ${decision}`, `- [x] ${decision}`);
}

function selectHistory(choice: string, template = TEMPLATE): string {
  return replaceExactly(template, `- [ ] ${choice}`, `- [x] ${choice}`);
}

function labelerScript(): string {
  const workflow = readFileSync(
    new URL("../.github/workflows/compatibility-labeler.yml", import.meta.url),
    "utf8",
  );
  const lines = workflow.split("\n");
  const scriptStart = lines.findIndex((line) => line.trim() === "script: |");
  assert.notEqual(scriptStart, -1, "compatibility labeler script block must exist");

  const scriptLines: string[] = [];
  for (const line of lines.slice(scriptStart + 1)) {
    if (line && !line.startsWith("            ")) {
      break;
    }
    scriptLines.push(line.slice(12));
  }
  return scriptLines.join("\n");
}

async function runLabeler(payload: LabelerPayload, options: RunOptions = {}): Promise<RunResult> {
  const attempts: LabelChanges = {added: [], removed: []};
  const changes: LabelChanges = {added: [], removed: []};
  const warnings: string[] = [];
  const github = {
    paginate: async (): Promise<{name: string}[]> => {
      if (options.listFailure) {
        throw options.listFailure;
      }
      return (options.existingLabels ?? []).map((name) => ({name}));
    },
    rest: {
      issues: {
        listLabelsOnIssue: async (): Promise<void> => {},
        addLabels: async ({labels}: AddLabelsParameters): Promise<void> => {
          attempts.added.push(...labels);
          if (options.addFailure) {
            throw options.addFailure;
          }
          changes.added.push(...labels);
        },
        removeLabel: async ({name}: RemoveLabelParameters): Promise<void> => {
          attempts.removed.push(name);
          if (options.removeFailure) {
            throw options.removeFailure;
          }
          changes.removed.push(name);
        },
      },
    },
  };
  const context = {payload, repo: {owner: "owner", repo: "repo"}};
  const core = {
    warning: (message: string): void => {
      warnings.push(message);
    },
  };

  await new AsyncFunction("context", "github", "core", labelerScript())(context, github, core);
  return {attempts, changes, warnings};
}

async function pullRequestResult(body: string, existingLabels: readonly string[] = []): Promise<RunResult> {
  return runLabeler({pull_request: {number: 42, body}}, {existingLabels});
}

function assertChanges(result: RunResult, changes: LabelChanges): void {
  assert.deepEqual(result.changes, changes);
  assert.deepEqual(result.warnings, []);
}

test("exactly one visible Breaking selection adds the label", async () => {
  assertChanges(await pullRequestResult(select(BREAKING)), {added: ["breaking change"], removed: []});
});

test("Breaking compatibility adds the label in either commit-history mode", async () => {
  for (const choice of [COMBINE, KEEP]) {
    assertChanges(await pullRequestResult(select(BREAKING, selectHistory(choice))), {
      added: ["breaking change"],
      removed: [],
    });
  }
});

test("exactly one visible Compatible selection removes the label", async () => {
  assertChanges(await pullRequestResult(select(COMPATIBLE), ["breaking change"]), {
    added: [],
    removed: ["breaking change"],
  });
});

test("Compatible compatibility removes the label in either commit-history mode", async () => {
  for (const choice of [COMBINE, KEEP]) {
    assertChanges(await pullRequestResult(select(COMPATIBLE, selectHistory(choice)), ["breaking change"]), {
      added: [],
      removed: ["breaking change"],
    });
  }
});

test("missing or invalid commit-history metadata does not change compatibility labeling", async () => {
  assertChanges(await pullRequestResult(select(BREAKING)), {added: ["breaking change"], removed: []});
  const invalidHistory = replaceExactly(TEMPLATE, `- [ ] ${COMBINE}`, "- [x] Squash merge");
  assertChanges(await pullRequestResult(select(COMPATIBLE, invalidHistory), ["breaking change"]), {
    added: [],
    removed: ["breaking change"],
  });
});

test("commit-history examples in fences and comments do not change compatibility labeling", async () => {
  const examples = `\n\n\`\`\`markdown\n## Commit History in Main\n- [x] ${COMBINE}\n\`\`\`\n`
    + `~~~markdown\n## Commit History in Main\n- [x] ${KEEP}\n~~~\n`
    + `<!--\n## Commit History in Main\n- [x] ${COMBINE}\n-->\n`;
  assertChanges(await pullRequestResult(`${select(COMPATIBLE)}${examples}`), {added: [], removed: []});
  assertChanges(await pullRequestResult(`${select(BREAKING)}${examples}`), {
    added: ["breaking change"],
    removed: [],
  });
});

test("Unsure, missing, and multiple decisions do not add the label", async () => {
  assertChanges(await pullRequestResult(select(UNSURE)), {added: [], removed: []});
  assertChanges(await pullRequestResult(TEMPLATE), {added: [], removed: []});
  assertChanges(await pullRequestResult(select(BREAKING, select(COMPATIBLE))), {added: [], removed: []});
});

test("duplicate visible compatibility sections do not add the label", async () => {
  const breaking = select(BREAKING);
  assertChanges(await pullRequestResult(`${breaking}\n${breaking}`), {added: [], removed: []});
});

test("backtick and tilde fenced examples do not add the label", async () => {
  const backticks = `\`\`\`markdown\n## Compatibility Decision\n- [x] ${BREAKING}\n\`\`\``;
  const tildes = `~~~markdown\n## Compatibility Decision\n- [x] ${BREAKING}\n~~~`;
  assertChanges(await pullRequestResult(`${backticks}\n${TEMPLATE}`), {added: [], removed: []});
  assertChanges(await pullRequestResult(`${tildes}\n${TEMPLATE}`), {added: [], removed: []});
});

test("HTML-commented examples do not add the label", async () => {
  const body = `<!--\n## Compatibility Decision\n- [x] ${BREAKING}\n-->\n${TEMPLATE}`;
  assertChanges(await pullRequestResult(body), {added: [], removed: []});
});

test("blockquoted and nested Breaking examples do not add the label", async () => {
  const body = replaceExactly(
    TEMPLATE,
    "Choose one option.",
    `Choose one option.\n\n> - [x] ${BREAKING}\n  - [x] ${BREAKING}`,
  );
  assertChanges(await pullRequestResult(body), {added: [], removed: []});
});

test("Breaking text in rationale and explanatory prose does not add the label", async () => {
  const body = replaceExactly(
    select(COMPATIBLE),
    "`Because ...`",
    `\`Because the text - [x] ${BREAKING} is only an example.\``,
  );
  assertChanges(await pullRequestResult(body), {added: [], removed: []});
});

test("a Breaking checkbox in another section does not add the label", async () => {
  const body = `${TEMPLATE}\n## Example\n\n- [x] ${BREAKING}\n`;
  assertChanges(await pullRequestResult(body), {added: [], removed: []});
});

test("removed previous-template choices do not add the label", async () => {
  for (const oldSelection of ["No visible change", "Could break existing usage", "Unsure"]) {
    const body = replaceExactly(TEMPLATE, `- [ ] ${COMPATIBLE}`, `- [x] ${oldSelection}\n- [ ] ${COMPATIBLE}`);
    assertChanges(await pullRequestResult(body), {added: [], removed: []});
  }
});

test("the exact issue-form Breaking answer still adds the label", async () => {
  const body = `### Affects existing users?\n\nMay break existing usage\n\n### What breaks?\n\nExisting configuration.`;
  const result = await runLabeler({issue: {number: 42, body}});
  assertChanges(result, {added: ["breaking change"], removed: []});
});

test("non-breaking issue-form answers remove the label", async () => {
  const body = "### Affects existing users?\n\nNo visible impact\n\n### Notes\n\nMay break existing usage is quoted.";
  const result = await runLabeler({issue: {number: 42, body}}, {existingLabels: ["breaking change"]});
  assertChanges(result, {added: [], removed: ["breaking change"]});
});

test("label-read failure warns and attempts no mutation", async () => {
  const result = await runLabeler(
    {pull_request: {number: 42, body: select(BREAKING)}},
    {listFailure: new Error("read failed")},
  );
  assert.deepEqual(result.attempts, {added: [], removed: []});
  assert.deepEqual(result.changes, {added: [], removed: []});
  assert.match(result.warnings[0] ?? "", /Unable to read labels.*read failed/);
});

test("label-add failure remains best effort and truthful", async () => {
  const result = await runLabeler(
    {pull_request: {number: 42, body: select(BREAKING)}},
    {addFailure: new Error("add failed")},
  );
  assert.deepEqual(result.attempts, {added: ["breaking change"], removed: []});
  assert.deepEqual(result.changes, {added: [], removed: []});
  assert.match(result.warnings[0] ?? "", /Unable to add breaking change label.*add failed/);
});

test("label-removal failure remains best effort and truthful", async () => {
  const result = await runLabeler(
    {pull_request: {number: 42, body: select(COMPATIBLE)}},
    {existingLabels: ["breaking change"], removeFailure: new Error("remove failed")},
  );
  assert.deepEqual(result.attempts, {added: [], removed: ["breaking change"]});
  assert.deepEqual(result.changes, {added: [], removed: []});
  assert.match(result.warnings[0] ?? "", /Unable to remove breaking change label.*remove failed/);
});
