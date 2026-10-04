import type {ScenarioRegistry} from "../lib/scenario.ts";
import {CLEANUP_SCENARIOS} from "./cleanup.ts";
import {CODEX_ACCESS_SCENARIOS} from "./codex-access.ts";
import {DIAGNOSTICS_SCENARIOS} from "./diagnostics.ts";
import {LIFECYCLE_SCENARIOS} from "./lifecycle.ts";
import {REPOSITORY_SOURCE_SCENARIOS} from "./repository-source.ts";
import {SETUP_SCENARIOS} from "./setup.ts";
import {SOAK_SCENARIOS} from "./soak.ts";
import {TRELLO_PREREQUISITES_SCENARIOS} from "./trello-prerequisites.ts";
import {TRELLO_TOOLS_SCENARIOS} from "./trello-tools.ts";

/** Every automated scenario, keyed by manifest id. Manifest rows missing here are not-yet-automated. */
export const SCENARIOS: ScenarioRegistry = {
  ...SETUP_SCENARIOS,
  ...LIFECYCLE_SCENARIOS,
  ...REPOSITORY_SOURCE_SCENARIOS,
  ...TRELLO_PREREQUISITES_SCENARIOS,
  ...TRELLO_TOOLS_SCENARIOS,
  ...DIAGNOSTICS_SCENARIOS,
  ...CODEX_ACCESS_SCENARIOS,
  ...CLEANUP_SCENARIOS,
  ...SOAK_SCENARIOS,
};
