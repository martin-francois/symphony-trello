import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const verifyScript = resolve("scripts/verify-release-signatures");
const bundle = "symphony-trello-1.2.3.intoto.jsonl";
const assets = ["install.sh", "checksums.txt", bundle, "symphony-trello-1.2.3.zip"];
const signerIdentity =
  "https://github.com/martin-francois/symphony-trello/.github/workflows/release-please.yml@refs/heads/main";

function fixture({ rejectedAsset = "", withBundle = true } = {}) {
  const directory = mkdtempSync(join(tmpdir(), "release-signatures-"));
  const assetDirectory = join(directory, "assets");
  const scriptsDirectory = join(directory, "scripts");
  const binaryDirectory = join(directory, "bin");
  const log = join(directory, "gh.log");
  mkdirSync(assetDirectory);
  mkdirSync(scriptsDirectory);
  mkdirSync(binaryDirectory);
  writeFileSync(log, "");

  for (const asset of assets) {
    if (asset !== bundle || withBundle) {
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
printf 'GH_HOST=%s %s\\n' "\${GH_HOST:-}" "$*" >>"$FAKE_GH_LOG"
if [[ -n "$FAKE_REJECTED_ASSET" && "$3" == */"$FAKE_REJECTED_ASSET" ]]; then
  echo 'Error: verifying with issuer "sigstore.dev"' >&2
  exit 1
fi
`,
  );
  chmodSync(gh, 0o755);

  const result = spawnSync("bash", [verifyScript], {
    cwd: directory,
    encoding: "utf8",
    env: {
      ...process.env,
      ASSET_DIR: assetDirectory,
      FAKE_GH_LOG: log,
      FAKE_REJECTED_ASSET: rejectedAsset,
      GITHUB_REPOSITORY: "martin-francois/symphony-trello",
      PATH: `${binaryDirectory}:${process.env.PATH}`,
      RELEASE_VERSION: "1.2.3",
    },
  });

  return { assetDirectory, log: readFileSync(log, "utf8"), result };
}

test("verifies every public asset except the bundle against the installer signer identity", () => {
  const { assetDirectory, log, result } = fixture();

  assert.equal(result.status, 0, result.stderr);
  const verifiedAssets = log
    .trim()
    .split("\n")
    .map((line) => line.split(" ")[3]);
  assert.deepEqual(verifiedAssets, [
    join(assetDirectory, "install.sh"),
    join(assetDirectory, "checksums.txt"),
    join(assetDirectory, "symphony-trello-1.2.3.zip"),
  ]);
  assert.match(
    log,
    new RegExp(
      `^GH_HOST=github\\.com attestation verify \\S+/install\\.sh --bundle \\S+/${bundle.replaceAll(".", "\\.")} ` +
        `--repo martin-francois/symphony-trello --cert-identity ${signerIdentity.replaceAll(".", "\\.")}$`,
      "m",
    ),
  );
});

test("fails before publication when an asset does not verify", () => {
  const { log, result } = fixture({ rejectedAsset: "checksums.txt" });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /release asset signature does not match the installer signer identity: checksums\.txt/);
  assert.doesNotMatch(log, /symphony-trello-1\.2\.3\.zip/);
});

test("fails when the signature bundle is missing", () => {
  const { log, result } = fixture({ withBundle: false });

  assert.equal(result.status, 2);
  assert.match(result.stderr, /release signature bundle was not created: symphony-trello-1\.2\.3\.intoto\.jsonl/);
  assert.equal(log, "");
});
