// The API-owned environment settings (gaps), the GeoIP lookup the bootstrap import needs, and the
// read-only verification report. Nothing here writes, except gapApply for the three settings the
// provider cannot express.

import {createHash} from "node:crypto";
import {readFileSync} from "node:fs";
import {asObject, InfraError, isObject, PAGE_LIMIT, PostHogApi, realSleep, type Json, type JsonObject, type Sleeper} from "./posthog-api.ts";
import {erasureFlagList, ERASURE_FLAG_WARNING} from "./posthog-erasure-flags.ts";
import {POLICY_SETTINGS, type Role} from "./posthog-state-files.ts";

export const ENVIRONMENT_GAP_SETTINGS = {
  autocapture_opt_out: true,
  capture_console_log_opt_in: false,
  capture_dead_clicks: false,
} as const;

export const GEOIP_TEMPLATE_ID = "template-geoip";
export const DESTINATION_TYPES = "destination,site_destination,internal_destination,source_webhook,site_app";
const READ_BACK_ATTEMPTS = 5;
const READ_BACK_DELAY_MS = 2000;

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
