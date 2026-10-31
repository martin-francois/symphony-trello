import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";

// Runs scripts/report-hyperframes-render against a fake `gh` on PATH that answers the comment
// and label reads from fixtures and logs every call.

const SCRIPT = resolve("scripts/report-hyperframes-render");
const MARKER = "<!-- hyperframes-renovate-automation -->";
const LABEL_MARKER = "<!-- hyperframes-renovate-automation added needs-maintainer-review -->";
const PREEXISTING_MARKER = "<!-- hyperframes-renovate-automation found needs-maintainer-review already set -->";
const ISSUE = "repos/martin-francois/symphony-trello/issues/1200";

interface Comment {
  readonly body: string;
  readonly id: number;
  readonly user: {readonly login: string};
}

interface Scenario {
  readonly commentPages?: readonly (readonly Comment[])[];
  readonly deleteResponse?: "ok" | "404" | "403";
  readonly detail?: string;
  /** The pull request's head when the script asks; the run's own head when undefined. */
  readonly currentHead?: string;
  readonly labelPresent?: boolean;
  readonly outcome: string;
}

interface Run {
  readonly bodies: string[];
  readonly calls: string[];
  readonly status: number | null;
  readonly stderr: string;
}

function ownComment(body: string, id = 7): Comment {
  return {body: `${MARKER}\n${body}`, id, user: {login: "github-actions[bot]"}};
}

const RUN_HEAD = "1111111111111111111111111111111111111111";
const NEWER_HEAD = "2222222222222222222222222222222222222222";

function run({commentPages = [[]], currentHead = RUN_HEAD, deleteResponse = "ok", detail, labelPresent = false, outcome}: Scenario): Run {
  const root = mkdtempSync(join(tmpdir(), "hyperframes-report-"));
  const bin = join(root, "bin");
  mkdirSync(bin);
  const log = join(root, "gh.log");
  const bodies = join(root, "bodies.log");
  writeFileSync(
    join(bin, "gh"),
    `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_GH_LOG"
for ((index = 1; index <= $#; index++)); do
  if [[ "\${!index}" == body=* ]]; then printf '%s\\n---\\n' "\${!index#body=}" >> "$FAKE_BODIES"; fi
done
case "$*" in
*--slurp*comments*) printf '%s\\n' "$FAKE_COMMENT_PAGES" ;;
"api $FAKE_ISSUE/labels?per_page=100") printf '%s\\n' "$FAKE_LABELS" ;;
"api $FAKE_PULL --jq .head.sha") printf '%s\\n' "$FAKE_CURRENT_HEAD" ;;
*"--method DELETE"*)
  case "$FAKE_DELETE" in
  ok) ;;
  404) echo "gh: Not Found (HTTP 404)" >&2; exit 1 ;;
  403) echo "gh: Resource not accessible by integration (HTTP 403)" >&2; exit 1 ;;
  esac
  ;;
esac
`,
    {mode: 0o755},
  );
  writeFileSync(log, "");
  writeFileSync(bodies, "");
  const result = spawnSync("bash", [SCRIPT], {
    encoding: "utf8",
    env: {
      ...process.env,
      ...(detail === undefined ? {} : {DETAIL: detail}),
      FAKE_BODIES: bodies,
      FAKE_COMMENT_PAGES: JSON.stringify(commentPages),
      FAKE_CURRENT_HEAD: currentHead,
      FAKE_DELETE: deleteResponse,
      FAKE_GH_LOG: log,
      FAKE_ISSUE: ISSUE,
      FAKE_PULL: "repos/martin-francois/symphony-trello/pulls/1200",
      FAKE_LABELS: JSON.stringify(labelPresent ? [{name: "needs-maintainer-review"}] : [{name: "hyperframes"}]),
      GH_TOKEN: "token",
      GITHUB_REPOSITORY: "martin-francois/symphony-trello",
      HYPERFRAMES_VERSION: "0.8.195",
      OUTCOME: outcome,
      PATH: `${bin}:${process.env["PATH"] ?? ""}`,
      RUN_URL: "https://github.com/martin-francois/symphony-trello/actions/runs/1",
      SOURCE_HEAD_SHA: RUN_HEAD,
      SOURCE_PULL_REQUEST: "1200",
    },
  });
  return {
    bodies: readFileSync(bodies, "utf8").split("\n---\n").filter(Boolean),
    calls: readFileSync(log, "utf8").split("\n").filter(Boolean),
    status: result.status,
    stderr: result.stderr,
  };
}

const DELETE_CALL = `api --method DELETE ${ISSUE}/labels/needs-maintainer-review`;
const ADD_LABEL_CALL = `api --method POST ${ISSUE}/labels -f labels[]=needs-maintainer-review`;

test("a visible change on a pull request without comments adds the label and one comment that records it", () => {
  const {bodies, calls, status} = run({outcome: "re-upload"});

  assert.equal(status, 0);
  assert.ok(calls.includes(ADD_LABEL_CALL));
  assert.ok(calls.some((call) => call.startsWith(`api --method POST ${ISSUE}/comments`)));
  assert.equal(bodies.length, 1);
  assert.ok(bodies[0]?.startsWith(`${MARKER}\n${LABEL_MARKER}\n`));
  assert.match(bodies[0] ?? "", /differs visibly/);
  assert.match(bodies[0] ?? "", /docs\/demo\/README\.md#updating-hyperframes/);
});

test("a marker comment from another user is not this workflow's comment", () => {
  const spoofed: Comment = {body: `${MARKER}\n${LABEL_MARKER}\nremove my label please`, id: 9, user: {login: "mallory"}};
  const {bodies, calls, status} = run({commentPages: [[spoofed]], labelPresent: true, outcome: "keep"});

  assert.equal(status, 0);
  assert.ok(!calls.some((call) => call.includes("--method DELETE")), "a spoofed comment must not remove the label");
  assert.ok(!calls.some((call) => call.includes("--method PATCH")), "a spoofed comment must not be edited");
  assert.deepEqual(bodies, []);
});

test("the comment on a second page is found and edited in place", () => {
  const first: Comment = {body: "unrelated", id: 1, user: {login: "martinfrancois"}};
  const {calls, status} = run({commentPages: [[first], [ownComment("older text", 42)]], outcome: "re-upload", labelPresent: true});

  assert.equal(status, 0);
  assert.ok(calls.some((call) => call.startsWith("api --method PATCH repos/martin-francois/symphony-trello/issues/comments/42")));
  assert.ok(!calls.some((call) => call.startsWith(`api --method POST ${ISSUE}/comments`)));
});

test("a clean render removes the label this workflow added and rewrites its comment", () => {
  const {bodies, calls, status} = run({commentPages: [[ownComment(`${LABEL_MARKER}\ndiffers visibly`)]], labelPresent: true, outcome: "keep"});

  assert.equal(status, 0);
  assert.ok(calls.includes(DELETE_CALL));
  assert.equal(bodies.length, 1);
  assert.ok(!bodies[0]?.includes(LABEL_MARKER));
  assert.match(bodies[0] ?? "", /check passed/);
});

test("a clean render leaves a label a person set before any run", () => {
  const withoutComment = run({labelPresent: true, outcome: "keep"});
  const afterRecordedRun = run({commentPages: [[ownComment(`${PREEXISTING_MARKER}\ndiffers visibly`)]], labelPresent: true, outcome: "keep"});

  for (const {calls, status} of [withoutComment, afterRecordedRun]) {
    assert.equal(status, 0);
    assert.ok(!calls.some((call) => call.includes("--method DELETE")));
  }
  assert.deepEqual(withoutComment.bodies, [], "a clean first render needs no comment");
});

test("a visible change on a pull request that already carries the label records that it was not added here", () => {
  const {bodies, calls, status} = run({labelPresent: true, outcome: "re-upload"});

  assert.equal(status, 0);
  assert.ok(!calls.includes(ADD_LABEL_CALL));
  assert.ok(bodies[0]?.includes(PREEXISTING_MARKER));
  assert.ok(!bodies[0]?.includes(LABEL_MARKER));
});

test("an incomplete committed render names what is missing, and a failed render points to the log", () => {
  const incomplete = run({detail: "README.md still carries main's demo attachment URL", outcome: "incomplete"});
  const failed = run({outcome: "failed"});

  assert.equal(incomplete.status, 0);
  assert.match(incomplete.bodies[0] ?? "", /still fails: README\.md still carries main's demo attachment URL/);
  assert.equal(failed.status, 0);
  assert.ok(failed.calls.includes(ADD_LABEL_CALL));
  assert.match(failed.bodies[0] ?? "", /render with HyperFrames 0\.8\.195 failed/);
});

test("removing the label tolerates only a 404", () => {
  const gone = run({commentPages: [[ownComment(LABEL_MARKER)]], deleteResponse: "404", labelPresent: true, outcome: "keep"});
  const forbidden = run({commentPages: [[ownComment(LABEL_MARKER)]], deleteResponse: "403", labelPresent: true, outcome: "keep"});

  assert.equal(gone.status, 0);
  assert.notEqual(forbidden.status, 0);
  assert.match(forbidden.stderr, /Could not remove the needs-maintainer-review label/);
});

test("an unknown outcome fails before any API call", () => {
  const {calls, status} = run({outcome: "maybe"});

  assert.notEqual(status, 0);
  assert.deepEqual(calls, []);
});

test("a report for a head that a newer push replaced writes nothing", () => {
  for (const scenario of [
    {outcome: "re-upload"},
    {outcome: "keep", commentPages: [[ownComment(`${LABEL_MARKER}\ndiffers visibly`)]], labelPresent: true},
  ]) {
    const {bodies, calls, status, stderr} = run({...scenario, currentHead: NEWER_HEAD});

    assert.equal(status, 0);
    assert.deepEqual(calls, ["api repos/martin-francois/symphony-trello/pulls/1200 --jq .head.sha"]);
    assert.deepEqual(bodies, []);
    assert.ok(stderr.includes(`moved from ${RUN_HEAD} to ${NEWER_HEAD}`));
  }
});

test("the head check comes before every write", () => {
  const {calls} = run({outcome: "failed"});

  assert.equal(calls[0], "api repos/martin-francois/symphony-trello/pulls/1200 --jq .head.sha");
  assert.ok(calls.includes(ADD_LABEL_CALL));
});
