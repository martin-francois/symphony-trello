// Readers for the local files scripts/posthog-infra consults before or around OpenTofu: the backend
// record, `tofu show -json`, `tofu version -json`, the outputs file and the roles it describes, the
// committed production project id, and the lifecycle ledger. None of them needs the personal API
// key, so the wrapper calls the ones it uses without credentials.

import {readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import {authorizedHandlerSha256, type LifecycleOutcome} from "./erasure-lifecycle-ledger.ts";
import {asObject, InfraError, type Json, type JsonObject} from "./posthog-api.ts";

interface BackendRecord {
  readonly backend?: {readonly config?: {readonly path?: string}};
}

interface ShowResource {
  readonly address: string;
  readonly values: {readonly id: unknown};
}

interface Show {
  readonly values?: {readonly root_module?: {readonly child_modules?: readonly {readonly resources?: readonly ShowResource[]}[]}};
}

interface Outputs {
  readonly projects: {readonly value: Readonly<Record<string, {readonly id: unknown}>>};
  readonly capture_tokens: {readonly value: {readonly production: string}};
}

export class StateFileError extends Error {}

/** The state path OpenTofu recorded at init, or empty when the record names none. */
export function boundStatePath(record: BackendRecord): string {
  return String(record.backend?.config?.path || "");
}

/** A role's project id straight from the state; outputs are not complete until the full apply. */
export function projectIdFromShow(show: Show, role: string): string {
  const address = `module.${role}.posthog_project.this`;
  const modules = show.values?.root_module?.child_modules ?? [];
  const resource = modules.flatMap((module) => module.resources ?? []).find((entry) => entry.address === address);
  if (resource === undefined) {
    throw new StateFileError(`no ${address} in state`);
  }
  return String(resource.values.id);
}

/** The OpenTofu argument that passes the lifecycle pass on, or none when the ledger grants none. */
export function lifecyclePassArguments(ledger: LifecycleOutcome): readonly string[] {
  const sha256 = authorizedHandlerSha256(ledger);
  return sha256 === undefined ? [] : [`-var=erasure_lifecycle_pass=${sha256}`];
}

export function tofuVersion(version: {readonly terraform_version: string}): string {
  return version.terraform_version;
}

export function productionProjectId(outputs: Outputs): string {
  const production = outputs.projects.value["production"];
  if (production === undefined) {
    throw new StateFileError("outputs carry no production project");
  }
  return String(production.id);
}

export function productionCaptureToken(outputs: Outputs): string {
  return outputs.capture_tokens.value.production;
}

/** Whether a project id belongs to one of the roles in the outputs. */
export function managesProject(outputs: Outputs, projectId: string): boolean {
  return Object.values(outputs.projects.value).some((project) => String(project.id) === projectId);
}

/** Reads a JSON file, or standard input for `-`. */
export function readJson<T>(path: string): T {
  return JSON.parse(readFileSync(path === "-" ? 0 : path, "utf8")) as T;
}

/** Settings the OpenTofu definition exports per role (`privacy_policy` output); the verifier reads
 * the expected values from there so the definition stays the single source. */
export const POLICY_SETTINGS = [
  "timezone",
  "anonymize_ips",
  "cookieless_server_hash_mode",
  "session_recording_opt_in",
  "capture_performance_opt_in",
  "autocapture_exceptions_opt_in",
  "autocapture_web_vitals_opt_in",
  "heatmaps_opt_in",
  "surveys_opt_in",
  "app_urls",
  "recording_domains",
  "test_account_filters",
] as const;

export interface Role {
  readonly name: string;
  readonly projectId: number;
  readonly dashboardId: number;
  readonly insights: ReadonlyMap<string, {readonly id: number; readonly name: string}>;
  readonly captureToken: string;
  /** Declared privacy settings from the definition, including `geoip_enabled`. */
  readonly policy: JsonObject;
}

/** Parses `tofu output -json` into roles; the file is private because it holds capture tokens. */
export function readRoles(outputsFile: string): readonly Role[] {
  const outputs = asObject(JSON.parse(readFileSync(outputsFile, "utf8")) as Json, outputsFile);
  const projects = asObject(asObject(outputs["projects"] ?? null, "projects")["value"] ?? null, "projects.value");
  const tokens = asObject(asObject(outputs["capture_tokens"] ?? null, "capture_tokens")["value"] ?? null, "capture_tokens.value");
  const policies = asObject(asObject(outputs["privacy_policy"] ?? null, "privacy_policy")["value"] ?? null, "privacy_policy.value");
  return Object.entries(projects).map(([name, value]) => {
    const project = asObject(value, `projects.${name}`);
    const insights = new Map<string, {id: number; name: string}>();
    for (const [key, entry] of Object.entries(asObject(project["insights"] ?? null, `projects.${name}.insights`))) {
      const insight = asObject(entry, key);
      insights.set(key, {id: Number(insight["id"]), name: String(insight["name"])});
    }
    const token = tokens[name];
    if (typeof token !== "string" || token === "") {
      throw new InfraError(`outputs carry no capture token for role ${name}`);
    }
    const policy = asObject(policies[name] ?? null, `privacy_policy.${name}`);
    for (const setting of [...POLICY_SETTINGS, "geoip_enabled"]) {
      if (!(setting in policy)) {
        throw new InfraError(`privacy_policy.${name} lacks ${setting}`);
      }
    }
    return {
      name,
      projectId: Number(project["id"]),
      dashboardId: Number(project["dashboard_id"]),
      insights,
      captureToken: token,
      policy,
    };
  });
}

/** The production project number committed in infra/posthog/production-project-id. */
export function readProductionProjectId(): number {
  const text = readFileSync(join(infraDir(), "production-project-id"), "utf8").trim();
  if (!/^[1-9][0-9]*$/.test(text)) {
    throw new InfraError("infra/posthog/production-project-id must hold one project number");
  }
  return Number(text);
}

/** infra/posthog, found from this file's location. */
export function infraDir(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "..", "infra", "posthog");
}
