import {MODES, type Manifest, type Scenario} from "./manifest.ts";
import type {RunOptions, SelectionCriteria} from "./options.ts";

export type Decision = "run" | "skip" | "not-yet-automated";

export interface PlanEntry {
  scenario: Scenario;
  decision: Decision;
  reason: string;
}

/** Facts about the host that decide whether an opted-in integration can actually run. */
export interface Capabilities {
  codexCli: boolean;
  ghCli: boolean;
  trelloCredentials: boolean;
}

export class SelectionError extends Error {
  override name = "SelectionError";
}

/**
 * Applies the selection criteria. Scenario ids select rows directly; profiles and families narrow
 * the manifest otherwise, and `full` stands for every profile. A future selection input, such as a
 * bug-bash theme, adds one more criterion here instead of filtering in the runner.
 */
export function selectScenarios(manifest: Manifest, criteria: SelectionCriteria): Scenario[] {
  const known = new Set(manifest.scenarios.map((scenario) => scenario.id));
  const unknown = criteria.scenarioIds.filter((id) => !known.has(id));
  if (unknown.length > 0) {
    throw new SelectionError(`unknown scenario id: ${unknown.join(", ")}`);
  }
  const ids = new Set(criteria.scenarioIds);
  const profiles = new Set(criteria.profiles);
  const families = new Set(criteria.families);
  const selectsByGroup = profiles.size > 0 || families.size > 0;
  const selected = manifest.scenarios.filter((scenario) => {
    if (ids.has(scenario.id)) {
      return true;
    }
    if (!selectsByGroup) {
      return false;
    }
    const inProfile =
      profiles.size === 0 || profiles.has("full") || scenario.profiles.some((profile) => profiles.has(profile));
    const inFamily = families.size === 0 || families.has(scenario.family);
    return inProfile && inFamily;
  });
  return orderForExecution(selected);
}

/** Stable sort by execution mode so fake-Codex rows always run before real-Codex rows. */
export function orderForExecution(scenarios: readonly Scenario[]): Scenario[] {
  return scenarios
    .map((scenario, index) => ({scenario, index}))
    .sort((left, right) => MODES.indexOf(left.scenario.mode) - MODES.indexOf(right.scenario.mode) || left.index - right.index)
    .map((entry) => entry.scenario);
}

export function planRun(
  scenarios: readonly Scenario[],
  options: Pick<RunOptions, "trello" | "codex" | "github" | "hostProfile" | "network">,
  capabilities: Capabilities,
  automated: ReadonlySet<string>,
): PlanEntry[] {
  return scenarios.map((scenario) => {
    const blocker = missingRequirement(scenario, options, capabilities);
    if (blocker !== null) {
      return {scenario, decision: "skip", reason: blocker};
    }
    if (!automated.has(scenario.id)) {
      return {scenario, decision: "not-yet-automated", reason: "manifest row without automation yet"};
    }
    return {scenario, decision: "run", reason: "requirements met"};
  });
}

function missingRequirement(
  scenario: Scenario,
  options: Pick<RunOptions, "trello" | "codex" | "github" | "hostProfile" | "network">,
  capabilities: Capabilities,
): string | null {
  const {requires} = scenario;
  if (requires.trello && options.trello === "real" && !capabilities.trelloCredentials) {
    return "--trello real needs TRELLO_API_KEY and TRELLO_API_TOKEN";
  }
  if (requires.codex === "real") {
    if (options.codex !== "real") {
      return "real-Codex row; pass --codex real to opt in";
    }
    if (!capabilities.codexCli) {
      return "real-Codex row; the codex CLI is not on PATH";
    }
  }
  if (requires.github) {
    if (options.github !== "real-sandbox") {
      return "GitHub sandbox row; pass --github real-sandbox to opt in";
    }
    if (!capabilities.ghCli) {
      return "GitHub sandbox row; the gh CLI is not on PATH";
    }
  }
  if (requires.network && !options.network) {
    return "needs outbound network; pass --network to opt in";
  }
  if (requires.hardened_host && options.hostProfile !== "hardened") {
    return "needs --host-profile hardened";
  }
  return null;
}
