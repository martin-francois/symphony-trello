import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { parse } from "yaml";
import { applyRegexUpdate, extractRegexDependencies } from "./test-support/renovate-regex-manager.ts";
import { RENOVATE_CONFIG, matchesRegexOrGlobList } from "./test-support/renovate-config.ts";

const POM = readFileSync(new URL("../pom.xml", import.meta.url), "utf8");
const CONFIDENCE_MAP = readFileSync(
  new URL("../docs/testing/dependency-upgrade-confidence.md", import.meta.url),
  "utf8",
);
const CI_WORKFLOW = readFileSync(
  new URL("../.github/workflows/ci.yml", import.meta.url),
  "utf8",
);

const PROJECT_ARTIFACTS = new Set([
  "${quarkus.platform.artifact-id}",
  "symphony-trello",
]);

test("Renovate owns both commitlint packages", () => {
  // given
  const path = "scripts/commitlint-versions";
  const content = readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

  // when
  const pins = extractRegexDependencies(path, content);

  // then
  assert.deepEqual(
    pins.map(({ depName, datasource }) => ({ depName, datasource })).sort(
      (left, right) => (left.depName ?? "").localeCompare(right.depName ?? ""),
    ),
    [
      { depName: "@commitlint/cli", datasource: "npm" },
      { depName: "@commitlint/config-conventional", datasource: "npm" },
    ],
    `${path} must expose exactly both commitlint packages to Renovate`,
  );
  assert.deepEqual(
    pins.filter(({ depName, currentValue }) => !content.includes(`"${depName}@${currentValue}"`)),
    [],
    `Renovate must read each full version pinned in ${path}`,
  );
});

test("Renovate updates the actual Palantir formatter pin without changing other POM content", () => {
  // given
  const formatterPins = extractRegexDependencies("pom.xml", POM).filter(
    ({ depName }) => depName === "com.palantir.javaformat:palantir-java-format",
  );
  assert.equal(formatterPins.length, 1, "the configured formatter must have exactly one Renovate owner");
  const [formatter] = formatterPins;
  assert.ok(formatter, "the configured formatter version must be extracted");
  assert.equal(formatter.datasource, "maven");

  // when
  const updated = applyRegexUpdate(POM, formatter, "99.0.0");

  // then
  assert.match(updated, /<palantirJavaFormat>\s*<version>99\.0\.0<\/version>/u);
  assert.equal(updated.replace("99.0.0", formatter.currentValue), POM);
});

// A row documents its artifacts only when both evidence cells are filled; a name that appears in
// prose, or beside an empty cell, proves nothing about the required check.
function artifactsWithEvidence(confidenceMap: string): Set<string> {
  const documented = new Set<string>();

  for (const row of confidenceMap.split("\n")) {
    const cells = row
      .split("|")
      .slice(1, -1)
      .map((cell) => cell.trim());
    const [artifacts, failureSurface, requiredDetection] = cells;
    if (
      cells.length !== 3 ||
      artifacts === undefined ||
      failureSurface === undefined ||
      requiredDetection === undefined ||
      failureSurface === "" ||
      requiredDetection === ""
    ) {
      continue;
    }

    for (const match of artifacts.matchAll(/`([^`]+)`/gu)) {
      if (match[1] !== undefined) {
        documented.add(match[1]);
      }
    }
  }

  return documented;
}

test("every Maven dependency and plugin has upgrade-confidence evidence", () => {
  // given
  const declaredArtifacts = [
    ...new Set(
      [...POM.matchAll(/<artifactId>([^<]+)<\/artifactId>/gu)].map(
        (match) => match[1],
      ),
    ),
  ].filter(
    (artifact): artifact is string =>
      artifact !== undefined && !PROJECT_ARTIFACTS.has(artifact),
  );
  const documented = artifactsWithEvidence(CONFIDENCE_MAP);

  // when
  const missingEvidence = declaredArtifacts.filter(
    (artifact) => !documented.has(artifact),
  );

  // then
  assert.deepEqual(
    missingEvidence,
    [],
    "every Maven artifact must name its required update checks",
  );
});

test("an artifact without both evidence cells has no upgrade-confidence evidence", () => {
  // given
  const confidenceMap = [
    "| Dependencies and plugins | Primary failure surface | Required detection |",
    "| ------------------------ | ----------------------- | ------------------ |",
    "| `documented-artifact`    | Configuration parsing   | Parser tests       |",
    "| `missing-detection`      | Configuration parsing   |                    |",
    "| `missing-surface`        |                         | Parser tests       |",
    "",
    "The map also mentions `prose-only-artifact` outside the table.",
  ].join("\n");

  // when
  const documented = artifactsWithEvidence(confidenceMap);

  // then
  assert.deepEqual([...documented], ["documented-artifact"]);
});

test("Renovate validation is reproducible and permits only required builds", () => {
  // given
  const expectedCommand =
    "pnpm dlx --allow-build=core-js-pure --allow-build=dtrace-provider " +
    "--allow-build=protobufjs --allow-build=re2 --package renovate@44.181.1 " +
    "renovate-config-validator renovate.json --strict";
  const ciWorkflow = parse(CI_WORKFLOW) as {
    jobs?: {
      renovate?: {
        steps?: Array<{ name?: unknown; run?: unknown }>;
      };
    };
  };

  // when
  const validationCommand = ciWorkflow.jobs?.renovate?.steps?.find(
    ({ name }) => name === "Validate Renovate config",
  )?.run;

  // then
  assert.equal(validationCommand, expectedCommand);
});

test("Renovate holds json-schema-validator on 2.x while Quarkus is on Jackson 2", () => {
  // given
  const quarkusVersion = /<quarkus\.platform\.version>([^<]+)<\/quarkus\.platform\.version>/u.exec(POM)?.[1];
  assert.match(quarkusVersion ?? "", /^\d+\./u, "pom.xml must declare a numeric quarkus.platform.version");

  // when
  const holds = RENOVATE_CONFIG.packageRules
    .filter(
      ({ allowedVersions, enabled, matchDatasources, matchPackageNames }) =>
        allowedVersions !== undefined &&
        enabled !== false &&
        (matchDatasources === undefined || matchDatasources.includes("maven")) &&
        matchPackageNames !== undefined &&
        matchesRegexOrGlobList(matchPackageNames, "com.networknt:json-schema-validator"),
    )
    .map(({ allowedVersions }) => allowedVersions);

  // then
  // The hold expires with Quarkus 4, so this check fails first and says what to do.
  assert.ok(
    Number.parseInt(quarkusVersion ?? "", 10) < 4,
    `Quarkus ${quarkusVersion} runs on Jackson 3: remove the json-schema-validator hold from renovate.json, ` +
      "this test, and the hold notes in ADR 0088 and docs/agents/dependency-updates.md, then move to " +
      "validator 3.x as https://github.com/martin-francois/symphony-trello/issues/1119 describes",
  );
  // A Maven range, because Renovate's Maven versioning rejects "<3" and would fall back to npm semver.
  assert.deepEqual(
    holds,
    ["(,3.0)"],
    "restore the (,3.0) json-schema-validator hold in renovate.json: 3.x requires Jackson 3, which Quarkus 3 does not use",
  );
});

test("the OpenTofu version is pinned once per owner and the owners agree", () => {
  // given
  const ciWorkflow = parse(CI_WORKFLOW) as {
    jobs?: {"posthog-infra"?: {steps?: Array<{uses?: unknown; with?: {tofu_version?: unknown}}>}};
  };
  const miseConfig = readFileSync("infra/posthog/mise.toml", "utf8");
  const renovateConfig = JSON.parse(readFileSync("renovate.json", "utf8")) as {
    customManagers?: Array<{depNameTemplate?: string; matchStrings?: string[]; datasourceTemplate?: string}>;
  };

  // when
  const workflowVersion = ciWorkflow.jobs?.["posthog-infra"]?.steps?.find(
    ({uses}) => typeof uses === "string" && uses.startsWith("opentofu/setup-opentofu@"),
  )?.with?.tofu_version;
  const miseVersion = /opentofu\s*=\s*"([^"]+)"/.exec(miseConfig)?.[1];
  const manager = renovateConfig.customManagers?.find(({depNameTemplate}) => depNameTemplate === "opentofu/opentofu");

  const registryRule = (renovateConfig as {packageRules?: Array<{matchDatasources?: string[]; registryUrls?: string[]}>}).packageRules?.find(
    ({matchDatasources}) => matchDatasources?.includes("terraform-provider"),
  );

  // then
  assert.deepEqual(registryRule?.registryUrls, ["https://registry.opentofu.org"]);
  assert.equal(workflowVersion, miseVersion);
  assert.equal(manager?.datasourceTemplate, "github-releases");
  assert.ok(manager?.matchStrings?.some((pattern) => new RegExp(pattern).test(`tofu_version: ${String(workflowVersion)}`)));
});

test("one Renovate group moves both OpenTofu pins, since each alone would fail the agreement test", () => {
  // given
  const miseConfig = readFileSync("infra/posthog/mise.toml", "utf8");
  const renovateConfig = JSON.parse(readFileSync("renovate.json", "utf8")) as {
    customManagers?: Array<{depNameTemplate?: string; datasourceTemplate?: string}>;
    packageRules?: Array<Record<string, unknown>>;
  };
  // Renovate's mise manager reports the `opentofu` tool as package opentofu/opentofu from GitHub
  // releases; the custom manager's depName doubles as its package name.
  const pins = [
    {source: "infra/posthog/mise.toml", present: /^opentofu\s*=/m.test(miseConfig), packageName: "opentofu/opentofu", datasource: "github-releases"},
    ...(renovateConfig.customManagers ?? [])
      .filter(({depNameTemplate}) => depNameTemplate === "opentofu/opentofu")
      .map(({depNameTemplate, datasourceTemplate}) => ({source: "ci.yml custom manager", present: true, packageName: String(depNameTemplate), datasource: String(datasourceTemplate)})),
  ];

  // when
  const groupOf = (pin: (typeof pins)[number]) => {
    let group: unknown;
    for (const rule of renovateConfig.packageRules ?? []) {
      const names = rule["matchPackageNames"] as string[] | undefined;
      const datasources = rule["matchDatasources"] as string[] | undefined;
      const otherMatchers = Object.keys(rule).filter((key) => key.startsWith("match") && key !== "matchPackageNames" && key !== "matchDatasources");
      if (names?.includes(pin.packageName) && (datasources === undefined || datasources.includes(pin.datasource)) && otherMatchers.length === 0 && "groupName" in rule) {
        group = rule["groupName"];
      }
    }
    return group;
  };
  const groups = pins.map(groupOf);

  // then
  assert.equal(pins.length, 2);
  assert.ok(pins.every(({present}) => present));
  assert.equal(typeof groups[0], "string");
  assert.equal(groups[1], groups[0]);
});
