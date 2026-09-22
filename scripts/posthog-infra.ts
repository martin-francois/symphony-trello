// API-owned supplements to the OpenTofu configuration under infra/posthog, and the read-only
// verification report. Everything here is narrow on purpose: the official provider owns projects,
// settings it knows, the adopted GeoIP transformation, insights, dashboards, and layouts. This
// file covers only what the provider cannot express (three environment settings), the scoped lookup
// the bootstrap import needs, the dashboard fixture check, retiring an unmanaged project, and the
// report. Nothing here prints or stores a credential.
//
// Usage: node scripts/posthog-infra.ts <command> [--host URL] [--key-file PATH] ...
// scripts/posthog-infra is the normal entry point and sets the environment.

import {randomUUID} from "node:crypto";
import {readFileSync, writeFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";

export const ENVIRONMENT_GAP_SETTINGS = {
  autocapture_opt_out: true,
  capture_console_log_opt_in: false,
  capture_dead_clicks: false,
} as const;

export const EXPECTED_ENVIRONMENT_SETTINGS = {
  timezone: "UTC",
  anonymize_ips: true,
  cookieless_server_hash_mode: 0,
  session_recording_opt_in: false,
  capture_performance_opt_in: false,
  autocapture_exceptions_opt_in: false,
  autocapture_web_vitals_opt_in: false,
  heatmaps_opt_in: false,
  surveys_opt_in: false,
  app_urls: [] as readonly string[],
  recording_domains: [] as readonly string[],
  test_account_filters: [] as readonly unknown[],
  ...ENVIRONMENT_GAP_SETTINGS,
} as const;

export const GEOIP_TEMPLATE_ID = "template-geoip";
export const DESTINATION_TYPES = "destination,site_destination,internal_destination,source_webhook,site_app";
export const HEARTBEAT_EVENT = "installation_heartbeat";
export const DEFAULT_CAPTURE_ENDPOINT = "https://eu.i.posthog.com/i/v0/e/";
const PERSONAL_KEY_PREFIX = "phx_";
const PAGE_LIMIT = 100;
const READ_BACK_ATTEMPTS = 5;
const READ_BACK_DELAY_MS = 2000;
const INGESTION_ATTEMPTS = 20;
const INGESTION_DELAY_MS = 15000;

export type Json = null | boolean | number | string | Json[] | {readonly [key: string]: Json};
type JsonObject = {readonly [key: string]: Json};

export interface Role {
  readonly name: string;
  readonly projectId: number;
  readonly dashboardId: number;
  readonly insights: ReadonlyMap<string, {readonly id: number; readonly name: string}>;
  readonly captureToken: string;
}

export type CheckStatus = "PASS" | "FAIL" | "UNKNOWN" | "INFO";

export interface Check {
  readonly role: string;
  readonly item: string;
  readonly expected: string;
  readonly observed: string;
  readonly status: CheckStatus;
  readonly owner: string;
}

export class InfraError extends Error {}

export interface Sleeper {
  (milliseconds: number): Promise<void>;
}

const realSleep: Sleeper = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

/** Minimal management API client. Messages name the method and path, never the key. */
export class PostHogApi {
  readonly #host: string;
  readonly #key: string;
  readonly #fetch: typeof fetch;

  constructor(host: string, key: string, fetchImpl: typeof fetch = fetch) {
    this.#host = host.replace(/\/+$/, "");
    this.#key = key;
    this.#fetch = fetchImpl;
  }

  async get(path: string): Promise<Json> {
    return this.#send("GET", path);
  }

  async patch(path: string, body: JsonObject): Promise<Json> {
    return this.#send("PATCH", path, body);
  }

  async post(path: string, body: JsonObject): Promise<Json> {
    return this.#send("POST", path, body);
  }

  async delete(path: string): Promise<void> {
    await this.#send("DELETE", path);
  }

  /** Follows `next` links and refuses a listing whose `count` the pages do not add up to. */
  async listAll(path: string): Promise<readonly JsonObject[]> {
    const results: JsonObject[] = [];
    let next: string | null = path;
    let count: number | undefined;
    while (next !== null) {
      const page = asObject(await this.get(next), next);
      const pageResults = page["results"];
      if (!Array.isArray(pageResults)) {
        throw new InfraError(`${next}: listing has no results array`);
      }
      results.push(...pageResults.map((entry) => asObject(entry, next as string)));
      if (typeof page["count"] === "number") {
        count = page["count"];
      }
      const link = page["next"];
      next = typeof link === "string" && link.length > 0 ? link.replace(this.#host, "") : null;
    }
    if (count !== undefined && count !== results.length) {
      throw new InfraError(`${path}: listing reports ${count} entries but the pages held ${results.length}`);
    }
    return results;
  }

  async #send(method: string, path: string, body?: JsonObject): Promise<Json> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.#key}`,
      Accept: "application/json",
      "User-Agent": "symphony-trello-posthog-infra/1",
    };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    let response: Response;
    try {
      response = await this.#fetch(`${this.#host}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        redirect: "manual",
      });
    } catch (error) {
      throw new InfraError(`${method} ${path} failed: ${error instanceof Error ? error.name : "network error"}`);
    }
    if (response.status >= 300 && response.status < 400) {
      throw new InfraError(`${method} ${path} answered a redirect (${response.status}), refused`);
    }
    const text = await response.text();
    if (!response.ok) {
      throw new InfraError(`${method} ${path} answered HTTP ${response.status}: ${detailOf(text)}`);
    }
    if (text.trim() === "") {
      return null;
    }
    try {
      return JSON.parse(text) as Json;
    } catch {
      throw new InfraError(`${method} ${path} answered non-JSON content`);
    }
  }
}

function detailOf(text: string): string {
  try {
    const parsed = JSON.parse(text) as Json;
    if (isObject(parsed) && typeof parsed["detail"] === "string") {
      return parsed["detail"].slice(0, 200);
    }
  } catch {
    // Not JSON; fall through to the raw prefix.
  }
  return text.slice(0, 200);
}

function isObject(value: Json | undefined): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asObject(value: Json, context: string): JsonObject {
  if (!isObject(value)) {
    throw new InfraError(`${context}: expected a JSON object`);
  }
  return value;
}

export function readKey(keyFile: string): string {
  let key: string;
  try {
    key = readFileSync(keyFile, "utf8").trim();
  } catch {
    throw new InfraError(`personal API key file not readable: ${keyFile}`);
  }
  if (!key.startsWith(PERSONAL_KEY_PREFIX)) {
    throw new InfraError(`${keyFile} does not hold a personal API key`);
  }
  return key;
}

/** Parses `tofu output -json` into roles; the file is private because it holds capture tokens. */
export function readRoles(outputsFile: string): readonly Role[] {
  const outputs = asObject(JSON.parse(readFileSync(outputsFile, "utf8")) as Json, outputsFile);
  const projects = asObject(asObject(outputs["projects"] ?? null, "projects")["value"] ?? null, "projects.value");
  const tokens = asObject(asObject(outputs["capture_tokens"] ?? null, "capture_tokens")["value"] ?? null, "capture_tokens.value");
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
    return {
      name,
      projectId: Number(project["id"]),
      dashboardId: Number(project["dashboard_id"]),
      insights,
      captureToken: token,
    };
  });
}

/** The server-created GeoIP transformation of one project, rejecting ambiguity. */
export async function findGeoipFunction(api: PostHogApi, projectId: number): Promise<JsonObject> {
  const transformations = await api.listAll(`/api/projects/${projectId}/hog_functions/?type=transformation&limit=${PAGE_LIMIT}`);
  const matches = transformations.filter((entry) => templateIdOf(entry) === GEOIP_TEMPLATE_ID);
  if (matches.length === 0) {
    throw new InfraError(`project ${projectId}: no ${GEOIP_TEMPLATE_ID} transformation found among ${transformations.length}`);
  }
  if (matches.length > 1) {
    throw new InfraError(`project ${projectId}: ${matches.length} ${GEOIP_TEMPLATE_ID} transformations, refusing to choose`);
  }
  return matches[0] as JsonObject;
}

function templateIdOf(entry: JsonObject): string | undefined {
  if (typeof entry["template_id"] === "string") {
    return entry["template_id"];
  }
  const template = entry["template"];
  return isObject(template) && typeof template["id"] === "string" ? template["id"] : undefined;
}

export interface GapChange {
  readonly role: string;
  readonly setting: keyof typeof ENVIRONMENT_GAP_SETTINGS;
  readonly observed: Json | undefined;
  readonly expected: boolean;
}

/** Settings the provider cannot manage: what differs from the canonical values right now. */
export async function gapDiff(api: PostHogApi, roles: readonly Role[]): Promise<readonly GapChange[]> {
  const changes: GapChange[] = [];
  for (const role of roles) {
    const environment = asObject(await api.get(`/api/environments/${role.projectId}/`), `environment ${role.projectId}`);
    for (const [setting, expected] of Object.entries(ENVIRONMENT_GAP_SETTINGS) as [keyof typeof ENVIRONMENT_GAP_SETTINGS, boolean][]) {
      if (environment[setting] !== expected) {
        changes.push({role: role.name, setting, observed: environment[setting], expected});
      }
    }
  }
  return changes;
}

/** Patches only the differing settings, then reads back until the server shows them. */
export async function gapApply(api: PostHogApi, roles: readonly Role[], sleep: Sleeper = realSleep): Promise<readonly GapChange[]> {
  const changes = await gapDiff(api, roles);
  const byRole = new Map<string, GapChange[]>();
  for (const change of changes) {
    byRole.set(change.role, [...(byRole.get(change.role) ?? []), change]);
  }
  for (const [roleName, roleChanges] of byRole) {
    const role = roles.find((candidate) => candidate.name === roleName) as Role;
    const body: Record<string, boolean> = {};
    for (const change of roleChanges) {
      body[change.setting] = change.expected;
    }
    await api.patch(`/api/environments/${role.projectId}/`, body);
    let remaining = await gapDiff(api, [role]);
    for (let attempt = 1; remaining.length > 0 && attempt < READ_BACK_ATTEMPTS; attempt++) {
      await sleep(READ_BACK_DELAY_MS);
      remaining = await gapDiff(api, [role]);
    }
    if (remaining.length > 0) {
      const names = remaining.map((change) => change.setting).join(", ");
      throw new InfraError(`role ${roleName}: PostHog accepted the update but still reports ${names} unchanged`);
    }
  }
  return changes;
}

export interface VerifyOptions {
  readonly organizationChecks?: boolean;
}

/** Read-only checks; nothing here writes. Nulls and missing fields are UNKNOWN, never PASS. */
export async function verify(api: PostHogApi, roles: readonly Role[], options: VerifyOptions = {}): Promise<readonly Check[]> {
  const checks: Check[] = [];
  for (const role of roles) {
    const environment = asObject(await api.get(`/api/environments/${role.projectId}/`), `environment ${role.projectId}`);
    for (const [setting, expected] of Object.entries(EXPECTED_ENVIRONMENT_SETTINGS)) {
      const observed = environment[setting];
      const owner = setting in ENVIRONMENT_GAP_SETTINGS ? "scripts/posthog-infra gaps" : setting === "timezone" ? "posthog_project" : "posthog_project_settings";
      checks.push(compare(role.name, `environment.${setting}`, expected as Json, observed, owner));
    }
    for (const setting of ["event_retention_months", "events_retention_enforced", "is_demo", "access_control"] as const) {
      const observed = environment[setting];
      checks.push({
        role: role.name,
        item: `environment.${setting}`,
        expected: setting === "is_demo" || setting === "access_control" ? "false" : "read-only, recorded as observed",
        observed: observed === undefined ? "absent" : JSON.stringify(observed),
        status: setting === "is_demo" || setting === "access_control" ? (observed === false ? "PASS" : observed === undefined || observed === null ? "UNKNOWN" : "FAIL") : observed === undefined || observed === null ? "UNKNOWN" : "INFO",
        owner: "provider-managed / read-only",
      });
    }

    const transformations = await api.listAll(`/api/projects/${role.projectId}/hog_functions/?type=transformation&limit=${PAGE_LIMIT}`);
    const geoip = transformations.filter((entry) => templateIdOf(entry) === GEOIP_TEMPLATE_ID);
    const others = transformations.filter((entry) => templateIdOf(entry) !== GEOIP_TEMPLATE_ID);
    checks.push({
      role: role.name,
      item: "transformations.geoip",
      expected: "exactly one, enabled=false",
      observed: geoip.length === 1 ? `one, enabled=${String((geoip[0] as JsonObject)["enabled"])}` : `${geoip.length} functions`,
      status: geoip.length === 1 && (geoip[0] as JsonObject)["enabled"] === false ? "PASS" : "FAIL",
      owner: "posthog_hog_function (adopted by bootstrap)",
    });
    const enabledOthers = others.filter((entry) => entry["enabled"] === true);
    checks.push({
      role: role.name,
      item: "transformations.other",
      expected: "none",
      observed: others.length === 0 ? "none" : `${others.length} (${enabledOthers.length} enabled): ${others.map((entry) => String(entry["name"])).join(", ")}`,
      status: others.length === 0 ? "PASS" : "FAIL",
      owner: "untracked; remove by hand and record why",
    });

    const destinations = await api.listAll(`/api/projects/${role.projectId}/hog_functions/?type=${DESTINATION_TYPES}&limit=${PAGE_LIMIT}`);
    checks.push(countCheck(role.name, "destinations", destinations.length, "untracked; none expected"));
    const batchExports = await api.listAll(`/api/projects/${role.projectId}/batch_exports/?limit=${PAGE_LIMIT}`);
    checks.push(countCheck(role.name, "batch_exports", batchExports.length, "untracked; none expected"));

    const dashboards = await api.listAll(`/api/projects/${role.projectId}/dashboards/?limit=${PAGE_LIMIT}`);
    const managed = dashboards.filter((entry) => entry["id"] === role.dashboardId);
    const sameName = dashboards.filter((entry) => entry["id"] !== role.dashboardId && entry["name"] === (managed[0]?.["name"] ?? ""));
    checks.push({
      role: role.name,
      item: "dashboard.managed",
      expected: `id ${role.dashboardId} present, no duplicate with its name`,
      observed: managed.length === 1 ? `present${sameName.length === 0 ? "" : `, ${sameName.length} duplicate(s)`}` : "missing",
      status: managed.length === 1 && sameName.length === 0 ? "PASS" : "FAIL",
      owner: "posthog_dashboard",
    });
    const untrackedDashboards = dashboards.filter((entry) => entry["id"] !== role.dashboardId);
    checks.push({
      role: role.name,
      item: "dashboard.untracked",
      expected: "listed for review",
      observed: untrackedDashboards.length === 0 ? "none" : untrackedDashboards.map((entry) => `${String(entry["name"])} (${String(entry["id"])})`).join(", "),
      status: "INFO",
      owner: "untracked (PostHog creates a starter dashboard per project)",
    });
    const dashboard = asObject(await api.get(`/api/projects/${role.projectId}/dashboards/${role.dashboardId}/`), "dashboard");
    const tiles = Array.isArray(dashboard["tiles"]) ? dashboard["tiles"].map((tile) => asObject(tile, "tile")) : [];
    const tileInsightIds = tiles.map((tile) => (isObject(tile["insight"]) ? Number(tile["insight"]["id"]) : NaN));
    const expectedIds = [...role.insights.values()].map((insight) => insight.id).sort((a, b) => a - b);
    const observedIds = [...tileInsightIds].sort((a, b) => a - b);
    checks.push({
      role: role.name,
      item: "dashboard.tiles",
      expected: `${expectedIds.length} tiles, one per panel`,
      observed: `${tiles.length} tiles${sameSet(expectedIds, observedIds) ? "" : ", insight set differs"}`,
      status: sameSet(expectedIds, observedIds) && tiles.length === expectedIds.length ? "PASS" : "FAIL",
      owner: "posthog_insight.dashboard_ids + posthog_dashboard_layout",
    });
    const sharing = asObject(await api.get(`/api/projects/${role.projectId}/dashboards/${role.dashboardId}/sharing/`), "sharing");
    checks.push(compare(role.name, "dashboard.sharing.enabled", false, sharing["enabled"], "read-only; never enabled"));
    checks.push(compare(role.name, "dashboard.is_shared", false, dashboard["is_shared"], "read-only; never enabled"));

    const insights = await api.listAll(`/api/projects/${role.projectId}/insights/?limit=${PAGE_LIMIT}&saved=true`);
    const managedNames = new Map<string, number>();
    for (const insight of insights) {
      if (typeof insight["name"] === "string" && [...role.insights.values()].some((expected) => expected.name === insight["name"])) {
        managedNames.set(insight["name"], (managedNames.get(insight["name"]) ?? 0) + 1);
      }
    }
    const duplicates = [...managedNames.entries()].filter(([, count]) => count > 1).map(([name]) => name);
    const missing = [...role.insights.values()].filter((expected) => !managedNames.has(expected.name)).map((expected) => expected.name);
    checks.push({
      role: role.name,
      item: "insights.managed",
      expected: `${role.insights.size} panels, each once`,
      observed: `${managedNames.size} present${duplicates.length === 0 ? "" : `, duplicated: ${duplicates.join(", ")}`}${missing.length === 0 ? "" : `, missing: ${missing.join(", ")}`}`,
      status: duplicates.length === 0 && missing.length === 0 ? "PASS" : "FAIL",
      owner: "posthog_insight",
    });
  }
  if (options.organizationChecks !== false) {
    const organizations = await api.listAll(`/api/organizations/?limit=${PAGE_LIMIT}`);
    for (const organization of organizations) {
      const name = "organization";
      checks.push({
        role: name,
        item: "organization.is_ai_training_opted_in",
        expected: "false (shared control, affects every project)",
        observed: organization["is_ai_training_opted_in"] === undefined ? "absent" : JSON.stringify(organization["is_ai_training_opted_in"]),
        status: organization["is_ai_training_opted_in"] === false ? "PASS" : organization["is_ai_training_opted_in"] === undefined ? "UNKNOWN" : "FAIL",
        owner: "manual, organization settings",
      });
      checks.push({
        role: name,
        item: "organization.allow_publicly_shared_resources",
        expected: "recorded; sharing stays off per dashboard",
        observed: JSON.stringify(organization["allow_publicly_shared_resources"] ?? null),
        status: organization["allow_publicly_shared_resources"] === undefined ? "UNKNOWN" : "INFO",
        owner: "manual, organization settings",
      });
    }
  }
  return checks;
}

function compare(role: string, item: string, expected: Json, observed: Json | undefined, owner: string): Check {
  const observedText = observed === undefined ? "absent" : JSON.stringify(observed);
  const status: CheckStatus = observed === undefined || observed === null ? "UNKNOWN" : JSON.stringify(observed) === JSON.stringify(expected) ? "PASS" : "FAIL";
  return {role, item, expected: JSON.stringify(expected), observed: observedText, status, owner};
}

function countCheck(role: string, item: string, count: number, owner: string): Check {
  return {role, item, expected: "0", observed: String(count), status: count === 0 ? "PASS" : "FAIL", owner};
}

function sameSet(left: readonly number[], right: readonly number[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

export function renderReport(checks: readonly Check[], context: {readonly observedAt: string; readonly configRevision: string; readonly tofuVersion: string; readonly providerVersion: string}): string {
  const lines = [
    "# PostHog configuration verification",
    "",
    `Observed at ${context.observedAt} (UTC) with OpenTofu ${context.tofuVersion} and provider posthog/posthog ${context.providerVersion}; configuration revision ${context.configRevision}.`,
    "",
    "A maintainer-generated point-in-time read-back of the deployed configuration. It shows what the API reported when asked; it is not proof that a setting cannot be changed later, that historical data was physically erased, or that PostHog's own infrastructure never sees a source address.",
    "",
    "| Role | Item | Expected | Observed | Status | Owner |",
    "| --- | --- | --- | --- | --- | --- |",
  ];
  for (const check of checks) {
    lines.push(`| ${check.role} | ${check.item} | ${cell(check.expected)} | ${cell(check.observed)} | ${check.status} | ${cell(check.owner)} |`);
  }
  const counts = {PASS: 0, FAIL: 0, UNKNOWN: 0, INFO: 0};
  for (const check of checks) {
    counts[check.status]++;
  }
  lines.push("", `Totals: ${counts.PASS} pass, ${counts.FAIL} fail, ${counts.UNKNOWN} unknown, ${counts.INFO} informational.`, "");
  return lines.join("\n");
}

function cell(text: string): string {
  return text.replace(/\|/g, "\\|").replace(/\n/g, " ");
}

// Dashboard fixture: the documented synthetic installations, sent to one role and checked panel
// by panel with the canonical query files.

interface FixtureReport {
  readonly days_ago: number;
  readonly time: string;
  readonly properties?: Readonly<Record<string, Json>>;
}

interface FixtureInstallation {
  readonly distinct_id: string;
  readonly registered_days_ago: number;
  readonly properties: Readonly<Record<string, Json>>;
  readonly reports: readonly FixtureReport[];
}

interface Fixture {
  readonly release_version: string;
  readonly release_date_days_ago: number;
  readonly installations: readonly FixtureInstallation[];
  readonly expected: Readonly<Record<string, Json>>;
}

export function fixtureEvents(fixture: Fixture, token: string, today: Date): readonly JsonObject[] {
  const events: JsonObject[] = [];
  for (const installation of fixture.installations) {
    const registeredOn = isoDate(daysBefore(today, installation.registered_days_ago));
    for (const report of installation.reports) {
      const properties: Record<string, Json> = {
        telemetry_schema_version: 1,
        registered_on: registeredOn,
        ...installation.properties,
        ...(report.properties ?? {}),
        $geoip_disable: true,
        $process_person_profile: true,
      };
      events.push({
        api_key: token,
        event: HEARTBEAT_EVENT,
        distinct_id: installation.distinct_id,
        uuid: randomUUID(),
        timestamp: `${isoDate(daysBefore(today, report.days_ago))}T${report.time}:00Z`,
        properties,
      });
    }
  }
  return events;
}

function daysBefore(date: Date, days: number): Date {
  return new Date(date.getTime() - days * 86_400_000);
}

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function renderQuery(sql: string, values: Readonly<Record<string, string>>): string {
  return sql.replace(/\$\{(\w+)\}/g, (_match, name: string) => {
    const value = values[name];
    if (value === undefined) {
      throw new InfraError(`query template needs ${name}`);
    }
    return value;
  });
}

export async function runQuery(api: PostHogApi, projectId: number, sql: string): Promise<readonly Json[]> {
  const response = asObject(
    await api.post(`/api/projects/${projectId}/query/`, {query: {kind: "HogQLQuery", query: sql}, refresh: "force_blocking"}),
    "query",
  );
  if (response["is_cached"] === true) {
    throw new InfraError("query answered from cache despite refresh=force_blocking");
  }
  const results = response["results"];
  if (!Array.isArray(results)) {
    throw new InfraError("query answered without results");
  }
  return results;
}

export interface PanelOutcome {
  readonly panel: string;
  readonly expected: string;
  readonly observed: string;
  readonly pass: boolean;
}

export function comparePanel(panel: string, expected: Json, rows: readonly Json[]): PanelOutcome {
  if (isObject(expected) && "row_count" in expected) {
    const total = rows.reduce<number>((sum, row) => sum + (Array.isArray(row) ? Number(row[row.length - 1]) : 0), 0);
    const pass = rows.length === expected["row_count"] && total === expected["installations_total"];
    return {panel, expected: JSON.stringify(expected), observed: `${rows.length} rows, ${total} installations`, pass};
  }
  if (isObject(expected) && "after_0915_utc" in expected) {
    const alternatives = [expected["after_0915_utc"], expected["before_0915_utc"]];
    const pass = alternatives.some((alternative) => sameRows(alternative as Json[], rows));
    return {panel, expected: alternatives.map((alternative) => JSON.stringify(alternative)).join(" or "), observed: JSON.stringify(rows), pass};
  }
  return {panel, expected: JSON.stringify(expected), observed: JSON.stringify(rows), pass: sameRows(expected as Json[], rows)};
}

function sameRows(expected: readonly Json[], observed: readonly Json[]): boolean {
  const normalize = (rows: readonly Json[]) => rows.map((row) => JSON.stringify(row)).sort();
  const left = normalize(expected);
  const right = normalize(observed);
  return left.length === right.length && left.every((row, index) => row === right[index]);
}

// Command line.

interface Options {
  readonly command: string;
  readonly flags: ReadonlyMap<string, string>;
}

function parseArguments(argv: readonly string[]): Options {
  const [command = "", ...rest] = argv;
  const flags = new Map<string, string>();
  for (let index = 0; index < rest.length; index++) {
    const argument = rest[index] as string;
    if (argument.startsWith("--")) {
      const value = rest[index + 1];
      if (value === undefined || value.startsWith("--")) {
        flags.set(argument.slice(2), "true");
      } else {
        flags.set(argument.slice(2), value);
        index++;
      }
    } else if (!flags.has("subcommand")) {
      flags.set("subcommand", argument);
    }
  }
  return {command, flags};
}

function requireFlag(options: Options, name: string): string {
  const value = options.flags.get(name);
  if (value === undefined) {
    throw new InfraError(`--${name} is required`);
  }
  return value;
}

export async function main(argv: readonly string[], environment: NodeJS.ProcessEnv, sleep: Sleeper = realSleep): Promise<number> {
  const options = parseArguments(argv);
  const host = options.flags.get("host") ?? environment["POSTHOG_HOST"] ?? "https://eu.posthog.com";
  const key = environment["POSTHOG_API_KEY"] ?? readKey(options.flags.get("key-file") ?? join(environment["HOME"] ?? ".", "posthog-personal-api-key"));
  const api = new PostHogApi(host, key);
  switch (options.command) {
    case "geoip-id": {
      const found = await findGeoipFunction(api, Number(requireFlag(options, "project")));
      process.stdout.write(`${String(found["id"])}\n`);
      return 0;
    }
    case "gaps": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const mode = options.flags.get("subcommand");
      if (mode === "diff") {
        const changes = await gapDiff(api, roles);
        for (const change of changes) {
          console.log(`${change.role}: ${change.setting} is ${JSON.stringify(change.observed)}, expected ${String(change.expected)}`);
        }
        console.log(changes.length === 0 ? "gaps: no changes" : `gaps: ${changes.length} change(s) needed`);
        return changes.length === 0 ? 0 : 2;
      }
      if (mode === "apply") {
        const applied = await gapApply(api, roles, sleep);
        console.log(applied.length === 0 ? "gaps: nothing to apply" : `gaps: applied ${applied.map((change) => `${change.role}.${change.setting}`).join(", ")} and read them back`);
        return 0;
      }
      throw new InfraError("gaps needs diff or apply");
    }
    case "verify": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const checks = await verify(api, roles);
      const report = renderReport(checks, {
        observedAt: new Date().toISOString(),
        configRevision: options.flags.get("config-revision") ?? "unknown",
        tofuVersion: options.flags.get("tofu-version") ?? "unknown",
        providerVersion: options.flags.get("provider-version") ?? "unknown",
      });
      const reportPath = options.flags.get("report");
      if (reportPath !== undefined) {
        writeFileSync(reportPath, report);
      } else {
        console.log(report);
      }
      const failures = checks.filter((check) => check.status === "FAIL");
      for (const failure of failures) {
        console.error(`FAIL ${failure.role} ${failure.item}: expected ${failure.expected}, observed ${failure.observed}`);
      }
      return failures.length === 0 ? 0 : 1;
    }
    case "fixture": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const roleName = requireFlag(options, "role");
      const role = roles.find((candidate) => candidate.name === roleName);
      if (role === undefined) {
        throw new InfraError(`no role ${roleName} in outputs`);
      }
      if (roleName === "production") {
        throw new InfraError("the fixture is never sent to the production role");
      }
      const fixturePath = options.flags.get("fixture") ?? join(infraDir(), "fixtures", "dashboard-fixture.json");
      const fixture = JSON.parse(readFileSync(fixturePath, "utf8")) as Fixture;
      const captureEndpoint = options.flags.get("capture") ?? DEFAULT_CAPTURE_ENDPOINT;
      const today = new Date();
      const events = fixtureEvents(fixture, role.captureToken, today);
      for (const event of events) {
        const response = await fetch(captureEndpoint, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(event)});
        if (!response.ok) {
          throw new InfraError(`capture answered HTTP ${response.status}`);
        }
      }
      console.log(`fixture: sent ${events.length} events to role ${roleName}`);
      const distinctIds = fixture.installations.map((installation) => `'${installation.distinct_id}'`).join(", ");
      const countSql = `SELECT count() FROM events WHERE event = '${HEARTBEAT_EVENT}' AND distinct_id IN (${distinctIds}) AND timestamp >= now() - interval 400 day`;
      let ingested = 0;
      for (let attempt = 1; attempt <= INGESTION_ATTEMPTS; attempt++) {
        const rows = await runQuery(api, role.projectId, countSql);
        ingested = Number((rows[0] as Json[])[0]);
        if (ingested >= events.length) {
          break;
        }
        await sleep(INGESTION_DELAY_MS);
      }
      if (ingested < events.length) {
        throw new InfraError(`fixture: only ${ingested} of ${events.length} events queryable after waiting`);
      }
      const releaseDate = isoDate(daysBefore(today, fixture.release_date_days_ago));
      let failures = 0;
      for (const [panel, expected] of Object.entries(fixture.expected)) {
        const sql = panel === "release-adoption-and-delay"
          ? renderQuery(readFileSync(join(infraDir(), "queries", `${panel}.sql.tftpl`), "utf8"), {release_date: releaseDate, release_version: fixture.release_version})
          : readFileSync(join(infraDir(), "queries", `${panel}.sql`), "utf8");
        const outcome = comparePanel(panel, expected, await runQuery(api, role.projectId, sql));
        console.log(`${outcome.pass ? "PASS" : "FAIL"} ${panel}: ${outcome.observed}${outcome.pass ? "" : ` (expected ${outcome.expected})`}`);
        if (!outcome.pass) {
          failures++;
        }
      }
      return failures === 0 ? 0 : 1;
    }
    case "retire-project": {
      const id = Number(requireFlag(options, "id"));
      const name = requireFlag(options, "name");
      const project = asObject(await api.get(`/api/projects/${id}/`), `project ${id}`);
      if (project["name"] !== name) {
        throw new InfraError(`project ${id} is named ${JSON.stringify(project["name"])}, not ${JSON.stringify(name)}; refusing`);
      }
      const organization = String(project["organization"]);
      await api.delete(`/api/organizations/${organization}/projects/${id}/`);
      console.log(`retire-project: deletion of project ${id} (${name}) requested`);
      return 0;
    }
    default:
      throw new InfraError(`unknown command ${JSON.stringify(options.command)}`);
  }
}

function infraDir(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "..", "infra", "posthog");
}

if (process.argv[1] !== undefined && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv.slice(2), process.env)
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      console.error(`posthog-infra: ${error instanceof Error ? error.message : String(error)}`);
      process.exitCode = 1;
    });
}
