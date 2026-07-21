import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {readFileSync} from "node:fs";
import test from "node:test";

const BRANDED_CAPTURES = {
  "github-pr-merged.jpg": "e271dd461a7d50c6a33e374db9328ec0348fe49198960097ec745c6a6d70ad08",
  "github-review-diff.jpg": "f303dbcffd8a216ec0f044ac5626fa5ed17bc60a2fa524c11b4764f4548859c7",
} as const;

for (const [fileName, expectedSha256] of Object.entries(BRANDED_CAPTURES)) {
  test(`${fileName} preserves the project owner's branding`, () => {
    const capture = readFileSync(
      new URL(`../docs/demo/assets/captures/${fileName}`, import.meta.url),
    );
    const actualSha256 = createHash("sha256").update(capture).digest("hex");

    assert.equal(
      actualSha256,
      expectedSha256,
      `${fileName} must retain François Martin's martinfrancois branding; `
        + "review intentional capture updates and update this checksum together",
    );
  });
}
