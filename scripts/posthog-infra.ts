// Command line for the PostHog infrastructure under infra/posthog: the API-owned supplements to the
// OpenTofu configuration, the read-only verification report, and the local-file reads the wrapper
// needs. The official provider owns projects, settings it knows, the adopted GeoIP transformation,
// insights, dashboards, and layouts. The modules this file imports cover only what the provider
// cannot express (three environment settings), the scoped lookup the bootstrap import needs, the
// dashboard fixture check, the erasure flag review, the Hog probe, retiring an unmanaged project,
// and the report. Nothing here prints or stores a credential.
//
// Usage: node scripts/posthog-infra.ts <command> [--host URL] [--key-file PATH] ...
// scripts/posthog-infra is the normal entry point and sets the environment.

import {randomUUID} from "node:crypto";
import {writeFileSync} from "node:fs";
import {join} from "node:path";
import {isEntryPoint} from "./entry-point.ts";
import {asObject, DEFAULT_CAPTURE_ENDPOINT, DEFAULT_HOST, InfraError, keyFilePath, PostHogApi, readKey, realSleep, type Sleeper} from "./posthog-api.ts";
import {reviewErasureFlags} from "./posthog-erasure-flags.ts";
import {runFixture} from "./posthog-fixture.ts";
import {probeHogRuntime, verifyProbeTarget} from "./posthog-hog-probe.ts";
import {boundStatePath, infraDir, lifecyclePassArguments, managesProject, productionCaptureToken, productionProjectId, projectIdFromShow, readJson, readProductionProjectId, readRoles, tofuVersion} from "./posthog-state-files.ts";
import {findGeoipFunction, gapApply, gapDiff, outcomeOf, renderReport, verify} from "./posthog-verify.ts";

// The public surface the tests and the lifecycle runner import from this entry point.
export {DEFAULT_CAPTURE_ENDPOINT, DEFAULT_HOST, InfraError, keyFilePath, PostHogApi, readBounded, readKey, runQuery, type Json, type Sleeper} from "./posthog-api.ts";
export {ERASURE_FLAG_MIN_AGE_MS, ERASURE_FLAG_STALE_MS, ERASURE_FLAG_WARNING, erasureFlagList, reviewErasureFlags, type ErasureFlagAction, type ErasureFlagReview} from "./posthog-erasure-flags.ts";
export {comparePanel, fixtureEvents, HEARTBEAT_EVENT, newFixtureRun, renderQuery, scopeQuery, timeDependentExpectations, type Fixture, type FixtureRun, type PanelOutcome} from "./posthog-fixture.ts";
export {HOG_PROBES, probeHogRuntime, verifyProbeTarget, type HogProbe, type ProbeResult, type ProbeStatus} from "./posthog-hog-probe.ts";
export {POLICY_SETTINGS, readProductionProjectId, readRoles, type Role} from "./posthog-state-files.ts";
export {DESTINATION_TYPES, ENVIRONMENT_GAP_SETTINGS, findGeoipFunction, gapApply, gapDiff, GEOIP_TEMPLATE_ID, outcomeOf, renderReport, verify, type Check, type CheckStatus, type GapChange, type Outcome} from "./posthog-verify.ts";

// Command line.

interface Options {
  readonly command: string;
  readonly flags: ReadonlyMap<string, string>;
}

function parseArguments(argv: readonly string[]): Options {
  const [command = "", ...rest] = argv;
  const flags = new Map<string, string>();
  for (let index = 0; index < rest.length; index++) {
    const argument = rest[index] as string;
    if (argument.startsWith("--")) {
      const value = rest[index + 1];
      if (value === undefined || value.startsWith("--")) {
        flags.set(argument.slice(2), "true");
      } else {
        flags.set(argument.slice(2), value);
        index++;
      }
    } else if (!flags.has("subcommand")) {
      flags.set("subcommand", argument);
    }
  }
  return {command, flags};
}

function requireFlag(options: Options, name: string): string {
  const value = options.flags.get(name);
  if (value === undefined) {
    throw new InfraError(`--${name} is required`);
  }
  return value;
}

/** Commands that read local files only; they run before, and without, the personal API key.
 * Returns undefined for any other command. `-` as a file reads standard input. */
function runLocalCommand(options: Options): number | undefined {
  switch (options.command) {
    case "bound-state-path":
      process.stdout.write(boundStatePath(readJson(requireFlag(options, "record"))));
      return 0;
    case "project-id-from-show":
      console.log(projectIdFromShow(readJson("-"), requireFlag(options, "role")));
      return 0;
    case "lifecycle-pass-args":
      for (const argument of lifecyclePassArguments(readJson(requireFlag(options, "ledger")))) {
        console.log(argument);
      }
      return 0;
    case "tofu-version":
      process.stdout.write(tofuVersion(readJson("-")));
      return 0;
    case "production-project-id":
      console.log(productionProjectId(readJson(requireFlag(options, "outputs"))));
      return 0;
    case "production-capture-token":
      // Written without a newline: the wrapper pipes it straight into `gh secret set`.
      process.stdout.write(productionCaptureToken(readJson(requireFlag(options, "outputs"))));
      return 0;
    case "manages-project":
      return managesProject(readJson(requireFlag(options, "outputs")), requireFlag(options, "id")) ? 0 : 1;
    default:
      return undefined;
  }
}

export async function main(argv: readonly string[], environment: NodeJS.ProcessEnv, sleep: Sleeper = realSleep): Promise<number> {
  const options = parseArguments(argv);
  const local = runLocalCommand(options);
  if (local !== undefined) {
    return local;
  }
  const host = options.flags.get("host") ?? environment["POSTHOG_HOST"] ?? DEFAULT_HOST;
  const key = environment["POSTHOG_API_KEY"] ?? readKey(options.flags.get("key-file") ?? keyFilePath(environment));
  const api = new PostHogApi(host, key);
  switch (options.command) {
    case "geoip-id": {
      const found = await findGeoipFunction(api, Number(requireFlag(options, "project")));
      process.stdout.write(`${String(found["id"])}\n`);
      return 0;
    }
    case "gaps": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const mode = options.flags.get("subcommand");
      if (mode === "diff") {
        const changes = await gapDiff(api, roles);
        for (const change of changes) {
          console.log(`${change.role}: ${change.setting} is ${JSON.stringify(change.observed)}, expected ${String(change.expected)}`);
        }
        console.log(changes.length === 0 ? "gaps: no changes" : `gaps: ${changes.length} change(s) needed`);
        return changes.length === 0 ? 0 : 2;
      }
      if (mode === "apply") {
        const applied = await gapApply(api, roles, sleep);
        console.log(applied.length === 0 ? "gaps: nothing to apply" : `gaps: applied ${applied.map((change) => `${change.role}.${change.setting}`).join(", ")} and read them back`);
        return 0;
      }
      throw new InfraError("gaps needs diff or apply");
    }
    case "verify": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const checks = await verify(api, roles, readProductionProjectId());
      const report = renderReport(checks, {
        observedAt: new Date().toISOString(),
        configRevision: options.flags.get("config-revision") ?? "unknown",
        tofuVersion: options.flags.get("tofu-version") ?? "unknown",
        providerVersion: options.flags.get("provider-version") ?? "unknown",
      });
      const reportPath = options.flags.get("report");
      if (reportPath !== undefined) {
        writeFileSync(reportPath, report);
      } else {
        console.log(report);
      }
      const outcome = outcomeOf(checks);
      for (const check of checks) {
        if (check.status === "FAIL" || (check.required && check.status === "UNKNOWN")) {
          console.error(`${check.status} ${check.role} ${check.item}: expected ${check.expected}, observed ${check.observed}`);
        }
      }
      console.log(`verify: ${outcome}`);
      return outcome === "verified" ? 0 : 1;
    }
    case "fixture": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const roleName = requireFlag(options, "role");
      const role = roles.find((candidate) => candidate.name === roleName);
      if (role === undefined) {
        throw new InfraError(`no role ${roleName} in outputs`);
      }
      if (roleName === "production") {
        throw new InfraError("the fixture is never sent to the production role");
      }
      const fixturePath = options.flags.get("fixture") ?? join(infraDir(), "fixtures", "dashboard-fixture.json");
      const captureEndpoint = options.flags.get("capture") ?? DEFAULT_CAPTURE_ENDPOINT;
      return runFixture(api, role, fixturePath, captureEndpoint, requireFlag(options, "run-file"), sleep);
    }
    case "hog-probe": {
      if (environment["SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE"] !== "1") {
        throw new InfraError("hog-probe requires explicit live opt-in: SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE=1");
      }
      if (host !== DEFAULT_HOST && !/^http:\/\/(127\.0\.0\.1|localhost):[0-9]+$/.test(host)) {
        throw new InfraError("hog-probe requires the EU administration host");
      }
      const roleName = options.flags.get("role") ?? "test";
      if (roleName !== "test") {
        throw new InfraError("the probe runs in the test role only");
      }
      const target = await verifyProbeTarget(api, readRoles(requireFlag(options, "outputs")), requireFlag(options, "state"));
      const report = requireFlag(options, "report");
      const ledger = {runId: randomUUID(), startedAt: new Date().toISOString(), role: "test", ...target,
        operation: "unsaved mocked Hog invocations", createdResources: [], capturedEvents: [], deletions: [], status: "started"};
      writeFileSync(report, JSON.stringify(ledger, null, 2) + "\n", {mode: 0o600, flag: "wx"});
      const results = await probeHogRuntime(api, target.projectId);
      writeFileSync(report, JSON.stringify({...ledger, status: "observed", completedAt: new Date().toISOString(), results}, null, 2) + "\n");
      console.log(`| Primitive | Status | Detail |\n| --- | --- | --- |`);
      for (const result of results) {
        console.log(`| ${result.name} | ${result.status} | ${result.detail.replace(/\|/g, "\\|")} |`);
      }
      return results.some((result) => result.status === "unexpected") ? 1 : 0;
    }
    case "erasure-flags": {
      const roles = readRoles(requireFlag(options, "outputs"));
      const roleName = requireFlag(options, "role");
      const role = roles.find((candidate) => candidate.name === roleName);
      if (role === undefined) {
        throw new InfraError(`no role ${roleName} in outputs`);
      }
      const archive = options.flags.get("archive") === "true";
      const reviews = await reviewErasureFlags(api, role.projectId, new Date(), archive);
      for (const review of reviews) {
        const events = review.remainingEvents === undefined ? "" : `, ${review.remainingEvents} event(s) remain`;
        console.log(`${review.action}${review.action === "archive" && !archive ? " (dry run)" : ""} flag ${review.id} ${review.name} [${review.state}, created ${review.createdAt}${events}]`);
      }
      const attention = reviews.filter((review) => review.action === "late-events" || review.action === "refused" || review.action === "stale");
      console.log(`erasure-flags: ${reviews.length} flag(s), ${reviews.filter((review) => review.action === "archive").length} ${archive ? "archived" : "archivable"}, ${attention.length} need the maintainer`);
      return attention.length === 0 ? 0 : 1;
    }
    case "retire-project": {
      const id = Number(requireFlag(options, "id"));
      const name = requireFlag(options, "name");
      const project = asObject(await api.get(`/api/projects/${id}/`), `project ${id}`);
      if (project["name"] !== name) {
        throw new InfraError(`project ${id} is named ${JSON.stringify(project["name"])}, not ${JSON.stringify(name)}; refusing`);
      }
      const organization = String(project["organization"]);
      await api.delete(`/api/organizations/${organization}/projects/${id}/`);
      console.log(`retire-project: deletion of project ${id} (${name}) requested`);
      return 0;
    }
    default:
      throw new InfraError(`unknown command ${JSON.stringify(options.command)}`);
  }
}

if (isEntryPoint(import.meta.url)) {
  main(process.argv.slice(2), process.env)
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      console.error(`posthog-infra: ${error instanceof Error ? error.message : String(error)}`);
      process.exitCode = 1;
    });
}
