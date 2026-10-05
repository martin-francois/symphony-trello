// Minimal PostHog management API client and the HogQL query helper the other posthog-* modules
// share. Messages name the method and path, never the key; nothing here prints or stores a
// credential.

import {readFileSync} from "node:fs";
import {join} from "node:path";

export const DEFAULT_CAPTURE_ENDPOINT = "https://eu.i.posthog.com/i/v0/e/";
export const DEFAULT_HOST = "https://eu.posthog.com";
const PERSONAL_KEY_PREFIX = "phx_";
export const PAGE_LIMIT = 100;
const MAX_PAGES = 50;
export const REQUEST_TIMEOUT_MS = 60_000;
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
const REDACTED = "[redacted]";

export type Json = null | boolean | number | string | Json[] | {readonly [key: string]: Json};
export type JsonObject = {readonly [key: string]: Json};

export class InfraError extends Error {}

export interface Sleeper {
  (milliseconds: number): Promise<void>;
}

export const realSleep: Sleeper = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

/** Minimal management API client. Messages name the method and path, never the key. */
export class PostHogApi {
  readonly #host: string;
  readonly #key: string;
  readonly #fetch: typeof fetch;

  constructor(host: string, key: string, fetchImpl: typeof fetch = fetch) {
    this.#host = host.replace(/\/+$/, "");
    this.#key = key;
    this.#fetch = fetchImpl;
  }

  async get(path: string): Promise<Json> {
    return this.#send("GET", path);
  }

  async patch(path: string, body: JsonObject): Promise<Json> {
    return this.#send("PATCH", path, body);
  }

  async post(path: string, body: JsonObject): Promise<Json> {
    return this.#send("POST", path, body);
  }

  async delete(path: string): Promise<void> {
    await this.#send("DELETE", path);
  }

  /** The key never appears in anything this client returns or throws, even when a server echoes it. */
  #redact(text: string): string {
    return this.#key === "" ? text : text.split(this.#key).join(REDACTED);
  }

  /** Follows `next` links, bounded by page count and by never revisiting a link, and refuses a
   * listing whose `count` the pages do not add up to. */
  async listAll(path: string): Promise<readonly JsonObject[]> {
    const results: JsonObject[] = [];
    const visited = new Set<string>();
    let next: string | null = path;
    let count: number | undefined;
    while (next !== null) {
      if (visited.has(next)) {
        throw new InfraError(`${path}: pagination repeats ${next}`);
      }
      if (visited.size >= MAX_PAGES) {
        throw new InfraError(`${path}: more than ${MAX_PAGES} pages, refusing`);
      }
      visited.add(next);
      const page = asObject(await this.get(next), next);
      const pageResults = page["results"];
      if (!Array.isArray(pageResults)) {
        throw new InfraError(`${next}: listing has no results array`);
      }
      results.push(...pageResults.map((entry) => asObject(entry, next as string)));
      if (typeof page["count"] === "number") {
        count = page["count"];
      }
      const link = page["next"];
      next = typeof link === "string" && link.length > 0 ? link.replace(this.#host, "") : null;
    }
    if (count !== undefined && count !== results.length) {
      throw new InfraError(`${path}: listing reports ${count} entries but the pages held ${results.length}`);
    }
    return results;
  }

  async #send(method: string, path: string, body?: JsonObject): Promise<Json> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.#key}`,
      Accept: "application/json",
      "User-Agent": "symphony-trello-posthog-infra/1",
    };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    let response: Response;
    try {
      response = await this.#fetch(`${this.#host}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        redirect: "manual",
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (error) {
      throw new InfraError(`${method} ${path} failed: ${error instanceof Error ? error.name : "network error"}`);
    }
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      throw new InfraError(`${method} ${path} answered a redirect (${response.status}), refused`);
    }
    const text = this.#redact(await readBounded(response, `${method} ${path}`));
    if (!response.ok) {
      throw new InfraError(`${method} ${path} answered HTTP ${response.status}: ${detailOf(text)}`);
    }
    if (text.trim() === "") {
      return null;
    }
    try {
      return JSON.parse(text) as Json;
    } catch {
      throw new InfraError(`${method} ${path} answered non-JSON content`);
    }
  }
}

/** Reads at most MAX_RESPONSE_BYTES within the request's deadline; more is refused, not truncated. */
export async function readBounded(response: Response, context: string): Promise<string> {
  const reader = response.body?.getReader();
  if (reader === undefined) {
    return "";
  }
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const {done, value} = await reader.read();
      if (done) {
        break;
      }
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        throw new InfraError(`${context} answered more than ${MAX_RESPONSE_BYTES} bytes, refused`);
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof InfraError) {
      throw error;
    }
    throw new InfraError(`${context} body failed: ${error instanceof Error ? error.name : "read error"}`);
  } finally {
    await reader.cancel().catch(() => undefined);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function detailOf(text: string): string {
  try {
    const parsed = JSON.parse(text) as Json;
    if (isObject(parsed) && typeof parsed["detail"] === "string") {
      return parsed["detail"].slice(0, 200);
    }
  } catch {
    // Not JSON; fall through to the raw prefix.
  }
  return text.slice(0, 200);
}

export function isObject(value: Json | undefined): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function asObject(value: Json, context: string): JsonObject {
  if (!isObject(value)) {
    throw new InfraError(`${context}: expected a JSON object`);
  }
  return value;
}

/** The personal API key file: SYMPHONY_TRELLO_POSTHOG_KEY_FILE, or posthog-personal-api-key in the home directory. */
export function keyFilePath(environment: NodeJS.ProcessEnv): string {
  return environment["SYMPHONY_TRELLO_POSTHOG_KEY_FILE"] ?? join(environment["HOME"] ?? ".", "posthog-personal-api-key");
}

export function readKey(keyFile: string): string {
  let key: string;
  try {
    key = readFileSync(keyFile, "utf8").trim();
  } catch {
    throw new InfraError(`personal API key file not readable: ${keyFile}`);
  }
  if (!key.startsWith(PERSONAL_KEY_PREFIX)) {
    throw new InfraError(`${keyFile} does not hold a personal API key`);
  }
  return key;
}

export async function runQuery(api: PostHogApi, projectId: number, sql: string): Promise<readonly Json[]> {
  const response = asObject(
    await api.post(`/api/projects/${projectId}/query/`, {query: {kind: "HogQLQuery", query: sql}, refresh: "force_blocking"}),
    "query",
  );
  if (response["is_cached"] === true) {
    throw new InfraError("query answered from cache despite refresh=force_blocking");
  }
  const results = response["results"];
  if (!Array.isArray(results)) {
    throw new InfraError("query answered without a results array");
  }
  return results;
}
