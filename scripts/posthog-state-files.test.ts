import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {boundStatePath, managesProject, productionCaptureToken, productionProjectId, projectIdFromShow, StateFileError, tofuVersion} from "./posthog-state-files.ts";

const OUTPUTS = {
  projects: {value: {production: {id: 101}, test: {id: 202}}},
  capture_tokens: {value: {production: "phc_production_token"}},
};
const SCRIPT = join(import.meta.dirname, "posthog-infra.ts");

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
  const outputs = join(root, "outputs.json");
  writeFileSync(outputs, JSON.stringify(OUTPUTS));
  const {POSTHOG_API_KEY: _key, SYMPHONY_TRELLO_POSTHOG_KEY_FILE: _keyFile, ...inherited} = process.env;
  const run = (args: readonly string[], input = "") => spawnSync(process.execPath, [SCRIPT, ...args], {encoding: "utf8", input, env: {...inherited, HOME: root}});

  // when
  const token = run(["production-capture-token", "--outputs", outputs]);
  const managed = run(["manages-project", "--outputs", outputs, "--id", "101"]);
  const unmanaged = run(["manages-project", "--outputs", outputs, "--id", "999"]);
  const version = run(["tofu-version"], JSON.stringify({terraform_version: "1.12.6"}));

  // then
  assert.equal(token.stdout, "phc_production_token");
  assert.equal(managed.status, 0, managed.stderr);
  assert.equal(managed.stdout, "yes\n");
  assert.equal(unmanaged.status, 0, unmanaged.stderr);
  assert.equal(unmanaged.stdout, "no\n");
  assert.equal(version.stdout, "1.12.6");
});
