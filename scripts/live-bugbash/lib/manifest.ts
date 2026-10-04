import {readFileSync} from "node:fs";
import {parse} from "yaml";

export const FAMILIES = [
  "setup",
  "lifecycle",
  "repository-source",
  "trello-prerequisites",
  "trello-tools",
  "diagnostics",
  "codex-access",
  "github",
  "cleanup",
  "soak",
] as const;
export type Family = (typeof FAMILIES)[number];

export const PROFILES = [
  "quick",
  "release",
  "trello-live",
  "real-codex",
  "github-live",
  "repository-source",
  "trello-tools",
  "lifecycle",
  "soak",
  "full",
] as const;
export type Profile = (typeof PROFILES)[number];

/**
 * Execution modes in the order the runner executes them. Deterministic rows run first, every
 * fake-Codex row runs before any real-Codex row, and cleanup runs before the passive soak that
 * verifies it.
 */
export const MODES = [
  "deterministic-cli",
  "trello-cli",
  "service-fake-codex",
  "direct-real-codex",
  "service-real-codex",
  "github-sandbox",
  "cleanup",
  "soak",
] as const;
export type Mode = (typeof MODES)[number];

export const EXPECTED_STATUSES = ["covered", "covered-with-caveat", "known-bug"] as const;
export type ExpectedStatus = (typeof EXPECTED_STATUSES)[number];

export const CODEX_REQUIREMENTS = [false, "fake", "real"] as const;
export type CodexRequirement = (typeof CODEX_REQUIREMENTS)[number];

export interface Requirements {
  trello: boolean;
  codex: CodexRequirement;
  github: boolean;
  network: boolean;
  hardened_host: boolean;
}

export interface Evidence {
  public: string;
  private: string;
}

export interface Scenario {
  id: string;
  family: Family;
  profiles: Profile[];
  requires: Requirements;
  mode: Mode;
  description: string;
  setup: string;
  action: string;
  assertions: string[];
  cleanup: string;
  evidence: Evidence;
  expected: ExpectedStatus;
  linked_issue: number | null;
  harness_only: boolean;
  notes: string | null;
}

export interface Manifest {
  version: 1;
  scenarios: Scenario[];
}

const SCENARIO_ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const REQUIRED_TEXT_FIELDS = ["description", "setup", "action", "cleanup"] as const;

export class ManifestError extends Error {
  override name = "ManifestError";
}

export function loadManifest(path: string): Manifest {
  return parseManifest(readFileSync(path, "utf8"));
}

export function parseManifest(text: string): Manifest {
  const document: unknown = parse(text);
  const root = record(document, "manifest");
  if (root["version"] !== 1) {
    throw new ManifestError("manifest version must be 1");
  }
  const defaults = record(root["defaults"] ?? {}, "defaults");
  const rows = root["scenarios"];
  if (!Array.isArray(rows) || rows.length === 0) {
    throw new ManifestError("manifest must list at least one scenario");
  }
  const scenarios = rows.map((row: unknown, index) => scenario(row, defaults, index));
  const seen = new Set<string>();
  for (const entry of scenarios) {
    if (seen.has(entry.id)) {
      throw new ManifestError(`duplicate scenario id ${entry.id}`);
    }
    seen.add(entry.id);
  }
  return {version: 1, scenarios};
}

function scenario(value: unknown, defaults: Record<string, unknown>, index: number): Scenario {
  const row = record(value, `scenario #${index + 1}`);
  const id = text(row["id"], `scenario #${index + 1} id`);
  if (!SCENARIO_ID.test(id)) {
    throw new ManifestError(`scenario id ${id} must be lowercase kebab-case`);
  }
  const where = `scenario ${id}`;
  for (const field of REQUIRED_TEXT_FIELDS) {
    text(row[field], `${where} ${field}`);
  }
  const assertions = row["assertions"];
  if (!Array.isArray(assertions) || assertions.length === 0) {
    throw new ManifestError(`${where} must list at least one assertion`);
  }
  const profiles = row["profiles"];
  if (!Array.isArray(profiles) || profiles.length === 0) {
    throw new ManifestError(`${where} must belong to at least one profile`);
  }
  const expected = oneOf(row["expected"], EXPECTED_STATUSES, `${where} expected`);
  const linkedIssue = row["linked_issue"] ?? null;
  if (linkedIssue !== null && (!Number.isInteger(linkedIssue) || (linkedIssue as number) < 1)) {
    throw new ManifestError(`${where} linked_issue must be a GitHub issue number or null`);
  }
  if (expected === "known-bug" && linkedIssue === null) {
    throw new ManifestError(`${where} is a known bug and must link its GitHub issue`);
  }
  return {
    id,
    family: oneOf(row["family"], FAMILIES, `${where} family`),
    profiles: profiles.map((profile: unknown) => oneOf(profile, PROFILES, `${where} profile`)),
    requires: requirements(row["requires"], where),
    mode: oneOf(row["mode"], MODES, `${where} mode`),
    description: row["description"] as string,
    setup: row["setup"] as string,
    action: row["action"] as string,
    assertions: assertions.map((assertion: unknown) => text(assertion, `${where} assertion`)),
    cleanup: row["cleanup"] as string,
    evidence: evidence(row["evidence"] ?? defaults["evidence"], where),
    expected,
    linked_issue: linkedIssue as number | null,
    harness_only: optionalBoolean(row["harness_only"], `${where} harness_only`),
    notes: row["notes"] === undefined ? null : text(row["notes"], `${where} notes`),
  };
}

function requirements(value: unknown, where: string): Requirements {
  const row = record(value, `${where} requires`);
  return {
    trello: booleanField(row["trello"], `${where} requires.trello`),
    codex: oneOf(row["codex"], CODEX_REQUIREMENTS, `${where} requires.codex`),
    github: booleanField(row["github"], `${where} requires.github`),
    network: booleanField(row["network"], `${where} requires.network`),
    hardened_host: optionalBoolean(row["hardened_host"], `${where} requires.hardened_host`),
  };
}

function evidence(value: unknown, where: string): Evidence {
  const row = record(value, `${where} evidence`);
  return {
    public: text(row["public"], `${where} evidence.public`),
    private: text(row["private"], `${where} evidence.private`),
  };
}

function record(value: unknown, where: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ManifestError(`${where} must be a mapping`);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, where: string): string {
  if (typeof value !== "string" || value.trim() === "") {
    throw new ManifestError(`${where} must be non-empty text`);
  }
  return value;
}

function booleanField(value: unknown, where: string): boolean {
  if (typeof value !== "boolean") {
    throw new ManifestError(`${where} must be true or false`);
  }
  return value;
}

function optionalBoolean(value: unknown, where: string): boolean {
  return value === undefined ? false : booleanField(value, where);
}

function oneOf<T>(value: unknown, allowed: readonly T[], where: string): T {
  if (!allowed.includes(value as T)) {
    throw new ManifestError(`${where} must be one of ${allowed.map(String).join(", ")}`);
  }
  return value as T;
}
