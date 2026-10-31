import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {test, type TestContext} from "node:test";
import {LIFECYCLE_PASS_STATUS} from "./erasure-lifecycle-ledger.ts";
import {main, mailboxKey, type Owner} from "./erasure-lifecycle-live.ts";
import {handlerTemplateSha256} from "./erasure-service.ts";
import {asObject, type Json} from "./posthog-api.ts";
import {POLICY_SETTINGS} from "./posthog-state-files.ts";

type FlagKind = "symphony-erasure-empty-v2" | "symphony-erasure-v1";
type Outcome = "complete" | "incomplete";
interface Flag {id: number; key: string; name: string; deleted: boolean}
interface Source {id: string; name: string; type: string; enabled: boolean; deleted: boolean}
interface SavedLedger {
  status?: string;
  error?: string;
  handlerSha256: string;
  canaryCleanupAccepted?: boolean;
  sources: {archived?: boolean}[];
  checks: {name: string; passed: boolean}[];
}

/** Resumes after provider completion and replay, with a fake API that implements acknowledgment's
 * documented side effects. Every unrecognized request fails locally; no fetch reaches a host. */
function resumedLifecycle(t: TestContext, kind: FlagKind, outcome: Outcome, behavior: {privateFlagDelay?: number; duplicateFlag?: boolean} = {}) {
  const directory = mkdtempSync(join(tmpdir(), "erasure-lifecycle-runner-"));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  mkdirSync(join(directory, ".terraform"));
  const json = (name: string, value: unknown): void => writeFileSync(join(directory, name), JSON.stringify(value));
  const project = {id: 123, organization: "synthetic-organization", name: "test", api_token: "phc_synthetic"};
  const policy = Object.fromEntries([...POLICY_SETTINGS, "geoip_enabled"].map(key => [key, false]));
  json("outputs.json", {projects: {value: {test: {id: project.id, dashboard_id: 1, insights: {}}}}, capture_tokens: {value: {test: project.api_token}}, privacy_policy: {value: {test: policy}}});
  json("terraform.tfstate", {resources: [{module: "module.test", mode: "managed", type: "posthog_project", name: "this", instances: [{attributes: {id: project.id, organization_id: project.organization, name: project.name, api_token: project.api_token}}]}]});
  json(".terraform/terraform.tfstate", {backend: {config: {path: join(directory, "terraform.tfstate")}}});
  writeFileSync(join(directory, "key"), "phx_synthetic", {mode: 0o600});
  const owner: Owner = {installation_id: randomUUID(), key_version: "k1", secret: "a".repeat(64)};
  const periods = [1, 2].map(() => ({id: randomUUID(), operation: randomUUID(), complete: true, outcome, lostResponseVerified: true, admissionOrderVerified: true}));
  const partial = {id: randomUUID(), operation: randomUUID()};
  const ledger = {run: randomUUID(), projectId: project.id, master: "b".repeat(64), started: new Date().toISOString(), periods, checks: [], sources: [], owner, emptyVerified: true, canary: randomUUID(), canaryCaptured: true, partialRecoveryVerified: true, replayVerified: true, partial, handlerSha256: handlerTemplateSha256()};
  json("ledger.json", ledger);
  const scope = `test:${project.id}:${ledger.run}`;
  const operations = [...periods, partial];
  const flags: Flag[] = operations.map((operation, index) => ({id: index + 1, key: mailboxKey(owner, scope, operation.id), name: `${kind}|${kind === "symphony-erasure-v1" ? randomUUID() : `${owner.installation_id}.${operation.id}`}`, deleted: false}));
  const acknowledgments: string[] = [];
  const unexpected: string[] = [];
  const privateReads = new Map<string, number>();
  const visibleMailboxes = new Set<string>();
  let source: Source | undefined;
  let canaryDeleted = false;
  const canaryPerson = {id: randomUUID(), distinct_ids: [ledger.canary]};
  const base = `/api/projects/${project.id}`;
  const response = (value: unknown, status = 200): Response => Response.json(value, {status});
  t.mock.method(globalThis, "fetch", async (input: string | URL | Request, options: RequestInit = {}): Promise<Response> => {
    const url = new URL(input instanceof Request ? input.url : input.toString());
    const method = options.method ?? "GET";
    const body = typeof options.body === "string" ? asObject(JSON.parse(options.body) as Json, "fake request") : {};
    if (url.origin === "https://eu.posthog.com") {
      if (method === "GET" && [base + "/", `/api/environments/${project.id}/`].includes(url.pathname)) return response(project);
      if (url.pathname === `${base}/hog_functions/`) {
        if (method === "POST") {
          source = {id: randomUUID(), name: String(body["name"]), type: "source_webhook", enabled: false, deleted: false};
          return response(source, 201);
        }
        if (method === "GET") return response({results: source && !source.deleted ? [source] : [], next: null});
      }
      if (source && url.pathname === `${base}/hog_functions/${source.id}/`) {
        if (method === "PATCH") {
          if (typeof body["enabled"] === "boolean") source.enabled = body["enabled"];
          if (typeof body["deleted"] === "boolean") source.deleted = body["deleted"];
          return response(source);
        }
        if (method === "GET") return response(source);
      }
      if (source && method === "GET" && [`${base}/hog_functions/${source.id}/logs/`, `${base}/hog_functions/${source.id}/metrics/`].includes(url.pathname)) return response({});
      if (method === "GET" && url.pathname === `${base}/feature_flags/`) {
        const mailbox = url.searchParams.get("search") ?? "";
        const reads = (privateReads.get(mailbox) ?? 0) + 1;
        privateReads.set(mailbox, reads);
        const matching = reads <= (behavior.privateFlagDelay ?? 0) ? [] : flags.filter(flag => flag.key === mailbox && !flag.deleted);
        if (matching.length > 0) visibleMailboxes.add(mailbox);
        return response({results: behavior.duplicateFlag ? [...matching, ...matching] : matching, next: null});
      }
      if (method === "GET" && url.pathname === `${base}/persons/` && url.searchParams.get("distinct_id") === ledger.canary) return response({results: canaryDeleted ? [] : [canaryPerson], next: null});
      if (method === "GET" && url.pathname === `${base}/persons/deletion_status/` && url.searchParams.get("person_uuid") === canaryPerson.id) return response({results: []});
      if (method === "POST" && url.pathname === `${base}/query/`) {
        const query = asObject(body["query"] ?? null, "fake query");
        assert.equal(asObject(query["values"] ?? null, "query values")["id"], ledger.canary);
        return response({is_cached: false, results: [[1]]});
      }
      if (method === "POST" && url.pathname === `${base}/persons/bulk_delete/`) {
        assert.deepEqual(body, {ids: [canaryPerson.id], delete_events: true, delete_recordings: true});
        canaryDeleted = true;
        return response({deletion_errors: []});
      }
    }
    if (source && method === "POST" && url.href === `https://webhooks.eu.posthog.com/public/webhooks/${source.id}`) {
      const parts = String(body["payload"]).split("|");
      const operationIndex = operations.findIndex(operation => operation.id === parts[4]);
      assert.notEqual(operationIndex, -1, "only a fixture-owned period is invoked");
      assert.ok(parts[1] === "status" || parts[1] === "ack", "resumed completion does not repeat erase or capture");
      if (parts[1] === "ack") {
        if (behavior.privateFlagDelay) assert.ok(visibleMailboxes.has(flags[operationIndex]!.key), "ack waits until the private flag identifies the result kind");
        acknowledgments.push(parts[4]!);
        if (kind === "symphony-erasure-v1" && outcome === "complete") flags[operationIndex]!.deleted = true;
      }
      return response({}, 201);
    }
    if (method === "POST" && url.origin === "https://eu.i.posthog.com" && url.pathname === "/flags") {
      const keys = body["flag_keys_to_evaluate"];
      assert.ok(Array.isArray(keys) && keys.length === 1 && typeof keys[0] === "string");
      assert.ok(flags.some(flag => flag.key === keys[0]), "public status reads a fixture-owned mailbox");
      return response({errorsWhileComputingFlags: false, flags: {[keys[0]]: {variant: outcome}}});
    }
    unexpected.push(`${method} ${url}`);
    throw new Error(`Unmocked network call blocked: ${method} ${url}`);
  });
  const timer = globalThis.setTimeout;
  t.mock.method(globalThis, "setTimeout", (callback: () => void) => timer(callback, 0));
  t.mock.method(console, "log", () => {});
  const environment = {SYMPHONY_TRELLO_ERASURE_LIFECYCLE: "1", SYMPHONY_TRELLO_POSTHOG_STATE_DIR: directory, SYMPHONY_TRELLO_ERASURE_LIFECYCLE_DIR: directory, SYMPHONY_TRELLO_POSTHOG_KEY_FILE: join(directory, "key")};
  const original = new Map(Object.keys(environment).map(key => [key, process.env[key]]));
  const argv = process.argv;
  Object.assign(process.env, environment);
  process.argv = [...argv, "--resume"];
  t.after(() => {
    process.argv = argv;
    for (const [key, value] of original) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  });
  return {flags, acknowledgments, unexpected, operations, privateReads, ledger: (): SavedLedger => JSON.parse(readFileSync(join(directory, "ledger.json"), "utf8")) as SavedLedger, source: () => source};
}

const scenarios = [
  {name: "resumed completed lifecycle passes with replay-created empty flags retained", kind: "symphony-erasure-empty-v2", outcome: "complete", archived: false},
  {name: "resumed completed lifecycle archives person-bound request flags", kind: "symphony-erasure-v1", outcome: "complete", archived: true},
] as const;

for (const scenario of scenarios) {
  test(scenario.name, async t => {
    // given
    const fixture = resumedLifecycle(t, scenario.kind, scenario.outcome);

    // when
    await main();

    // then
    const ledger = fixture.ledger();
    assert.equal(ledger.status, LIFECYCLE_PASS_STATUS);
    assert.equal(ledger.error, undefined);
    assert.equal(ledger.checks.find(check => !check.passed), undefined, "every recorded lifecycle check passed");
    assert.deepEqual(fixture.flags.map(flag => flag.deleted), [scenario.archived, scenario.archived, scenario.archived]);
    assert.deepEqual(fixture.acknowledgments, fixture.operations.map(operation => operation.id));
    assert.equal(ledger.canaryCleanupAccepted, true);
    assert.deepEqual(ledger.sources.map(source => source.archived), [true]);
    assert.equal(fixture.source()?.deleted, true);
    assert.equal(fixture.source()?.enabled, false);
    assert.deepEqual(fixture.unexpected, []);
  });
}

test("public completion waits for the replay-created private flag before acknowledgment", async t => {
  // given
  const fixture = resumedLifecycle(t, "symphony-erasure-empty-v2", "complete", {privateFlagDelay: 2});

  // when
  await main();

  // then
  assert.equal(fixture.ledger().status, LIFECYCLE_PASS_STATUS);
  assert.deepEqual(fixture.flags.map(flag => flag.deleted), [false, false, false]);
  assert.deepEqual(fixture.acknowledgments, fixture.operations.map(operation => operation.id));
  assert.equal(fixture.privateReads.size, 3, "each operation waits for its own private flag");
  assert.deepEqual(fixture.unexpected, []);
});

test("duplicate private bindings fail the lifecycle before any acknowledgment", async t => {
  // given
  const fixture = resumedLifecycle(t, "symphony-erasure-empty-v2", "complete", {duplicateFlag: true});

  // when
  await assert.rejects(main(), /Invalid status flag binding/);

  // then
  assert.equal(fixture.ledger().status, undefined);
  assert.match(fixture.ledger().error ?? "", /Invalid status flag binding/);
  assert.deepEqual(fixture.acknowledgments, []);
  assert.deepEqual(fixture.flags.map(flag => flag.deleted), [false, false, false]);
  assert.equal(fixture.source()?.deleted, true, "a rejected gate still archives its temporary source");
  assert.deepEqual(fixture.unexpected, []);
});
