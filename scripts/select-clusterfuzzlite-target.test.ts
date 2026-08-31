import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, mkdtempSync, readFileSync, readdirSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

const script = resolve("scripts/select-clusterfuzzlite-target");
const fuzzTargets = [
  "RepositorySourceFuzzer",
  "TrelloCardReferenceParserFuzzer",
  "TrelloChecklistClassifierFuzzer",
  "WorkflowLoaderFuzzer",
] as const;

function buildFixture() {
  const buildOut = mkdtempSync(join(tmpdir(), "clusterfuzzlite-build-out-"));
  for (const target of fuzzTargets) {
    for (const suffix of ["", ".dict", ".options", "_seed_corpus.zip"]) {
      const path = join(buildOut, `${target}${suffix}`);
      writeFileSync(path, suffix === "" ? "#!/bin/bash\n" : "fixture\n");
      if (suffix === "") {
        chmodSync(path, 0o755);
      }
    }
  }
  writeFileSync(join(buildOut, "jazzer_driver"), "helper\n");
  return buildOut;
}

test("keeps the selected fuzzer and removes every other target artifact", () => {
  const buildOut = buildFixture();
  const selected = "RepositorySourceFuzzer";

  const result = spawnSync("bash", [script, selected], {
    encoding: "utf8",
    env: {...process.env, CFL_BUILD_OUT: buildOut},
  });

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(readdirSync(buildOut).sort(), [
    selected,
    `${selected}.dict`,
    `${selected}.options`,
    `${selected}_seed_corpus.zip`,
    "jazzer_driver",
  ]);
});

test("rejects an unknown target before changing the build", () => {
  const buildOut = buildFixture();
  const before = readdirSync(buildOut).sort();

  const result = spawnSync("bash", [script, "UnknownFuzzer"], {
    encoding: "utf8",
    env: {...process.env, CFL_BUILD_OUT: buildOut},
  });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /Unknown ClusterFuzzLite target/);
  assert.deepEqual(readdirSync(buildOut).sort(), before);
});

test("reclaims container-owned build output before selecting a target", () => {
  const buildOut = buildFixture();
  const selected = "WorkflowLoaderFuzzer";
  const uid = process.getuid?.();
  const gid = process.getgid?.();
  if (uid === undefined || gid === undefined) {
    throw new Error("This test requires POSIX user and group identifiers.");
  }
  const fakeBin = mkdtempSync(join(tmpdir(), "clusterfuzzlite-fake-bin-"));
  const sudoLog = join(fakeBin, "sudo.log");
  const sudo = join(fakeBin, "sudo");
  writeFileSync(
    sudo,
    `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_SUDO_LOG"
last_argument="\${!#}"
chmod u+w "$last_argument"
`,
  );
  chmodSync(sudo, 0o755);
  chmodSync(buildOut, 0o555);

  const result = spawnSync("bash", [script, selected], {
    encoding: "utf8",
    env: {
      ...process.env,
      CFL_BUILD_OUT: buildOut,
      FAKE_SUDO_LOG: sudoLog,
      PATH: `${fakeBin}:${process.env.PATH}`,
    },
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(readFileSync(sudoLog, "utf8"), new RegExp(`chown -R -- ${uid}:${gid} `));
  assert.deepEqual(readdirSync(buildOut).sort(), [
    selected,
    `${selected}.dict`,
    `${selected}.options`,
    `${selected}_seed_corpus.zip`,
    "jazzer_driver",
  ]);
});
