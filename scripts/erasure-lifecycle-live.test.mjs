import {test} from "node:test";
import assert from "node:assert/strict";
import {createHmac} from "node:crypto";
import {signedOperation} from "./erasure-lifecycle-live.mjs";

test("ownership proof binds every scope field without transmitting the secret", () => {
  const owner = {installation_id: "5095b32f-dbe1-4704-a625-5a241d331cd6", key_version: "k1", secret: "ab".repeat(32)};
  const proof = signedOperation(owner, "test:erasure", "333bd471-61a4-4b8f-8ae7-ead618e8c00f", "9a733176-01ea-4d01-bd94-76f94dbb4306", "erase", 1790078400);
  assert.equal(proof.includes(owner.secret), false);
  const parts = proof.split("|");
  assert.equal(parts.length, 9);
  assert.deepEqual(parts.slice(0, 8), ["h2", "erase", "test:erasure", `h1-k1-${owner.installation_id}`, "333bd471-61a4-4b8f-8ae7-ead618e8c00f", "9a733176-01ea-4d01-bd94-76f94dbb4306", "1790078400", "1790079300"]);
  for (let i = 0; i < 8; i++) {
    const changed = parts.slice(0, 8); changed[i] += "changed";
    assert.notEqual(createHmac("sha256", owner.secret).update(changed.join("|")).digest("hex"), parts[8]);
  }
});

test("resume archives both interrupted sources and a creation with a lost response", async () => {
  const {archiveRunSources} = await import("./erasure-lifecycle-live.mjs");
  const run = "run-owned";
  const first = {id: "one", name: `erasure-lifecycle-${run}-first`, type: "source_webhook"};
  const lost = {id: "two", name: `erasure-lifecycle-${run}-lost`, type: "source_webhook"};
  const foreign = {id: "other", name: "unrelated-source", type: "source_webhook"};
  const ledger = {run, sources: [{id: first.id, name: first.name}], plannedSource: lost.name};
  const writes = [];
  const api = {
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
  const {archiveRunSources} = await import("./erasure-lifecycle-live.mjs");
  const source = {id: "one", name: "erasure-lifecycle-owned-first", type: "source_webhook"};
  let writes = 0;
  const api = {
    listAll: async () => [source],
    get: async () => ({...source, name: "foreign"}),
    patch: async () => {writes++;},
  };
  await assert.rejects(archiveRunSources(api, "/api/projects/test", {run: "owned", sources: [source]}, () => {}), /ownership mismatch/);
  assert.equal(writes, 0);
});

test("source cleanup failure preserves a completed lifecycle and resumes independently", async () => {
  const {archiveRunSources} = await import("./erasure-lifecycle-live.mjs");
  const source = {id: "one", name: "erasure-lifecycle-owned-first", type: "source_webhook"};
  const ledger = {run: "owned", status: "TWO_PERIOD_LIFECYCLE_PASS", sources: [source]};
  let unavailable = true;
  const api = {
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
  assert.equal(ledger.sources[0].archived, true);
});
