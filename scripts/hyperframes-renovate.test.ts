import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import test from "node:test";
import {parse} from "yaml";
import {
  MANIFEST_RELATIVE_PATH,
  POSTER_RELATIVE_PATH,
  VIDEO_RELATIVE_PATH,
} from "./readme-demo-manifest.ts";
import {
  KEEP_ATTACHMENT_VERDICT,
  REUPLOAD_ATTACHMENT_VERDICT,
} from "./readme-demo-visual-change.ts";

// Covers the shape of .github/workflows/hyperframes-renovate.yml and the Bash scripts its jobs
// run; see docs/adr/0149-check-hyperframes-updates-by-rendering-in-ci.md.

const WORKFLOW_FILE = "hyperframes-renovate.yml";
const SCRIPTS = new URL("./", import.meta.url);
const WORKFLOW = readFileSync(new URL(`../.github/workflows/${WORKFLOW_FILE}`, import.meta.url), "utf8");
const RENDER_SCRIPT = readFileSync(new URL("render-hyperframes-update", SCRIPTS), "utf8");
const REPORT_SCRIPT = readFileSync(new URL("report-hyperframes-render", SCRIPTS), "utf8");
const RENDERED_ARTIFACTS = [VIDEO_RELATIVE_PATH, POSTER_RELATIVE_PATH, MANIFEST_RELATIVE_PATH];
// Same repository, then the Renovate author, then the branch name a fork could reuse.
const SAME_REPOSITORY_RENOVATE_BRANCH =
  /github\.event\.pull_request\.head\.repo\.full_name == github\.repository &&\s*github\.event\.pull_request\.user\.login == 'renovate\[bot\]' &&\s*startsWith\(github\.head_ref, 'renovate\/hyperframes'\)/;

interface Job {
  readonly if?: string;
  readonly timeout?: number;
  readonly outputs?: Readonly<Record<string, string>>;
  readonly permissions?: Readonly<Record<string, string>>;
  readonly steps: readonly {
    readonly env?: Readonly<Record<string, string>>;
    readonly id?: string;
    readonly run?: string;
    readonly uses?: string;
    readonly with?: Readonly<Record<string, unknown>>;
  }[];
}

const DOCUMENT = parse(WORKFLOW) as {on: unknown; jobs: Record<string, Record<string, unknown>>};

function job(name: string): Job {
  const found = DOCUMENT.jobs[name];
  assert.ok(found, `${WORKFLOW_FILE} must define the ${name} job`);
  return {...found, timeout: found["timeout-minutes"]} as Job;
}

test("the workflow runs on pull_request only, with no privileged trigger and no secret", () => {
  assert.deepEqual(Object.keys(DOCUMENT.on as object), ["pull_request"]);
  assert.doesNotMatch(WORKFLOW, /secrets\./);
  assert.doesNotMatch(WORKFLOW, /create-github-app-token/);
  assert.deepEqual(Object.keys(DOCUMENT.jobs), ["readme-demo-render", "report"]);
});

test("the render check runs read-only on same-repository HyperFrames Renovate branches", () => {
  const render = job("readme-demo-render");
  assert.match(String(render.if), SAME_REPOSITORY_RENOVATE_BRANCH);
  assert.deepEqual(render.permissions, {contents: "read"});
  for (const step of render.steps.filter(({uses}) => uses?.startsWith("actions/checkout@"))) {
    assert.equal(step.with?.["persist-credentials"], false);
    assert.equal(step.with?.["ref"], undefined, "the render reads the pull request's merge commit");
    assert.equal(step.with?.["fetch-depth"], 2, "the comparison needs the merge commit's first parent");
  }
  assert.equal(typeof render.timeout, "number");
  assert.equal(render.steps.find(({id}) => id === "render")?.run, "scripts/render-hyperframes-update");
  assert.deepEqual(Object.keys(render.outputs ?? {}), ["detail", "verdict", "version"]);
});

test("the report job labels and comments from the render's outputs with a pull-request write only", () => {
  const report = job("report");
  assert.match(String(report.if), /always\(\)/);
  assert.match(String(report.if), SAME_REPOSITORY_RENOVATE_BRANCH);
  assert.deepEqual(report.permissions, {"pull-requests": "write"});
  assert.equal(typeof report.timeout, "number");
  const [checkout] = report.steps;
  assert.equal(checkout?.with?.["ref"], "${{ github.event.pull_request.base.sha }}", "no pull request code runs with the write grant");
  assert.equal(checkout?.with?.["sparse-checkout"], "scripts/report-hyperframes-render");
  assert.equal(checkout?.with?.["persist-credentials"], false);
  const report_step = report.steps.find(({run}) => run === "scripts/report-hyperframes-render");
  assert.deepEqual(report_step?.env, {
    DETAIL: "${{ needs.readme-demo-render.outputs.detail }}",
    GH_TOKEN: "${{ github.token }}",
    HYPERFRAMES_VERSION: "${{ needs.readme-demo-render.outputs.version }}",
    OUTCOME: "${{ needs.readme-demo-render.outputs.verdict || 'failed' }}",
    RUN_URL: "${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}",
    SOURCE_PULL_REQUEST: "${{ github.event.pull_request.number }}",
  });
});

test("the render script reads verdicts through the TypeScript module and compares with the attachment", () => {
  // scripts/render-hyperframes-update.test.ts runs the script; this pins what it must not repeat.
  for (const verdict of [KEEP_ATTACHMENT_VERDICT, REUPLOAD_ATTACHMENT_VERDICT, "README video: "]) {
    assert.ok(!RENDER_SCRIPT.includes(verdict), `the script must read "${verdict}" from scripts/readme-demo-values.ts`);
  }
  assert.ok(RENDER_SCRIPT.includes('node scripts/readme-demo-values.ts verdict "$render_log"'));
  assert.ok(RENDER_SCRIPT.includes('node scripts/readme-demo-values.ts attachment-limit-bytes'));
  assert.ok(RENDER_SCRIPT.includes('node scripts/render-readme-demo.ts --compare-with HEAD'));
  assert.ok(RENDER_SCRIPT.includes('node scripts/readme-demo-compare-videos.ts "$attachment" "$compared"'));
  assert.match(RENDER_SCRIPT, /--proto =https --proto-redir =https/);
  // A render that dies before the verdict leaves the failed verdict for the report job.
  assert.ok(RENDER_SCRIPT.indexOf('echo "verdict=failed"') < RENDER_SCRIPT.indexOf("node scripts/render-readme-demo.ts"));
  assert.match(RENDER_SCRIPT, /echo "verdict=keep"/);
  assert.match(RENDER_SCRIPT, /echo "verdict=re-upload"[\s\S]*exit 1/);
  assert.doesNotMatch(RENDER_SCRIPT, /git (commit|push)/, "a render never commits or pushes");
});

test("the owner's steps live in one guide section that the check message and the comment point to", () => {
  const guide = readFileSync(new URL("../docs/demo/README.md", import.meta.url), "utf8");
  assert.match(guide, /^## Updating HyperFrames$/m);
  assert.match(guide, /^What needs a person:$/m);
  for (const path of RENDERED_ARTIFACTS) {
    assert.ok(guide.includes(path), `the guide's steps must name ${path}`);
  }
  assert.ok(guide.includes("node scripts/render-readme-demo.ts --compare-with origin/main"));
  assert.ok(RENDER_SCRIPT.includes('"What needs a person" under "Updating HyperFrames" in docs/demo/README.md'));
  assert.match(REPORT_SCRIPT, /^GUIDE="https:\/\/github\.com\/\$GITHUB_REPOSITORY\/blob\/main\/docs\/demo\/README\.md#updating-hyperframes"$/m);
  assert.match(REPORT_SCRIPT, /^LABEL="needs-maintainer-review"$/m);
  assert.match(REPORT_SCRIPT, /^MARKER="<!-- hyperframes-renovate-automation -->"$/m);
  for (const outcome of ["keep", "re-upload", "incomplete", "failed"]) {
    assert.ok(REPORT_SCRIPT.includes(`\n  ${outcome})\n`), `the report script must handle ${outcome}`);
  }
});
