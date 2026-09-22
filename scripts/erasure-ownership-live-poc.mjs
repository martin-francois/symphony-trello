// Opt-in TEST-only authorization experiment. Hosted functions receive no deletion capability.
import { randomUUID } from "node:crypto";
import { writeFileSync, readFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  PostHogApi,
  readKey,
  readRoles,
  verifyProbeTarget,
  readBounded,
} from "./posthog-infra.ts";
import {
  hog,
  newMaster,
  newBearer,
  payload,
  envelope,
  derivedKey,
  verify,
} from "./erasure-ownership-poc.ts";

export async function trackedCreate(api, path, configuration, planned, save) {
  planned.push({ name: configuration.name, type: configuration.type });
  save();
  return api.post(path, { ...configuration, enabled: false });
}

export async function reconcileCreated(api, path, planned, created) {
  const remote = await api.listAll(path);
  for (const resource of remote) {
    if (
      planned.some(
        (plan) => plan.name === resource.name && plan.type === resource.type,
      ) &&
      !created.some((known) => known.id === resource.id)
    ) {
      created.push({
        id: resource.id,
        name: resource.name,
        type: resource.type,
      });
    }
  }
}

export async function main() {
  if (process.env.SYMPHONY_TRELLO_OWNERSHIP_POC !== "1")
    throw new Error("Explicit live opt-in required");
  const stateDir =
    process.env.SYMPHONY_TRELLO_POSTHOG_STATE_DIR ??
    `${process.env.HOME}/.local/state/symphony-trello/posthog`;
  const state = `${stateDir}/terraform.tfstate`;
  const backend = JSON.parse(
    readFileSync(`${stateDir}/.terraform/terraform.tfstate`, "utf8"),
  );
  if (backend.backend.config.path !== state)
    throw new Error("Backend mismatch");
  const api = new PostHogApi(
    "https://eu.posthog.com",
    readKey(
      process.env.SYMPHONY_TRELLO_POSTHOG_KEY_FILE ??
        `${process.env.HOME}/posthog-personal-api-key`,
    ),
  );
  const target = await verifyProbeTarget(
    api,
    readRoles(`${stateDir}/outputs.json`),
    state,
  );
  const dir =
    process.env.SYMPHONY_TRELLO_OWNERSHIP_POC_DIR ??
    "/var/tmp/symphony-ownership-poc";
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const master = newMaster(),
    scope = `test:${target.projectId}:poc`,
    run = randomUUID();
  const path = `${dir}/live-${run}.json`,
    created = [],
    planned = [];
  const ledger = {
    run,
    projectId: target.projectId,
    started: new Date().toISOString(),
    mode: "authorization-only",
    functions: created,
    planned,
    checks: [],
    cleanup: [],
    captures: [],
    deletions: [],
  };
  writeFileSync(path, JSON.stringify(ledger, null, 2), {
    flag: "wx",
    mode: 0o600,
  });
  function save() {
    writeFileSync(path, JSON.stringify(ledger, null, 2));
  }
  function record(name, passed, detail = {}) {
    ledger.checks.push({ name, passed, ...detail });
    save();
    console.log(JSON.stringify({ name, passed, ...detail }));
    if (!passed) throw new Error(`Failed: ${name}`);
  }
  async function call(id, raw, method = "POST") {
    const res = await fetch(
      `https://webhooks.eu.posthog.com/public/webhooks/${id}`,
      {
        method,
        headers: { "Content-Type": "application/json" },
        body: method === "POST" ? raw : undefined,
        redirect: "manual",
        signal: AbortSignal.timeout(15000),
      },
    );
    const txt = await readBounded(res, "ownership PoC response");
    if (txt.length > 12000) throw new Error("Unexpected large response");
    let body;
    try {
      body = JSON.parse(txt);
    } catch {
      body = { nonJson: true };
    }
    return { status: res.status, body };
  }
  try {
    const org = await api.get(`/api/organizations/${target.organizationId}/`);
    record(
      "existing pipeline entitlement",
      org.available_product_features.some(
        (f) => f.key === "data_pipelines_free_allowance" && f.is_plan_default,
      ),
    );
    for (const mode of ["issue", "hmac", "bearer"]) {
      const name = `ownership-poc-${run}-${mode}`;
      const f = await trackedCreate(
        api,
        `/api/projects/${target.projectId}/hog_functions/`,
        {
          type: "source_webhook",
          name,
          description:
            "Temporary authorization-only PoC; no captures, fetch or deletion capability",
          hog: hog(mode, scope),
          enabled: true,
          inputs: mode === "bearer" ? {} : { master: { value: master } },
          inputs_schema:
            mode === "bearer"
              ? []
              : [
                  {
                    key: "master",
                    type: "string",
                    secret: true,
                    required: true,
                  },
                ],
          filters: {},
        },
        planned,
        save,
      );
      if (
        typeof f.id !== "string" ||
        f.name !== name ||
        f.type !== "source_webhook" ||
        f.enabled !== false
      )
        throw new Error("Created function mismatch");
      created.push({ id: f.id, name, mode });
      save();
      record(
        `${mode}: management response hides master`,
        !JSON.stringify(f).includes(master),
      );
      await api.patch(
        `/api/projects/${target.projectId}/hog_functions/${f.id}/`,
        { enabled: true },
      );
    }
    const ids = Object.fromEntries(created.map((f) => [f.mode, f.id]));
    let issued;
    for (let n = 0; n < 6; n++) {
      issued = await call(ids.issue, envelope("issue-v1"));
      if (issued.status !== 404) break;
      await new Promise((r) => setTimeout(r, 1000));
    }
    record(
      "native issuance",
      issued.status === 200 &&
        issued.body.dryRun === true &&
        typeof issued.body.secret === "string",
      { status: issued.status },
    );
    const owner = issued.body;
    record(
      "native HMAC derivation agrees with Node",
      owner.secret === derivedKey(master, scope, owner.id),
    );
    const second = await call(ids.issue, envelope("issue-v1"));
    record(
      "lost issuance response permits fresh enrollment before reporting",
      second.status === 200 &&
        second.body.id !== owner.id &&
        second.body.secret === derivedKey(master, scope, second.body.id),
    );
    const inject = await call(
      ids.issue,
      JSON.stringify({ payload: "issue-v1", id: owner.id }),
    );
    record("issuer rejects caller-selected identity", inject.status === 401);
    for (const mode of ["hmac", "bearer"]) {
      const credential = mode === "hmac" ? owner : newBearer(scope),
        now = Math.floor(Date.now() / 1000);
      const raw = envelope(payload(mode, credential, scope, now));
      const good = await call(ids[mode], raw);
      record(
        `${mode}: native authorization`,
        good.status === 200 &&
          good.body.authorized === true &&
          good.body.target === credential.id,
        { status: good.status },
      );
      const cases = [
        ["missing", "{}"],
        ["ID only", envelope(credential.id)],
        [
          "duplicate JSON",
          raw.replace('{"payload":', '{"payload":"ignored","payload":'),
        ],
        ["target override", raw.replace(/}$/, ',"target":"victim"}')],
        ["whitespace", " " + raw],
        ["extra field", envelope(JSON.parse(raw).payload + "|extra")],
        [
          "wrong audience",
          envelope(payload(mode, credential, "another:deployment", now)),
        ],
        ["expired", envelope(payload(mode, credential, scope, now - 1000))],
        ["future", envelope(payload(mode, credential, scope, now + 1000))],
        [
          "long lifetime",
          envelope(payload(mode, credential, scope, now, randomUUID(), 901)),
        ],
        ["random input", envelope(newMaster())],
        ["oversized", JSON.stringify({ payload: "x".repeat(1201) })],
      ];
      if (mode === "hmac")
        cases.push(
          ["forged operation", raw.replace(good.body.operation, randomUUID())],
          ["another target", raw.replace(credential.id, second.body.id)],
        );
      for (const [name, input] of cases) {
        const bad = await call(ids[mode], input);
        record(
          `${mode}: reject ${name}`,
          bad.status === 401 && bad.body.authorized === false,
          { status: bad.status },
        );
      }
      const get = await call(ids[mode], raw, "GET");
      record(`${mode}: reject GET`, get.status === 405, { status: get.status });
      const replay = await call(ids[mode], raw);
      record(
        `${mode}: replay limitation observed`,
        replay.status === 200 && replay.body.operation === good.body.operation,
        { limitation: "stateless authentication accepts repeated proof" },
      );
      const restored = JSON.parse(JSON.stringify(credential));
      const repeat = await call(
        ids[mode],
        envelope(payload(mode, restored, scope, now)),
      );
      record(
        `${mode}: restart without binding history`,
        repeat.status === 200 && repeat.body.target === credential.id,
      );
      const concurrent = await Promise.all(
        Array.from({ length: 3 }, () => call(ids[mode], raw)),
      );
      record(
        `${mode}: concurrent replay limitation`,
        concurrent.every(
          (r) => r.status === 200 && r.body.operation === good.body.operation,
        ),
        { limitation: "authentication is not atomic job admission" },
      );
      if (mode === "bearer") {
        const stranger = newBearer(scope);
        const unknown = await call(
          ids[mode],
          envelope(payload(mode, stranger, scope, now)),
        );
        record(
          "bearer: random valid secret derives another ID",
          unknown.status === 200 &&
            unknown.body.target === stranger.id &&
            unknown.body.target !== credential.id,
        );
      }
      const logs = await api.get(
        `/api/projects/${target.projectId}/hog_functions/${ids[mode]}/logs/?after=${encodeURIComponent(ledger.started)}&limit=100`,
      );
      const logText = JSON.stringify(logs);
      record(
        `${mode}: observed logs contain no credentials`,
        !logText.includes(master) && !logText.includes(credential.secret),
        {
          entries: Array.isArray(logs.results) ? logs.results.length : null,
          qualification: "one immediate read; not all provider storage",
        },
      );
      const later = now + 400 * 86400;
      record(
        `${mode}: 400-day local reference`,
        verify(
          mode,
          envelope(payload(mode, restored, scope, later)),
          scope,
          master,
          later,
        )?.target === credential.id,
        { evidence: "local simulated clock, not aged hosted storage" },
      );
    }
    ledger.status = "AUTHENTICATION_PASS_LIFECYCLE_UNPROVEN";
  } catch (error) {
    ledger.status = "FAILED";
    // API error bodies are untrusted and can echo submitted credentials.
    ledger.error = error instanceof Error ? error.name : "unknown";
    save();
    console.error(ledger.error);
    process.exitCode = 1;
  } finally {
    try {
      await reconcileCreated(
        api,
        `/api/projects/${target.projectId}/hog_functions/?types=source_webhook`,
        planned,
        created,
      );
      for (const plan of planned) {
        if (!created.some((resource) => resource.name === plan.name)) {
          ledger.cleanup.push({
            name: plan.name,
            observation:
              "creation unresolved; no matching function listed; creation was requested disabled",
          });
          process.exitCode = 1;
        }
      }
    } catch (error) {
      ledger.cleanup.push({
        stage: "reconcile",
        error: error instanceof Error ? error.name : "unknown",
      });
      process.exitCode = 1;
    }
    save();
    for (const f of created) {
      try {
        const live = await api.get(
          `/api/projects/${target.projectId}/hog_functions/${f.id}/`,
        );
        if (live.name !== f.name) throw new Error("Cleanup ownership mismatch");
        await api.patch(
          `/api/projects/${target.projectId}/hog_functions/${f.id}/`,
          { enabled: false },
        );
        await api.patch(
          `/api/projects/${target.projectId}/hog_functions/${f.id}/`,
          { deleted: true },
        );
        const listing = await api.listAll(
          `/api/projects/${target.projectId}/hog_functions/?types=source_webhook`,
        );
        let absent = false;
        try {
          await api.get(
            `/api/projects/${target.projectId}/hog_functions/${f.id}/`,
          );
        } catch (e) {
          if (e.message.includes("HTTP 404")) absent = true;
          else throw e;
        }
        if (!absent || listing.some((x) => x.id === f.id))
          throw new Error("Removal not confirmed");
        ledger.cleanup.push({
          id: f.id,
          disabledAndDeleted: true,
          readback: "404 and absent from listing",
        });
      } catch (e) {
        ledger.cleanup.push({
          id: f.id,
          error: e instanceof Error ? e.name : "unknown",
        });
        process.exitCode = 1;
      }
      save();
    }
    ledger.finished = new Date().toISOString();
    save();
    console.log(
      JSON.stringify({
        status: ledger.status,
        checks: ledger.checks.length,
        cleanup: ledger.cleanup,
        ledger: path,
      }),
    );
  }
}
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
