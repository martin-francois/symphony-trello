// Readers for the local files scripts/posthog-infra consults before or around OpenTofu: the backend
// record, `tofu show -json`, `tofu version -json`, the outputs file, and the lifecycle ledger.
// None of them needs the personal API key, so the wrapper calls them without credentials.

import {readFileSync} from "node:fs";
import {authorizedHandlerSha256, type LifecycleOutcome} from "./erasure-lifecycle-ledger.ts";

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
