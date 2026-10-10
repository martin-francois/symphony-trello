import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, copyFileSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

function runScript(script: string, stage?: string) {
  const directory = mkdtempSync(join(tmpdir(), "maven-central-"));
  const scriptsDirectory = join(directory, "scripts");
  const log = join(directory, "mvnw.log");
  mkdirSync(scriptsDirectory);
  writeFileSync(log, "");
  copyFileSync(resolve("scripts", script), join(scriptsDirectory, script));
  const mvnw = join(directory, "mvnw");
  writeFileSync(mvnw, `#!/bin/bash\nprintf '%s\\n' "$*" >>"${log}"\n`);
  chmodSync(mvnw, 0o755);

  const environment: NodeJS.ProcessEnv = { ...process.env };
  delete environment.JRELEASER_MAVENCENTRAL_STAGE;
  if (stage !== undefined) {
    environment.JRELEASER_MAVENCENTRAL_STAGE = stage;
  }
  const result = spawnSync("bash", [join(scriptsDirectory, script)], {
    cwd: tmpdir(),
    encoding: "utf8",
    env: environment,
  });

  return { log: readFileSync(log, "utf8"), result };
}

test("stages the release artifacts without running the verify phase", () => {
  const { log, result } = runScript("stage-maven-central-artifacts");

  assert.equal(result.status, 0, result.stderr);
  assert.equal(log, "-B -ntp -Pmaven-central -DskipTests clean package deploy:deploy\n");
});

for (const stage of ["UPLOAD", "FULL"]) {
  test(`deploys with JReleaser for stage ${stage}`, () => {
    const { log, result } = runScript("deploy-maven-central", stage);

    assert.equal(result.status, 0, result.stderr);
    assert.equal(log, "-B -ntp -Pmaven-central jreleaser:deploy\n");
  });
}

for (const stage of [undefined, "", "PUBLISH", "upload"]) {
  test(`refuses to deploy for stage ${JSON.stringify(stage)}`, () => {
    const { log, result } = runScript("deploy-maven-central", stage);

    assert.equal(result.status, 2);
    assert.match(result.stderr, /MAVEN_CENTRAL_STAGE must be UPLOAD or FULL/);
    assert.equal(log, "");
  });
}

test("Renovate leaves JReleaser updates for a maintainer's dry run", () => {
  const renovate = JSON.parse(readFileSync(resolve("renovate.json"), "utf8")) as {
    packageRules: Array<{ matchPackageNames?: string[]; automerge?: boolean }>;
  };

  const jreleaserRules = renovate.packageRules.filter((rule) =>
    rule.matchPackageNames?.includes("org.jreleaser:jreleaser-maven-plugin"),
  );

  assert.equal(jreleaserRules.length, 1);
  assert.equal(jreleaserRules[0]?.automerge, false);
});
