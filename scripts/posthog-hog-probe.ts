// Hog runtime capability probe: harmless test invocations of unsaved Hog code through the same
// endpoint the PostHog UI uses for "test function", with asynchronous functions mocked, so nothing
// is captured or deleted and no handler is saved. This probes the authenticated test runtime,
// not a deployed public ingress path. Absence by name alone does not establish capability absence.

import {readFileSync} from "node:fs";
import {asObject, InfraError, isObject, PostHogApi, type Json} from "./posthog-api.ts";
import type {Role} from "./posthog-state-files.ts";

export interface HogProbe {
  readonly name: string;
  readonly hog: string;
  /** What a present primitive prints; a missing one raises "Global variable not found". */
  readonly expect?: string;
}

export const HOG_PROBES: readonly HogProbe[] = [
  {name: "sha256Hex", hog: "print(sha256Hex('abc'))", expect: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
  {name: "sha256HmacChainHex", hog: "print(typeof(sha256HmacChainHex))", expect: "function"},
  {name: "md5Hex", hog: "print(typeof(md5Hex))", expect: "function"},
  {name: "base64Decode", hog: "print(base64Decode('YWJj'))", expect: "abc"},
  {name: "jsonParse", hog: "print(typeof(jsonParse))", expect: "function"},
  {name: "generateUUIDv4", hog: "print(typeof(generateUUIDv4))", expect: "function"},
  {name: "ecdsaVerify", hog: "print(typeof(ecdsaVerify))"},
  {name: "verifySignature", hog: "print(typeof(verifySignature))"},
  {name: "rsaVerify", hog: "print(typeof(rsaVerify))"},
  {name: "jwtVerify", hog: "print(typeof(jwtVerify))"},
  {name: "verifyJwt", hog: "print(typeof(verifyJwt))"},
  {name: "jwtDecode", hog: "print(typeof(jwtDecode))"},
  {name: "crypto", hog: "print(typeof(crypto))", expect: "object"},
  {name: "hmacSha256", hog: "print(typeof(hmacSha256))"},
  {name: "base64UrlDecode", hog: "print(typeof(base64UrlDecode))"},
  {name: "hexDecode", hog: "print(typeof(hexDecode))"},
];

export type ProbeStatus = "present" | "absent" | "unexpected";

export interface ProbeResult {
  readonly name: string;
  readonly status: ProbeStatus;
  readonly detail: string;
}

/** Bind the harmless trial to the selected managed test resource before invoking hosted code. */
export async function verifyProbeTarget(api: PostHogApi, roles: readonly Role[], stateFile: string): Promise<{projectId: number; organizationId: string}> {
  const tests = roles.filter((role) => role.name === "test");
  const role = tests[0];
  if (tests.length !== 1 || role === undefined || !Number.isSafeInteger(role.projectId) || role.projectId <= 0
      || roles.some((other) => other.name !== "test" && other.projectId === role.projectId)) {
    throw new InfraError("probe requires a distinct test role");
  }
  const state = asObject(JSON.parse(readFileSync(stateFile, "utf8")) as Json, "selected state");
  const resources = Array.isArray(state["resources"]) ? state["resources"].filter(isObject) : [];
  const matches = resources.filter((resource) => resource["module"] === "module.test" && resource["mode"] === "managed" && resource["type"] === "posthog_project" && resource["name"] === "this");
  const instances = matches.length === 1 ? matches[0]?.["instances"] : undefined;
  if (!Array.isArray(instances) || instances.length !== 1 || !isObject(instances[0])) {
    throw new InfraError("selected state must contain exactly one managed test project");
  }
  const attributes = asObject(instances[0]["attributes"] ?? null, "test project attributes");
  let organizationId = attributes["organization_id"];
  if (String(attributes["id"]) !== String(role.projectId) || attributes["api_token"] !== role.captureToken
      || typeof organizationId !== "string" || organizationId === "" || typeof attributes["name"] !== "string") {
    throw new InfraError("test outputs do not match the selected state");
  }
  // The provider retains @current in state. Resolve it, then bind it to the fixed project and
  // token below; changing the account's current organization never selects a different project.
  if (organizationId === "@current") {
    const organization = asObject(await api.get("/api/organizations/@current/"), "selected organization");
    organizationId = organization["id"];
    if (typeof organizationId !== "string" || organizationId === "" || organizationId.startsWith("@")) {
      throw new InfraError("selected organization did not resolve to an identifier");
    }
  }
  for (const kind of ["projects", "environments"]) {
    const live = asObject(await api.get(`/api/${kind}/${role.projectId}/`), `live test ${kind}`);
    if (live["id"] !== role.projectId || live["organization"] !== organizationId
        || live["api_token"] !== role.captureToken || live["name"] !== attributes["name"]) {
      throw new InfraError(`live test ${kind} binding differs from the selected state`);
    }
  }
  return {projectId: role.projectId, organizationId};
}

export async function probeHogRuntime(api: PostHogApi, projectId: number, probes: readonly HogProbe[] = HOG_PROBES): Promise<readonly ProbeResult[]> {
  const results: ProbeResult[] = [];
  for (const probe of probes) {
    const response = asObject(
      await api.post(`/api/projects/${projectId}/hog_functions/new/invocations/`, {
        configuration: {type: "destination", name: "capability probe", hog: probe.hog, inputs: {}, inputs_schema: [], filters: {}, enabled: false},
        mock_async_functions: true,
        globals: {
          event: {uuid: "00000000-0000-0000-0000-000000000000", event: "probe", distinct_id: "probe", properties: {}, timestamp: "2026-01-01T00:00:00Z"},
          person: {properties: {}},
        },
      }),
      `probe ${probe.name}`,
    );
    const errors = Array.isArray(response["errors"]) ? response["errors"].map(String) : [];
    const logs = Array.isArray(response["logs"]) ? response["logs"].map((entry) => (isObject(entry) ? String(entry["message"] ?? "") : "")) : [];
    const printed = logs.find((line) => !line.startsWith("Function completed") && !line.startsWith("Error executing")) ?? "";
    if (response["status"] === "success") {
      const matches = errors.length === 0 && printed === (probe.expect ?? "function");
      results.push({name: probe.name, status: matches ? "present" : "unexpected", detail: printed});
    } else {
      const missing = errors.some((error) => error === `Global variable not found: ${probe.name}` || error === `Unsupported function call: ${probe.name}`);
      results.push({name: probe.name, status: missing ? "absent" : "unexpected", detail: errors[0] ?? "no error text"});
    }
  }
  return results;
}
