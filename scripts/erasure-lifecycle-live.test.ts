import {test} from "node:test";
import assert from "node:assert/strict";
import {createHash, createHmac} from "node:crypto";
import {readFileSync} from "node:fs";
import type {Json} from "./posthog-infra.ts";
import {archiveRunSources, recordHandler, SIGNATURE_LIFETIME_SECONDS, signedOperation, type HandlerLedger, type Owner, type SourceApi, type SourceLedger} from "./erasure-lifecycle-live.ts";
import {handlerTemplateSha256} from "./erasure-service.ts";

type JsonObject = {readonly [key: string]: Json};
type Source = {readonly id: string; readonly name: string; readonly type: string};

const OWNER: Owner = {installation_id: "5095b32f-dbe1-4704-a625-5a241d331cd6", key_version: "k1", secret: "ab".repeat(32)};
const ISSUED_AT = 1790078400;

test("ownership proof binds every scope field without transmitting the secret", () => {
  const proof = signedOperation(OWNER, "test:erasure", "333bd471-61a4-4b8f-8ae7-ead618e8c00f", "9a733176-01ea-4d01-bd94-76f94dbb4306", "erase", ISSUED_AT);
  assert.equal(proof.includes(OWNER.secret), false);
  const parts = proof.split("|");
  assert.equal(parts.length, 9);
  assert.deepEqual(parts.slice(0, 8), ["h2", "erase", "test:erasure", `h1-k1-${OWNER.installation_id}`, "333bd471-61a4-4b8f-8ae7-ead618e8c00f", "9a733176-01ea-4d01-bd94-76f94dbb4306", String(ISSUED_AT), String(ISSUED_AT + SIGNATURE_LIFETIME_SECONDS)]);
  for (let i = 0; i < 8; i++) {
    const changed = parts.slice(0, 8); changed[i] += "changed";
    assert.notEqual(createHmac("sha256", OWNER.secret).update(changed.join("|")).digest("hex"), parts[8]);
  }
});

test("signed operations use exactly the lifetime the native erasure handler accepts", () => {
  // given
  const handler = readFileSync(new URL("../infra/posthog/erasure-service.hog.tftpl", import.meta.url), "utf8");
  const limits = [...handler.matchAll(/expires - issued > (\d+)/g)].map(match => Number(match[1]));

  // when
  const parts = signedOperation(OWNER, "test:erasure", "period", "operation", "erase", ISSUED_AT).split("|");

  // then
  assert.equal(limits.length, 1, "the handler must enforce exactly one `expires - issued > <seconds>` lifetime bound");
  assert.equal(SIGNATURE_LIFETIME_SECONDS, limits[0], "the runner's signature lifetime must equal the handler's limit");
  assert.equal(Number(parts[7]) - Number(parts[6]), limits[0], "a signed operation's expiry minus issue time must equal the handler's limit");
});

test("resume archives both interrupted sources and a creation with a lost response", async () => {
  const run = "run-owned";
  const first: Source = {id: "one", name: `erasure-lifecycle-${run}-first`, type: "source_webhook"};
  const lost: Source = {id: "two", name: `erasure-lifecycle-${run}-lost`, type: "source_webhook"};
  const foreign: Source = {id: "other", name: "unrelated-source", type: "source_webhook"};
  const ledger: SourceLedger = {run, sources: [{id: first.id, name: first.name}], plannedSource: lost.name};
  const writes: {path: string; body: JsonObject}[] = [];
  const api: SourceApi = {
    listAll: async () => [first, lost, foreign],
    get: async path => path.includes("/one/") ? first : lost,
    patch: async (path, body) => writes.push({path, body}),
  };
  await archiveRunSources(api, "/api/projects/test", ledger, () => {});
  assert.deepEqual(writes, [
    {path: "/api/projects/test/hog_functions/one/", body: {enabled: false, deleted: true}},
    {path: "/api/projects/test/hog_functions/two/", body: {enabled: false, deleted: true}},
  ]);
  assert.equal(ledger.plannedSource, undefined);
  assert.deepEqual(ledger.sources.map(source => source.archived), [true, true]);
});

test("cleanup refuses a source whose name changed after listing", async () => {
  const source: Source = {id: "one", name: "erasure-lifecycle-owned-first", type: "source_webhook"};
  let writes = 0;
  const api: SourceApi = {
    listAll: async () => [source],
    get: async () => ({...source, name: "foreign"}),
    patch: async () => {writes++;},
  };
  await assert.rejects(archiveRunSources(api, "/api/projects/test", {run: "owned", sources: [source]}, () => {}), /ownership mismatch/);
  assert.equal(writes, 0);
});

test("source cleanup failure preserves a completed lifecycle and resumes independently", async () => {
  const source: Source = {id: "one", name: "erasure-lifecycle-owned-first", type: "source_webhook"};
  const ledger: SourceLedger & {status: string} = {run: "owned", status: "TWO_PERIOD_LIFECYCLE_PASS", sources: [source]};
  let unavailable = true;
  const api: SourceApi = {
    listAll: async () => [source],
    get: async () => source,
    patch: async () => {if (unavailable) throw new Error("service unavailable");},
  };
  await assert.rejects(archiveRunSources(api, "/api/projects/test", ledger, () => {}), /service unavailable/);
  assert.equal(ledger.status, "TWO_PERIOD_LIFECYCLE_PASS");
  assert.equal(ledger.cleanupFailed, true);
  unavailable = false;
  await archiveRunSources(api, "/api/projects/test", ledger, () => {});
  assert.equal(ledger.status, "TWO_PERIOD_LIFECYCLE_PASS");
  assert.equal(ledger.cleanupFailed, undefined);
  assert.equal(ledger.sources[0]?.archived, true);
});

test("a lifecycle pass names only the handler that ran every stage", () => {
  const fresh: HandlerLedger = {};
  recordHandler(fresh, "a".repeat(64), true);
  const resumed: HandlerLedger = {handlerSha256: "a".repeat(64)};
  recordHandler(resumed, "a".repeat(64), false);
  const changed: HandlerLedger = {handlerSha256: "a".repeat(64)};
  recordHandler(changed, "b".repeat(64), false);
  const older: HandlerLedger = {};
  recordHandler(older, "a".repeat(64), false);
  assert.deepEqual(fresh, {handlerSha256: "a".repeat(64)});
  assert.equal(resumed.handlerMixed, undefined);
  assert.equal(changed.handlerMixed, true);
  assert.equal(older.handlerMixed, true);
});

test("the recorded handler hash is the raw template hash OpenTofu's filesha256 compares", () => {
  const raw = readFileSync(new URL("../infra/posthog/erasure-service.hog.tftpl", import.meta.url));
  assert.equal(handlerTemplateSha256(), createHash("sha256").update(raw).digest("hex"));
});
