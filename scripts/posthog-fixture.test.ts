import assert from "node:assert/strict";
import {mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {PostHogApi, type JsonObject} from "./posthog-api.ts";
import {newFixtureRun, runFixture, type Fixture, type FixtureRun} from "./posthog-fixture.ts";
import type {Role} from "./posthog-state-files.ts";

test("failed fixture cleanup stays retryable without resending or rechecking the cohort", async (t) => {
  const root = mkdtempSync(join(tmpdir(), "posthog-fixture-cleanup-"));
  const fixturePath = join(root, "fixture.json");
  const runFile = join(root, "run.json");
  const fixture: Fixture = {
    release_version: "1.2.0", release_date_days_ago: 1, expected: {},
    installations: [{distinct_id: "fixture", registered_days_ago: 1, properties: {}, reports: [{days_ago: 0, time: "09:15"}]}],
  };
  const run = newFixtureRun(fixture, new Date("2026-09-22T12:00:00Z"));
  writeFileSync(fixturePath, JSON.stringify(fixture));
  writeFileSync(runFile, JSON.stringify(run));
  const role: Role = {name: "test", projectId: 4242, dashboardId: 1, insights: new Map(), captureToken: "phc_fixture", policy: {}};
  const api = new PostHogApi("http://127.0.0.1:9", "phx_fixture");
  const capture = t.mock.method(globalThis, "fetch", async () => Response.json({status: 1}));
  let deletionAttempts = 0;
  const requests = t.mock.method(api, "post", async (path: string, body: JsonObject) => {
    if (path.endsWith("bulk_delete/")) {
      deletionAttempts++;
      return deletionAttempts === 1
        ? {persons_found: 1, events_queued_for_deletion: 0, deletion_errors: ["fixture refusal"]}
        : {persons_found: 1, events_queued_for_deletion: 1, deletion_errors: []};
    }
    const sql = String((body["query"] as JsonObject)["query"]);
    if (sql.includes("toString(uuid)")) return {results: run.event_uuids.map((uuid) => [uuid])};
    if (sql.includes("active_1d")) return {results: [[1, 1, 1]]};
    return {results: [["2026-09-01", 1]]};
  });

  await assert.rejects(runFixture(api, role, fixturePath, "http://127.0.0.1:9/capture", runFile, async () => {}), /cleanup/);
  assert.equal((JSON.parse(readFileSync(runFile, "utf8")) as FixtureRun).cleanup, undefined);
  const requestsBeforeRetry = requests.mock.callCount();
  const exit = await runFixture(api, role, fixturePath, "http://127.0.0.1:9/capture", runFile, async () => {});

  assert.equal(exit, 0);
  assert.equal(capture.mock.callCount(), 1);
  assert.equal(requests.mock.callCount(), requestsBeforeRetry + 1);
  assert.equal(deletionAttempts, 2);
  assert.ok((JSON.parse(readFileSync(runFile, "utf8")) as FixtureRun).cleanup);
});
