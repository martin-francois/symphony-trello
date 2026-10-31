// Review of the erasure status flags the native handler writes: classify each one and, on request,
// archive the ones that are safe to archive.

import {InfraError, isObject, PAGE_LIMIT, PostHogApi, runQuery, type JsonObject} from "./posthog-api.ts";

const DAY_MS = 24 * 60 * 60 * 1000;
/** Clients read their result within hours. An archived empty or complete flag is recreated by the
 * owner's next request, so archiving one never strands a client; the age only avoids churn. */
export const ERASURE_FLAG_MIN_AGE_MS = DAY_MS;
/** A pending or accepted operation this old has lost its client or is stuck at PostHog. */
export const ERASURE_FLAG_STALE_MS = 14 * DAY_MS;
/** Well below PostHog's limit of 2,000 non-deleted flags per project. */
export const ERASURE_FLAG_WARNING = 500;
const RANDOM_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const PERIOD_ANALYTICS_ID = new RegExp(`^${RANDOM_UUID}\\.${RANDOM_UUID}$`);

export type ErasureFlagAction = "archive" | "late-events" | "refused" | "stale" | "keep";

export interface ErasureFlagReview {
  readonly id: number;
  readonly name: string;
  readonly state: string;
  readonly createdAt: string;
  readonly action: ErasureFlagAction;
  readonly remainingEvents?: number;
}

/** Non-deleted status flags written by infra/posthog/erasure-service.hog.tftpl. */
export async function erasureFlagList(api: PostHogApi, projectId: number): Promise<readonly JsonObject[]> {
  const flags = await api.listAll(`/api/projects/${projectId}/feature_flags/?search=erasure-&limit=${PAGE_LIMIT}`);
  return flags.filter((flag) => typeof flag["key"] === "string" && flag["key"].startsWith("erasure-")
    && typeof flag["name"] === "string" && flag["name"].startsWith("symphony-erasure-") && flag["deleted"] !== true);
}

function flagVariant(flag: JsonObject): string {
  const filters = isObject(flag["filters"]) ? flag["filters"] : {};
  const multivariate = isObject(filters["multivariate"]) ? filters["multivariate"] : {};
  const variants = Array.isArray(multivariate["variants"]) ? multivariate["variants"] : [];
  const first = variants[0];
  return isObject(first) && typeof first["key"] === "string" ? first["key"] : "unknown";
}

/** Classifies every status flag and, when asked, archives the ones that are safe to archive:
 * empty results and completed deletions older than a day. An empty result that names its analytics
 * ID is archived only after a fresh query still finds no event for it; a late event is reported
 * for manual deletion instead. Refused and stale operations are reported, never archived. */
export async function reviewErasureFlags(api: PostHogApi, projectId: number, now: Date, archive: boolean): Promise<readonly ErasureFlagReview[]> {
  const reviews: ErasureFlagReview[] = [];
  for (const flag of await erasureFlagList(api, projectId)) {
    const name = String(flag["name"]);
    const createdAt = typeof flag["created_at"] === "string" ? flag["created_at"] : "";
    const age = now.getTime() - Date.parse(createdAt);
    const state = flagVariant(flag);
    const [kind, binding = ""] = name.split("|");
    let action: ErasureFlagAction = "keep";
    let remainingEvents: number | undefined;
    if (!(age >= 0)) {
      action = "keep";
    } else if (kind === "symphony-erasure-empty-v2") {
      if (!PERIOD_ANALYTICS_ID.test(binding)) {
        throw new InfraError(`status flag ${String(flag["id"])} names no valid analytics ID`);
      }
      if (age >= ERASURE_FLAG_MIN_AGE_MS) {
        const rows = await runQuery(api, projectId, `SELECT count() FROM events WHERE distinct_id = '${binding}'`);
        const first = rows[0];
        remainingEvents = Array.isArray(first) ? Number(first[0]) : NaN;
        if (!Number.isInteger(remainingEvents)) {
          throw new InfraError(`status flag ${String(flag["id"])}: unreadable event count`);
        }
        action = remainingEvents === 0 ? "archive" : "late-events";
      }
    } else if (kind === "symphony-erasure-empty-v1") {
      action = age >= ERASURE_FLAG_MIN_AGE_MS ? "archive" : "keep";
    } else if (kind === "symphony-erasure-v1") {
      if (state === "refused") {
        action = "refused";
      } else if (state === "complete") {
        action = age >= ERASURE_FLAG_MIN_AGE_MS ? "archive" : "keep";
      } else if (age >= ERASURE_FLAG_STALE_MS) {
        action = "stale";
      }
    }
    if (action === "archive" && archive) {
      await api.patch(`/api/projects/${projectId}/feature_flags/${String(flag["id"])}/`, {active: false, deleted: true});
    }
    reviews.push({id: Number(flag["id"]), name, state, createdAt, action, ...(remainingEvents === undefined ? {} : {remainingEvents})});
  }
  return reviews;
}
