import {existsSync, readFileSync, writeFileSync} from "node:fs";
import {resolve} from "node:path";
import {parseOptions, UsageError, type RunOptions} from "./options.ts";
import {RunRoot} from "./run-root.ts";

const CONFIG_FILE = "run-options.json";
const IMMUTABLE_FLAGS = {
  "--trello": "trello",
  "--codex": "codex",
  "--github": "github",
  "--host-profile": "hostProfile",
  "--network": "network",
  "--symphony-command": "symphonyCommand",
  "--trello-workspace-id": "trelloWorkspaceId",
} as const;

/** Credentials are deliberately absent; every invocation reads them from its current environment. */
export function saveRunOptions(run: RunRoot, options: RunOptions): void {
  const args: string[] = [];
  for (const [flag, key] of Object.entries(IMMUTABLE_FLAGS)) {
    const value = options[key];
    if (value === true) {
      args.push(flag);
    } else if (typeof value === "string") {
      args.push(flag, key === "symphonyCommand" ? resolve(value) : value);
    }
  }
  for (const [flag, values] of [
    ["--profile", options.selection.profiles],
    ["--family", options.selection.families],
    ["--scenario", options.selection.scenarioIds],
  ] as const) {
    for (const value of values) args.push(flag, value);
  }
  if (options.timeBudgetMs !== null) args.push("--time-budget", `${options.timeBudgetMs}ms`);
  if (options.soak.durationMs !== null) args.push("--soak-duration", `${options.soak.durationMs}ms`);
  if (options.soak.until !== null) args.push("--soak-until", options.soak.until.toISOString());
  args.push("--soak-interval", `${options.soak.intervalMs}ms`);
  writeFileSync(run.path("state", CONFIG_FILE), JSON.stringify({formatVersion: 1, args}, null, 2), {mode: 0o600});
}

/** Restores the original targets before resume planning or cleanup can contact any service. */
export function resolveRunOptions(repoRoot: string, args: readonly string[]): RunOptions {
  const current = parseOptions(args);
  if (!current.resume && current.command !== "cleanup-only") return current;
  const run = new RunRoot(repoRoot, current.runId);
  const path = run.path("state", CONFIG_FILE);
  if (!existsSync(path)) {
    throw new UsageError(`cannot restore run "${current.runId}": saved configuration is missing; inspect its resource registries before manual cleanup`);
  }
  let saved: unknown;
  try {
    saved = JSON.parse(readFileSync(path, "utf8"));
  } catch (cause) {
    throw new UsageError(`cannot restore run "${current.runId}": saved configuration must contain valid JSON`, {cause});
  }
  if (typeof saved !== "object" || saved === null || !("formatVersion" in saved) || saved.formatVersion !== 1 ||
      !("args" in saved) || !Array.isArray(saved.args) || !saved.args.every((arg: unknown) => typeof arg === "string")) {
    throw new UsageError(`cannot restore run "${current.runId}": expected configuration format version 1`);
  }
  // An old absolute soak deadline is valid stored state; the resumed invocation can renew it.
  const original = parseOptions(saved.args as string[], new Date(0));
  const flags = new Set(args.map((arg) => arg.split("=", 1)[0]));
  for (const [flag, key] of Object.entries(IMMUTABLE_FLAGS)) {
    const supplied = key === "symphonyCommand" && current[key] !== null ? resolve(current[key]) : current[key];
    if (flags.has(flag) && supplied !== original[key]) {
      throw new UsageError(`cannot change run option "${flag}": resume and cleanup must use the saved target`);
    }
  }
  if (["--profile", "--family", "--scenario"].some((flag) => flags.has(flag)) &&
      JSON.stringify(current.selection) !== JSON.stringify(original.selection)) {
    throw new UsageError(`cannot change run selection "${current.runId}": resume must preserve the original scenarios`);
  }
  const renewSoak = flags.has("--soak-duration") || flags.has("--soak-until");
  return {
    ...original,
    command: current.command,
    runId: current.runId,
    resume: current.resume,
    timeBudgetMs: flags.has("--time-budget") ? current.timeBudgetMs : original.timeBudgetMs,
    soak: {
      durationMs: renewSoak ? current.soak.durationMs : original.soak.durationMs,
      until: renewSoak ? current.soak.until : original.soak.until,
      intervalMs: flags.has("--soak-interval") ? current.soak.intervalMs : original.soak.intervalMs,
    },
  };
}
