import assert from "node:assert/strict";
import {createServer, type IncomingMessage, type Server, type ServerResponse} from "node:http";
import {mkdtempSync, readdirSync, readFileSync, statSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {after, before, beforeEach, test} from "node:test";
import {
  comparePanel,
  ERASURE_FLAG_WARNING,
  fixtureEvents,
  findGeoipFunction,
  gapApply,
  gapDiff,
  InfraError,
  main,
  newFixtureRun,
  outcomeOf,
  PostHogApi,
  probeHogRuntime,
  readKey,
  readRoles,
  renderQuery,
  renderReport,
  reviewErasureFlags,
  scopeQuery,
  timeDependentExpectations,
  verify,
  verifyProbeTarget,
  type Fixture,
  type Json,
} from "./posthog-infra.ts";

const KEY = "phx_test_personal_key_0123456789";
const TOKEN = "phc_" + "faketoken0".repeat(4) + "0123";
const PROJECT_ID = 4242;
const DASHBOARD_ID = 77;
const STARTER_DASHBOARD_ID = 5;
const ORGANIZATION = "org-uuid";
const NO_SLEEP = async () => {};

interface FakeState {
  environment: Record<string, Json>;
  transformations: Record<string, Json>[];
  dashboards: Record<string, Json>[];
  insights: Record<string, Json>[];
  organization: Record<string, Json>;
  listingCountOverride?: number;
  environmentStatus?: number;
  patchIgnored: boolean;
  patchFailuresRemaining: number;
  staleReadBacks: number;
  publicDashboardIds: number[];
  publicInsightIds: number[];
  sharingStatus?: number;
  loopPagination: boolean;
  oversizedEnvironment: boolean;
  echoKeyInError: boolean;
  requests: string[];
  deleted: string[];
  flags: Record<string, Json>[];
  eventCounts: Record<string, number>;
}

const POLICY: Record<string, Json> = {
  timezone: "UTC",
  anonymize_ips: true,
  cookieless_server_hash_mode: 0,
  session_recording_opt_in: false,
  capture_performance_opt_in: false,
  autocapture_exceptions_opt_in: false,
  autocapture_web_vitals_opt_in: false,
  heatmaps_opt_in: false,
  surveys_opt_in: false,
  app_urls: [],
  recording_domains: [],
  test_account_filters: [],
  geoip_enabled: false,
};

function healthyEnvironment(): Record<string, Json> {
  return {
    id: PROJECT_ID,
    name: "Symphony for Trello (test)",
    organization: ORGANIZATION,
    api_token: TOKEN,
    ...POLICY,
    autocapture_opt_out: true,
    capture_console_log_opt_in: false,
    capture_dead_clicks: false,
    event_retention_months: null,
    events_retention_enforced: null,
    is_demo: false,
    access_control: false,
  };
}

const PANELS = ["active-installations", "latest-version-distribution"] as const;

function freshState(): FakeState {
  return {
    environment: healthyEnvironment(),
    transformations: [{id: "geoip-uuid", name: "GeoIP", type: "transformation", enabled: false, template: {id: "template-geoip"}}],
    dashboards: [
      {id: DASHBOARD_ID, name: "Symphony for Trello installations", is_shared: false, tiles: [{insight: {id: 1}}, {insight: {id: 2}}]},
      {id: STARTER_DASHBOARD_ID, name: "Your starter dashboard", is_shared: false, tiles: []},
    ],
    insights: [
      {id: 1, name: "Active installations over 1, 7, and 30 days"},
      {id: 2, name: "Latest version distribution"},
    ],
    organization: {id: ORGANIZATION, is_ai_training_opted_in: false, allow_publicly_shared_resources: true},
    patchIgnored: false,
    patchFailuresRemaining: 0,
    staleReadBacks: 0,
    publicDashboardIds: [],
    publicInsightIds: [],
    loopPagination: false,
    oversizedEnvironment: false,
    echoKeyInError: false,
    requests: [],
    deleted: [],
    flags: [],
    eventCounts: {},
  };
}

let server: Server;
let host: string;
let state: FakeState;
let tempDir: string;
let keyFile: string;
let outputsFile: string;

function respond(response: ServerResponse, status: number, body: Json | string): void {
  response.writeHead(status, {"Content-Type": "application/json"});
  response.end(typeof body === "string" ? body : JSON.stringify(body));
}

function paginated(results: readonly Json[], count?: number, next: string | null = null): Json {
  return {count: count ?? results.length, next, previous: null, results: [...results]};
}

async function readBody(request: IncomingMessage): Promise<Record<string, Json>> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(chunk as Buffer);
  }
  const text = Buffer.concat(chunks).toString("utf8");
  return text === "" ? {} : (JSON.parse(text) as Record<string, Json>);
}

async function handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const url = new URL(request.url ?? "/", host);
  state.requests.push(`${request.method} ${url.pathname}${url.search}`);
  if (request.headers.authorization !== `Bearer ${KEY}`) {
    respond(response, 401, {detail: "Invalid personal API key."});
    return;
  }
  const path = url.pathname;
  if (path === `/api/environments/${PROJECT_ID}/`) {
    if (state.environmentStatus !== undefined) {
      respond(response, state.environmentStatus, {detail: state.echoKeyInError ? `key ${KEY} lacks scope project:read` : "API key missing scope project:read"});
      return;
    }
    if (state.oversizedEnvironment) {
      respond(response, 200, JSON.stringify({padding: "x".repeat(5 * 1024 * 1024)}));
      return;
    }
    if (request.method === "PATCH") {
      const body = await readBody(request);
      if (state.patchFailuresRemaining > 0) {
        state.patchFailuresRemaining--;
        respond(response, 500, {detail: "temporary failure"});
        return;
      }
      if (!state.patchIgnored) {
        if (state.staleReadBacks > 0) {
          const pending = {...body};
          const original = state.environment;
          state.environment = {...original};
          const remaining = state.staleReadBacks;
          state.staleReadBacks = 0;
          let reads = 0;
          Object.defineProperty(state, "environmentView", {
            configurable: true,
            get: () => (reads++ < remaining ? original : {...original, ...pending}),
          });
        } else {
          state.environment = {...state.environment, ...body};
        }
      }
      respond(response, 200, state.environment);
      return;
    }
    const view = (state as unknown as {environmentView?: Record<string, Json>}).environmentView;
    respond(response, 200, view ?? state.environment);
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/`) {
    respond(response, 200, state.environment);
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/hog_functions/`) {
    const type = url.searchParams.get("type") ?? "";
    const results = type.includes("transformation") ? state.transformations : [];
    if (state.loopPagination) {
      respond(response, 200, paginated(results, undefined, `${host}${path}?type=${type}&offset=0`));
      return;
    }
    respond(response, 200, paginated(results, state.listingCountOverride));
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/batch_exports/`) {
    respond(response, 200, paginated([]));
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/dashboards/`) {
    respond(response, 200, paginated(state.dashboards.map(({tiles: _tiles, ...rest}) => ({...rest, is_shared: state.publicDashboardIds.includes(Number(rest["id"])) ? true : (rest["is_shared"] ?? null)}))));
    return;
  }
  const dashboardMatch = path.match(new RegExp(`^/api/projects/${PROJECT_ID}/dashboards/(\\d+)/(sharing/)?$`));
  if (dashboardMatch !== null) {
    const id = Number(dashboardMatch[1]);
    const dashboard = state.dashboards.find((entry) => entry["id"] === id);
    if (dashboard === undefined) {
      respond(response, 404, {detail: "Not found."});
      return;
    }
    if (dashboardMatch[2] !== undefined) {
      if (state.sharingStatus !== undefined) {
        respond(response, state.sharingStatus, {detail: "API key missing scope sharing_configuration:read"});
        return;
      }
      respond(response, 200, {enabled: state.publicDashboardIds.includes(id)});
      return;
    }
    respond(response, 200, dashboard);
    return;
  }
  const insightSharing = path.match(new RegExp(`^/api/projects/${PROJECT_ID}/insights/(\\d+)/sharing/$`));
  if (insightSharing !== null) {
    respond(response, 200, {enabled: state.publicInsightIds.includes(Number(insightSharing[1]))});
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/insights/`) {
    respond(response, 200, paginated(state.insights));
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/hog_functions/new/invocations/` && request.method === "POST") {
    const body = await readBody(request);
    const configuration = body["configuration"] as Record<string, Json>;
    const hog = String(configuration["hog"]);
    if (body["mock_async_functions"] !== true) {
      respond(response, 400, {detail: "probe must mock async functions"});
      return;
    }
    if (hog.includes("sha256Hex('abc')")) {
      respond(response, 200, {status: "success", errors: [], logs: [{message: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}, {message: "Function completed in 0.3ms"}]});
      return;
    }
    if (/typeof\((sha256HmacChainHex|md5Hex|jsonParse|generateUUIDv4)\)/.test(hog) || hog.includes("base64Decode")) {
      respond(response, 200, {status: "success", errors: [], logs: [{message: hog.includes("base64Decode") ? "abc" : "function"}]});
      return;
    }
    const name = /typeof\((\w+)\)/.exec(hog)?.[1] ?? "?";
    respond(response, 200, {status: "error", errors: [`Global variable not found: ${name}`], logs: [{message: `Error executing function: HogVMException: Global variable not found: ${name}`}]});
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/feature_flags/` && request.method === "GET") {
    respond(response, 200, paginated(state.flags));
    return;
  }
  const flagMatch = path.match(new RegExp(`^/api/projects/${PROJECT_ID}/feature_flags/(\\d+)/$`));
  if (flagMatch !== null && request.method === "PATCH") {
    const flag = state.flags.find((entry) => entry["id"] === Number(flagMatch[1]));
    if (flag === undefined) {
      respond(response, 404, {detail: "Not found."});
      return;
    }
    Object.assign(flag, await readBody(request));
    respond(response, 200, flag);
    return;
  }
  if (path === `/api/projects/${PROJECT_ID}/query/` && request.method === "POST") {
    const query = (await readBody(request))["query"] as Record<string, Json>;
    const target = /distinct_id = '([^']+)'/.exec(String(query["query"]))?.[1] ?? "";
    respond(response, 200, {is_cached: false, results: [[state.eventCounts[target] ?? 0]]});
    return;
  }
  if (path === `/api/organizations/${ORGANIZATION}/` || path === "/api/organizations/@current/") {
    respond(response, 200, state.organization);
    return;
  }
  if (path === "/api/organizations/") {
    respond(response, 200, paginated([state.organization, {id: "other-org", is_ai_training_opted_in: true, allow_publicly_shared_resources: true}]));
    return;
  }
  if (path === `/api/organizations/${ORGANIZATION}/projects/${PROJECT_ID}/` && request.method === "DELETE") {
    state.deleted.push(path);
    response.writeHead(204);
    response.end();
    return;
  }
  respond(response, 404, {detail: "Not found."});
}

before(async () => {
  server = createServer((request, response) => {
    handle(request, response).catch((error: unknown) => {
      respond(response, 500, {detail: String(error)});
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("no server address");
  }
  host = `http://127.0.0.1:${address.port}`;
  tempDir = mkdtempSync(join(tmpdir(), "posthog-infra-test-"));
  keyFile = join(tempDir, "key");
  writeFileSync(keyFile, `${KEY}\n`);
  outputsFile = join(tempDir, "outputs.json");
  writeFileSync(outputsFile, JSON.stringify(outputs()));
});

function outputs(policyOverride: Record<string, Json> = {}): Json {
  return {
    projects: {
      value: {
        test: {
          id: PROJECT_ID,
          name: "Symphony for Trello (test)",
          dashboard_id: DASHBOARD_ID,
          insights: {
            [PANELS[0]]: {id: 1, short_id: "a", name: "Active installations over 1, 7, and 30 days"},
            [PANELS[1]]: {id: 2, short_id: "b", name: "Latest version distribution"},
          },
        },
      },
    },
    privacy_policy: {value: {test: {...POLICY, ...policyOverride}}},
    capture_tokens: {value: {test: TOKEN}},
  };
}

after(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

beforeEach(() => {
  state = freshState();
  delete (state as unknown as {environmentView?: unknown}).environmentView;
});

function api(): PostHogApi {
  return new PostHogApi(host, KEY);
}

function roles() {
  return readRoles(outputsFile);
}

async function verifyExit(): Promise<number> {
  return main(["verify", "--outputs", outputsFile, "--host", host, "--report", join(tempDir, "report.md")], {POSTHOG_API_KEY: KEY});
}

test("a healthy project is verified, exits 0, and the report holds no secret", async () => {
  const checks = await verify(api(), roles());
  const report = renderReport(checks, {observedAt: "2026-09-22T00:00:00Z", configRevision: "abc", tofuVersion: "1.12.6", providerVersion: "1.0.21"});
  assert.equal(outcomeOf(checks), "verified");
  assert.deepEqual(checks.filter((check) => check.status === "FAIL").map((check) => check.item), []);
  assert.equal(checks.find((check) => check.item === "environment.event_retention_months")?.status, "UNKNOWN");
  assert.equal(checks.find((check) => check.item === "environment.event_retention_months")?.required, false);
  assert.ok(report.includes("Outcome: **verified**"));
  assert.ok(report.includes("| test | environment.anonymize_ips | yes | true | true | PASS |"));
  assert.ok(!report.includes(KEY));
  assert.ok(!report.includes(TOKEN));
  assert.equal(await verifyExit(), 0);
});

test("a required setting that is absent, null, or of the wrong type makes verification incomplete and exits 1", async () => {
  for (const value of [undefined, null, "true"] as const) {
    state = freshState();
    if (value === undefined) {
      delete state.environment["anonymize_ips"];
    } else {
      state.environment["anonymize_ips"] = value;
    }
    const checks = await verify(api(), roles());
    assert.equal(checks.find((check) => check.item === "environment.anonymize_ips")?.status, "UNKNOWN", String(value));
    assert.equal(outcomeOf(checks), "incomplete", String(value));
    assert.equal(await verifyExit(), 1, String(value));
  }
  state = freshState();
  state.environment["anonymize_ips"] = false;
  assert.equal(outcomeOf(await verify(api(), roles())), "drift");
});

test("the expected values come from the definition's policy output, not from a second copy", async () => {
  const other = join(tempDir, "outputs-policy.json");
  writeFileSync(other, JSON.stringify(outputs({anonymize_ips: false})));
  const checks = await verify(api(), readRoles(other));
  assert.equal(checks.find((check) => check.item === "environment.anonymize_ips")?.status, "FAIL");
  const incomplete = join(tempDir, "outputs-no-policy.json");
  writeFileSync(incomplete, JSON.stringify({...(outputs() as Record<string, Json>), privacy_policy: {value: {test: {timezone: "UTC"}}}}));
  assert.throws(() => readRoles(incomplete), (error: unknown) => error instanceof InfraError && error.message.includes("lacks anonymize_ips"));
});

test("a public untracked dashboard or a public insight fails verification while the managed dashboard stays private", async () => {
  state.publicDashboardIds = [STARTER_DASHBOARD_ID];
  let checks = await verify(api(), roles());
  assert.equal(checks.find((check) => check.item === `dashboard.${STARTER_DASHBOARD_ID}.sharing.enabled`)?.status, "FAIL");
  assert.equal(checks.find((check) => check.item === `dashboard.${DASHBOARD_ID}.sharing.enabled`)?.status, "PASS");
  assert.equal(outcomeOf(checks), "drift");
  state = freshState();
  state.publicInsightIds = [2];
  checks = await verify(api(), roles());
  assert.ok(checks.find((check) => check.item === "insights.sharing.enabled")?.observed.includes("Latest version distribution"));
  assert.equal(outcomeOf(checks), "drift");
  state = freshState();
  state.sharingStatus = 403;
  await assert.rejects(verify(api(), roles()), (error: unknown) => error instanceof InfraError && error.message.includes("HTTP 403"));
});

test("only the organization owning the projects is read, not every organization the key reaches", async () => {
  const checks = await verify(api(), roles());
  assert.equal(checks.filter((check) => check.item === "organization.is_ai_training_opted_in").length, 1);
  assert.equal(checks.find((check) => check.item === "organization.is_ai_training_opted_in")?.status, "PASS");
  assert.ok(state.requests.some((request) => request === `GET /api/organizations/${ORGANIZATION}/`));
  assert.ok(!state.requests.some((request) => request.startsWith("GET /api/organizations/?")));
});

test("the wrong project name refuses retirement before any delete", async () => {
  await assert.rejects(
    main(["retire-project", "--id", String(PROJECT_ID), "--name", "Symphony for Trello", "--host", host, "--key-file", keyFile], {}),
    (error: unknown) => error instanceof InfraError && error.message.includes("refusing"),
  );
  assert.deepEqual(state.deleted, []);
});

test("a denied scope fails with the status, without the key even when the server echoes it", async () => {
  state.environmentStatus = 403;
  state.echoKeyInError = true;
  await assert.rejects(gapDiff(api(), roles()), (error: unknown) => {
    assert.ok(error instanceof InfraError);
    assert.ok(error.message.includes("HTTP 403"));
    assert.ok(error.message.includes("[redacted] lacks scope"));
    assert.ok(!error.message.includes(KEY));
    return true;
  });
});

test("a missing key file or capture token is reported by path, never by value", () => {
  assert.throws(() => readKey(join(tempDir, "absent")), (error: unknown) => error instanceof InfraError && error.message.includes("absent"));
  const noToken = join(tempDir, "no-token.json");
  writeFileSync(noToken, JSON.stringify({projects: {value: {test: {id: 1, dashboard_id: 2, insights: {}}}}, privacy_policy: {value: {test: POLICY}}, capture_tokens: {value: {}}}));
  assert.throws(() => readRoles(noToken), (error: unknown) => error instanceof InfraError && error.message.includes("no capture token for role test"));
});

test("two GeoIP transformations are an ambiguity, not a choice", async () => {
  state.transformations.push({id: "second", name: "GeoIP", type: "transformation", enabled: true, template_id: "template-geoip"});
  await assert.rejects(findGeoipFunction(api(), PROJECT_ID), (error: unknown) => error instanceof InfraError && error.message.includes("refusing to choose"));
  state.transformations.pop();
  assert.equal((await findGeoipFunction(api(), PROJECT_ID))["id"], "geoip-uuid");
});

test("incomplete or looping pagination is refused instead of read as fewer entries", async () => {
  state.listingCountOverride = 3;
  await assert.rejects(api().listAll(`/api/projects/${PROJECT_ID}/hog_functions/?type=transformation`), (error: unknown) =>
    error instanceof InfraError && error.message.includes("reports 3 entries but the pages held 1"),
  );
  state = freshState();
  state.loopPagination = true;
  await assert.rejects(api().listAll(`/api/projects/${PROJECT_ID}/hog_functions/?type=transformation`), (error: unknown) =>
    error instanceof InfraError && error.message.includes("pagination repeats"),
  );
});

test("an oversized response is refused rather than read into memory", async () => {
  state.oversizedEnvironment = true;
  await assert.rejects(api().get(`/api/environments/${PROJECT_ID}/`), (error: unknown) => error instanceof InfraError && error.message.includes("more than"));
});

test("a setting the server accepts but ignores fails the apply", async () => {
  state.environment["autocapture_opt_out"] = false;
  state.patchIgnored = true;
  await assert.rejects(gapApply(api(), roles(), NO_SLEEP), (error: unknown) => error instanceof InfraError && error.message.includes("still reports autocapture_opt_out"));
});

test("a stale read-back is retried until the server shows the change", async () => {
  state.environment["capture_dead_clicks"] = true;
  state.staleReadBacks = 2;
  const applied = await gapApply(api(), roles(), NO_SLEEP);
  assert.deepEqual(applied.map((change) => change.setting), ["capture_dead_clicks"]);
  assert.ok(state.requests.filter((request) => request.startsWith("GET /api/environments/")).length >= 3);
});

test("a failed patch surfaces and a rerun applies only what is still missing", async () => {
  state.environment["autocapture_opt_out"] = false;
  state.patchFailuresRemaining = 1;
  await assert.rejects(gapApply(api(), roles(), NO_SLEEP), (error: unknown) => error instanceof InfraError && error.message.includes("HTTP 500"));
  const applied = await gapApply(api(), roles(), NO_SLEEP);
  assert.deepEqual(applied.map((change) => change.setting), ["autocapture_opt_out"]);
  assert.deepEqual(await gapDiff(api(), roles()), []);
});

test("an unexpected enabled transformation and an enabled GeoIP both fail verification", async () => {
  state.transformations.push({id: "extra", name: "IP Anonymization", type: "transformation", enabled: true, template_id: "template-ip-anonymization"});
  (state.transformations[0] as Record<string, Json>)["enabled"] = true;
  const checks = await verify(api(), roles());
  assert.equal(checks.find((check) => check.item === "transformations.other")?.status, "FAIL");
  assert.equal(checks.find((check) => check.item === "transformations.geoip")?.status, "FAIL");
});

test("duplicate dashboards and insights are detected", async () => {
  state.dashboards.push({id: 99, name: "Symphony for Trello installations", is_shared: false, tiles: []});
  state.insights.push({id: 3, name: "Latest version distribution"});
  const checks = await verify(api(), roles());
  assert.equal(checks.find((check) => check.item === "dashboard.managed")?.status, "FAIL");
  assert.ok(checks.find((check) => check.item === "insights.managed")?.observed.includes("duplicated: Latest version distribution"));
});

test("gaps diff lists changes and exits 2, apply patches them", async () => {
  state.environment["capture_console_log_opt_in"] = true;
  const environment = {POSTHOG_API_KEY: KEY};
  assert.equal(await main(["gaps", "diff", "--outputs", outputsFile, "--host", host], environment), 2);
  assert.equal(await main(["gaps", "apply", "--outputs", outputsFile, "--host", host], environment, NO_SLEEP), 0);
  assert.equal(state.environment["capture_console_log_opt_in"], false);
  assert.equal(await main(["gaps", "diff", "--outputs", outputsFile, "--host", host], environment), 0);
});

const SMALL_FIXTURE: Fixture = {
  release_version: "1.3.0",
  release_date_days_ago: 10,
  installations: [
    {
      distinct_id: "template-1",
      registered_days_ago: 40,
      properties: {app_version: "1.3.0", os_family: "linux", connected_board_count: 3},
      reports: [{days_ago: 1, time: "09:15"}, {days_ago: 0, time: "10:15", properties: {connected_board_count: null}}],
    },
    {distinct_id: "template-2", registered_days_ago: 5, properties: {app_version: "1.2.0"}, reports: [{days_ago: 35, time: "09:15"}]},
  ],
  expected: {},
};

test("a fixture run owns fresh installation ids, keeps its event ids on retry, and dates everything from its reference time", () => {
  const reference = new Date("2026-09-22T12:00:00Z");
  const run = newFixtureRun(SMALL_FIXTURE, reference);
  const events = fixtureEvents(SMALL_FIXTURE, run, TOKEN);
  const again = fixtureEvents(SMALL_FIXTURE, run, TOKEN);
  assert.equal(events.length, 3);
  assert.deepEqual(events.map((event) => event["uuid"]), again.map((event) => event["uuid"]));
  assert.notEqual(run.cohort["template-1"], "template-1");
  assert.notEqual(run.cohort["template-1"], newFixtureRun(SMALL_FIXTURE, reference).cohort["template-1"]);
  const second = events[1] as Record<string, Json>;
  const properties = second["properties"] as Record<string, Json>;
  assert.equal(second["distinct_id"], run.cohort["template-1"]);
  assert.equal(second["timestamp"], "2026-09-22T10:15:00Z");
  assert.equal(properties["registered_on"], "2026-08-13");
  assert.equal(properties["connected_board_count"], null);
  assert.equal(properties["$geoip_disable"], true);
  assert.equal(renderQuery("x ${release_date} ${release_version}", {release_date: "2026-09-12", release_version: "1.3.0"}), "x 2026-09-12 1.3.0");
  assert.throws(() => renderQuery("${missing}", {}), InfraError);
});

test("time-dependent expectations follow the reference instant across day and month boundaries", () => {
  const early = timeDependentExpectations(SMALL_FIXTURE, new Date("2026-09-22T08:00:00Z"));
  const late = timeDependentExpectations(SMALL_FIXTURE, new Date("2026-09-22T11:00:00Z"));
  assert.deepEqual(early["active-installations"], [[1, 1, 1]]);
  assert.deepEqual(late["active-installations"], [[1, 1, 1]]);
  const lateOnly = timeDependentExpectations({...SMALL_FIXTURE, installations: [SMALL_FIXTURE.installations[0] as Fixture["installations"][number]]}, new Date("2026-09-23T09:00:00Z"));
  assert.deepEqual(lateOnly["active-installations"], [[1, 1, 1]]);
  assert.deepEqual(timeDependentExpectations(SMALL_FIXTURE, new Date("2026-09-22T12:00:00Z"))["registration-cohorts"], [["2026-08-01", 1], ["2026-09-01", 1]]);
  assert.deepEqual(timeDependentExpectations(SMALL_FIXTURE, new Date("2026-10-01T12:00:00Z"))["registration-cohorts"], [["2026-08-01", 1], ["2026-09-01", 1]]);
  assert.deepEqual(timeDependentExpectations(SMALL_FIXTURE, new Date("2026-10-15T12:00:00Z"))["registration-cohorts"], [["2026-09-01", 1], ["2026-10-01", 1]]);
});

test("every dashboard query counts installations, not reporting periods", () => {
  // After erasure an installation reports under `<installation>.<period>`; a bare distinct_id
  // would count each period as a separate installation.
  const installationPart = "splitByChar('.', distinct_id)[1]";
  const queries = join(import.meta.dirname, "..", "infra", "posthog", "queries");
  for (const file of readdirSync(queries)) {
    const sql = readFileSync(join(queries, file), "utf8");
    assert.ok(!sql.replaceAll(installationPart, "").includes("distinct_id"), `${file} uses a bare distinct_id`);
  }
});

test("canonical queries are scoped to the cohort without a second copy of their logic", () => {
  const scoped = scopeQuery("SELECT count() FROM events WHERE event = 'installation_heartbeat' AND timestamp >= now() GROUP BY x", ["a", "b"]);
  assert.ok(scoped.includes("WHERE event = 'installation_heartbeat' AND distinct_id IN ('a', 'b') AND timestamp >= now()"));
  assert.throws(() => scopeQuery("SELECT 1 FROM events", ["a"]), InfraError);
  assert.equal(comparePanel("p", [["a", 1], ["b", 2]], [["b", 2], ["a", 1]]).pass, true);
  assert.equal(comparePanel("p", [["a", 1]], [["a", 2]]).pass, false);
  assert.equal(comparePanel("cohorts", [["2026-07-01", 2]], [["2026-07-01", 3]]).pass, false);
});

test("the Hog capability probe reports present, absent, and unexpected primitives without saving anything", async () => {
  const results = await probeHogRuntime(api(), PROJECT_ID);
  assert.equal(results.find((result) => result.name === "sha256Hex")?.status, "present");
  assert.equal(results.find((result) => result.name === "ecdsaVerify")?.status, "absent");
  assert.equal(results.find((result) => result.name === "jwtVerify")?.status, "absent");
  const surprising = await probeHogRuntime(api(), PROJECT_ID, [{name: "sha256Hex", hog: "print(sha256Hex('abc'))", expect: "other"}]);
  assert.equal(surprising[0]?.status, "unexpected");
  assert.ok(state.requests.every((request) => !request.startsWith("POST /api/projects/4242/hog_functions/") || request.includes("/new/invocations/")));
});

function probeState(overrides: Record<string, Json> = {}): string {
  const file = join(tempDir, "probe-state.json");
  writeFileSync(file, JSON.stringify({resources: [{module: "module.test", mode: "managed", type: "posthog_project", name: "this", instances: [{attributes: {id: String(PROJECT_ID), name: "Symphony for Trello (test)", organization_id: ORGANIZATION, api_token: TOKEN, ...overrides}}]}]}));
  return file;
}

test("Hog probing binds the selected test resource, live environment, organization, and token", async () => {
  assert.deepEqual(await verifyProbeTarget(api(), roles(), probeState()), {projectId: PROJECT_ID, organizationId: ORGANIZATION});
  assert.ok(state.requests.every((request) => request.startsWith("GET ")));
});

for (const [field, value] of Object.entries({id: "9999", organization_id: "wrong-org", api_token: "wrong-token", name: "wrong-name"})) {
  test(`Hog probing rejects a mismatched state ${field} before invocation`, async () => {
    await assert.rejects(verifyProbeTarget(api(), roles(), probeState({[field]: value})), InfraError);
    assert.ok(state.requests.every((request) => request.startsWith("GET ")));
  });
}

test("Hog probing rejects missing opt-in before contacting the API", async () => {
  await assert.rejects(main(["hog-probe", "--outputs", outputsFile, "--state", probeState(), "--host", host], {POSTHOG_API_KEY: KEY}), /explicit live opt-in/);
  assert.deepEqual(state.requests, []);
});

test("Hog probing rejects a production alias before contacting the API", async () => {
  const testRole = roles()[0]!;
  await assert.rejects(verifyProbeTarget(api(), [testRole, {...testRole, name: "production"}], probeState()), /distinct test role/);
  assert.deepEqual(state.requests, []);
});

test("Hog probing resolves the provider's organization alias and still checks the pinned project", async () => {
  assert.deepEqual(await verifyProbeTarget(api(), roles(), probeState({organization_id: "@current"})), {projectId: PROJECT_ID, organizationId: ORGANIZATION});
  state.organization["id"] = "another-current-organization";
  await assert.rejects(verifyProbeTarget(api(), roles(), probeState({organization_id: "@current"})), /binding differs/);
  assert.ok(state.requests.every((request) => request.startsWith("GET ")));
});

test("the explicit local probe writes a credential-free ledger and refuses to overwrite it", async () => {
  const report = join(tempDir, "probe-ledger.json");
  const args = ["hog-probe", "--outputs", outputsFile, "--state", probeState(), "--report", report, "--host", host];
  const environment = {POSTHOG_API_KEY: KEY, SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE: "1"};
  assert.equal(await main(args, environment), 0);
  const raw = readFileSync(report, "utf8");
  const ledger = JSON.parse(raw) as Record<string, unknown>;
  assert.equal(ledger["status"], "observed");
  assert.equal(ledger["projectId"], PROJECT_ID);
  assert.deepEqual(ledger["createdResources"], []);
  assert.deepEqual(ledger["capturedEvents"], []);
  assert.deepEqual(ledger["deletions"], []);
  assert.ok(!raw.includes(KEY) && !raw.includes(TOKEN));
  if (process.platform !== "win32") assert.equal(statSync(report).mode & 0o777, 0o600);
  const invocations = state.requests.filter((request) => request.includes("/new/invocations/")).length;
  await assert.rejects(main(args, environment), /EEXIST/);
  assert.equal(state.requests.filter((request) => request.includes("/new/invocations/")).length, invocations);
});

for (const field of ["id", "name", "organization", "api_token"]) {
  test(`Hog probing rejects a changed live ${field} before invocation`, async () => {
    state.environment[field] = field === "id" ? PROJECT_ID + 1 : "changed";
    await assert.rejects(verifyProbeTarget(api(), roles(), probeState()), /binding differs/);
    assert.ok(state.requests.every((request) => request.startsWith("GET ")));
  });
}

const REVIEWED_AT = new Date("2026-09-25T12:00:00Z");
const PERIOD_ID = "5095b32f-dbe1-4704-a625-5a241d331cd6.8f14e45f-ceea-467a-9575-5d0c5e5f3b8a";
const LATE_ID = "5095b32f-dbe1-4704-a625-5a241d331cd6.c9f0f895-fb98-4b7a-8e0f-2f3a1b2c3d4e";

function statusFlag(id: number, name: string, state: string, createdAt: string): Record<string, Json> {
  return {id, key: `erasure-${String(id).padStart(64, "0")}`, name, deleted: false, active: true, created_at: createdAt,
    filters: {groups: [{properties: [], rollout_percentage: 100}], multivariate: {variants: [{key: state, rollout_percentage: 100}]}}};
}

function erasureFlagFixture(): Record<string, Json>[] {
  return [
    statusFlag(1, `symphony-erasure-empty-v2|${PERIOD_ID}`, "complete", "2026-09-23T00:00:00Z"),
    statusFlag(2, `symphony-erasure-empty-v2|${LATE_ID}`, "complete", "2026-09-23T00:00:00Z"),
    statusFlag(3, `symphony-erasure-empty-v2|${PERIOD_ID}`, "complete", "2026-09-25T11:00:00Z"),
    statusFlag(4, "symphony-erasure-empty-v1", "complete", "2026-09-23T00:00:00Z"),
    statusFlag(5, "symphony-erasure-v1|0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", "complete", "2026-09-23T00:00:00Z"),
    statusFlag(6, "symphony-erasure-v1|0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c", "refused", "2026-09-24T00:00:00Z"),
    statusFlag(7, "symphony-erasure-v1|0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5d", "accepted", "2026-09-01T00:00:00Z"),
    statusFlag(8, "symphony-erasure-v1|0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5e", "pending", "2026-09-25T11:00:00Z"),
  ];
}

test("erasure flag review archives only settled results and reports what needs the maintainer", async () => {
  state.flags = erasureFlagFixture();
  state.eventCounts = {[LATE_ID]: 2};
  const dryRun = await reviewErasureFlags(api(), PROJECT_ID, REVIEWED_AT, false);
  assert.deepEqual(dryRun.map((review) => [review.id, review.action]), [
    [1, "archive"], [2, "late-events"], [3, "keep"], [4, "archive"], [5, "archive"], [6, "refused"], [7, "stale"], [8, "keep"],
  ]);
  assert.equal(dryRun.find((review) => review.id === 2)?.remainingEvents, 2);
  assert.ok(!state.requests.some((request) => request.startsWith("PATCH")));

  await reviewErasureFlags(api(), PROJECT_ID, REVIEWED_AT, true);
  assert.deepEqual(state.flags.filter((flag) => flag["deleted"] === true).map((flag) => flag["id"]), [1, 4, 5]);
});

test("erasure flag review refuses an empty result whose name carries no valid analytics ID", async () => {
  state.flags = [statusFlag(9, "symphony-erasure-empty-v2|x' OR 1=1 --", "complete", "2026-09-23T00:00:00Z")];
  await assert.rejects(reviewErasureFlags(api(), PROJECT_ID, REVIEWED_AT, true), InfraError);
  assert.ok(!state.requests.some((request) => request.includes("/query/") || request.startsWith("PATCH")));
});

test("verification fails when the production project differs from the committed production project", async () => {
  const production = roles().map((role) => ({...role, name: "production"}));
  const mismatch = await verify(api(), production, PROJECT_ID + 1);
  const match = await verify(api(), production, PROJECT_ID);
  assert.equal(mismatch.find((check) => check.item === "project.id")?.status, "FAIL");
  assert.equal(match.find((check) => check.item === "project.id")?.status, "PASS");
});

test("verification flags a status flag count near PostHog's flag limit", async () => {
  const erasureRoles = roles().map((role) => ({...role, policy: {...role.policy, erasure_function_id: "erasure-uuid"}}));
  state.flags = Array.from({length: ERASURE_FLAG_WARNING}, (_, index) => statusFlag(index + 1, "symphony-erasure-empty-v1", "complete", "2026-09-23T00:00:00Z"));
  const crowded = (await verify(api(), erasureRoles)).find((check) => check.item === "erasure.status_flags");
  state.flags = state.flags.slice(1);
  const below = (await verify(api(), erasureRoles)).find((check) => check.item === "erasure.status_flags");
  assert.equal(crowded?.status, "FAIL");
  assert.equal(crowded?.required, false);
  assert.equal(below?.status, "PASS");
});
