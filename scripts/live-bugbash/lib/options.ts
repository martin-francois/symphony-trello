import {FAMILIES, PROFILES, type Family, type Profile} from "./manifest.ts";

export type TrelloMode = "fake" | "real";
export type CodexMode = "fake" | "real";
export type GithubMode = "fake" | "real-sandbox";
export type HostProfile = "standard" | "hardened";
export type Command = "run" | "list" | "dry-run" | "cleanup-only" | "help";

export interface SelectionCriteria {
  profiles: Profile[];
  families: Family[];
  scenarioIds: string[];
}

export interface SoakOptions {
  durationMs: number | null;
  until: Date | null;
  intervalMs: number;
}

export interface RunOptions {
  command: Command;
  selection: SelectionCriteria;
  runId: string;
  resume: boolean;
  trello: TrelloMode;
  codex: CodexMode;
  github: GithubMode;
  hostProfile: HostProfile;
  network: boolean;
  symphonyCommand: string | null;
  trelloWorkspaceId: string | null;
  timeBudgetMs: number | null;
  soak: SoakOptions;
}

export const USAGE = `Usage: scripts/live-bugbash/run.sh [options]

Selects scenarios from scripts/live-bugbash/manifest.yml, runs them against run-scoped resources,
and writes reports under target/live-bugbash/<run-id>/.

Commands (default: run):
  --list                    List the selected scenarios with their metadata.
  --dry-run                 Print the execution plan without creating anything.
  --cleanup-only RUN_ID     Archive every registered board and stop every registered worker of an
                            earlier run, then verify that nothing run-owned remains.
  --help                    Show this help.

Selection (default: --profile quick):
  --profile NAME            ${PROFILES.join(", ")}. Repeatable.
  --family NAME             ${FAMILIES.join(", ")}. Repeatable.
  --scenario ID             One manifest scenario id. Repeatable.

Integrations (fake by default; real services need an explicit opt-in):
  --trello fake|real        real needs TRELLO_API_KEY and TRELLO_API_TOKEN (environment or .env).
  --trello-workspace-id ID  Trello Workspace for disposable boards when the token sees several.
  --codex fake|real         real needs the codex CLI on PATH; real-Codex rows are skipped otherwise.
  --github fake|real-sandbox
                            real-sandbox is reserved for GitHub sandbox rows (not yet automated).
  --host-profile standard|hardened
                            hardened allows run-scoped danger-full-access rows.
  --network                 Allow rows that need outbound network from Codex or Git.

Run control:
  --run-id ID               Run id (default: live-bugbash-<UTC timestamp>).
  --resume RUN_ID           Rerun the rows of an earlier run that did not pass.
  --symphony-command PATH   Use an installed symphony-trello command instead of a source install.
  --time-budget DURATION    Hard stop for the whole run, for example 10m. Remaining rows are
                            reported as interrupted.
  --soak-duration DURATION  Passive soak length (soak family).
  --soak-until TIMESTAMP    Passive soak hard stop as an ISO-8601 timestamp.
  --soak-interval DURATION  Pause between soak cycles (default 60s).
`;

export class UsageError extends Error {
  override name = "UsageError";
}

const SAFE_RUN_ID = /^[A-Za-z0-9._-]+$/;
const DURATION = /^(\d+)(ms|s|m|h)?$/;
const DURATION_UNITS_MS: Readonly<Record<string, number>> = {ms: 1, s: 1_000, m: 60_000, h: 3_600_000};
const DEFAULT_SOAK_INTERVAL_MS = 60_000;

export function parseOptions(args: readonly string[], now: Date = new Date()): RunOptions {
  const profiles: Profile[] = [];
  const families: Family[] = [];
  const scenarioIds: string[] = [];
  let command: Command = "run";
  let runId: string | null = null;
  let resume = false;
  let trello: TrelloMode = "fake";
  let codex: CodexMode = "fake";
  let github: GithubMode = "fake";
  let hostProfile: HostProfile = "standard";
  let network = false;
  let symphonyCommand: string | null = null;
  let trelloWorkspaceId: string | null = null;
  let timeBudgetMs: number | null = null;
  let soakDurationMs: number | null = null;
  let soakUntil: Date | null = null;
  let soakIntervalMs = DEFAULT_SOAK_INTERVAL_MS;

  const queue = [...args];
  const value = (flag: string): string => {
    const next = queue.shift();
    if (next === undefined || next.startsWith("--")) {
      throw new UsageError(`${flag} needs a value`);
    }
    return next;
  };
  const setCommand = (next: Command) => {
    if (command !== "run" && command !== next) {
      throw new UsageError(`choose only one of --list, --dry-run, --cleanup-only, and --help`);
    }
    command = next;
  };

  while (queue.length > 0) {
    const raw = queue.shift() as string;
    const [flag, inline] = raw.includes("=") ? splitInline(raw) : [raw, null];
    if (inline !== null) {
      queue.unshift(inline);
    }
    switch (flag) {
      case "--help":
      case "-h":
        setCommand("help");
        break;
      case "--list":
        setCommand("list");
        break;
      case "--dry-run":
        setCommand("dry-run");
        break;
      case "--cleanup-only":
        setCommand("cleanup-only");
        runId = safeRunId(value(flag));
        break;
      case "--profile":
        profiles.push(choice(value(flag), PROFILES, flag));
        break;
      case "--family":
        families.push(choice(value(flag), FAMILIES, flag));
        break;
      case "--scenario":
        scenarioIds.push(value(flag));
        break;
      case "--run-id":
        runId = safeRunId(value(flag));
        break;
      case "--resume":
        runId = safeRunId(value(flag));
        resume = true;
        break;
      case "--trello":
        trello = choice(value(flag), ["fake", "real"] as const, flag);
        break;
      case "--trello-workspace-id":
        trelloWorkspaceId = value(flag);
        break;
      case "--codex":
        codex = choice(value(flag), ["fake", "real"] as const, flag);
        break;
      case "--github":
        github = choice(value(flag), ["fake", "real-sandbox"] as const, flag);
        break;
      case "--host-profile":
        hostProfile = choice(value(flag), ["standard", "hardened"] as const, flag);
        break;
      case "--network":
        network = true;
        break;
      case "--symphony-command":
        symphonyCommand = value(flag);
        break;
      case "--time-budget":
        timeBudgetMs = parseDuration(value(flag), flag);
        break;
      case "--soak-duration":
        soakDurationMs = parseDuration(value(flag), flag);
        break;
      case "--soak-until":
        soakUntil = parseTimestamp(value(flag), flag);
        break;
      case "--soak-interval":
        soakIntervalMs = parseDuration(value(flag), flag);
        break;
      default:
        throw new UsageError(`unknown option ${flag}`);
    }
  }

  if (soakDurationMs !== null && soakUntil !== null) {
    throw new UsageError("choose either --soak-duration or --soak-until");
  }
  if (soakUntil !== null && soakUntil.getTime() <= now.getTime()) {
    throw new UsageError("--soak-until must be in the future");
  }
  if (profiles.length === 0 && families.length === 0 && scenarioIds.length === 0) {
    profiles.push("quick");
  }
  return {
    command,
    selection: {profiles, families, scenarioIds},
    runId: runId ?? defaultRunId(now),
    resume,
    trello,
    codex,
    github,
    hostProfile,
    network,
    symphonyCommand,
    trelloWorkspaceId,
    timeBudgetMs,
    soak: {durationMs: soakDurationMs, until: soakUntil, intervalMs: soakIntervalMs},
  };
}

export function defaultRunId(now: Date): string {
  return `live-bugbash-${now.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "")}`;
}

export function safeRunId(value: string): string {
  if (!SAFE_RUN_ID.test(value) || value === "." || value === "..") {
    throw new UsageError(
      `run id must be one path segment of ASCII letters, digits, '.', '_', or '-': ${JSON.stringify(value)}`,
    );
  }
  return value;
}

export function parseDuration(value: string, flag: string): number {
  const match = DURATION.exec(value);
  if (match === null || match[1] === undefined) {
    throw new UsageError(`${flag} must be a duration such as 90s, 10m, or 2h`);
  }
  return Number(match[1]) * (DURATION_UNITS_MS[match[2] ?? "s"] ?? 1_000);
}

function parseTimestamp(value: string, flag: string): Date {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime()) || !/(Z|[+-]\d{2}:?\d{2})$/.test(value)) {
    throw new UsageError(`${flag} must be an ISO-8601 timestamp with a timezone, for example 2026-07-01T18:00:00Z`);
  }
  return parsed;
}

function splitInline(raw: string): [string, string] {
  const index = raw.indexOf("=");
  return [raw.slice(0, index), raw.slice(index + 1)];
}

function choice<T extends string>(value: string, allowed: readonly T[], flag: string): T {
  if (!allowed.includes(value as T)) {
    throw new UsageError(`${flag} must be one of ${allowed.join(", ")}`);
  }
  return value as T;
}
