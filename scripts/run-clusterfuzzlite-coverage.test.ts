import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {chmodSync, copyFileSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

const script = resolve("scripts/run-clusterfuzzlite-coverage");

function fixture(
  version = "0.8.15",
  coveredLines = 42,
  githubActions = false,
  imageInventory = "",
) {
  const root = mkdtempSync(join(tmpdir(), "clusterfuzzlite-coverage-script-"));
  const workspace = join(root, "workspace");
  const fakeBin = join(root, "bin");
  const dockerLog = join(root, "docker.log");
  const eventPath = join(root, "event.json");
  const mavenLog = join(root, "maven.log");
  mkdirSync(join(workspace, ".clusterfuzzlite"), {recursive: true});
  mkdirSync(join(workspace, "build-out"));
  mkdirSync(join(workspace, "scripts"));
  mkdirSync(fakeBin);
  writeFileSync(dockerLog, "");
  writeFileSync(eventPath, "{}\n");
  writeFileSync(mavenLog, "");
  writeFileSync(
    join(workspace, ".clusterfuzzlite", "Dockerfile"),
    `FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:${"a".repeat(64)}\n`,
  );
  writeFileSync(join(workspace, ".clusterfuzzlite", "coverage-runner.Dockerfile"), "FROM scratch\n");
  copyFileSync(
    resolve("scripts/verify-clusterfuzzlite-coverage"),
    join(workspace, "scripts", "verify-clusterfuzzlite-coverage"),
  );
  chmodSync(join(workspace, "scripts", "verify-clusterfuzzlite-coverage"), 0o755);

  const maven = join(fakeBin, "mvnw");
  writeFileSync(
    maven,
    `#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_MAVEN_LOG"
if [[ "$*" == *"help:evaluate"* ]]; then
  printf '%s\n' "$FAKE_JACOCO_VERSION"
  exit 0
fi
for argument in "$@"; do
  case "$argument" in
  -DoutputDirectory=*) output_directory="\${argument#*=}" ;;
  -Dartifact=org.jacoco:org.jacoco.agent:*) artifact=agent ;;
  -Dartifact=org.jacoco:org.jacoco.cli:*) artifact=cli ;;
  esac
done
mkdir -p "$output_directory"
if [[ "$artifact" == agent ]]; then
  touch "$output_directory/org.jacoco.agent-$FAKE_JACOCO_VERSION-runtime.jar"
else
  touch "$output_directory/org.jacoco.cli-$FAKE_JACOCO_VERSION-nodeps.jar"
fi
`,
  );
  chmodSync(maven, 0o755);

  const docker = join(fakeBin, "podman");
  writeFileSync(
    docker,
    `#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
if [[ "$1" == image && "$2" == ls ]]; then
  printf '%s\n' "$FAKE_IMAGE_INVENTORY"
  exit 0
fi
if [[ "$1" == run ]]; then
  coverage="$FAKE_WORKSPACE/cifuzz-coverage"
  mkdir -p "$coverage/report/linux" "$coverage/fuzzer_stats"
  printf 'report\n' >"$coverage/report/linux/index.html"
  printf 'xml\n' >"$coverage/report/linux/jacoco.xml"
  write_report() {
    target_file="$1"
    source_file="$2"
    printf '{"type":"oss-fuzz.java.coverage.json.export","version":"1.0.0","data":[{"files":[{"filename":"src/main/java/ch/fmartin/symphony/trello/%s","summary":{"lines":{"covered":%s}}}],"totals":{"lines":{"covered":%s}}}]}' \
      "$source_file" "$FAKE_COVERED_LINES" "$FAKE_COVERED_LINES" >"$target_file"
  }
  write_report "$coverage/report/linux/summary.json" "workflow/WorkflowLoader.java"
  write_report "$coverage/fuzzer_stats/RepositorySourceFuzzer.json" "repository/RepositorySourceResolver.java"
  write_report "$coverage/fuzzer_stats/TrelloCardReferenceParserFuzzer.json" "tracker/TrelloCardReferenceParser.java"
  write_report "$coverage/fuzzer_stats/TrelloChecklistClassifierFuzzer.json" "tracker/TrelloChecklistClassifier.java"
  write_report "$coverage/fuzzer_stats/WorkflowLoaderFuzzer.json" "workflow/WorkflowLoader.java"
fi
`,
  );
  chmodSync(docker, 0o755);

  const result = spawnSync("bash", [script], {
    encoding: "utf8",
    env: {
      ...process.env,
      CFL_MAVEN_WRAPPER: maven,
      FAKE_DOCKER_LOG: dockerLog,
      FAKE_COVERED_LINES: String(coveredLines),
      FAKE_JACOCO_VERSION: version,
      FAKE_IMAGE_INVENTORY: imageInventory,
      FAKE_MAVEN_LOG: mavenLog,
      FAKE_WORKSPACE: workspace,
      GITHUB_ACTIONS: githubActions ? "true" : undefined,
      GITHUB_REPOSITORY: "owner/project",
      GITHUB_EVENT_NAME: "workflow_dispatch",
      GITHUB_EVENT_PATH: eventPath,
      GITHUB_SHA: "0123456789abcdef",
      GITHUB_TOKEN: "github-secret",
      GITHUB_WORKSPACE: workspace,
      GIT_STORE_REPO: "https://storage-secret@github.com/owner/storage.git",
      PATH: `${fakeBin}:${process.env.PATH}`,
      SYMPHONY_TRELLO_CONTAINER_RUNTIME: "podman",
    },
  });

  return {
    dockerLog: readFileSync(dockerLog, "utf8"),
    eventPath,
    mavenLog: readFileSync(mavenLog, "utf8"),
    result,
    root,
    workspace,
  };
}

test("builds the runner from the POM JaCoCo version and runs coverage without exposing tokens", () => {
  const {dockerLog, eventPath, mavenLog, result, workspace} = fixture();

  assert.equal(result.status, 0, result.stderr);
  assert.match(mavenLog, /help:evaluate -Dexpression=jacoco\.version/);
  assert.match(mavenLog, /org\.jacoco\.agent:0\.8\.15:jar:runtime/);
  assert.match(mavenLog, /org\.jacoco\.cli:0\.8\.15:jar:nodeps/);
  assert.match(dockerLog, /build --build-arg JACOCO_VERSION=0\.8\.15/);
  assert.doesNotMatch(dockerLog, /image ls|image rm|builder prune/);
  assert.match(dockerLog, /run --rm --security-opt label=disable --userns=keep-id/);
  assert.match(dockerLog, /--env GITHUB_TOKEN(?: |$)/);
  assert.match(dockerLog, /--env GIT_STORE_REPO(?: |$)/);
  assert.doesNotMatch(dockerLog, /LOW_DISK_SPACE/);
  assert.match(dockerLog, new RegExp(`--volume ${eventPath}:/github/workflow/event.json:ro`));
  assert.match(dockerLog, new RegExp(`--volume ${workspace}:/github/workspace`));
  assert.doesNotMatch(dockerLog, /github-secret|storage-secret/);
});

test("removes only this JVM build's images on a GitHub-hosted runner", () => {
  const pinnedDigest = `sha256:${"a".repeat(64)}`;
  const jvmImageId = `sha256:${"1".repeat(64)}`;
  const projectImageId = `sha256:${"2".repeat(64)}`;
  const staleJvmImageId = `sha256:${"3".repeat(64)}`;
  const unrelatedImageId = `sha256:${"4".repeat(64)}`;
  const imageInventory = [
    `gcr.io/oss-fuzz-base/base-builder-jvm|${pinnedDigest}|${jvmImageId}`,
    `localhost/external-cfl-project-abcd|sha256:${"b".repeat(64)}|${projectImageId}`,
    `gcr.io/oss-fuzz-base/base-builder-jvm|sha256:${"c".repeat(64)}|${staleJvmImageId}`,
    `gcr.io/oss-fuzz-base/base-builder-go|sha256:${"d".repeat(64)}|${unrelatedImageId}`,
  ].join("\n");

  const {dockerLog, result} = fixture("0.8.15", 42, true, imageInventory);

  assert.equal(result.status, 0, result.stderr);
  assert.match(dockerLog, /image ls --digests --no-trunc/);
  assert.match(dockerLog, new RegExp(`image rm --force ${jvmImageId} ${projectImageId}`));
  assert.doesNotMatch(dockerLog, new RegExp(`${staleJvmImageId}|${unrelatedImageId}`));
  assert.doesNotMatch(dockerLog, /builder prune/);
});

test("rejects an invalid POM JaCoCo version before invoking the container runtime", () => {
  const {dockerLog, result} = fixture("not-a-version");

  assert.equal(result.status, 1);
  assert.match(result.stderr, /invalid JaCoCo version/);
  assert.equal(dockerLog, "");
});

test("rejects a generated coverage report without covered source files", () => {
  const {result} = fixture("0.8.15", 0);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /empty or malformed Java coverage summary/);
});
