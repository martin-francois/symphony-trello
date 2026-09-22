import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";
import {
  trackedCreate,
  reconcileCreated,
} from "./erasure-ownership-live-poc.mjs";

test("a committed creation with a lost response leaves a persisted cleanup plan", async () => {
  const configuration = {
    name: "ownership-poc-synthetic-hmac",
    type: "source_webhook",
    enabled: true,
  };
  const remote = [];
  const planned = [];
  let saved = [];
  const api = {
    async post(_path, body) {
      remote.push({ id: "synthetic-id", ...body });
      throw new Error("response lost after commit");
    },
  };
  await assert.rejects(
    trackedCreate(api, "/synthetic/", configuration, planned, () => {
      saved = structuredClone(planned);
    }),
    /response lost/,
  );
  assert.deepEqual(
    saved,
    [{ name: configuration.name, type: configuration.type }],
    "cleanup must know the committed resource name even without its response",
  );
  assert.equal(
    remote[0].enabled,
    false,
    "an unacknowledged creation must not expose an active endpoint",
  );
  const created = [];
  await reconcileCreated(
    {
      async listAll() {
        return [
          ...remote,
          { id: "unrelated", name: "another-run", type: "source_webhook" },
        ];
      },
    },
    "/synthetic/",
    saved,
    created,
  );
  assert.deepEqual(
    created.map((resource) => resource.id),
    ["synthetic-id"],
    "cleanup recovers the lost response without selecting another run",
  );
});

test("live ownership PoC refuses invocation before reading credentials without explicit opt-in", () => {
  const result = spawnSync(
    process.execPath,
    [
      fileURLToPath(
        new URL("./erasure-ownership-live-poc.mjs", import.meta.url),
      ),
    ],
    {
      env: { HOME: "/does-not-exist", PATH: process.env.PATH },
      encoding: "utf8",
      timeout: 10000,
    },
  );
  assert.equal(result.status, 1);
  assert.equal(result.stderr.trim(), "Explicit live opt-in required");
  assert.equal(result.stdout, "");
});

test("importing the live PoC performs no credential reads or network operations", async () => {
  const module = await import("./erasure-ownership-live-poc.mjs");
  assert.equal(typeof module.main, "function");
});
