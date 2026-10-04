import {appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {join, relative, resolve, sep} from "node:path";
import {publicText, type PrivatePath} from "./redact.ts";

/** Directories created under every run root. */
export const RUN_DIRECTORIES = [
  "config",
  "workflows",
  "workspaces",
  "logs",
  "state",
  "private-evidence",
  "issues",
  "fakes/codex",
  "installer-sandboxes/home",
  "installer-sandboxes/xdg-config",
  "installer-sandboxes/xdg-data",
  "installer-sandboxes/xdg-state",
  "installer-sandboxes/xdg-cache",
  "installer-sandboxes/symphony-home",
] as const;

/** Registries the live-bugbash skill and cleanup rely on. Only registered resources are mutated. */
export const REGISTRIES = {
  trelloBoards: "created-trello-boards.jsonl",
  trelloCards: "created-trello-cards.jsonl",
  githubSandboxRepos: "created-github-sandbox-repos.jsonl",
  workers: "started-workers.jsonl",
  ownedPaths: "owned-local-paths.txt",
  sensitivePaths: "sensitive-cleanup-paths.txt",
} as const;
export type Registry = keyof typeof REGISTRIES;

export const PUBLIC_FILES = {
  progress: "progress.md",
  ledger: "coverage-ledger.md",
  finalReport: "final-report.md",
  cleanupSummary: "cleanup-summary.md",
} as const;

export class RunRoot {
  readonly root: string;
  readonly runId: string;
  readonly repoRoot: string;

  constructor(repoRoot: string, runId: string) {
    this.repoRoot = repoRoot;
    this.runId = runId;
    const base = resolve(repoRoot, "target", "live-bugbash");
    this.root = resolve(base, runId);
    if (!this.root.startsWith(base + sep)) {
      throw new Error(`run root escaped target/live-bugbash: ${runId}`);
    }
  }

  get exists(): boolean {
    return existsSync(this.root);
  }

  create(): void {
    for (const directory of RUN_DIRECTORIES) {
      mkdirSync(join(this.root, directory), {recursive: true});
    }
    for (const file of Object.values(REGISTRIES)) {
      const path = join(this.root, file);
      if (!existsSync(path)) {
        writeFileSync(path, "");
      }
    }
    const started = this.path("state", "run-started-at");
    if (!existsSync(started)) {
      writeFileSync(started, `${new Date().toISOString()}\n`);
      this.registerOwnedPath(this.root);
    }
  }

  /** When the run root was first created; a resumed run keeps the original time. */
  get startedAt(): Date {
    const started = this.path("state", "run-started-at");
    return existsSync(started) ? new Date(readFileSync(started, "utf8").trim()) : new Date();
  }

  path(...segments: string[]): string {
    const target = resolve(this.root, ...segments);
    if (target !== this.root && !target.startsWith(this.root + sep)) {
      throw new Error(`path escapes the run root: ${segments.join("/")}`);
    }
    return target;
  }

  /** A fresh run-owned directory, for example `scenarioDir("workspaces", id)`. */
  dir(...segments: string[]): string {
    const target = this.path(...segments);
    mkdirSync(target, {recursive: true});
    return target;
  }

  privateEvidence(scenarioId: string, name: string, content: string): string {
    const target = join(this.dir("private-evidence", scenarioId), name);
    writeFileSync(target, content);
    return target;
  }

  register(registry: Registry, entry: Record<string, unknown>): void {
    appendFileSync(this.path(REGISTRIES[registry]), `${JSON.stringify({...entry, registeredAt: new Date().toISOString()})}\n`);
  }

  registerOwnedPath(path: string): void {
    appendFileSync(this.path(REGISTRIES.ownedPaths), `${path}\n`);
  }

  registerSensitivePath(path: string): void {
    appendFileSync(this.path(REGISTRIES.sensitivePaths), `${path}\n`);
  }

  entries<T>(registry: Registry): T[] {
    const path = this.path(REGISTRIES[registry]);
    if (!existsSync(path)) {
      return [];
    }
    return readFileSync(path, "utf8")
      .split("\n")
      .filter((line) => line.trim() !== "")
      .map((line) => JSON.parse(line) as T);
  }

  lines(registry: Registry): string[] {
    const path = this.path(REGISTRIES[registry]);
    return existsSync(path) ? readFileSync(path, "utf8").split("\n").filter((line) => line.trim() !== "") : [];
  }

  /** Private values that public text must never contain, mapped to their public labels. */
  privatePaths(): PrivatePath[] {
    const home = process.env["HOME"];
    return [
      {path: this.root, label: "<run-root>"},
      {path: relative(this.repoRoot, this.root), label: "<run-root>"},
      {path: this.repoRoot, label: "<repo>"},
      ...(home === undefined ? [] : [{path: home, label: "<home>"}]),
    ];
  }

  publicText(text: string): string {
    return publicText(text, this.privatePaths());
  }

  writePublic(file: string, content: string): void {
    writeFileSync(this.path(file), this.publicText(content));
  }

  appendPublic(file: string, content: string): void {
    appendFileSync(this.path(file), this.publicText(content));
  }
}
