import {readFileSync} from "node:fs";

export interface RenovateRegexManager {
  readonly currentValueTemplate?: string;
  readonly customType?: string;
  readonly datasourceTemplate?: string;
  readonly depNameTemplate?: string;
  readonly managerFilePatterns?: readonly string[];
  readonly matchStrings?: readonly string[];
}

export interface RenovatePackageRule {
  readonly addLabels?: readonly string[];
  readonly automerge?: boolean;
  readonly branchTopic?: string;
  readonly dependencyDashboardApproval?: boolean;
  readonly enabled?: boolean;
  readonly groupName?: string | null;
  readonly groupSlug?: string;
  readonly internalChecksFilter?: string;
  readonly matchDatasources?: readonly string[];
  readonly matchDepTypes?: readonly string[];
  readonly matchManagers?: readonly string[];
  readonly matchPackageNames?: readonly string[];
  readonly matchUpdateTypes?: readonly string[];
  readonly minimumReleaseAge?: string;
  readonly minimumReleaseAgeBehaviour?: string;
  readonly platformAutomerge?: boolean;
  readonly prBodyNotes?: readonly string[];
  readonly schedule?: readonly string[];
}

export interface RenovateConfig {
  readonly branchConcurrentLimit?: number;
  readonly customDatasources?: Readonly<
    Record<
      string,
      {readonly defaultRegistryUrlTemplate?: string; readonly transformTemplates?: readonly string[]}
    >
  >;
  readonly customManagers: readonly RenovateRegexManager[];
  readonly dependencyDashboardApproval?: boolean;
  readonly extends?: readonly string[];
  readonly internalChecksFilter?: string;
  readonly lockFileMaintenance?: {
    readonly automerge?: boolean;
    readonly enabled?: boolean;
    readonly schedule?: readonly string[];
  };
  readonly minimumReleaseAge?: string;
  readonly minimumReleaseAgeBehaviour?: string;
  readonly packageRules: readonly RenovatePackageRule[];
  readonly pinDigests?: boolean;
  readonly prConcurrentLimit?: number;
  readonly schedule?: readonly string[];
  readonly separateMajorMinor?: boolean;
  readonly separateMultipleMajor?: boolean;
  readonly statusCheckWhen?: {
    readonly minimumReleaseAge?: string;
  };
  readonly vulnerabilityAlerts?: {
    readonly enabled?: boolean;
  };
}

export const RENOVATE_CONFIG = JSON.parse(
  readFileSync(new URL("../../renovate.json", import.meta.url), "utf8"),
) as RenovateConfig;

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

function globToRegExp(glob: string): RegExp {
  const source = glob
    .split(/(\*\*|\*|\?)/u)
    .map((part) => {
      if (part === "**") {
        return ".*";
      }
      if (part === "*") {
        return "[^/]*";
      }
      if (part === "?") {
        return "[^/]";
      }
      return part.replaceAll(/[.+^${}()|[\]\\]/gu, "\\$&");
    })
    .join("");
  return new RegExp(`^${source}$`, "iu");
}

// Renovate reads a pattern wrapped in slashes, with optional flags after the closing slash, as a
// regular expression, a lone `*` as everything, and any other pattern as a case-insensitive glob;
// brace and character-class globs are not modelled because no pattern in renovate.json uses them.
export function matchesRegexOrGlob(pattern: string, value: string): boolean {
  if (pattern === "*") {
    return true;
  }
  const regex = /^\/(.*)\/([a-z]*)$/u.exec(pattern);
  if (regex === null) {
    return globToRegExp(pattern).test(value);
  }
  const [, source = "", flags = ""] = regex;
  return new RegExp(source, flags.includes("u") ? flags : `${flags}u`).test(value);
}

// A `!` pattern excludes, and a list of only exclusions matches every other value, as in
// Renovate's matchPackageNames.
export function matchesRegexOrGlobList(patterns: readonly string[], value: string): boolean {
  const exclusions = patterns.filter((pattern) => pattern.startsWith("!"));
  const inclusions = patterns.filter((pattern) => !pattern.startsWith("!"));
  if (exclusions.some((pattern) => matchesRegexOrGlob(pattern.slice(1), value))) {
    return false;
  }
  return inclusions.length === 0 || inclusions.some((pattern) => matchesRegexOrGlob(pattern, value));
}
