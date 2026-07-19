import assert from "node:assert/strict";
import test from "node:test";

import {
  generatedBranch,
  generatedPullRequestBody,
  validateVersionOnlyPomChange,
  validateVersionUpdate,
} from "./openrewrite-renovate.ts";

const BASE_POM = `<project>
  <properties>
    <error-prone-support.version>0.29.0</error-prone-support.version>
    <rewrite-maven-plugin.version>6.44.0</rewrite-maven-plugin.version>
    <rewrite-maven.version>8.87.0</rewrite-maven.version>
    <rewrite-error-prone-support.version>0.30.0</rewrite-error-prone-support.version>
    <rewrite-migrate-java.version>3.40.0</rewrite-migrate-java.version>
    <rewrite-static-analysis.version>2.39.0</rewrite-static-analysis.version>
    <rewrite-testing-frameworks.version>3.42.0</rewrite-testing-frameworks.version>
  </properties>
</project>
`;

test("accepts changes limited to exact OpenRewrite version properties", () => {
  const updated = BASE_POM
    .replace(">0.29.0<", ">0.30.0<")
    .replace(">6.44.0<", ">6.45.0<")
    .replace(">3.42.0<", ">3.43.1<");

  const changes = validateVersionOnlyPomChange(BASE_POM, updated);

  assert.deepEqual(changes, [
    {name: "error-prone-support.version", previous: "0.29.0", next: "0.30.0"},
    {name: "rewrite-maven-plugin.version", previous: "6.44.0", next: "6.45.0"},
    {name: "rewrite-testing-frameworks.version", previous: "3.42.0", next: "3.43.1"},
  ]);
});

test("rejects a POM update that also changes unrelated content", () => {
  const updated = BASE_POM
    .replace(">6.44.0<", ">6.45.0<")
    .replace("</properties>", "  <unrelated.version>1.0.0</unrelated.version>\n  </properties>");

  assert.throws(
    () => validateVersionOnlyPomChange(BASE_POM, updated),
    /changes pom\.xml outside OpenRewrite version properties/,
  );
});

test("rejects missing, duplicate, invalid, and unchanged version properties", () => {
  assert.throws(
    () => validateVersionOnlyPomChange(BASE_POM, BASE_POM),
    /does not update an OpenRewrite version property/,
  );
  assert.throws(
    () => validateVersionOnlyPomChange(
      BASE_POM,
      BASE_POM.replace(
        "</properties>",
        "    <rewrite-maven.version>8.88.0</rewrite-maven.version>\n  </properties>",
      ),
    ),
    /exactly one rewrite-maven\.version/,
  );
  assert.throws(
    () => validateVersionOnlyPomChange(
      BASE_POM,
      BASE_POM.replace(">8.87.0<", ">${rewrite.version}<"),
    ),
    /invalid rewrite-maven\.version value/,
  );
});

test("uses a stable generated branch per source pull request", () => {
  assert.equal(generatedBranch(592), "automation/openrewrite/renovate-592");
  assert.throws(() => generatedBranch(0), /invalid source pull request number/);
});

test("renders a complete generated pull request body", () => {
  const body = generatedPullRequestBody({
    sourceNumber: 612,
    sourceSha: "0123456789abcdef0123456789abcdef01234567",
    sourceUrl: "https://github.com/example/project/pull/612",
    versionChanges: [
      {name: "rewrite-static-analysis.version", previous: "2.39.0", next: "2.40.0"},
    ],
    generatedFiles: ["pom.xml", "src/main/java/example/Example.java"],
  });

  assert.match(body, /\[Renovate PR #612\]\(https:\/\/github\.com\/example\/project\/pull\/612\)/);
  assert.match(body, /\| `rewrite-static-analysis\.version` \| `2\.39\.0` \| `2\.40\.0` \|/);
  assert.match(body, /- `src\/main\/java\/example\/Example\.java`/);
  assert.match(body, /- \[x\] Compatible:/);
  assert.match(body, /- \[x\] Combine this pull request/);
  assert.match(body, /- \[ \] AI-assisted PR/);
});

test("rejects incomplete generated pull request metadata", () => {
  assert.throws(
    () => generatedPullRequestBody({
      sourceNumber: 612,
      sourceSha: "not-a-sha",
      sourceUrl: "https://github.com/example/project/pull/612",
      versionChanges: [],
      generatedFiles: [],
    }),
    /invalid source SHA/,
  );
});

test("rejects publisher metadata that is malformed or belongs to another source", () => {
  const metadata = {
    changes: [
      {
        name: "rewrite-maven-plugin.version",
        previous: "6.44.0",
        next: "6.45.0",
      },
    ],
    sourcePullRequest: 612,
    sourceSha: "0123456789abcdef0123456789abcdef01234567",
  };

  assert.deepEqual(validateVersionUpdate(metadata, 612), metadata);
  assert.throws(
    () => validateVersionUpdate(metadata, 613),
    /does not match the expected source pull request/,
  );
  assert.throws(
    () => validateVersionUpdate({...metadata, sourceSha: "--upload-pack=malicious"}, 612),
    /invalid source SHA/,
  );
  assert.throws(
    () => validateVersionUpdate({...metadata, unexpected: true}, 612),
    /unexpected fields/,
  );
});
