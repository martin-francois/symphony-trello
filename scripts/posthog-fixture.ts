// Dashboard fixture: the documented synthetic installations, sent as a run-owned cohort with fresh
// installation IDs, waited for by exact event UUID, checked panel by panel with the canonical query
// files scoped to that cohort, and then queued for deletion.

import {randomUUID} from "node:crypto";
import {existsSync, readFileSync, writeFileSync} from "node:fs";
import {join} from "node:path";
import {InfraError, isObject, PostHogApi, REQUEST_TIMEOUT_MS, runQuery, type Json, type JsonObject, type Sleeper} from "./posthog-api.ts";
import {infraDir, type Role} from "./posthog-state-files.ts";

export const HEARTBEAT_EVENT = "installation_heartbeat";
const INGESTION_ATTEMPTS = 20;
const INGESTION_DELAY_MS = 15000;

interface FixtureReport {
  readonly days_ago: number;
  readonly time: string;
  readonly properties?: Readonly<Record<string, Json>>;
}

interface FixtureInstallation {
  readonly distinct_id: string;
  readonly registered_days_ago: number;
  readonly properties: Readonly<Record<string, Json>>;
  readonly reports: readonly FixtureReport[];
}

export interface Fixture {
  readonly release_version: string;
  readonly release_date_days_ago: number;
  readonly installations: readonly FixtureInstallation[];
  readonly expected: Readonly<Record<string, Json>>;
}

/** One run's cohort: the identities are fixed at creation so a retry resends the same events. */
export interface FixtureRun {
  readonly run_id: string;
  readonly reference_time: string;
  readonly cohort: Readonly<Record<string, string>>;
  readonly event_uuids: readonly string[];
  readonly cleanup?: Json;
}

export function newFixtureRun(fixture: Fixture, referenceTime: Date): FixtureRun {
  const cohort: Record<string, string> = {};
  for (const installation of fixture.installations) {
    cohort[installation.distinct_id] = randomUUID();
  }
  const eventCount = fixture.installations.reduce((sum, installation) => sum + installation.reports.length, 0);
  return {
    run_id: `fixture-${randomUUID().slice(0, 8)}`,
    reference_time: referenceTime.toISOString(),
    cohort,
    event_uuids: Array.from({length: eventCount}, () => randomUUID()),
  };
}

export function fixtureEvents(fixture: Fixture, run: FixtureRun, token: string): readonly JsonObject[] {
  const reference = new Date(run.reference_time);
  const events: JsonObject[] = [];
  let index = 0;
  for (const installation of fixture.installations) {
    const distinctId = run.cohort[installation.distinct_id];
    if (distinctId === undefined) {
      throw new InfraError(`run has no cohort id for ${installation.distinct_id}`);
    }
    const registeredOn = isoDate(daysBefore(reference, installation.registered_days_ago));
    for (const report of installation.reports) {
      const properties: Record<string, Json> = {
        telemetry_schema_version: 1,
        registered_on: registeredOn,
        ...installation.properties,
        ...(report.properties ?? {}),
        $geoip_disable: true,
        $process_person_profile: true,
      };
      events.push({
        api_key: token,
        event: HEARTBEAT_EVENT,
        distinct_id: distinctId,
        uuid: run.event_uuids[index] ?? randomUUID(),
        timestamp: `${isoDate(daysBefore(reference, report.days_ago))}T${report.time}:00Z`,
        properties,
      });
      index++;
    }
  }
  return events;
}

function daysBefore(date: Date, days: number): Date {
  return new Date(date.getTime() - days * 86_400_000);
}

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function renderQuery(sql: string, values: Readonly<Record<string, string>>): string {
  return sql.replace(/\$\{(\w+)\}/g, (_match, name: string) => {
    const value = values[name];
    if (value === undefined) {
      throw new InfraError(`query template needs ${name}`);
    }
    return value;
  });
}

const EVENT_FILTER = `WHERE event = '${HEARTBEAT_EVENT}'`;

/** The canonical query, restricted to the run's cohort by extending its event filter. The canonical
 * text is otherwise untouched, so what runs is the deployed logic, not a second copy. */
export function scopeQuery(sql: string, cohortIds: readonly string[]): string {
  if (!sql.includes(EVENT_FILTER)) {
    throw new InfraError("canonical query lacks the heartbeat event filter; cannot scope it to the cohort");
  }
  const list = cohortIds.map((id) => `'${id}'`).join(", ");
  return sql.split(EVENT_FILTER).join(`${EVENT_FILTER} AND distinct_id IN (${list})`);
}
export interface PanelOutcome {
  readonly panel: string;
  readonly expected: string;
  readonly observed: string;
  readonly pass: boolean;
}

/** Expected rows for the panels whose result depends on the reference time: the activity windows
 * count reports inside each window as of the reference instant, and the registration cohorts group
 * the registration dates by calendar month. */
export function timeDependentExpectations(fixture: Fixture, referenceTime: Date): Readonly<Record<string, Json>> {
  const windows = [1, 7, 30].map((days) => referenceTime.getTime() - days * 86_400_000);
  const active = windows.map((start) =>
    fixture.installations.filter((installation) =>
      installation.reports.some((report) => {
        const at = Date.parse(`${isoDate(daysBefore(referenceTime, report.days_ago))}T${report.time}:00Z`);
        return at >= start && at <= referenceTime.getTime();
      }),
    ).length,
  );
  const months = new Map<string, number>();
  for (const installation of fixture.installations) {
    const registered = daysBefore(referenceTime, installation.registered_days_ago);
    const month = `${registered.toISOString().slice(0, 7)}-01`;
    months.set(month, (months.get(month) ?? 0) + 1);
  }
  return {
    "active-installations": [active],
    "registration-cohorts": [...months.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([month, count]) => [month, count]),
  };
}

export function comparePanel(panel: string, expected: Json, rows: readonly Json[]): PanelOutcome {
  return {panel, expected: JSON.stringify(expected), observed: JSON.stringify(rows), pass: sameRows(expected as Json[], rows)};
}

function sameRows(expected: readonly Json[], observed: readonly Json[]): boolean {
  const normalize = (rows: readonly Json[]) => rows.map((row) => JSON.stringify(row)).sort();
  const left = normalize(expected);
  const right = normalize(observed);
  return left.length === right.length && left.every((row, index) => row === right[index]);
}

/** Sends the fixture cohort to one role, waits until every event is queryable, checks each panel,
 * and queues the cohort for deletion. Returns the exit code: 0 when every panel passes. */
export async function runFixture(api: PostHogApi, role: Role, fixturePath: string, captureEndpoint: string, runFile: string, sleep: Sleeper): Promise<number> {
  const fixture = JSON.parse(readFileSync(fixturePath, "utf8")) as Fixture;
  // A run file from an interrupted attempt keeps its identities so the retry resends the same
  // events; a new attempt gets a new cohort, so old fixtures never satisfy the new check.
  const run: FixtureRun = existsSync(runFile) ? (JSON.parse(readFileSync(runFile, "utf8")) as FixtureRun) : newFixtureRun(fixture, new Date());
  if (run.cleanup !== undefined) {
    throw new InfraError(`${runFile} belongs to a finished run; remove it or choose another run file`);
  }
  writeFileSync(runFile, JSON.stringify(run, null, 2), {mode: 0o600});
  const reference = new Date(run.reference_time);
  const events = fixtureEvents(fixture, run, role.captureToken);
  for (const event of events) {
    const response = await fetch(captureEndpoint, {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify(event),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    await response.body?.cancel();
    if (!response.ok) {
      throw new InfraError(`capture answered HTTP ${response.status}`);
    }
  }
  console.log(`fixture ${run.run_id}: sent ${events.length} events to role ${role.name}`);
  const cohortIds = Object.values(run.cohort);
  const uuidList = run.event_uuids.map((uuid) => `'${uuid}'`).join(", ");
  const visibleSql = scopeQuery(`SELECT toString(uuid) FROM events ${EVENT_FILTER} AND uuid IN (${uuidList}) AND timestamp >= now() - interval 400 day`, cohortIds);
  let visible = new Set<string>();
  for (let attempt = 1; attempt <= INGESTION_ATTEMPTS; attempt++) {
    visible = new Set((await runQuery(api, role.projectId, visibleSql)).map((row) => String((row as Json[])[0])));
    if (run.event_uuids.every((uuid) => visible.has(uuid))) {
      break;
    }
    await sleep(INGESTION_DELAY_MS);
  }
  const missing = run.event_uuids.filter((uuid) => !visible.has(uuid));
  if (missing.length > 0) {
    throw new InfraError(`fixture ${run.run_id}: ${missing.length} of ${events.length} events not queryable after waiting; rerun with the same run file to retry`);
  }
  const releaseDate = isoDate(daysBefore(reference, fixture.release_date_days_ago));
  const expectations = {...fixture.expected, ...timeDependentExpectations(fixture, reference)};
  let failures = 0;
  for (const [panel, expected] of Object.entries(expectations)) {
    const canonical = panel === "release-adoption-and-delay"
      ? renderQuery(readFileSync(join(infraDir(), "queries", `${panel}.sql.tftpl`), "utf8"), {release_date: releaseDate, release_version: fixture.release_version})
      : readFileSync(join(infraDir(), "queries", `${panel}.sql`), "utf8");
    const outcome = comparePanel(panel, expected, await runQuery(api, role.projectId, scopeQuery(canonical, cohortIds)));
    console.log(`${outcome.pass ? "PASS" : "FAIL"} ${panel}: ${outcome.observed}${outcome.pass ? "" : ` (expected ${outcome.expected})`}`);
    if (!outcome.pass) {
      failures++;
    }
  }
  // Cleanup is queued, never awaited: PostHog deletes events in a later batch. Its status is
  // recorded in the run file and reported separately from the panel result.
  const cleanup = await api.post(`/api/projects/${role.projectId}/persons/bulk_delete/`, {distinct_ids: cohortIds, delete_events: true});
  writeFileSync(runFile, JSON.stringify({...run, cleanup: {requested_at: new Date().toISOString(), response: cleanup}}, null, 2), {mode: 0o600});
  const cleanupBody = isObject(cleanup) ? cleanup : {};
  console.log(`fixture ${run.run_id}: cleanup queued (persons_found ${String(cleanupBody["persons_found"])}, events_queued_for_deletion ${String(cleanupBody["events_queued_for_deletion"])}); panels ${failures === 0 ? "all pass" : `${failures} failing`}`);
  return failures === 0 ? 0 : 1;
}
