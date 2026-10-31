// The parts of the hosted erasure lifecycle ledger that other tools read. The runner in
// erasure-lifecycle-live.ts writes the ledger; scripts/posthog-infra reads the pass from it.

/** The ledger status a run reaches after both periods and every recovery check passed. */
export const LIFECYCLE_PASS_STATUS = "TWO_PERIOD_LIFECYCLE_PASS";

const SHA256_HEX = /^[0-9a-f]{64}$/;

/** The ledger fields that bind a run to one handler version and record its outcome. */
export interface LifecycleOutcome {
  readonly status?: string;
  readonly handlerSha256?: string;
  readonly handlerMixed?: boolean;
}

/** The handler hash a ledger authorizes for production, or undefined when it authorizes none: the
 * run must have passed, on one handler only, and recorded that handler's hash. OpenTofu compares
 * the hash with the current template, so a changed handler needs a new pass. */
export function authorizedHandlerSha256(ledger: LifecycleOutcome): string | undefined {
  if (ledger.status !== LIFECYCLE_PASS_STATUS || ledger.handlerMixed === true) {
    return undefined;
  }
  return SHA256_HEX.test(ledger.handlerSha256 ?? "") ? ledger.handlerSha256 : undefined;
}
