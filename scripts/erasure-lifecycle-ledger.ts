// Status shared by the hosted lifecycle runner and its tests. The ledger records verification
// evidence; it does not control production deployment.

/** The ledger status a run reaches after both periods and every recovery check passed. */
export const LIFECYCLE_PASS_STATUS = "TWO_PERIOD_LIFECYCLE_PASS";
