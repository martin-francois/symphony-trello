import {readFileSync, writeFileSync} from "node:fs";
import {parseDocument} from "yaml";

/** One front-matter edit: a key path and the new value, or `undefined` to delete the key. */
export type WorkflowEdit = readonly [path: readonly (string | number)[], value: unknown];

const FRONT_MATTER = /^---\r?\n([\s\S]*?)\r?\n---(\r?\n|$)/;

export function readFrontMatter(path: string): Record<string, unknown> {
  const match = FRONT_MATTER.exec(readFileSync(path, "utf8"));
  if (match === null || match[1] === undefined) {
    throw new Error("workflow has no YAML front matter");
  }
  return (parseDocument(match[1]).toJS() ?? {}) as Record<string, unknown>;
}

/**
 * Edits a generated WORKFLOW.md front matter through the YAML document model so comments and the
 * prompt body stay intact. Used to point generated workflows at the fake Codex app-server or to
 * set repository defaults for a scenario.
 */
export function patchWorkflow(path: string, edits: readonly WorkflowEdit[]): void {
  const text = readFileSync(path, "utf8");
  const match = FRONT_MATTER.exec(text);
  if (match === null || match[1] === undefined) {
    throw new Error("workflow has no YAML front matter");
  }
  const document = parseDocument(match[1]);
  for (const [keyPath, value] of edits) {
    if (value === undefined) {
      document.deleteIn(keyPath);
    } else {
      document.setIn(keyPath, value);
    }
  }
  const frontMatter = document.toString().replace(/\n$/, "");
  writeFileSync(path, `---\n${frontMatter}\n---${match[2] ?? "\n"}${text.slice(match[0].length)}`);
}

export function getIn(frontMatter: Record<string, unknown>, keyPath: readonly string[]): unknown {
  let current: unknown = frontMatter;
  for (const key of keyPath) {
    if (typeof current !== "object" || current === null) {
      return undefined;
    }
    current = (current as Record<string, unknown>)[key];
  }
  return current;
}
