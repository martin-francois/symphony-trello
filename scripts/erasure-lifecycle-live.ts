// Explicit TEST-only lifecycle gate. Credentials stay in its owner-only ledger, never in output.
import {randomBytes, randomUUID, createHmac} from "node:crypto";
import {mkdirSync, readFileSync, writeFileSync, renameSync, chmodSync} from "node:fs";
import {resolve, join} from "node:path";
import {fileURLToPath} from "node:url";
import {PostHogApi, readKey, readRoles, verifyProbeTarget, type Json} from "./posthog-infra.ts";
import {erasureService, handlerTemplateSha256} from "./erasure-service.ts";

/** Seconds between a signed operation's issue and expiry times. The native handler in
 * `infra/posthog/erasure-service.hog.tftpl` rejects any longer lifetime; a test pins the two. */
export const SIGNATURE_LIFETIME_SECONDS = 900;

type JsonObject = {readonly [key: string]: Json};

/** The credential the native issuer returned, stored verbatim in the ledger. */
export interface Owner {
  readonly status?: string;
  readonly installation_id: string;
  readonly key_version: string;
  readonly secret: string;
}

export interface LedgerSource {
  readonly id: string;
  readonly name: string;
  archived?: boolean;
}

/** The ledger fields that source cleanup reads and writes. */
export interface SourceLedger {
  readonly run: string;
  readonly sources: LedgerSource[];
  plannedSource?: string;
  cleanupFailed?: boolean;
}

interface LedgerCheck {
  readonly name: string;
  readonly passed: boolean;
  readonly at: string;
}

interface Period {
  readonly id: string;
  readonly operation: string;
  complete: boolean;
  captured?: boolean;
  personId?: string;
  requested?: boolean;
  admissionObserved?: boolean;
  lostResponseVerified?: boolean;
  admissionOrderVerified?: boolean;
}

interface PartialFixture {
  readonly id: string;
  readonly operation: string;
  personId?: string;
  queued?: boolean;
}

/** The resumable ledger. Field names are its on-disk format; a resumed run reads older files. */
interface Ledger extends SourceLedger {
  readonly projectId: number;
  readonly master: string;
  readonly started: string;
  readonly periods: Period[];
  readonly checks: LedgerCheck[];
  status?: string;
  error?: string;
  owner?: Owner;
  canary?: string;
  canaryPersonId?: string;
  canaryCaptured?: boolean;
  canaryCleanupAccepted?: boolean;
  emptyPeriod?: string;
  emptyOperation?: string;
  emptyVerified?: boolean;
  partial?: PartialFixture;
  partialRecoveryVerified?: boolean;
  replayVerified?: boolean;
  handlerSha256?: string;
  handlerMixed?: boolean;
}

/** The ledger fields that bind a run to one handler version. */
export interface HandlerLedger {
  handlerSha256?: string;
  handlerMixed?: boolean;
}

/** A pass authorizes production only for the handler that ran every stage: the activation
 * precondition in infra/posthog/modules/project/erasure.tf compares this hash with the template.
 * A resumed run on another handler, or a ledger from before this field, can never authorize one. */
export function recordHandler(ledger: HandlerLedger, sha256: string, fresh: boolean): void {
  if (fresh) {
    ledger.handlerSha256 = sha256;
  } else if (ledger.handlerSha256 !== sha256) {
    ledger.handlerMixed = true;
  }
}

/** The management calls source cleanup makes; `PostHogApi` satisfies it. */
export interface SourceApi {
  listAll(path: string): Promise<readonly JsonObject[]>;
  get(path: string): Promise<Json>;
  patch(path: string, body: JsonObject): Promise<unknown>;
}

interface HogFunction {
  readonly id: string;
  readonly name: string;
  readonly type: string;
}

interface Person {
  readonly id: string;
  readonly distinct_ids: readonly string[];
}

interface PersonListing {
  readonly results?: readonly Person[];
  readonly next?: string | null;
}

interface DeletionRecord {
  readonly person_uuid: string;
  readonly status: string;
  readonly delete_verified_at?: string | null;
}

interface DeletionStatus {
  readonly results?: readonly DeletionRecord[];
}

interface BulkDeleteResult {
  readonly deletion_errors?: readonly Json[];
}

interface QueryResult {
  readonly is_cached?: boolean;
  readonly results?: readonly (readonly Json[])[];
}

interface FeatureFlagListing {
  readonly results: readonly {readonly key: string; readonly deleted?: boolean}[];
}

interface FlagsResult {
  readonly errorsWhileComputingFlags?: boolean;
  readonly flags?: Readonly<Record<string, {readonly variant?: string} | undefined>>;
}

interface Project {
  readonly api_token: string;
}

interface TerraformBackend {
  readonly backend: {readonly config: {readonly path: string}};
}

interface SourceResponse {
  readonly status: number;
  readonly body: Json;
}

/** Names the response fields the runner reads. It adds no validation: the runner's explicit
 * checks stay as they were, so a resumed live run makes the same decisions as the run that began. */
function shape<T>(value: Json | readonly JsonObject[]): T {
  return value as unknown as T;
}

const hmac = (key: string, value: string): string => createHmac("sha256", key).update(value).digest("hex");
export function signedOperation(owner: Owner, scope: string, period: string, operation: string, action: string, now: number = Math.floor(Date.now() / 1000)): string {
  const subject = `h1-${owner.key_version}-${owner.installation_id}`;
  const unsigned = ["h2", action, scope, subject, period, operation, now, now + SIGNATURE_LIFETIME_SECONDS].join("|");
  return `${unsigned}|${hmac(owner.secret, unsigned)}`;
}

/** Reconciles exact run-owned names before creation, including a lost creation response. */
export async function archiveRunSources(api: SourceApi, base: string, ledger: SourceLedger, save: () => void): Promise<void> {
  try {
    const names = new Set(ledger.sources.map(source => source.name));
    if (ledger.plannedSource) names.add(ledger.plannedSource);
    const prefix = `erasure-lifecycle-${ledger.run}-`;
    if ([...names].some(name => !name.startsWith(prefix))) throw new Error("Source ledger ownership mismatch");
    const remote = shape<readonly HogFunction[]>(await api.listAll(`${base}/hog_functions/?type=source_webhook`));
    for (const source of remote.filter(source => names.has(source.name))) {
      const known = ledger.sources.find(item => item.name === source.name);
      if (known && known.id !== source.id) throw new Error("Source identity mismatch");
      const live = shape<HogFunction>(await api.get(`${base}/hog_functions/${source.id}/`));
      if (live.name !== source.name || live.type !== "source_webhook") throw new Error("Source cleanup ownership mismatch");
      await api.patch(`${base}/hog_functions/${source.id}/`, {enabled: false, deleted: true});
      if (known) known.archived = true;
      else ledger.sources.push({id: source.id, name: source.name, archived: true});
      save();
    }
    delete ledger.plannedSource;
    save();
    delete ledger.cleanupFailed;
    save();
  } catch (error) {
    ledger.cleanupFailed = true;
    save();
    throw error;
  }
}

async function cleanupVerifiedCanary(api: PostHogApi, base: string, ledger: Ledger, save: () => void): Promise<void> {
  if (ledger.canaryCleanupAccepted) return;
  const result = shape<PersonListing>(await api.get(`${base}/persons/?distinct_id=${ledger.canary}`));
  if (!Array.isArray(result.results) || result.next || result.results.length > 1) throw new Error("Ambiguous canary cleanup");
  const person = result.results[0];
  if (person) {
    if (person.id !== ledger.canaryPersonId || person.distinct_ids.length !== 1 || person.distinct_ids[0] !== ledger.canary) throw new Error("Canary cleanup ownership mismatch");
    const cleaned = shape<BulkDeleteResult>(await api.post(`${base}/persons/bulk_delete/`, {ids: [person.id], delete_events: true, delete_recordings: true}));
    if (cleaned.deletion_errors?.length) throw new Error("Canary cleanup failed");
  }
  ledger.canaryCleanupAccepted = true; save();
}

export async function main(): Promise<void> {
  if (process.env["SYMPHONY_TRELLO_ERASURE_LIFECYCLE"] !== "1") throw new Error("Explicit TEST lifecycle opt-in required");
  const stateDir = process.env["SYMPHONY_TRELLO_POSTHOG_STATE_DIR"] ?? `${process.env["HOME"]}/.local/state/symphony-trello/posthog`;
  const stateFile = join(stateDir, "terraform.tfstate");
  const backend = JSON.parse(readFileSync(join(stateDir, ".terraform/terraform.tfstate"), "utf8")) as TerraformBackend;
  if (backend.backend.config.path !== stateFile) throw new Error("Backend mismatch");
  const api = new PostHogApi("https://eu.posthog.com", readKey(process.env["SYMPHONY_TRELLO_POSTHOG_KEY_FILE"] ?? `${process.env["HOME"]}/posthog-personal-api-key`));
  const target = await verifyProbeTarget(api, readRoles(join(stateDir, "outputs.json")), stateFile);
  const directory = process.env["SYMPHONY_TRELLO_ERASURE_LIFECYCLE_DIR"] ?? `${process.env["HOME"]}/.local/state/symphony-trello/erasure-lifecycle`;
  mkdirSync(directory, {recursive: true, mode: 0o700});
  const path = join(directory, "ledger.json");
  const resume = process.argv.includes("--resume");
  let ledger: Ledger;
  if (resume) {
    ledger = JSON.parse(readFileSync(path, "utf8")) as Ledger;
    if (ledger.projectId !== target.projectId || !/^[0-9a-f-]{36}$/.test(ledger.run) || !/^[0-9a-f]{64}$/.test(ledger.master)) throw new Error("Ledger target mismatch");
  } else {
    ledger = {run: randomUUID(), projectId: target.projectId, master: randomBytes(32).toString("hex"), started: new Date().toISOString(), periods: [], checks: [], sources: []};
    writeFileSync(path, JSON.stringify(ledger), {mode: 0o600, flag: "wx"});
  }
  delete ledger.error;
  chmodSync(path, 0o600);
  const save = (): void => {
    const temporary = `${path}.${randomUUID()}.tmp`;
    writeFileSync(temporary, JSON.stringify(ledger, null, 2), {mode: 0o600, flag: "wx"});
    renameSync(temporary, path);
  };
  recordHandler(ledger, handlerTemplateSha256(), !resume);
  save();
  const check = (name: string, passed: boolean): void => {
    ledger.checks.push({name, passed, at: new Date().toISOString()}); save();
    console.log(JSON.stringify({check: name, passed}));
    if (!passed) throw new Error(name);
  };
  const base = `/api/projects/${target.projectId}`;
  const scope = `test:${target.projectId}:${ledger.run}`;
  const name = `erasure-lifecycle-${ledger.run}-${randomUUID()}`;
  let source: HogFunction;
  await archiveRunSources(api, base, ledger, save);
  if (ledger.status === "TWO_PERIOD_LIFECYCLE_PASS") {
    await cleanupVerifiedCanary(api, base, ledger, save);
    console.log(JSON.stringify({status: ledger.status})); return;
  }
  const project = shape<Project>(await api.get(`${base}/`));
  const pause = (): Promise<void> => new Promise(r => setTimeout(r, 2000));
  const person = async (distinct: string): Promise<Person | undefined> => {
    const result = shape<PersonListing>(await api.get(`${base}/persons/?distinct_id=${distinct}`));
    if (!Array.isArray(result.results) || result.next || result.results.length > 1) throw new Error("Ambiguous person lookup");
    const p = result.results[0];
    if (p && (p.distinct_ids.length !== 1 || p.distinct_ids[0] !== distinct)) throw new Error("Merged synthetic person");
    return p;
  };
  const rows = async (distinct: string): Promise<number> => {
    const result = shape<QueryResult>(await api.post(`${base}/query/`, {query: {kind: "HogQLQuery", query: "SELECT count() FROM events WHERE distinct_id = {id} AND properties.poc_run = {run}", values: {id: distinct, run: ledger.run}}, refresh: "force_blocking"}));
    if (result.is_cached) throw new Error("Deletion verification query returned cached data");
    const count = result.results?.[0]?.[0];
    if (!Number.isSafeInteger(count)) throw new Error("Unrecognized event query result");
    return count as number;
  };
  const capture = async (distinct: string): Promise<void> => {
    const response = await fetch("https://eu.i.posthog.com/i/v0/e/", {method: "POST", redirect: "manual", signal: AbortSignal.timeout(15000), headers: {"Content-Type": "application/json"}, body: JSON.stringify({api_key: project.api_token, event: "ownership_lifecycle_poc", distinct_id: distinct, properties: {$process_person_profile: true, poc_run: ledger.run}})});
    await response.body?.cancel();
    if (!response.ok) throw new Error("Synthetic capture rejected");
    for (let i = 0; i < 40; i++) {
      if (await person(distinct) && await rows(distinct) > 0) return;
      await pause();
    }
    throw new Error("Synthetic event did not become visible");
  };
  try {
    ledger.plannedSource = name; save();
    source = shape<HogFunction>(await api.post(`${base}/hog_functions/`, {
      type: "source_webhook", name, description: "Temporary owned TEST erasure lifecycle gate", enabled: false,
      hog: erasureService(scope, target.projectId), filters: {},
      inputs: {master_k1: {value: ledger.master}, api_key: {value: readKey(process.env["SYMPHONY_TRELLO_POSTHOG_KEY_FILE"] ?? `${process.env["HOME"]}/posthog-personal-api-key`)}},
      inputs_schema: [{key: "master_k1", type: "string", secret: true, required: true}, {key: "api_key", type: "string", secret: true, required: true}],
    }));
    ledger.sources.push({id: source.id, name}); save();
    await api.patch(`${base}/hog_functions/${source.id}/`, {enabled: true});
    const call = async (payload: string, discardResponse = false): Promise<SourceResponse | undefined> => {
      for (let i = 0; i < 10; i++) {
        const r = await fetch(`https://webhooks.eu.posthog.com/public/webhooks/${source.id}`, {method: "POST", redirect: "manual", signal: AbortSignal.timeout(15000), headers: {"Content-Type": "application/json"}, body: JSON.stringify({payload})});
        if (r.status === 404) {await r.body?.cancel(); await pause(); continue;}
        if (discardResponse) {
          await r.body?.cancel();
          check("native ingress accepted before response loss", r.status === 201);
          return;
        }
        const raw = await r.text();
        if (raw.length > 12000) throw new Error("Oversized source response");
        return {status: r.status, body: JSON.parse(raw) as Json};
      }
      throw new Error("Source unavailable");
    };
    if (!ledger.owner) {
      // Only a discarded response returns nothing; this call keeps its response.
      const issued = (await call("issue-v1"))!;
      const issuedBody = shape<Owner>(issued.body);
      check("issuer chooses and returns a credential", issued.status === 200 && issuedBody.status === "issued");
      ledger.owner = issuedBody;
      check("native derivation matches reference", ledger.owner.secret === hmac(ledger.master, `symphony-trello/owner/v1|${scope}|h1-k1-${ledger.owner.installation_id}`));
      ledger.canary = randomUUID(); save();
    }
    const owner = ledger.owner;
    const publicStatus = async (mailbox: string): Promise<string> => {
      const r = await fetch("https://eu.i.posthog.com/flags?v=2", {method: "POST", redirect: "manual", signal: AbortSignal.timeout(15000), headers: {"Content-Type": "application/json"}, body: JSON.stringify({api_key: project.api_token, distinct_id: "erasure-status", flag_keys_to_evaluate: [mailbox]})});
      const body = await r.text();
      checkNoIdentifiers(body, owner, owner.installation_id);
      const result = JSON.parse(body) as FlagsResult;
      if (r.status !== 200 || result.errorsWhileComputingFlags !== false) throw new Error("Public status unavailable");
      return result.flags?.[mailbox]?.variant ?? "pending";
    };
    if (!ledger.emptyVerified) {
      ledger.emptyPeriod ??= randomUUID();
      ledger.emptyOperation ??= randomUUID();
      save();
      const mailbox = `erasure-${hmac(owner.secret, `symphony-trello/status/v1|${scope}|${ledger.emptyPeriod}`)}`;
      let observed = "pending";
      for (let attempt = 0; attempt < 60; attempt++) {
        if (attempt % 10 === 0) await call(signedOperation(owner, scope, ledger.emptyPeriod, ledger.emptyOperation, "erase"));
        observed = await publicStatus(mailbox);
        if (observed === "complete") break;
        await pause();
      }
      check("missing first capture completes only after fresh empty-data check", observed === "complete");
      ledger.emptyVerified = true; save();
      await call(signedOperation(owner, scope, ledger.emptyPeriod, ledger.emptyOperation, "ack"));
    }
    // The canary is set together with the owner above, so a ledger with an owner always has one.
    if (!ledger.canaryCaptured) {await capture(ledger.canary!); ledger.canaryCaptured = true; save();}
    const canarySurvives = async (): Promise<boolean> => {
      const p = await person(ledger.canary!);
      if (!p) return false;
      const deletion = shape<DeletionStatus>(await api.get(`${base}/persons/deletion_status/?person_uuid=${p.id}&status=all`));
      return deletion.results?.length === 0 && await rows(ledger.canary!) > 0;
    };
    if (!ledger.partialRecoveryVerified) {
      ledger.partial ??= {id: randomUUID(), operation: randomUUID()};
      save();
      const partial = ledger.partial;
      const distinct = `${owner.installation_id}.${partial.id}`;
      if (!partial.personId) {
        await capture(distinct);
        partial.personId = (await person(distinct))!.id;
        save();
      }
      const mailbox = `erasure-${hmac(owner.secret, `symphony-trello/status/v1|${scope}|${partial.id}`)}`;
      const flags = shape<FeatureFlagListing>(await api.get(`${base}/feature_flags/?search=${mailbox}`));
      if (!flags.results.some(flag => flag.key === mailbox)) {
        await api.post(`${base}/feature_flags/`, {key: mailbox, name: `symphony-erasure-v1|${partial.personId}`, active: true,
          filters: {groups: [{properties: [], rollout_percentage: 100}], multivariate: {variants: [{key: "pending", rollout_percentage: 100}]}}});
      }
      if (!partial.queued) {
        // Reproduce the recoverable postcondition without inducing an outage: events queued, profile retained.
        const queued = shape<BulkDeleteResult>(await api.post(`${base}/persons/bulk_delete/`, {ids: [partial.personId], keep_person: true, delete_events: true}));
        check("partial-failure fixture retains its owned profile", !queued.deletion_errors?.length && (await person(distinct))?.id === partial.personId);
        partial.queued = true; save();
      }
      await call(signedOperation(owner, scope, partial.id, partial.operation, "status"));
      let removed = false;
      for (let attempt = 0; attempt < 90; attempt++) {
        if (!await person(distinct)) {removed = true; break;}
        await pause();
      }
      check("native status retries outstanding profile deletion", removed);
      check("partial-failure recovery preserves unrelated canary", await canarySurvives());
      ledger.partialRecoveryVerified = true; save();
    }
    for (let index = 0; index < 2; index++) {
      let period = ledger.periods[index];
      if (!period) {
        period = {id: randomUUID(), operation: randomUUID(), complete: false};
        ledger.periods.push(period); save();
      }
      const distinct = `${owner.installation_id}.${period.id}`;
      if (period.complete) continue;
      if (!period.captured) {await capture(distinct); period.captured = true; period.personId = (await person(distinct))!.id; save();}
      const proof = (action: string): string => signedOperation(owner, scope, period.id, period.operation, action);
      if (!period.requested) {
        // A forgery is never a discarded call, so its response is present.
        const forged = (await call(proof("erase").slice(0, -64) + "0".repeat(64)))!;
        check(`period ${index + 1}: random forgery rejected`, forged.status === 401);
        period.requested = true; save();
      }
      if (index === 1 && !ledger.replayVerified) {
        // The loop reaches the second period only after the first exists.
        const old = ledger.periods[0]!;
        const oldMailbox = `erasure-${hmac(owner.secret, `symphony-trello/status/v1|${scope}|${old.id}`)}`;
        const activeFlags = async (): Promise<FeatureFlagListing["results"]> => shape<FeatureFlagListing>(await api.get(`${base}/feature_flags/?search=${oldMailbox}`)).results.filter(flag => flag.key === oldMailbox && !flag.deleted);
        // Remove the prior completion result, then require a newly completed replay result.
        await call(signedOperation(owner, scope, old.id, old.operation, "ack"));
        for (let attempt = 0; attempt < 60 && (await activeFlags()).length; attempt++) await pause();
        check("prior status archived before replay", (await activeFlags()).length === 0);
        await call(signedOperation(owner, scope, old.id, old.operation, "erase"));
        let replayComplete = false;
        for (let attempt = 0; attempt < 60; attempt++) {
          if ((await activeFlags()).length === 1 && await publicStatus(oldMailbox) === "complete") {replayComplete = true; break;}
          await pause();
        }
        check("first-period replay finished processing", replayComplete);
        const nextDeletion = shape<DeletionStatus>(await api.get(`${base}/persons/deletion_status/?person_uuid=${period.personId}&status=all`));
        check("processed replay leaves second period unqueued and present", nextDeletion.results?.length === 0 && (await person(distinct))?.id === period.personId && await rows(distinct) > 0);
        ledger.replayVerified = true; save();
        await call(signedOperation(owner, scope, old.id, old.operation, "ack"));
      }
      if (!period.admissionObserved || !period.lostResponseVerified) {
        const retriedProof = proof("erase");
        // Deliver the request, discard its response, then retry identical bytes and the same operation.
        await call(retriedProof, true);
        let admitted = false;
        for (let attempt = 0; attempt < 90; attempt++) {
          const queued = shape<DeletionStatus>(await api.get(`${base}/persons/deletion_status/?person_uuid=${period.personId}&status=all`));
          if (queued.results?.length === 1 && queued.results[0]!.person_uuid === period.personId) {admitted = true; break;}
          await pause();
        }
        check(`period ${index + 1}: deletion admitted without further client writes`, admitted);
        check(`period ${index + 1}: profile retained until event admission is observed`, (await person(distinct))?.id === period.personId);
        await call(retriedProof);
        const retried = shape<DeletionStatus>(await api.get(`${base}/persons/deletion_status/?person_uuid=${period.personId}&status=all`));
        check(`period ${index + 1}: lost response retry retained one deletion record`, retried.results?.length === 1 && retried.results[0]!.person_uuid === period.personId);
        period.admissionOrderVerified = true;
        period.admissionObserved = true;
        period.lostResponseVerified = true;
        save();
      }
      const mailbox = `erasure-${hmac(owner.secret, `symphony-trello/status/v1|${scope}|${period.id}`)}`;
      let observed = "pending";
      for (let attempt = 0; attempt < 90; attempt++) {
        if (attempt % 10 === 0) await call(proof("status"));
        observed = await publicStatus(mailbox);
        if (observed === "refused") throw new Error("Synthetic erasure refused");
        if (observed === "accepted" || observed === "complete") break;
        await pause();
      }
      check(`period ${index + 1}: provider-backed acceptance`, observed === "accepted" || observed === "complete");
      const status = shape<DeletionStatus>(await api.get(`${base}/persons/deletion_status/?person_uuid=${period.personId}&status=all`));
      const deletion = status.results?.[0];
      check(`period ${index + 1}: matching deletion record`, status.results?.length === 1 && deletion!.person_uuid === period.personId);
      check("unrelated canary retains events, profile and no deletion job", await canarySurvives());
      // The matching-record check above throws unless exactly one record exists.
      if (deletion!.status !== "completed" || !deletion!.delete_verified_at) {
        ledger.status = "ACCEPTED_PHYSICAL_COMPLETION_PENDING"; save();
        console.log(JSON.stringify({status: ledger.status, resume: "Run this command with --resume after provider completion"}));
        return;
      }
      // Refresh the public result from the verified provider state, including a restarted source.
      for (let attempt = 0; observed !== "complete" && attempt < 40; attempt++) {
        if (attempt % 10 === 0) await call(proof("status"));
        await pause();
        observed = await publicStatus(mailbox);
      }
      check(`period ${index + 1}: physical event absence and public completion`, observed === "complete" && await rows(distinct) === 0 && !await person(distinct));
      period.complete = true; save();
      await call(proof("ack"));
    }
    check("partial provider failure recovered", ledger.partialRecoveryVerified === true);
    check("both periods include hosted lost-response recovery", ledger.periods.length === 2 && ledger.periods.every(period => period.lostResponseVerified));
    check("native admission precedes profile removal", ledger.periods.some(period => period.admissionOrderVerified));
    // The partial fixture exists once partialRecoveryVerified passed above.
    for (const operation of [...ledger.periods, ledger.partial!]) {
      const mailbox = `erasure-${hmac(owner.secret, `symphony-trello/status/v1|${scope}|${operation.id}`)}`;
      await call(signedOperation(owner, scope, operation.id, operation.operation, "ack"));
      let archived = false;
      for (let attempt = 0; attempt < 90; attempt++) {
        const flags = shape<FeatureFlagListing>(await api.get(`${base}/feature_flags/?search=${mailbox}`));
        if (!flags.results.some(flag => flag.key === mailbox && !flag.deleted)) {archived = true; break;}
        await pause();
      }
      check("native acknowledgment archives completed status", archived);
    }
    check("canary remains intact before fixture cleanup", await canarySurvives());
    ledger.canaryPersonId = (await person(ledger.canary!))!.id;
    ledger.status = "TWO_PERIOD_LIFECYCLE_PASS"; save();
    await cleanupVerifiedCanary(api, base, ledger, save);
    console.log(JSON.stringify({status: ledger.status}));
  } catch (error) {
    ledger.error = String((error as Error).message).split(ledger.master).join("[master]")
      .split(ledger.owner?.secret ?? "unissued-placeholder").join("[credential]");
    save();
    throw error;
  } finally {
    await archiveRunSources(api, base, ledger, save);
  }
}

function checkNoIdentifiers(body: string, owner: Owner, distinct: string): void {
  if ([owner.secret, owner.installation_id, distinct].some(value => body.includes(value))) throw new Error("Public status exposed an identifier or credential");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {console.error("Lifecycle gate failed; inspect the protected ledger. Production remains disabled."); process.exitCode = 1;});
}
