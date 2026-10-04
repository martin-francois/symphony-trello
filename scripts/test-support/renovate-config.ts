import {readFileSync} from "node:fs";

export interface RenovateRegexManager {
  readonly customType?: string;
  readonly datasourceTemplate?: string;
  readonly depNameTemplate?: string;
  readonly managerFilePatterns?: readonly string[];
  readonly matchStrings?: readonly string[];
}

export interface RenovatePackageRule {
  readonly addLabels?: readonly string[];
  readonly automerge?: boolean;
  readonly groupName?: string | null;
  readonly matchDatasources?: readonly string[];
  readonly matchPackageNames?: readonly string[];
  readonly matchUpdateTypes?: readonly string[];
  readonly prBodyNotes?: readonly string[];
}

export const RENOVATE_CONFIG = JSON.parse(
  readFileSync(new URL("../../renovate.json", import.meta.url), "utf8"),
) as {
  readonly customManagers?: readonly RenovateRegexManager[];
  readonly packageRules: readonly RenovatePackageRule[];
};

// Renovate applies a package rule to every update type unless matchUpdateTypes
// restricts it. An omitted matchUpdateTypes therefore matches majors AND non-majors,
// which is the opposite of what a naive `!rule.matchUpdateTypes?.includes("major")`
// check concludes.
export function matchesMajor(rule: Pick<RenovatePackageRule, "matchUpdateTypes">): boolean {
  return !rule.matchUpdateTypes || rule.matchUpdateTypes.includes("major");
}

export function matchesNonMajor(rule: Pick<RenovatePackageRule, "matchUpdateTypes">): boolean {
  return (
    !rule.matchUpdateTypes ||
    rule.matchUpdateTypes.some((updateType) => updateType !== "major")
  );
}
