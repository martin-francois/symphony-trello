// API-owned supplements to the OpenTofu configuration under infra/posthog, and the read-only
// verification report. Everything here is narrow on purpose: the official provider owns projects,
// settings it knows, the adopted GeoIP transformation, insights, dashboards, and layouts. This
// file covers only what the provider cannot express (three environment settings), the scoped lookup
// the bootstrap import needs, the dashboard fixture check, retiring an unmanaged project, and the
// report. Nothing here prints or stores a credential.
//
// Usage: node scripts/posthog-infra.ts <command> [--host URL] [--key-file PATH] ...
// scripts/posthog-infra is the normal entry point and sets the environment.

import {createHash, randomUUID} from "node:crypto";
import {existsSync, readFileSync, writeFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";

export const ENVIRONMENT_GAP_SETTINGS = {
  autocapture_opt_out: true,
  capture_console_log_opt_in: false,
  capture_dead_clicks: false,
} as const;

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

export const GEOIP_TEMPLATE_ID = "template-geoip";
export const DESTINATION_TYPES = "destination,site_destination,internal_destination,source_webhook,site_app";
export const HEARTBEAT_EVENT = "installation_heartbeat";
export const DEFAULT_CAPTURE_ENDPOINT = "https://eu.i.posthog.com/i/v0/e/";
const PERSONAL_KEY_PREFIX = "phx_";
const PAGE_LIMIT = 100;
const MAX_PAGES = 50;
const REQUEST_TIMEOUT_MS = 60_000;
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
const REDACTED = "[redacted]";
const READ_BACK_ATTEMPTS = 5;
const READ_BACK_DELAY_MS = 2000;
const INGESTION_ATTEMPTS = 20;
const INGESTION_DELAY_MS = 15000;
const DAY_MS = 24 * 60 * 60 * 1000;
/** Clients read their result within hours. An archived empty or complete flag is recreated by the
 * owner's next request, so archiving one never strands a client; the age only avoids churn. */
export const ERASURE_FLAG_MIN_AGE_MS = DAY_MS;
/** A pending or accepted operation this old has lost its client or is stuck at PostHog. */
export const ERASURE_FLAG_STALE_MS = 14 * DAY_MS;
/** Well below PostHog's limit of 2,000 non-deleted flags per project. */
export const ERASURE_FLAG_WARNING = 500;
const RANDOM_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const PERIOD_ANALYTICS_ID = new RegExp(`^${RANDOM_UUID}\\.${RANDOM_UUID}$`);

export type Json = null | boolean | number | string | Json[] | {readonly [key: string]: Json};
type JsonObject = {readonly [key: string]: Json};

export interface Role {
  readonly name: string;
  readonly projectId: number;
  readonly dashboardId: number;
  readonly insights: ReadonlyMap<string, {readonly id: number; readonly name: string}>;
  readonly captureToken: string;
  /** Declared privacy settings from the definition, including `geoip_enabled`. */
  readonly policy: JsonObject;
}

export type CheckStatus = "PASS" | "FAIL" | "UNKNOWN" | "INFO";

/** `required` checks decide the outcome: a required FAIL is drift, a required UNKNOWN is
 * incomplete evidence, and neither is a verified deployment. Advisory checks are reported only. */
export interface Check {
  readonly role: string;
  readonly item: string;
  readonly expected: string;
  readonly observed: string;
  readonly status: CheckStatus;
  readonly owner: string;
  readonly required: boolean;
}

export type Outcome = "verified" | "drift" | "incomplete";

export function outcomeOf(checks: readonly Check[]): Outcome {
  if (checks.some((check) => check.status === "FAIL")) {
    return "drift";
  }
  if (checks.some((check) => check.required && check.status === "UNKNOWN")) {
    return "incomplete";
  }
  return "verified";
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

  /** The key never appears in anything this client returns or throws, even when a server echoes it. */
  #redact(text: string): string {
    return this.#key === "" ? text : text.split(this.#key).join(REDACTED);
  }

  /** Follows `next` links, bounded by page count and by never revisiting a link, and refuses a
   * listing whose `count` the pages do not add up to. */
  async listAll(path: string): Promise<readonly JsonObject[]> {
    const results: JsonObject[] = [];
    const visited = new Set<string>();
    let next: string | null = path;
    let count: number | undefined;
    while (next !== null) {
      if (visited.has(next)) {
        throw new InfraError(`${path}: pagination repeats ${next}`);
      }
      if (visited.size >= MAX_PAGES) {
        throw new InfraError(`${path}: more than ${MAX_PAGES} pages, refusing`);
      }
      visited.add(next);
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
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (error) {
      throw new InfraError(`${method} ${path} failed: ${error instanceof Error ? error.name : "network error"}`);
    }
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      throw new InfraError(`${method} ${path} answered a redirect (${response.status}), refused`);
    }
    const text = this.#redact(await readBounded(response, `${method} ${path}`));
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

/** Reads at most MAX_RESPONSE_BYTES within the request's deadline; more is refused, not truncated. */
export async function readBounded(response: Response, context: string): Promise<string> {
  const reader = response.body?.getReader();
  if (reader === undefined) {
    return "";
  }
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const {done, value} = await reader.read();
      if (done) {
        break;
      }
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        throw new InfraError(`${context} answered more than ${MAX_RESPONSE_BYTES} bytes, refused`);
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof InfraError) {
      throw error;
    }
    throw new InfraError(`${context} body failed: ${error instanceof Error ? error.name : "read error"}`);
  } finally {
    await reader.cancel().catch(() => undefined);
  }
  return Buffer.concat(chunks).toString("utf8");
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

/** Read-only checks; nothing here writes. A required value that is null, absent, or of the wrong
 * type is UNKNOWN, which keeps the deployment unverified; only advisory items may stay unknown. */
export async function verify(api: PostHogApi, roles: readonly Role[], productionProjectId?: number): Promise<readonly Check[]> {
  const checks: Check[] = [];
  const organizations = new Map<string, string[]>();
  for (const role of roles) {
    const project = asObject(await api.get(`/api/projects/${role.projectId}/`), `project ${role.projectId}`);
    if (role.name === "production" && productionProjectId !== undefined) {
      // Release packaging checks the erasure audience against the same file.
      checks.push(compare(role.name, "project.id", productionProjectId, role.projectId, "infra/posthog/production-project-id", true));
    }
    const organization = typeof project["organization"] === "string" ? project["organization"] : "";
    organizations.set(organization, [...(organizations.get(organization) ?? []), role.name]);
    const environment = asObject(await api.get(`/api/environments/${role.projectId}/`), `environment ${role.projectId}`);
    for (const setting of POLICY_SETTINGS) {
      checks.push(compare(role.name, `environment.${setting}`, role.policy[setting] as Json, environment[setting], setting === "timezone" ? "posthog_project" : "posthog_project_settings", true));
    }
    for (const [setting, expected] of Object.entries(ENVIRONMENT_GAP_SETTINGS)) {
      checks.push(compare(role.name, `environment.${setting}`, expected, environment[setting], "scripts/posthog-infra gaps", true));
    }
    checks.push(compare(role.name, "environment.is_demo", false, environment["is_demo"], "provider-managed / read-only", true));
    checks.push(compare(role.name, "environment.access_control", false, environment["access_control"], "provider-managed / read-only", true));
    for (const setting of ["event_retention_months", "events_retention_enforced"] as const) {
      const observed = environment[setting];
      checks.push({
        role: role.name,
        item: `environment.${setting}`,
        expected: "advisory: recorded as observed, no retention resource exists",
        observed: observed === undefined ? "absent" : JSON.stringify(observed),
        status: observed === undefined || observed === null ? "UNKNOWN" : "INFO",
        owner: "provider-managed / read-only",
        required: false,
      });
    }

    const transformations = await api.listAll(`/api/projects/${role.projectId}/hog_functions/?type=transformation&limit=${PAGE_LIMIT}`);
    const geoip = transformations.filter((entry) => templateIdOf(entry) === GEOIP_TEMPLATE_ID);
    const filterId = role.policy["erasure_filter_id"];
    const serviceId = role.policy["erasure_function_id"];
    const others = transformations.filter((entry) => templateIdOf(entry) !== GEOIP_TEMPLATE_ID && entry["id"] !== filterId);
    if (typeof filterId === "string") {
      const filters = transformations.filter(entry => entry["id"] === filterId);
      const expected = readFileSync(new URL("../infra/posthog/merge-filter.hog", import.meta.url), "utf8").trim();
      checks.push(compare(role.name, "erasure.merge_filter", true,
        filters.length === 1 && filters[0]?.["enabled"] === true && String(filters[0]?.["hog"]).trim() === expected,
        "posthog_hog_function", true));
    }
    const geoipExpected = role.policy["geoip_enabled"];
    checks.push({
      role: role.name,
      item: "transformations.geoip",
      expected: `exactly one, enabled=${String(geoipExpected)}`,
      observed: geoip.length === 1 ? `one, enabled=${String((geoip[0] as JsonObject)["enabled"])}` : `${geoip.length} functions`,
      status: geoip.length === 1 && typeof (geoip[0] as JsonObject)["enabled"] === "boolean" ? ((geoip[0] as JsonObject)["enabled"] === geoipExpected ? "PASS" : "FAIL") : geoip.length === 1 ? "UNKNOWN" : "FAIL",
      owner: "posthog_hog_function (adopted by bootstrap)",
      required: true,
    });
    const enabledOthers = others.filter((entry) => entry["enabled"] === true);
    checks.push({
      role: role.name,
      item: "transformations.other",
      expected: "none",
      observed: others.length === 0 ? "none" : `${others.length} (${enabledOthers.length} enabled): ${others.map((entry) => String(entry["name"])).join(", ")}`,
      status: others.length === 0 ? "PASS" : "FAIL",
      owner: "untracked; remove by hand and record why",
      required: true,
    });

    const destinations = await api.listAll(`/api/projects/${role.projectId}/hog_functions/?type=${DESTINATION_TYPES}&limit=${PAGE_LIMIT}`);
    if (typeof serviceId === "string") {
      const services = destinations.filter(entry => entry["id"] === serviceId);
      const service = services[0];
      const hash = createHash("sha256").update(String(service?.["hog"]).trim()).digest("hex");
      checks.push(compare(role.name, "erasure.source", true,
        services.length === 1 && service?.["enabled"] === true && service?.["type"] === "source_webhook"
          && hash === role.policy["erasure_hog_sha256"], "posthog_hog_function", true));
      const flags = await erasureFlagList(api, role.projectId);
      checks.push({
        role: role.name,
        item: "erasure.status_flags",
        expected: `fewer than ${ERASURE_FLAG_WARNING} non-deleted`,
        observed: String(flags.length),
        status: flags.length < ERASURE_FLAG_WARNING ? "PASS" : "FAIL",
        owner: "scripts/posthog-infra erasure-flags",
        required: false,
      });
    }
    checks.push(countCheck(role.name, "destinations", destinations.filter(entry => entry["id"] !== serviceId).length, "untracked; none expected"));
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
      required: true,
    });
    const untrackedDashboards = dashboards.filter((entry) => entry["id"] !== role.dashboardId);
    checks.push({
      role: role.name,
      item: "dashboard.untracked",
      expected: "listed for review",
      observed: untrackedDashboards.length === 0 ? "none" : untrackedDashboards.map((entry) => `${String(entry["name"])} (${String(entry["id"])})`).join(", "),
      status: "INFO",
      owner: "untracked (PostHog creates a starter dashboard per project)",
      required: false,
    });
    // Sharing is checked on every dashboard of the project, managed or not: an untracked public
    // dashboard exposes the same data as a managed one would.
    for (const entry of dashboards) {
      const id = Number(entry["id"]);
      const sharing = asObject(await api.get(`/api/projects/${role.projectId}/dashboards/${id}/sharing/`), `sharing of dashboard ${id}`);
      checks.push(compare(role.name, `dashboard.${id}.sharing.enabled`, false, sharing["enabled"], "read-only; never enabled", true));
      checks.push(compare(role.name, `dashboard.${id}.is_shared`, false, entry["is_shared"], "read-only; never enabled", true));
    }
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
      required: true,
    });

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
      required: true,
    });
    // Insights have their own public-link control, independent of the dashboards they sit on.
    const sharedInsights: string[] = [];
    let unknownInsightSharing = 0;
    for (const insight of insights) {
      const sharing = asObject(await api.get(`/api/projects/${role.projectId}/insights/${String(insight["id"])}/sharing/`), `sharing of insight ${String(insight["id"])}`);
      if (sharing["enabled"] === true) {
        sharedInsights.push(String(insight["name"]));
      } else if (sharing["enabled"] !== false) {
        unknownInsightSharing++;
      }
    }
    checks.push({
      role: role.name,
      item: "insights.sharing.enabled",
      expected: "false on every insight",
      observed: sharedInsights.length === 0 ? (unknownInsightSharing === 0 ? `false on ${insights.length} insights` : `${unknownInsightSharing} insight(s) without a readable sharing state`) : `enabled: ${sharedInsights.join(", ")}`,
      status: sharedInsights.length > 0 ? "FAIL" : unknownInsightSharing > 0 ? "UNKNOWN" : "PASS",
      owner: "read-only; never enabled",
      required: true,
    });
  }
  // Only the organization that owns the selected projects is read; other organizations the key
  // can reach are unrelated to this deployment.
  if (organizations.size !== 1) {
    checks.push({
      role: "organization",
      item: "organization.single",
      expected: "both roles in one organization",
      observed: [...organizations.entries()].map(([id, names]) => `${id === "" ? "unknown" : id.slice(0, 8)}: ${names.join(", ")}`).join("; "),
      status: "FAIL",
      owner: "posthog_project.organization_id",
      required: true,
    });
  }
  for (const organizationId of organizations.keys()) {
    if (organizationId === "") {
      continue;
    }
    const organization = asObject(await api.get(`/api/organizations/${organizationId}/`), "organization");
    checks.push({
      role: "organization",
      item: "organization.is_ai_training_opted_in",
      expected: "false (shared control, affects every project of the organization)",
      observed: organization["is_ai_training_opted_in"] === undefined ? "absent" : JSON.stringify(organization["is_ai_training_opted_in"]),
      status: organization["is_ai_training_opted_in"] === false ? "PASS" : organization["is_ai_training_opted_in"] === undefined || organization["is_ai_training_opted_in"] === null ? "UNKNOWN" : "FAIL",
      owner: "manual, organization settings",
      required: false,
    });
    checks.push({
      role: "organization",
      item: "organization.allow_publicly_shared_resources",
      expected: "advisory: recorded; sharing is checked per dashboard and insight above",
      observed: JSON.stringify(organization["allow_publicly_shared_resources"] ?? null),
      status: organization["allow_publicly_shared_resources"] === undefined ? "UNKNOWN" : "INFO",
      owner: "manual, organization settings",
      required: false,
    });
  }
  return checks;
}

function compare(role: string, item: string, expected: Json, observed: Json | undefined, owner: string, required: boolean): Check {
  const observedText = observed === undefined ? "absent" : JSON.stringify(observed);
  const sameType = observed !== undefined && observed !== null && (Array.isArray(expected) ? Array.isArray(observed) : typeof observed === typeof expected);
  const status: CheckStatus = !sameType ? "UNKNOWN" : JSON.stringify(observed) === JSON.stringify(expected) ? "PASS" : "FAIL";
  return {role, item, expected: JSON.stringify(expected), observed: observedText, status, owner, required};
}

function countCheck(role: string, item: string, count: number, owner: string): Check {
  return {role, item, expected: "0", observed: String(count), status: count === 0 ? "PASS" : "FAIL", owner, required: true};
}

function sameSet(left: readonly number[], right: readonly number[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

export function renderReport(checks: readonly Check[], context: {readonly observedAt: string; readonly configRevision: string; readonly tofuVersion: string; readonly providerVersion: string}): string {
  const outcome = outcomeOf(checks);
  const lines = [
    "# PostHog configuration verification",
    "",
    `Outcome: **${outcome}**. Observed at ${context.observedAt} (UTC) with OpenTofu ${context.tofuVersion} and provider posthog/posthog ${context.providerVersion}; configuration revision ${context.configRevision}.`,
    "",
    "A maintainer-generated point-in-time read-back of the deployed configuration. `verified` means every required check passed; `drift` means a check failed; `incomplete` means a required value could not be read. It is not proof that a setting cannot be changed later, that historical data was physically erased, or that PostHog's own infrastructure never sees a source address. Advisory rows (required = no) record what was observed and never make the outcome verified on their own.",
    "",
    "| Role | Item | Required | Expected | Observed | Status | Owner |",
    "| --- | --- | --- | --- | --- | --- | --- |",
  ];
  for (const check of checks) {
    lines.push(`| ${check.role} | ${check.item} | ${check.required ? "yes" : "no"} | ${cell(check.expected)} | ${cell(check.observed)} | ${check.status} | ${cell(check.owner)} |`);
  }
  const counts = {PASS: 0, FAIL: 0, UNKNOWN: 0, INFO: 0};
  for (const check of checks) {
    counts[check.status]++;
  }
  const requiredUnknown = checks.filter((check) => check.required && check.status === "UNKNOWN").length;
  lines.push("", `Totals: ${counts.PASS} pass, ${counts.FAIL} fail, ${counts.UNKNOWN} unknown (${requiredUnknown} required), ${counts.INFO} informational.`, "");
  return lines.join("\n");
}

function cell(text: string): string {
  return text.replace(/\|/g, "\\|").replace(/\n/g, " ");
}

// Dashboard fixture: the documented synthetic installations, sent as a run-owned cohort with fresh
// installation IDs, waited for by exact event UUID, checked panel by panel with the canonical query
// files scoped to that cohort, and then queued for deletion.

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

export interface Fixture {
  readonly release_version: string;
  readonly release_date_days_ago: number;
  readonly installations: readonly FixtureInstallation[];
  readonly expected: Readonly<Record<string, Json>>;
}

/** One run's cohort: the identities are fixed at creation so a retry resends the same events. */
export interface FixtureRun {
  readonly run_id: string;
  readonly reference_time: string;
  readonly cohort: Readonly<Record<string, string>>;
  readonly event_uuids: readonly string[];
  readonly cleanup?: Json;
}

export function newFixtureRun(fixture: Fixture, referenceTime: Date): FixtureRun {
  const cohort: Record<string, string> = {};
  for (const installation of fixture.installations) {
    cohort[installation.distinct_id] = randomUUID();
  }
  const eventCount = fixture.installations.reduce((sum, installation) => sum + installation.reports.length, 0);
  return {
    run_id: `fixture-${randomUUID().slice(0, 8)}`,
    reference_time: referenceTime.toISOString(),
    cohort,
    event_uuids: Array.from({length: eventCount}, () => randomUUID()),
  };
}

export function fixtureEvents(fixture: Fixture, run: FixtureRun, token: string): readonly JsonObject[] {
  const reference = new Date(run.reference_time);
  const events: JsonObject[] = [];
  let index = 0;
  for (const installation of fixture.installations) {
    const distinctId = run.cohort[installation.distinct_id];
    if (distinctId === undefined) {
      throw new InfraError(`run has no cohort id for ${installation.distinct_id}`);
    }
    const registeredOn = isoDate(daysBefore(reference, installation.registered_days_ago));
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
        distinct_id: distinctId,
        uuid: run.event_uuids[index] ?? randomUUID(),
        timestamp: `${isoDate(daysBefore(reference, report.days_ago))}T${report.time}:00Z`,
        properties,
      });
      index++;
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

const EVENT_FILTER = `WHERE event = '${HEARTBEAT_EVENT}'`;

/** The canonical query, restricted to the run's cohort by extending its event filter. The canonical
 * text is otherwise untouched, so what runs is the deployed logic, not a second copy. */
export function scopeQuery(sql: string, cohortIds: readonly string[]): string {
  if (!sql.includes(EVENT_FILTER)) {
    throw new InfraError("canonical query lacks the heartbeat event filter; cannot scope it to the cohort");
  }
  const list = cohortIds.map((id) => `'${id}'`).join(", ");
  return sql.split(EVENT_FILTER).join(`${EVENT_FILTER} AND distinct_id IN (${list})`);
}

export type ErasureFlagAction = "archive" | "late-events" | "refused" | "stale" | "keep";

export interface ErasureFlagReview {
  readonly id: number;
  readonly name: string;
  readonly state: string;
  readonly createdAt: string;
  readonly action: ErasureFlagAction;
  readonly remainingEvents?: number;
}

/** Non-deleted status flags written by infra/posthog/erasure-service.hog.tftpl. */
export async function erasureFlagList(api: PostHogApi, projectId: number): Promise<readonly JsonObject[]> {
  const flags = await api.listAll(`/api/projects/${projectId}/feature_flags/?search=erasure-&limit=${PAGE_LIMIT}`);
  return flags.filter((flag) => typeof flag["key"] === "string" && flag["key"].startsWith("erasure-")
    && typeof flag["name"] === "string" && flag["name"].startsWith("symphony-erasure-") && flag["deleted"] !== true);
}

function flagVariant(flag: JsonObject): string {
  const filters = isObject(flag["filters"]) ? flag["filters"] : {};
  const multivariate = isObject(filters["multivariate"]) ? filters["multivariate"] : {};
  const variants = Array.isArray(multivariate["variants"]) ? multivariate["variants"] : [];
  const first = variants[0];
  return isObject(first) && typeof first["key"] === "string" ? first["key"] : "unknown";
}

/** Classifies every status flag and, when asked, archives the ones that are safe to archive:
 * empty results and completed deletions older than a day. An empty result that names its analytics
 * ID is archived only after a fresh query still finds no event for it; a late event is reported
 * for manual deletion instead. Refused and stale operations are reported, never archived. */
export async function reviewErasureFlags(api: PostHogApi, projectId: number, now: Date, archive: boolean): Promise<readonly ErasureFlagReview[]> {
  const reviews: ErasureFlagReview[] = [];
  for (const flag of await erasureFlagList(api, projectId)) {
    const name = String(flag["name"]);
    const createdAt = typeof flag["created_at"] === "string" ? flag["created_at"] : "";
    const age = now.getTime() - Date.parse(createdAt);
    const state = flagVariant(flag);
    const [kind, binding = ""] = name.split("|");
    let action: ErasureFlagAction = "keep";
    let remainingEvents: number | undefined;
    if (!(age >= 0)) {
      action = "keep";
    } else if (kind === "symphony-erasure-empty-v2") {
      if (!PERIOD_ANALYTICS_ID.test(binding)) {
        throw new InfraError(`status flag ${String(flag["id"])} names no valid analytics ID`);
      }
      if (age >= ERASURE_FLAG_MIN_AGE_MS) {
        const rows = await runQuery(api, projectId, `SELECT count() FROM events WHERE distinct_id = '${binding}'`);
        const first = rows[0];
        remainingEvents = Array.isArray(first) ? Number(first[0]) : NaN;
        if (!Number.isInteger(remainingEvents)) {
          throw new InfraError(`status flag ${String(flag["id"])}: unreadable event count`);
        }
        action = remainingEvents === 0 ? "archive" : "late-events";
      }
    } else if (kind === "symphony-erasure-empty-v1") {
      action = age >= ERASURE_FLAG_MIN_AGE_MS ? "archive" : "keep";
    } else if (kind === "symphony-erasure-v1") {
      if (state === "refused") {
        action = "refused";
      } else if (state === "complete") {
        action = age >= ERASURE_FLAG_MIN_AGE_MS ? "archive" : "keep";
      } else if (age >= ERASURE_FLAG_STALE_MS) {
        action = "stale";
      }
    }
    if (action === "archive" && archive) {
      await api.patch(`/api/projects/${projectId}/feature_flags/${String(flag["id"])}/`, {active: false, deleted: true});
    }
    reviews.push({id: Number(flag["id"]), name, state, createdAt, action, ...(remainingEvents === undefined ? {} : {remainingEvents})});
  }
  return reviews;
}

export function readProductionProjectId(): number {
  const text = readFileSync(join(infraDir(), "production-project-id"), "utf8").trim();
  if (!/^[1-9][0-9]*$/.test(text)) {
    throw new InfraError("infra/posthog/production-project-id must hold one project number");
  }
  return Number(text);
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
    throw new InfraError("query answered without a results array");
  }
  return results;
}

export interface PanelOutcome {
  readonly panel: string;
  readonly expected: string;
  readonly observed: string;
  readonly pass: boolean;
}

/** Expected rows for the panels whose result depends on the reference time: the activity windows
 * count reports inside each window as of the reference instant, and the registration cohorts group
 * the registration dates by calendar month. */
export function timeDependentExpectations(fixture: Fixture, referenceTime: Date): Readonly<Record<string, Json>> {
  const windows = [1, 7, 30].map((days) => referenceTime.getTime() - days * 86_400_000);
  const active = windows.map((start) =>
    fixture.installations.filter((installation) =>
      installation.reports.some((report) => {
        const at = Date.parse(`${isoDate(daysBefore(referenceTime, report.days_ago))}T${report.time}:00Z`);
        return at >= start && at <= referenceTime.getTime();
      }),
    ).length,
  );
  const months = new Map<string, number>();
  for (const installation of fixture.installations) {
    const registered = daysBefore(referenceTime, installation.registered_days_ago);
    const month = `${registered.toISOString().slice(0, 7)}-01`;
    months.set(month, (months.get(month) ?? 0) + 1);
  }
  return {
    "active-installations": [active],
    "registration-cohorts": [...months.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([month, count]) => [month, count]),
  };
}

export function comparePanel(panel: string, expected: Json, rows: readonly Json[]): PanelOutcome {
  return {panel, expected: JSON.stringify(expected), observed: JSON.stringify(rows), pass: sameRows(expected as Json[], rows)};
}

function sameRows(expected: readonly Json[], observed: readonly Json[]): boolean {
  const normalize = (rows: readonly Json[]) => rows.map((row) => JSON.stringify(row)).sort();
  const left = normalize(expected);
  const right = normalize(observed);
  return left.length === right.length && left.every((row, index) => row === right[index]);
}

// Hog runtime capability probe: harmless test invocations of unsaved Hog code through the same
// endpoint the PostHog UI uses for "test function", with asynchronous functions mocked, so nothing
// is captured or deleted and no handler is saved. This probes the authenticated test runtime,
// not a deployed public ingress path. Absence by name alone does not establish capability absence.

export interface HogProbe {
  readonly name: string;
  readonly hog: string;
  /** What a present primitive prints; a missing one raises "Global variable not found". */
  readonly expect?: string;
}

export const HOG_PROBES: readonly HogProbe[] = [
  {name: "sha256Hex", hog: "print(sha256Hex('abc'))", expect: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
  {name: "sha256HmacChainHex", hog: "print(typeof(sha256HmacChainHex))", expect: "function"},
  {name: "md5Hex", hog: "print(typeof(md5Hex))", expect: "function"},
  {name: "base64Decode", hog: "print(base64Decode('YWJj'))", expect: "abc"},
  {name: "jsonParse", hog: "print(typeof(jsonParse))", expect: "function"},
  {name: "generateUUIDv4", hog: "print(typeof(generateUUIDv4))", expect: "function"},
  {name: "ecdsaVerify", hog: "print(typeof(ecdsaVerify))"},
  {name: "verifySignature", hog: "print(typeof(verifySignature))"},
  {name: "rsaVerify", hog: "print(typeof(rsaVerify))"},
  {name: "jwtVerify", hog: "print(typeof(jwtVerify))"},
  {name: "verifyJwt", hog: "print(typeof(verifyJwt))"},
  {name: "jwtDecode", hog: "print(typeof(jwtDecode))"},
  {name: "crypto", hog: "print(typeof(crypto))", expect: "object"},
  {name: "hmacSha256", hog: "print(typeof(hmacSha256))"},
  {name: "base64UrlDecode", hog: "print(typeof(base64UrlDecode))"},
  {name: "hexDecode", hog: "print(typeof(hexDecode))"},
];

export type ProbeStatus = "present" | "absent" | "unexpected";

export interface ProbeResult {
  readonly name: string;
  readonly status: ProbeStatus;
  readonly detail: string;
}

/** Bind the harmless trial to the selected managed test resource before invoking hosted code. */
export async function verifyProbeTarget(api: PostHogApi, roles: readonly Role[], stateFile: string): Promise<{projectId: number; organizationId: string}> {
  const tests = roles.filter((role) => role.name === "test");
  const role = tests[0];
  if (tests.length !== 1 || role === undefined || !Number.isSafeInteger(role.projectId) || role.projectId <= 0
      || roles.some((other) => other.name !== "test" && other.projectId === role.projectId)) {
    throw new InfraError("probe requires a distinct test role");
  }
  const state = asObject(JSON.parse(readFileSync(stateFile, "utf8")) as Json, "selected state");
  const resources = Array.isArray(state["resources"]) ? state["resources"].filter(isObject) : [];
  const matches = resources.filter((resource) => resource["module"] === "module.test" && resource["mode"] === "managed" && resource["type"] === "posthog_project" && resource["name"] === "this");
  const instances = matches.length === 1 ? matches[0]?.["instances"] : undefined;
  if (!Array.isArray(instances) || instances.length !== 1 || !isObject(instances[0])) {
    throw new InfraError("selected state must contain exactly one managed test project");
  }
  const attributes = asObject(instances[0]["attributes"] ?? null, "test project attributes");
  let organizationId = attributes["organization_id"];
  if (String(attributes["id"]) !== String(role.projectId) || attributes["api_token"] !== role.captureToken
      || typeof organizationId !== "string" || organizationId === "" || typeof attributes["name"] !== "string") {
    throw new InfraError("test outputs do not match the selected state");
  }
  // The provider retains @current in state. Resolve it, then bind it to the fixed project and
  // token below; changing the account's current organization never selects a different project.
  if (organizationId === "@current") {
    const organization = asObject(await api.get("/api/organizations/@current/"), "selected organization");
    organizationId = organization["id"];
    if (typeof organizationId !== "string" || organizationId === "" || organizationId.startsWith("@")) {
      throw new InfraError("selected organization did not resolve to an identifier");
    }
  }
  for (const kind of ["projects", "environments"]) {
    const live = asObject(await api.get(`/api/${kind}/${role.projectId}/`), `live test ${kind}`);
    if (live["id"] !== role.projectId || live["organization"] !== organizationId
        || live["api_token"] !== role.captureToken || live["name"] !== attributes["name"]) {
      throw new InfraError(`live test ${kind} binding differs from the selected state`);
    }
  }
  return {projectId: role.projectId, organizationId};
}

export async function probeHogRuntime(api: PostHogApi, projectId: number, probes: readonly HogProbe[] = HOG_PROBES): Promise<readonly ProbeResult[]> {
  const results: ProbeResult[] = [];
  for (const probe of probes) {
    const response = asObject(
      await api.post(`/api/projects/${projectId}/hog_functions/new/invocations/`, {
        configuration: {type: "destination", name: "capability probe", hog: probe.hog, inputs: {}, inputs_schema: [], filters: {}, enabled: false},
        mock_async_functions: true,
        globals: {
          event: {uuid: "00000000-0000-0000-0000-000000000000", event: "probe", distinct_id: "probe", properties: {}, timestamp: "2026-01-01T00:00:00Z"},
          person: {properties: {}},
        },
      }),
      `probe ${probe.name}`,
    );
    const errors = Array.isArray(response["errors"]) ? response["errors"].map(String) : [];
    const logs = Array.isArray(response["logs"]) ? response["logs"].map((entry) => (isObject(entry) ? String(entry["message"] ?? "") : "")) : [];
    const printed = logs.find((line) => !line.startsWith("Function completed") && !line.startsWith("Error executing")) ?? "";
    if (response["status"] === "success") {
      const matches = errors.length === 0 && printed === (probe.expect ?? "function");
      results.push({name: probe.name, status: matches ? "present" : "unexpected", detail: printed});
    } else {
      const missing = errors.some((error) => error === `Global variable not found: ${probe.name}` || error === `Unsupported function call: ${probe.name}`);
      results.push({name: probe.name, status: missing ? "absent" : "unexpected", detail: errors[0] ?? "no error text"});
    }
  }
  return results;
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
      const checks = await verify(api, roles, readProductionProjectId());
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
      const outcome = outcomeOf(checks);
      for (const check of checks) {
        if (check.status === "FAIL" || (check.required && check.status === "UNKNOWN")) {
          console.error(`${check.status} ${check.role} ${check.item}: expected ${check.expected}, observed ${check.observed}`);
        }
      }
      console.log(`verify: ${outcome}`);
      return outcome === "verified" ? 0 : 1;
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
      const runFile = requireFlag(options, "run-file");
      // A run file from an interrupted attempt keeps its identities so the retry resends the same
      // events; a new attempt gets a new cohort, so old fixtures never satisfy the new check.
      const run: FixtureRun = existsSync(runFile) ? (JSON.parse(readFileSync(runFile, "utf8")) as FixtureRun) : newFixtureRun(fixture, new Date());
      if (run.cleanup !== undefined) {
        throw new InfraError(`${runFile} belongs to a finished run; remove it or choose another run file`);
      }
      writeFileSync(runFile, JSON.stringify(run, null, 2), {mode: 0o600});
      const reference = new Date(run.reference_time);
      const events = fixtureEvents(fixture, run, role.captureToken);
      for (const event of events) {
        const response = await fetch(captureEndpoint, {
          method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify(event),
          signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        });
        await response.body?.cancel();
        if (!response.ok) {
          throw new InfraError(`capture answered HTTP ${response.status}`);
        }
      }
      console.log(`fixture ${run.run_id}: sent ${events.length} events to role ${roleName}`);
      const cohortIds = Object.values(run.cohort);
      const uuidList = run.event_uuids.map((uuid) => `'${uuid}'`).join(", ");
      const visibleSql = scopeQuery(`SELECT toString(uuid) FROM events ${EVENT_FILTER} AND uuid IN (${uuidList}) AND timestamp >= now() - interval 400 day`, cohortIds);
      let visible = new Set<string>();
      for (let attempt = 1; attempt <= INGESTION_ATTEMPTS; attempt++) {
        visible = new Set((await runQuery(api, role.projectId, visibleSql)).map((row) => String((row as Json[])[0])));
        if (run.event_uuids.every((uuid) => visible.has(uuid))) {
          break;
        }
        await sleep(INGESTION_DELAY_MS);
      }
      const missing = run.event_uuids.filter((uuid) => !visible.has(uuid));
      if (missing.length > 0) {
        throw new InfraError(`fixture ${run.run_id}: ${missing.length} of ${events.length} events not queryable after waiting; rerun with the same run file to retry`);
      }
      const releaseDate = isoDate(daysBefore(reference, fixture.release_date_days_ago));
      const expectations = {...fixture.expected, ...timeDependentExpectations(fixture, reference)};
      let failures = 0;
      for (const [panel, expected] of Object.entries(expectations)) {
        const canonical = panel === "release-adoption-and-delay"
          ? renderQuery(readFileSync(join(infraDir(), "queries", `${panel}.sql.tftpl`), "utf8"), {release_date: releaseDate, release_version: fixture.release_version})
          : readFileSync(join(infraDir(), "queries", `${panel}.sql`), "utf8");
        const outcome = comparePanel(panel, expected, await runQuery(api, role.projectId, scopeQuery(canonical, cohortIds)));
        console.log(`${outcome.pass ? "PASS" : "FAIL"} ${panel}: ${outcome.observed}${outcome.pass ? "" : ` (expected ${outcome.expected})`}`);
        if (!outcome.pass) {
          failures++;
        }
      }
      // Cleanup is queued, never awaited: PostHog deletes events in a later batch. Its status is
      // recorded in the run file and reported separately from the panel result.
      const cleanup = await api.post(`/api/projects/${role.projectId}/persons/bulk_delete/`, {distinct_ids: cohortIds, delete_events: true});
      writeFileSync(runFile, JSON.stringify({...run, cleanup: {requested_at: new Date().toISOString(), response: cleanup}}, null, 2), {mode: 0o600});
      const cleanupBody = isObject(cleanup) ? cleanup : {};
      console.log(`fixture ${run.run_id}: cleanup queued (persons_found ${String(cleanupBody["persons_found"])}, events_queued_for_deletion ${String(cleanupBody["events_queued_for_deletion"])}); panels ${failures === 0 ? "all pass" : `${failures} failing`}`);
      return failures === 0 ? 0 : 1;
    }
    case "hog-probe": {
      if (environment["SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE"] !== "1") {
        throw new InfraError("hog-probe requires explicit live opt-in: SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE=1");
      }
      if (host !== "https://eu.posthog.com" && !/^http:\/\/(127\.0\.0\.1|localhost):[0-9]+$/.test(host)) {
        throw new InfraError("hog-probe requires the EU administration host");
      }
      const roleName = options.flags.get("role") ?? "test";
      if (roleName !== "test") {
        throw new InfraError("the probe runs in the test role only");
      }
      const target = await verifyProbeTarget(api, readRoles(requireFlag(options, "outputs")), requireFlag(options, "state"));
      const report = requireFlag(options, "report");
      const ledger = {runId: randomUUID(), startedAt: new Date().toISOString(), role: "test", ...target,
        operation: "unsaved mocked Hog invocations", createdResources: [], capturedEvents: [], deletions: [], status: "started"};
      writeFileSync(report, JSON.stringify(ledger, null, 2) + "\n", {mode: 0o600, flag: "wx"});
      const results = await probeHogRuntime(api, target.projectId);
      writeFileSync(report, JSON.stringify({...ledger, status: "observed", completedAt: new Date().toISOString(), results}, null, 2) + "\n");
      console.log(`| Primitive | Status | Detail |\n| --- | --- | --- |`);
      for (const result of results) {
        console.log(`| ${result.name} | ${result.status} | ${result.detail.replace(/\|/g, "\\|")} |`);
      }
      return results.some((result) => result.status === "unexpected") ? 1 : 0;
    }
    case "erasure-flags": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const roleName = requireFlag(options, "role");
      const role = roles.find((candidate) => candidate.name === roleName);
      if (role === undefined) {
        throw new InfraError(`no role ${roleName} in outputs`);
      }
      const archive = options.flags.get("archive") === "true";
      const reviews = await reviewErasureFlags(api, role.projectId, new Date(), archive);
      for (const review of reviews) {
        const events = review.remainingEvents === undefined ? "" : `, ${review.remainingEvents} event(s) remain`;
        console.log(`${review.action}${review.action === "archive" && !archive ? " (dry run)" : ""} flag ${review.id} ${review.name} [${review.state}, created ${review.createdAt}${events}]`);
      }
      const attention = reviews.filter((review) => review.action === "late-events" || review.action === "refused" || review.action === "stale");
      console.log(`erasure-flags: ${reviews.length} flag(s), ${reviews.filter((review) => review.action === "archive").length} ${archive ? "archived" : "archivable"}, ${attention.length} need the maintainer`);
      return attention.length === 0 ? 0 : 1;
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
