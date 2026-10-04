import {RENOVATE_CONFIG, type RenovateRegexManager} from "./renovate-config.ts";

export interface RegexDependency {
  readonly currentValue: string;
  readonly datasource: string | undefined;
  readonly depName: string | undefined;
  /** Offset of currentValue in the file, which is where Renovate writes the new version. */
  readonly valueOffset: number;
}

// Renovate treats a managerFilePatterns entry wrapped in slashes as a regular expression.
function managesFile(manager: RenovateRegexManager, path: string): boolean {
  return (manager.managerFilePatterns ?? []).some((pattern) => {
    const regex = /^\/(.*)\/$/u.exec(pattern)?.[1];
    return regex === undefined ? pattern === path : new RegExp(regex, "u").test(path);
  });
}

/**
 * Applies the repository's Renovate regex managers to one file the way Renovate's default
 * `matchStringsStrategy: "any"` does: every match string runs over the whole file.
 */
export function extractRegexDependencies(path: string, content: string): RegexDependency[] {
  return (RENOVATE_CONFIG.customManagers ?? [])
    .filter((manager) => manager.customType === "regex" && managesFile(manager, path))
    .flatMap((manager) =>
      (manager.matchStrings ?? []).flatMap((matchString) =>
        [...content.matchAll(new RegExp(matchString, "gdu"))].map((match) => {
          const currentValue = match.groups?.["currentValue"];
          const valueOffset = match.indices?.groups?.["currentValue"]?.[0];
          if (currentValue === undefined || valueOffset === undefined) {
            throw new Error(`${matchString} must capture currentValue`);
          }
          return {
            currentValue,
            datasource: manager.datasourceTemplate,
            depName: match.groups?.["depName"] ?? manager.depNameTemplate,
            valueOffset,
          };
        }),
      ),
    );
}

/** Replaces the extracted version in place, which is the only edit a Renovate update makes. */
export function applyRegexUpdate(
  content: string,
  dependency: RegexDependency,
  newValue: string,
): string {
  const valueEnd = dependency.valueOffset + dependency.currentValue.length;
  return content.slice(0, dependency.valueOffset) + newValue + content.slice(valueEnd);
}
