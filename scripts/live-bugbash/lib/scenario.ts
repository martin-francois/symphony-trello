import type {FakeTrelloApi} from "./fake-trello-api.ts";
import type {Scenario} from "./manifest.ts";
import type {RunOptions} from "./options.ts";
import type {CleanupSummary} from "./report.ts";
import type {RunRoot} from "./run-root.ts";
import type {CommandOutcome, SymphonyCli} from "./symphony.ts";
import type {TrelloApi} from "./trello.ts";

/** The product misbehaved in a way that prevents the remaining assertions. Reported as a failure. */
export class ProductFailure extends Error {
  override name = "ProductFailure";
}

/** The scenario cannot run in this environment (for example the CLI came from --symphony-command). */
export class SkipScenario extends Error {
  override name = "SkipScenario";
}

/** Collects assertion results. Descriptions are public text, so they must not contain private values. */
export class Checks {
  readonly failures: string[] = [];
  passed = 0;

  that(condition: boolean, description: string): boolean {
    if (condition) {
      this.passed += 1;
    } else {
      this.failures.push(description);
    }
    return condition;
  }

  equal<T>(actual: T, expected: T, description: string): boolean {
    return this.that(Object.is(actual, expected), `${description} (expected ${String(expected)}, got ${String(actual)})`);
  }

  contains(text: string, needle: string, description: string): boolean {
    return this.that(text.includes(needle), description);
  }

  excludes(text: string, needle: string, description: string): boolean {
    return this.that(!text.includes(needle), description);
  }

  exitCode(outcome: CommandOutcome, expected: number, description: string): boolean {
    return this.that(outcome.status === expected, `${description} (expected exit ${expected}, got ${outcome.timedOut ? "timeout" : String(outcome.status)})`);
  }

  failedExit(outcome: CommandOutcome, description: string): boolean {
    return this.that(outcome.status !== 0 && outcome.status !== null, `${description} (got exit ${String(outcome.status)})`);
  }
}

export interface SourceInstall {
  outcome: CommandOutcome;
  command: string;
}

export interface HarnessServices {
  run: RunRoot;
  options: RunOptions;
  targetCommit: string;
  /** Epoch milliseconds when the run's --time-budget ends, or null without a budget. */
  deadline: number | null;
  /** The CLI under test; installs from source on first use unless --symphony-command was given. */
  cli(): Promise<SymphonyCli>;
  /** The source install behind `cli()`, or null when --symphony-command selected an existing install. */
  sourceInstall(): Promise<SourceInstall> | null;
  trello(): TrelloApi;
  fakeTrello: FakeTrelloApi | null;
  /** Runs a shared fixture once per run and caches its result for every scenario that needs it. */
  fixture<T>(id: string, setup: () => Promise<T>): Promise<T>;
  /** Runs the idempotent cleanup routine and returns that pass's own counts. */
  cleanup(): Promise<CleanupSummary>;
  /** Writes the public report files that the private-context scan covers. */
  flushReports(): void;
}

export interface ScenarioContext extends HarnessServices {
  scenario: Scenario;
  check: Checks;
  evidence(name: string, content: string): string;
  caveat(text: string): void;
  /** Selected scenario ids, so shared fixtures prepare only what the run needs. */
  selected: ReadonlySet<string>;
}

/** Returns the public-safe evidence summary for the ledger row. */
export type ScenarioRunner = (context: ScenarioContext) => Promise<string>;

export type ScenarioRegistry = Readonly<Record<string, ScenarioRunner>>;

export function outputOf(outcome: CommandOutcome): string {
  return `${outcome.stdout}\n${outcome.stderr}`;
}

export function commandEvidence(args: readonly string[], outcome: CommandOutcome): string {
  return `$ symphony-trello ${args.join(" ")}\nexit=${String(outcome.status)} timedOut=${outcome.timedOut}\n--- stdout ---\n${outcome.stdout}\n--- stderr ---\n${outcome.stderr}\n`;
}
