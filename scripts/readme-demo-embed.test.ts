import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdtempSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";

// The HyperFrames Renovate check reads the README demo embed on main with scripts/readme-demo-embed
// and fails every update when it is gone; this test fails the change that removes it instead.
// scripts/render-hyperframes-update.test.ts covers how the check uses each outcome.

function readEmbed(readmePath: string): {status: number | null; stdout: string} {
  const result = spawnSync("bash", ["scripts/readme-demo-embed", readmePath], {encoding: "utf8"});
  return {status: result.status, stdout: result.stdout.trim()};
}

function fixture(content: string): string {
  const path = join(mkdtempSync(join(tmpdir(), "readme-demo-embed-")), "README.md");
  writeFileSync(path, content);
  return path;
}

for (const [name, markerEnd] of [["CRLF", "\r\n"], ["mixed line endings", "\n"]]) {
  test(`the embed parser skips blank lines with ${name}`, () => {
    const result = readEmbed(fixture(`<!-- readme-demo-video -->${markerEnd}\r\n \t\r\nhttps://github.com/user-attachments/assets/1234abcd\r\n`));

    assert.deepEqual(result, {status: 0, stdout: "https://github.com/user-attachments/assets/1234abcd"});
  });
}

test("README.md keeps the demo marker with a user-attachments URL after it", () => {
  const {status, stdout} = readEmbed("README.md");

  assert.equal(status, 0, "README.md must keep <!-- readme-demo-video --> followed by the uploaded demo URL");
  assert.match(stdout, /^https:\/\/github\.com\/user-attachments\/assets\/[0-9a-f-]+$/);
});

test("a missing marker and a line that is not an attachment URL are told apart", () => {
  const marker = spawnSync("bash", ["scripts/readme-demo-embed", "--marker"], {encoding: "utf8"}).stdout.trim();
  const noMarker = readEmbed(fixture("# Demo\n\nhttps://github.com/user-attachments/assets/1234abcd\n"));
  const nothingAfter = readEmbed(fixture(`# Demo\n\n${marker}\n\n`));
  const otherLine = readEmbed(fixture(`# Demo\n\n${marker}\nhttps://example.com/demo.mp4\n`));
  const blankLines = readEmbed(fixture(`# Demo\n\n${marker}\n\n  \nhttps://github.com/user-attachments/assets/1234abcd \n`));

  assert.equal(marker, "<!-- readme-demo-video -->");
  assert.deepEqual(noMarker, {status: 1, stdout: ""});
  assert.deepEqual(nothingAfter, {status: 1, stdout: ""});
  assert.deepEqual(otherLine, {status: 2, stdout: "https://example.com/demo.mp4"});
  assert.deepEqual(blankLines, {status: 0, stdout: "https://github.com/user-attachments/assets/1234abcd"});
});
