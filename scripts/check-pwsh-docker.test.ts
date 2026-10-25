import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test, {type TestContext} from "node:test";

function runCheck(
  context: TestContext,
  {skipped = 0, writeReport = true, mavenExit = 0, wrapperExit = 0} = {},
) {
  const root = mkdtempSync(join(tmpdir(), "check-pwsh-docker-"));
  context.after(() => rmSync(root, {recursive: true, force: true}));
  const scripts = join(root, "scripts");
  mkdirSync(scripts);
  copyFileSync(new URL("check-pwsh-docker", import.meta.url), join(scripts, "check-pwsh-docker"));
  const wrapper = join(scripts, "pwsh-docker.sh");
  writeFileSync(wrapper, `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >> "$TEST_WRAPPER_LOG"
exit "$TEST_WRAPPER_EXIT"
`);
  chmodSync(wrapper, 0o755);
  const maven = join(root, "mvnw");
  writeFileSync(maven, `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$@" > "$TEST_MAVEN_ARGUMENTS"
printf '%s\\n' "$SYMPHONY_TRELLO_TEST_PWSH" > "$TEST_SELECTED_WRAPPER"
if [[ "$TEST_WRITE_REPORT" == true ]]; then
  mkdir -p target/surefire-reports
  printf '<testsuite tests="1" failures="0" errors="0" skipped="%s"/>\\n' "$TEST_SKIPPED" \\
    > target/surefire-reports/TEST-ch.fmartin.symphony.trello.setup.InstallerScriptTest.xml
fi
exit "$TEST_MAVEN_EXIT"
`);
  chmodSync(maven, 0o755);
  const wrapperLog = join(root, "wrapper.log");
  const mavenArguments = join(root, "maven-arguments");
  const selectedWrapper = join(root, "selected-wrapper");
  writeFileSync(wrapperLog, "");
  writeFileSync(mavenArguments, "");
  writeFileSync(selectedWrapper, "");

  const result = spawnSync("bash", [join(scripts, "check-pwsh-docker"), "-B", "-ntp"], {
    encoding: "utf8",
    timeout: 10_000,
    env: {
      ...process.env,
      TEST_MAVEN_ARGUMENTS: mavenArguments,
      TEST_MAVEN_EXIT: String(mavenExit),
      TEST_SELECTED_WRAPPER: selectedWrapper,
      TEST_SKIPPED: String(skipped),
      TEST_WRAPPER_EXIT: String(wrapperExit),
      TEST_WRAPPER_LOG: wrapperLog,
      TEST_WRITE_REPORT: String(writeReport),
    },
  });
  return {
    result,
    wrapper,
    wrapperCalls: readFileSync(wrapperLog, "utf8").trim(),
    mavenArguments: readFileSync(mavenArguments, "utf8").trim().split("\n"),
    selectedWrapper: readFileSync(selectedWrapper, "utf8").trim(),
  };
}

test("the pinned-image check excludes native Windows cases while exercising both dry runs", (context) => {
  const check = runCheck(context);

  assert.equal(check.result.status, 0, check.result.stderr);
  assert.equal(check.wrapperCalls,
    "-NoProfile -File ./install.ps1 --dry-run --no-onboard\n-NoProfile -File ./uninstall.ps1 --dry-run --yes");
  assert.deepEqual(check.mavenArguments, [
    "-B",
    "-ntp",
    "-Djunit.parallel.enabled=false",
    "-DexcludedGroups=native-windows",
    "-Dtest=InstallerScriptTest#*powershell*+*powerShell*",
    "test",
  ]);
  assert.equal(check.selectedWrapper, check.wrapper);
});

for (const skipped of [1, 7]) {
  test(`the pinned-image check rejects ${skipped} skipped wrapper cases`, (context) => {
    const {result} = runCheck(context, {skipped});

    assert.equal(result.status, 1);
    assert.match(result.stderr, /PowerShell installer tests were skipped/);
  });
}

test("the pinned-image check fails without a Maven report", (context) => {
  const {result} = runCheck(context, {writeReport: false});

  assert.equal(result.status, 1);
  assert.match(result.stderr, /PowerShell installer tests were skipped/);
});

test("the pinned-image check preserves Maven failure even with a zero-skip report", (context) => {
  const {result} = runCheck(context, {mavenExit: 17});

  assert.equal(result.status, 17);
});

test("the pinned-image check stops before Maven when the wrapper cannot start", (context) => {
  const check = runCheck(context, {wrapperExit: 17});

  assert.equal(check.result.status, 17);
  assert.equal(check.wrapperCalls, "-NoProfile -File ./install.ps1 --dry-run --no-onboard");
  assert.deepEqual(check.mavenArguments, [""]);
});
