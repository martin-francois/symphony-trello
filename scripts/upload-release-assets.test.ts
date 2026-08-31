import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const uploadScript = resolve("scripts/upload-release-assets");
const assets = ["install.sh", "checksums.txt", "symphony-trello-1.2.3.zip"];

function fixture(existingAssets: readonly string[], missingLocalAsset = "") {
  const directory = mkdtempSync(join(tmpdir(), "release-assets-"));
  const assetDirectory = join(directory, "assets");
  const scriptsDirectory = join(directory, "scripts");
  const binaryDirectory = join(directory, "bin");
  const log = join(directory, "gh.log");
  mkdirSync(assetDirectory);
  mkdirSync(scriptsDirectory);
  mkdirSync(binaryDirectory);

  for (const asset of assets) {
    if (asset !== missingLocalAsset) {
      writeFileSync(join(assetDirectory, asset), asset);
    }
  }
  const listAssets = join(scriptsDirectory, "list-release-assets");
  writeFileSync(listAssets, `#!/bin/bash\nprintf '%s\\n' ${assets.map((asset) => `'${asset}'`).join(" ")}\n`);
  chmodSync(listAssets, 0o755);

  const gh = join(binaryDirectory, "gh");
  writeFileSync(
    gh,
    `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_GH_LOG"
if [[ "$1 $2" == "release view" ]]; then
  printf '%s\\n' "$FAKE_EXISTING_ASSETS"
fi
`,
  );
  chmodSync(gh, 0o755);

  const result = spawnSync("bash", [uploadScript], {
    cwd: directory,
    encoding: "utf8",
    env: {
      ...process.env,
      ASSET_DIR: assetDirectory,
      FAKE_EXISTING_ASSETS: existingAssets.join("\n"),
      FAKE_GH_LOG: log,
      GITHUB_REPOSITORY: "martin-francois/symphony-trello",
      PATH: `${binaryDirectory}:${process.env.PATH}`,
      RELEASE_TAG: "v1.2.3",
      RELEASE_VERSION: "1.2.3",
    },
  });

  return { log: readFileSync(log, "utf8"), result };
}

test("uploads every missing asset to the configured repository", () => {
  const { log, result } = fixture(["install.sh"]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(
    log,
    /release view --repo martin-francois\/symphony-trello v1\.2\.3 --json assets --jq \.assets\[\]\.name/,
  );
  assert.match(log, /release upload --repo martin-francois\/symphony-trello v1\.2\.3/);
  assert.doesNotMatch(log, /assets\/install\.sh/);
  assert.match(log, /assets\/checksums\.txt/);
  assert.match(log, /assets\/symphony-trello-1\.2\.3\.zip/);
});

test("refuses same-tag reuse after every expected asset exists", () => {
  const { log, result } = fixture(assets);

  assert.equal(result.status, 2);
  assert.match(result.stderr, /release already contains every expected public asset/);
  assert.doesNotMatch(log, /release upload/);
});

test("rejects a missing local asset before uploading", () => {
  const { log, result } = fixture([], "checksums.txt");

  assert.equal(result.status, 2);
  assert.match(result.stderr, /release asset was not built: checksums\.txt/);
  assert.doesNotMatch(log, /release upload/);
});
