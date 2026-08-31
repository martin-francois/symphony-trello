import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";

const script = resolve("scripts/report-clusterfuzzlite-failure");

function fixture(existingIssue = "", resultMetadata = {}) {
  const directory = mkdtempSync(join(tmpdir(), "clusterfuzzlite-report-"));
  const sarif = join(directory, "results.sarif");
  const log = join(directory, "gh.log");
  const body = join(directory, "body.md");
  const gh = join(directory, "gh");

  writeFileSync(
    sarif,
    JSON.stringify({
      runs: [
        {
          results: [
            {
              ruleId: "jazzer.crash",
              partialFingerprints: {
                primaryLocationLineHash: "stable-crash-fingerprint",
              },
              message: { text: "parser crashed for @maintainer\nwith malformed input" },
              locations: [
                {
                  physicalLocation: {
                    artifactLocation: { uri: "src/main/java/Parser.java" },
                    region: { startLine: 42 },
                  },
                },
              ],
              ...resultMetadata,
            },
          ],
        },
      ],
    }),
  );
  writeFileSync(
    gh,
    `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_GH_LOG"
if [[ "$1 $2" == "label list" ]]; then
  printf 'bug\\nfuzzed\\n'
elif [[ "$1 $2" == "issue list" ]]; then
  printf '%s\\n' "$FAKE_EXISTING_ISSUE"
fi
for ((index = 1; index <= $#; index++)); do
  if [[ "\${!index}" == "--body-file" ]]; then
    next=$((index + 1))
    cp "\${!next}" "$FAKE_BODY_FILE"
  fi
done
`,
    { mode: 0o755 },
  );

  const result = spawnSync("bash", [script], {
    encoding: "utf8",
    env: {
      ...process.env,
      CFL_SARIF_FILE: sarif,
      FAKE_BODY_FILE: body,
      FAKE_EXISTING_ISSUE: existingIssue,
      FAKE_GH_LOG: log,
      GITHUB_REPOSITORY: "martin-francois/symphony-trello",
      GITHUB_RUN_ID: "1234",
      GITHUB_SERVER_URL: "https://github.com",
      GITHUB_SHA: "0123456789012345678901234567890123456789",
      PATH: `${directory}:${process.env.PATH}`,
    },
  });

  assert.equal(result.status, 0, result.stderr);
  return { body: readFileSync(body, "utf8"), log: readFileSync(log, "utf8") };
}

test("creates a labelled issue with a fingerprint based on the SARIF partial fingerprint", () => {
  const first = fixture();
  const second = fixture("42", {
    executionSuccessful: false,
    properties: { runSpecificDiagnostic: "different" },
  });
  const fingerprint = first.body.match(/Fingerprint: `([0-9a-f]{64})`/)?.[1];

  assert.ok(fingerprint);
  assert.match(first.log, new RegExp(`issue create --title Fuzz failure: jazzer\\.crash \\(${fingerprint.slice(0, 12)}\\)`));
  assert.match(first.log, /--label bug --label fuzzed/);
  assert.match(first.body, /src\/main\/java\/Parser\.java:42/);
  assert.match(first.body, /parser crashed for ＠maintainer with malformed input/);
  assert.match(first.body, /https:\/\/github\.com\/martin-francois\/symphony-trello\/actions\/runs\/1234/);
  assert.match(second.log, new RegExp(`${fingerprint} in:body`));
  assert.match(second.body, new RegExp("Fingerprint: `" + fingerprint + "`"));
});

test("comments on the matching open issue instead of creating a duplicate", () => {
  const result = fixture("42");

  assert.match(result.log, /issue comment 42 --body-file/);
  assert.doesNotMatch(result.log, /issue create/);
});
