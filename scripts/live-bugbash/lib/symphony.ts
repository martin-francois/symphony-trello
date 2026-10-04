import {spawn} from "node:child_process";
import {existsSync, mkdirSync, writeFileSync} from "node:fs";
import {homedir} from "node:os";
import {join} from "node:path";
import type {RunRoot} from "./run-root.ts";
import type {TrelloCredentials} from "./trello.ts";

export interface CommandOutcome {
  status: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export interface CliPaths {
  configDir: string;
  stateHome: string;
  workspaceRoot: string;
  envFile: string;
  manifest: string;
}

export interface TrelloTarget {
  endpoint: string;
  credentials: TrelloCredentials;
}

const DEFAULT_TIMEOUT_MS = 120_000;
const INSTALL_TIMEOUT_MS = 15 * 60_000;

/** Environment variables that would leak the operator's own Symphony setup into a run. */
const SCRUBBED_VARIABLES = [
  "SYMPHONY_HTTP_PORT",
  "QUARKUS_HTTP_PORT",
  "SYMPHONY_WORKFLOW_PATH",
  "SYMPHONY_TRELLO_DOTENV",
  "SYMPHONY_TRELLO_CONFIG_DIR",
  "SYMPHONY_TRELLO_STATE_HOME",
  "SYMPHONY_TRELLO_WORKSPACE_ROOT",
  "SYMPHONY_CODEX_DANGER_FULL_ACCESS",
  "SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS",
  "TRELLO_API_KEY",
  "TRELLO_API_TOKEN",
];

/**
 * Runs the installed `symphony-trello` command with run-scoped HOME, XDG, config, state, and
 * workspace locations. Trello credentials for the selected target (fake or real) are passed only
 * through the environment of the child process.
 */
export class SymphonyCli {
  readonly command: string;
  readonly defaultPaths: CliPaths;
  private readonly run: RunRoot;
  private readonly trello: TrelloTarget;

  constructor(run: RunRoot, command: string, trello: TrelloTarget) {
    this.run = run;
    this.command = command;
    this.trello = trello;
    this.defaultPaths = cliPaths(run, "default");
  }

  /** Separate config/state/workspace locations for a scenario that needs its own connected boards. */
  paths(name: string): CliPaths {
    return cliPaths(this.run, name);
  }

  environment(paths: CliPaths = this.defaultPaths, extra: Record<string, string> = {}): NodeJS.ProcessEnv {
    return {
      ...sandboxEnvironment(this.run),
      SYMPHONY_TRELLO_CONFIG_DIR: paths.configDir,
      SYMPHONY_TRELLO_STATE_HOME: paths.stateHome,
      SYMPHONY_TRELLO_WORKSPACE_ROOT: paths.workspaceRoot,
      SYMPHONY_TRELLO_DOTENV: paths.envFile,
      TRELLO_API_KEY: this.trello.credentials.key,
      TRELLO_API_TOKEN: this.trello.credentials.token,
      ...extra,
    };
  }

  get endpoint(): string {
    return this.trello.endpoint;
  }

  exec(
    args: readonly string[],
    options: {paths?: CliPaths; env?: Record<string, string>; timeoutMs?: number; cwd?: string} = {},
  ): Promise<CommandOutcome> {
    return runCommand(this.command, args, {
      env: this.environment(options.paths, options.env),
      cwd: options.cwd ?? options.paths?.configDir ?? this.defaultPaths.configDir,
      timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
    });
  }

  /** Writes the run-scoped dotenv file that generated workers read for Trello credentials. */
  writeEnvFile(paths: CliPaths = this.defaultPaths, extra: Record<string, string> = {}): void {
    const values = {TRELLO_API_KEY: this.trello.credentials.key, TRELLO_API_TOKEN: this.trello.credentials.token, ...extra};
    writeFileSync(
      paths.envFile,
      Object.entries(values)
        .map(([key, value]) => `${key}=${value}\n`)
        .join(""),
      {mode: 0o600},
    );
    this.run.registerSensitivePath(paths.envFile);
  }

  /** Starts a managed worker for a workflow and registers it before waiting for anything else. */
  start(workflow: string, paths: CliPaths = this.defaultPaths, env: Record<string, string> = {}): Promise<CommandOutcome> {
    this.run.register("workers", {workflow, configDir: paths.configDir, stateHome: paths.stateHome});
    return this.exec(
      ["start", "--workflow", workflow, "--config-dir", paths.configDir, "--state-home", paths.stateHome, "--env", paths.envFile],
      {paths, env},
    );
  }

  stop(workflow: string, paths: CliPaths = this.defaultPaths): Promise<CommandOutcome> {
    return this.exec(["stop", "--workflow", workflow, "--config-dir", paths.configDir, "--state-home", paths.stateHome], {paths});
  }
}

export function cliPaths(run: RunRoot, name: string): CliPaths {
  const configDir = run.dir("config", name);
  const stateHome = run.dir("state", "symphony", name);
  const workspaceRoot = run.dir("workspaces", name);
  return {configDir, stateHome, workspaceRoot, envFile: join(configDir, ".env"), manifest: join(configDir, "connected-boards.json")};
}

/** HOME, XDG, and SYMPHONY_HOME pointing into the run root, plus a PATH without operator overrides. */
export function sandboxEnvironment(run: RunRoot): NodeJS.ProcessEnv {
  const environment: NodeJS.ProcessEnv = {...process.env};
  for (const variable of SCRUBBED_VARIABLES) {
    delete environment[variable];
  }
  const sandbox = (name: string) => {
    const path = run.path("installer-sandboxes", name);
    mkdirSync(path, {recursive: true});
    return path;
  };
  return {
    ...environment,
    HOME: sandbox("home"),
    XDG_CONFIG_HOME: sandbox("xdg-config"),
    XDG_DATA_HOME: sandbox("xdg-data"),
    XDG_STATE_HOME: sandbox("xdg-state"),
    XDG_CACHE_HOME: sandbox("xdg-cache"),
    SYMPHONY_HOME: sandbox("symphony-home"),
  };
}

/**
 * Installs Symphony from this checkout into the run root with the documented source-install path.
 * The Maven wrapper keeps using the operator's Maven cache (MAVEN_USER_HOME) so a run does not
 * download every dependency again; nothing else from the real home is visible to the installer.
 */
export async function installFromSource(run: RunRoot, commit: string): Promise<{outcome: CommandOutcome; command: string}> {
  const prefix = run.path("install", "app");
  const binDir = run.path("install", "bin");
  run.registerOwnedPath(prefix);
  // The installer clones the repository and fetches every ref. A run-scoped bare repository that
  // holds only the target commit keeps that clone small and independent of other branches that
  // change while the run is in progress.
  const source = run.path("install", "source.git");
  const prepared = await prepareSourceRepository(run.repoRoot, source, commit);
  if (prepared.status !== 0) {
    return {outcome: prepared, command: join(binDir, "symphony-trello")};
  }
  const outcome = await runCommand(
    "bash",
    [
      join(run.repoRoot, "install.sh"),
      "--from-source",
      "--repo",
      source,
      "--ref",
      commit,
      "--no-onboard",
      "--no-update-path",
      "--prefix",
      prefix,
      "--bin-dir",
      binDir,
    ],
    {
      env: {...sandboxEnvironment(run), MAVEN_USER_HOME: process.env["MAVEN_USER_HOME"] ?? join(homedir(), ".m2")},
      cwd: run.root,
      timeoutMs: INSTALL_TIMEOUT_MS,
    },
  );
  return {outcome, command: join(binDir, "symphony-trello")};
}

async function prepareSourceRepository(repoRoot: string, source: string, commit: string): Promise<CommandOutcome> {
  const init = await runCommand("git", ["init", "--quiet", "--bare", source], {cwd: repoRoot});
  if (init.status !== 0) {
    return init;
  }
  return runCommand("git", ["push", "--quiet", source, `${commit}:refs/heads/live-bugbash-source`], {cwd: repoRoot});
}

export function commandExists(path: string): boolean {
  return existsSync(path);
}

export interface WorkerState {
  counts?: {running?: number; retrying?: number};
  running?: Array<Record<string, unknown>>;
  retrying?: Array<Record<string, unknown>>;
  [key: string]: unknown;
}

export async function workerState(port: number): Promise<WorkerState | null> {
  try {
    const response = await fetch(`http://127.0.0.1:${port}/api/v1/state`, {signal: AbortSignal.timeout(3_000)});
    return response.ok ? ((await response.json()) as WorkerState) : null;
  } catch {
    return null;
  }
}

/** Polls `check` until it returns a value or the deadline passes. Polling is the behavior under test. */
export async function waitFor<T>(
  description: string,
  check: () => Promise<T | null | undefined | false> | T | null | undefined | false,
  timeoutMs: number,
  intervalMs = 500,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value !== null && value !== undefined && value !== false) {
      return value;
    }
    if (Date.now() >= deadline) {
      throw new TimeoutError(`timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${description}`);
    }
    await sleep(intervalMs);
  }
}

export class TimeoutError extends Error {
  override name = "TimeoutError";
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Time between SIGTERM and SIGKILL, and between exit and giving up on still-open output pipes. */
const KILL_GRACE_MS = 5_000;

/**
 * Runs a child command without a shell. Asynchronous on purpose: the in-process fake Trello API must
 * keep serving requests while the CLI under test talks to it. The child gets its own process group,
 * so a timeout stops grandchildren too (for example the Maven build that install.sh starts).
 */
export function runCommand(
  command: string,
  args: readonly string[],
  options: {cwd: string; env?: NodeJS.ProcessEnv; timeoutMs?: number; input?: string},
): Promise<CommandOutcome> {
  return new Promise((resolve) => {
    const child = spawn(command, args, {cwd: options.cwd, env: options.env ?? process.env, stdio: ["pipe", "pipe", "pipe"], detached: true});
    let stdout = "";
    let stderr = "";
    let timedOut = false;
    let settled = false;
    const finish = (status: number | null, extra = "") => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        resolve({status, stdout, stderr: `${stderr}${extra}`, timedOut});
      }
    };
    const signalGroup = (signal: NodeJS.Signals) => {
      try {
        if (child.pid !== undefined) {
          process.kill(-child.pid, signal);
        }
      } catch {
        // The group already exited.
      }
    };
    child.stdout.setEncoding("utf8").on("data", (chunk: string) => (stdout += chunk));
    child.stderr.setEncoding("utf8").on("data", (chunk: string) => (stderr += chunk));
    const timer = setTimeout(() => {
      timedOut = true;
      signalGroup("SIGTERM");
      setTimeout(() => signalGroup("SIGKILL"), KILL_GRACE_MS).unref();
    }, options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
    child.on("error", (error) => finish(null, error.message));
    // A background process that inherited the pipes can keep them open after the child exits.
    child.on("exit", (status) => setTimeout(() => finish(status), KILL_GRACE_MS).unref());
    child.on("close", (status) => finish(status));
    child.stdin.end(options.input ?? "");
  });
}
