import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {authorizedHandlerSha256, LIFECYCLE_PASS_STATUS} from "./erasure-lifecycle-ledger.ts";
import {boundStatePath, lifecyclePassArguments, managesProject, productionCaptureToken, productionProjectId, projectIdFromShow, StateFileError, tofuVersion} from "./posthog-state-files.ts";

const SHA = "a".repeat(64);
const OUTPUTS = {
  projects: {value: {production: {id: 101}, test: {id: 202}}},
  capture_tokens: {value: {production: "phc_production_token"}},
};
const SCRIPT = join(import.meta.dirname, "posthog-infra.ts");

test("only a passed run on one recorded handler authorizes production", () => {
  assert.equal(authorizedHandlerSha256({status: LIFECYCLE_PASS_STATUS, handlerSha256: SHA}), SHA);
  assert.equal(authorizedHandlerSha256({status: LIFECYCLE_PASS_STATUS, handlerSha256: SHA, handlerMixed: true}), undefined);
  assert.equal(authorizedHandlerSha256({status: "ACCEPTED_PHYSICAL_COMPLETION_PENDING", handlerSha256: SHA}), undefined);
  assert.equal(authorizedHandlerSha256({status: LIFECYCLE_PASS_STATUS}), undefined);
  assert.equal(authorizedHandlerSha256({status: LIFECYCLE_PASS_STATUS, handlerSha256: "A".repeat(64)}), undefined);
});

test("the lifecycle pass becomes one OpenTofu variable or none", () => {
  assert.deepEqual(lifecyclePassArguments({status: LIFECYCLE_PASS_STATUS, handlerSha256: SHA}), [`-var=erasure_lifecycle_pass=${SHA}`]);
  assert.deepEqual(lifecyclePassArguments({status: LIFECYCLE_PASS_STATUS, handlerSha256: SHA, handlerMixed: true}), []);
});

test("the backend record yields its state path or an empty string", () => {
  assert.equal(boundStatePath({backend: {config: {path: "/state/terraform.tfstate"}}}), "/state/terraform.tfstate");
  assert.equal(boundStatePath({backend: {}}), "");
  assert.equal(boundStatePath({}), "");
});

test("a role's project id comes from the state and a missing project is an error", () => {
  const show = {values: {root_module: {child_modules: [{resources: [{address: "module.test.posthog_project.this", values: {id: 202}}]}, {}]}}};
  assert.equal(projectIdFromShow(show, "test"), "202");
  assert.throws(() => projectIdFromShow(show, "production"), new StateFileError("no module.production.posthog_project.this in state"));
  assert.throws(() => projectIdFromShow({}, "test"), StateFileError);
});

test("the outputs give the production project, its token, and the managed ids", () => {
  assert.equal(tofuVersion({terraform_version: "1.12.6"}), "1.12.6");
  assert.equal(productionProjectId(OUTPUTS), "101");
  assert.throws(() => productionProjectId({...OUTPUTS, projects: {value: {test: {id: 202}}}}), StateFileError);
  assert.equal(productionCaptureToken(OUTPUTS), "phc_production_token");
  assert.equal(managesProject(OUTPUTS, "202"), true);
  assert.equal(managesProject(OUTPUTS, "303"), false);
});

test("the local-file commands run without a personal API key", () => {
  // given
  const root = mkdtempSync(join(tmpdir(), "posthog-state-files-"));
  const ledger = join(root, "ledger.json");
  const outputs = join(root, "outputs.json");
  writeFileSync(ledger, JSON.stringify({status: LIFECYCLE_PASS_STATUS, handlerSha256: SHA}));
  writeFileSync(outputs, JSON.stringify(OUTPUTS));
  const {POSTHOG_API_KEY: _key, SYMPHONY_TRELLO_POSTHOG_KEY_FILE: _keyFile, ...inherited} = process.env;
  const run = (args: readonly string[], input = "") => spawnSync(process.execPath, [SCRIPT, ...args], {encoding: "utf8", input, env: {...inherited, HOME: root}});

  // when
  const pass = run(["lifecycle-pass-args", "--ledger", ledger]);
  const token = run(["production-capture-token", "--outputs", outputs]);
  const managed = run(["manages-project", "--outputs", outputs, "--id", "101"]);
  const unmanaged = run(["manages-project", "--outputs", outputs, "--id", "999"]);
  const version = run(["tofu-version"], JSON.stringify({terraform_version: "1.12.6"}));

  // then
  assert.equal(pass.status, 0, pass.stderr);
  assert.equal(pass.stdout, `-var=erasure_lifecycle_pass=${SHA}\n`);
  assert.equal(token.stdout, "phc_production_token");
  assert.equal(managed.status, 0, managed.stderr);
  assert.equal(unmanaged.status, 1, unmanaged.stderr);
  assert.equal(version.stdout, "1.12.6");
});
