import type {Scenario} from "./manifest.ts";

const SCENARIO_TIMEOUT_MS = 10 * 60_000;

/** A soak controls its own duration; only the whole-run budget imposes an outer deadline. */
export function scenarioTimeoutMs(scenario: Pick<Scenario, "family">, deadline: number | null, now = Date.now()): number | null {
  const remaining = deadline === null ? null : Math.max(0, deadline - now);
  if (scenario.family === "soak") return remaining;
  return remaining === null ? SCENARIO_TIMEOUT_MS : Math.min(SCENARIO_TIMEOUT_MS, remaining);
}
