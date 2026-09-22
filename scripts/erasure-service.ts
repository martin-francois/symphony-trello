import {readFileSync} from "node:fs";

/** The same canonical source is used by OpenTofu and the hosted lifecycle test. */
export function erasureService(scope: string, project: number, versions: readonly string[] = ["k1"]): string {
  if (!/^[a-z0-9:-]{1,80}$/.test(scope) || !Number.isSafeInteger(project) || project < 1)
    throw new Error("Invalid erasure deployment");
  if (!versions.length || versions.some(v => !/^k[1-9][0-9]{0,3}$/.test(v)) || new Set(versions).size !== versions.length)
    throw new Error("Invalid master key versions");
  return readFileSync(new URL("../infra/posthog/erasure-service.hog.tftpl", import.meta.url), "utf8")
    .replaceAll("${scope}", scope)
    .replaceAll("${project}", String(project))
    .replaceAll("${active_key}", versions[versions.length - 1]!)
    .replaceAll("${master_key_references}", versions.map(v => `'${v}': inputs.master_${v}`).join(", "));
}

export const mergeFilter = readFileSync(new URL("../infra/posthog/merge-filter.hog", import.meta.url), "utf8");
