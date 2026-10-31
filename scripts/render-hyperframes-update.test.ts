import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import test from "node:test";
import {GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES} from "./readme-demo-manifest.ts";
import {describeVisualComparison} from "./readme-demo-visual-change.ts";

// Runs scripts/render-hyperframes-update against a fixture repository whose HEAD is a merge
// commit. A fake `node` prints the verdicts the render and the attachment comparison would print
// and passes scripts/readme-demo-values.ts to the real Node.js; a fake `curl` serves README
// attachments from a folder.

const SCRIPT = resolve("scripts/render-hyperframes-update");
const VALUES_SCRIPT = resolve("scripts/readme-demo-values.ts");
const SCREENSHOT_URL = "https://github.com/user-attachments/assets/0d0d0d0d-aaaa-4bbb-8ccc-dddddddddddd";
const MAIN_URL = "https://github.com/user-attachments/assets/10bb87aa-fd3b-484f-b801-9c25c7202d28";
const NEW_URL = "https://github.com/user-attachments/assets/5c2f1e0a-1111-4222-8333-944455556666";
const LOWEST = {frame: 3040, tile: {x: 240, y: 480, width: 120, height: 120}, ssim: 0.97};
const KEEP = describeVisualComparison({kind: "no visible change", lowest: LOWEST});
const REUPLOAD = describeVisualComparison({kind: "visible change", lowest: {...LOWEST, ssim: 0.9}});
const MAIN_VIDEO = "video rendered on main";
const BRANCH_VIDEO = "video rendered on the branch";
const CI_RENDER = "video the CI render wrote";
// The README attachment is an older render than main's video, as it is after merges without upload.
const MAIN_ATTACHMENT = "older render README readers see";
const EMBED_MARKER = "<!-- readme-demo-video -->";

/** A README whose demo embed is not its first attachment URL. */
function readme(embed: string, {blankLines = 0, marker = EMBED_MARKER, trailing = ""} = {}): string {
  return `# Demo\n\n![Board](${SCREENSHOT_URL})\n\n${marker}\n${"\n".repeat(blankLines)}${embed}\n\nUse it.\n${trailing}`;
}

interface Fixture {
  readonly mainReadme?: string;
  /** The pull request's README.md; main's when undefined. */
  readonly readme?: string;
  /** The pull request commits its own demo.mp4. */
  readonly newVideo?: boolean;
}

interface Behavior {
  /** What the fake render prints for its own comparison with the video committed in HEAD. */
  readonly render: string | "crash" | "silent";
  /** What the fake attachment comparison prints. */
  readonly attachment?: string;
  /** Attachment ID to the bytes the fake curl serves; "too large" makes curl exceed the size cap. */
  readonly served?: Readonly<Record<string, string>>;
}

interface Run {
  readonly curlCalls: string[];
  readonly nodeCalls: string[];
  readonly outputs: Record<string, string>;
  readonly status: number | null;
  readonly stderr: string;
}

function git(cwd: string, ...args: string[]): void {
  execFileSync("git", ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.com", ...args], {cwd, stdio: "pipe"});
}

function pin(version: string): string {
  return `export const HYPERFRAMES_VERSION = "${version}";\n`;
}

function assetId(url: string): string {
  return url.slice(url.lastIndexOf("/") + 1);
}

function createMergeCommitFixture({mainReadme = readme(MAIN_URL), readme: prReadme, newVideo = false}: Fixture): string {
  const root = mkdtempSync(join(tmpdir(), "hyperframes-render-"));
  git(root, "init", "--quiet", "--initial-branch=main");
  mkdirSync(join(root, "docs", "assets"), {recursive: true});
  mkdirSync(join(root, "scripts"), {recursive: true});
  writeFileSync(join(root, "README.md"), mainReadme);
  writeFileSync(join(root, "docs", "assets", "demo.mp4"), MAIN_VIDEO);
  writeFileSync(join(root, "scripts", "readme-demo-hyperframes.ts"), pin("0.7.100"));
  git(root, "add", "-A");
  git(root, "commit", "--quiet", "-m", "main");
  git(root, "checkout", "--quiet", "-b", "renovate/hyperframes-0.x");
  writeFileSync(join(root, "scripts", "readme-demo-hyperframes.ts"), pin("0.7.101"));
  if (newVideo) {
    writeFileSync(join(root, "docs", "assets", "demo.mp4"), BRANCH_VIDEO);
  }
  if (prReadme !== undefined) {
    writeFileSync(join(root, "README.md"), prReadme);
  }
  git(root, "add", "-A");
  git(root, "commit", "--quiet", "-m", "chore(deps): update dependency hyperframes to v0.7.101");
  git(root, "checkout", "--quiet", "main");
  git(root, "merge", "--quiet", "--no-ff", "-m", "merge", "renovate/hyperframes-0.x");
  return root;
}

function readLines(path: string): string[] {
  try {
    return readFileSync(path, "utf8").split("\n").filter(Boolean);
  } catch {
    return [];
  }
}

const SERVED_BY_DEFAULT = {[assetId(MAIN_URL)]: MAIN_ATTACHMENT, [assetId(NEW_URL)]: BRANCH_VIDEO};

function run(root: string, {render, attachment = KEEP, served = SERVED_BY_DEFAULT}: Behavior): Run {
  const bin = join(root, "fake-bin");
  const servedDirectory = join(root, "served");
  mkdirSync(bin);
  mkdirSync(servedDirectory);
  for (const [id, content] of Object.entries(served)) {
    writeFileSync(join(servedDirectory, id), content);
  }
  const nodeLog = join(root, "node.log");
  const curlLog = join(root, "curl.log");
  writeFileSync(
    join(bin, "node"),
    `#!/bin/bash
if [[ "$1" == scripts/readme-demo-values.ts ]]; then shift; exec "$REAL_NODE" "$VALUES_SCRIPT" "$@"; fi
case "$1" in
scripts/render-readme-demo.ts)
  printf '%s\\n' "$*" >> "$FAKE_NODE_LOG"
  # The real render replaces the working-tree video with its output.
  printf '%s' "$FAKE_CI_RENDER" > docs/assets/demo.mp4
  verdict="$FAKE_RENDER_VERDICT" ;;
scripts/readme-demo-compare-videos.ts)
  printf '%s baseline=%s video=%s\\n' "$1" "$(cat "$2")" "$(cat "$3")" >> "$FAKE_NODE_LOG"
  verdict="$FAKE_ATTACHMENT_VERDICT" ;;
*) echo "unexpected node call: $*" >&2; exit 99 ;;
esac
case "$verdict" in
crash) echo "render failed" >&2; exit 2 ;;
silent) echo "Done." ;;
*) echo "$verdict" ;;
esac
`,
    {mode: 0o755},
  );
  writeFileSync(
    join(bin, "curl"),
    `#!/bin/bash
printf '%s\\n' "$*" >> "$FAKE_CURL_LOG"
url="\${!#}"
for ((index = 1; index <= $#; index++)); do
  if [[ "\${!index}" == "--output" ]]; then next=$((index + 1)); target="\${!next}"; fi
done
served="$FAKE_SERVED/\${url##*/}"
[[ -f "$served" ]] || exit 22
[[ "$(cat "$served")" == "too large" ]] && exit 63
cp "$served" "$target"
`,
    {mode: 0o755},
  );
  const outputs = join(root, "github-output");
  writeFileSync(outputs, "");
  const result = spawnSync("bash", [SCRIPT], {
    cwd: root,
    encoding: "utf8",
    env: {
      ...process.env,
      FAKE_ATTACHMENT_VERDICT: attachment,
      FAKE_CI_RENDER: CI_RENDER,
      FAKE_CURL_LOG: curlLog,
      FAKE_NODE_LOG: nodeLog,
      FAKE_RENDER_VERDICT: render,
      FAKE_SERVED: servedDirectory,
      GITHUB_OUTPUT: outputs,
      PATH: `${bin}:${process.env["PATH"] ?? ""}`,
      REAL_NODE: process.execPath,
      RUNNER_TEMP: root,
      VALUES_SCRIPT,
    },
  });
  const parsed: Record<string, string> = {};
  for (const line of readLines(outputs)) {
    const separator = line.indexOf("=");
    parsed[line.slice(0, separator)] = line.slice(separator + 1);
  }
  return {
    curlCalls: readLines(curlLog),
    nodeCalls: readLines(nodeLog),
    outputs: parsed,
    status: result.status,
    stderr: result.stderr,
  };
}

const RENDER_CALL = "scripts/render-readme-demo.ts --compare-with HEAD";

function compareCall(baseline: string, video: string): string {
  return `scripts/readme-demo-compare-videos.ts baseline=${baseline} video=${video}`;
}

test("a Renovate update passes when its render looks like the README attachment, the embed after other URLs", () => {
  const {curlCalls, nodeCalls, outputs, status} = run(createMergeCommitFixture({}), {render: KEEP, attachment: KEEP});

  assert.equal(status, 0);
  assert.equal(outputs["verdict"], "keep");
  assert.equal(outputs["version"], "0.7.101");
  assert.deepEqual(nodeCalls, [RENDER_CALL, compareCall(MAIN_ATTACHMENT, CI_RENDER)]);
  assert.equal(curlCalls.length, 1);
  assert.ok(curlCalls[0]?.endsWith(` ${MAIN_URL}`), "the embed after the marker, not the first URL in README.md");
  assert.match(curlCalls[0] ?? "", /--proto =https --proto-redir =https/);
  assert.match(curlCalls[0] ?? "", new RegExp(`--max-filesize ${GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES} `));
});

test("a Renovate update fails on a visible change against the README attachment and points to the guide", () => {
  const {nodeCalls, outputs, status, stderr} = run(createMergeCommitFixture({}), {render: KEEP, attachment: REUPLOAD});

  assert.equal(status, 1);
  assert.equal(outputs["verdict"], "re-upload");
  assert.match(stderr, new RegExp(`visible change against the README attachment\\s+at ${MAIN_URL}`));
  assert.ok(stderr.includes(REUPLOAD));
  assert.match(stderr, /"What needs a person" under "Updating HyperFrames" in docs\/demo\/README\.md/);
  assert.doesNotMatch(stderr, /Also missing/);
  assert.deepEqual(nodeCalls, [RENDER_CALL, compareCall(MAIN_ATTACHMENT, CI_RENDER)]);
});

test("blank lines between the marker and the embed are skipped on both sides", () => {
  const {curlCalls, outputs, status} = run(
    createMergeCommitFixture({mainReadme: readme(MAIN_URL, {blankLines: 2}), readme: readme(MAIN_URL, {blankLines: 1})}),
    {render: KEEP},
  );

  assert.equal(status, 0);
  assert.equal(outputs["verdict"], "keep");
  assert.ok(curlCalls[0]?.endsWith(` ${MAIN_URL}`));
});

test("main's README without the marker fails before the render with a clear message", () => {
  const {curlCalls, nodeCalls, outputs, status, stderr} = run(
    createMergeCommitFixture({mainReadme: readme(MAIN_URL, {marker: "<!-- demo -->"})}),
    {render: KEEP},
  );

  assert.equal(status, 1);
  assert.equal(outputs["verdict"], "failed");
  assert.match(stderr, /README\.md on main has no user-attachments URL/);
  assert.match(stderr, /Restore the marker and the attachment URL on main/);
  assert.deepEqual(nodeCalls, []);
  assert.deepEqual(curlCalls, []);
});

test("a pull request that drops the marker or swaps the embed fails even when the video looks the same", () => {
  const noMarker = run(
    createMergeCommitFixture({readme: readme(MAIN_URL, {marker: "<!-- demo -->"})}),
    {render: KEEP, attachment: KEEP},
  );
  const swapped = run(createMergeCommitFixture({readme: readme(NEW_URL)}), {render: KEEP, attachment: KEEP});
  const notAnAttachment = run(
    createMergeCommitFixture({readme: readme("https://example.com/demo.mp4")}),
    {render: KEEP, attachment: KEEP},
  );

  for (const outcome of [noMarker, swapped, notAnAttachment]) {
    assert.equal(outcome.status, 1);
    assert.equal(outcome.outputs["verdict"], "incomplete");
  }
  assert.match(noMarker.outputs["detail"] ?? "", /README\.md has no <!-- readme-demo-video --> marker/);
  // NEW_URL serves the branch video, but this pull request keeps main's video.
  assert.equal(
    swapped.outputs["detail"],
    `the attachment at ${NEW_URL} is not the committed docs/assets/demo.mp4 byte for byte`,
  );
  assert.match(notAnAttachment.outputs["detail"] ?? "", /is not a user-attachments URL: https:\/\/example\.com\/demo\.mp4/);
});

test("a README attachment that cannot be downloaded fails the check, since nothing can be compared", () => {
  const {nodeCalls, outputs, status, stderr} = run(createMergeCommitFixture({}), {render: KEEP, served: {}});

  assert.equal(status, 1);
  assert.equal(outputs["verdict"], "failed");
  assert.ok(stderr.includes(`README attachment at ${MAIN_URL}, which the render is compared with, could not be downloaded (curl exit 22)`));
  assert.deepEqual(nodeCalls, []);
});

test("a committed video that looks like the README attachment passes once CI reproduces it, with no upload", () => {
  const {nodeCalls, outputs, status} = run(createMergeCommitFixture({newVideo: true}), {render: KEEP, attachment: KEEP});

  assert.equal(status, 0);
  assert.equal(outputs["verdict"], "keep");
  assert.deepEqual(nodeCalls, [RENDER_CALL, compareCall(MAIN_ATTACHMENT, BRANCH_VIDEO)]);
});

test("a committed video passes once its upload is the embed and serves the committed bytes", () => {
  const {curlCalls, nodeCalls, outputs, status} = run(
    createMergeCommitFixture({newVideo: true, readme: readme(NEW_URL)}),
    {render: KEEP, attachment: KEEP},
  );

  assert.equal(status, 0);
  assert.equal(outputs["verdict"], "keep");
  assert.equal(curlCalls.length, 1, "main's attachment is not needed once the new one is verified");
  assert.deepEqual(nodeCalls, [RENDER_CALL, compareCall(BRANCH_VIDEO, BRANCH_VIDEO)]);
});

test("a committed video that differs from the README attachment is incomplete while the embed holds main's URL", () => {
  const {outputs, status, stderr} = run(createMergeCommitFixture({newVideo: true}), {render: KEEP, attachment: REUPLOAD});

  assert.equal(status, 1);
  assert.equal(outputs["verdict"], "incomplete");
  assert.match(outputs["detail"] ?? "", /demo embed still holds main's attachment URL, but the committed video differs visibly/);
  assert.doesNotMatch(outputs["detail"] ?? "", /does not reproduce/);
  assert.match(stderr, /The README demo update is incomplete/);
});

test("an upload is incomplete while main's URL survives, the bytes differ, or the attachment cannot be fetched", () => {
  const uploaded = {newVideo: true, readme: readme(NEW_URL)};
  const elsewhere = run(
    createMergeCommitFixture({newVideo: true, readme: readme(NEW_URL, {trailing: `\nOld: ${MAIN_URL}\n`})}),
    {render: KEEP},
  );
  const otherBytes = run(createMergeCommitFixture(uploaded), {
    render: KEEP,
    served: {[assetId(MAIN_URL)]: MAIN_ATTACHMENT, [assetId(NEW_URL)]: "transcoded by someone"},
  });
  const unreachable = run(createMergeCommitFixture(uploaded), {render: KEEP, served: {[assetId(MAIN_URL)]: MAIN_ATTACHMENT}});
  const tooLarge = run(createMergeCommitFixture(uploaded), {
    render: KEEP,
    served: {[assetId(MAIN_URL)]: MAIN_ATTACHMENT, [assetId(NEW_URL)]: "too large"},
  });

  for (const outcome of [elsewhere, otherBytes, unreachable, tooLarge]) {
    assert.equal(outcome.status, 1);
    assert.equal(outcome.outputs["verdict"], "incomplete");
  }
  assert.match(elsewhere.outputs["detail"] ?? "", /still mentions main's attachment URL .* outside the demo embed/);
  assert.match(otherBytes.outputs["detail"] ?? "", /is not the committed docs\/assets\/demo\.mp4 byte for byte/);
  assert.match(unreachable.outputs["detail"] ?? "", /could not be downloaded \(curl exit 22\)/);
  assert.ok((tooLarge.outputs["detail"] ?? "").includes(
    `is larger than GitHub's ${GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES}-byte video attachment limit`,
  ));
  // An unverified upload leaves main's attachment as the baseline.
  assert.equal(otherBytes.nodeCalls[1], compareCall(MAIN_ATTACHMENT, BRANCH_VIDEO));
});

test("a committed video that CI does not reproduce is incomplete, and both missing parts are named together", () => {
  const notReproduced = run(createMergeCommitFixture({newVideo: true, readme: readme(NEW_URL)}), {render: REUPLOAD});
  const both = run(createMergeCommitFixture({newVideo: true}), {render: REUPLOAD, attachment: REUPLOAD});

  assert.equal(notReproduced.status, 1);
  assert.match(notReproduced.outputs["detail"] ?? "", /does not reproduce the committed docs\/assets\/demo\.mp4/);
  assert.doesNotMatch(notReproduced.outputs["detail"] ?? "", /still holds/);
  assert.equal(both.outputs["verdict"], "incomplete");
  assert.match(both.outputs["detail"] ?? "", /does not reproduce.*; README\.md's demo embed still holds/);
});

test("a render or comparison that dies or prints no verdict leaves the failed verdict", () => {
  const crashed = run(createMergeCommitFixture({}), {render: "crash"});
  const silent = run(createMergeCommitFixture({}), {render: "silent"});
  const silentComparison = run(createMergeCommitFixture({}), {render: KEEP, attachment: "silent"});

  assert.notEqual(crashed.status, 0);
  assert.equal(crashed.outputs["verdict"], "failed");
  assert.equal(silent.status, 1);
  assert.equal(silent.outputs["verdict"], "failed");
  assert.match(silent.stderr, /The render printed no README video verdict/);
  assert.equal(silentComparison.status, 1);
  assert.equal(silentComparison.outputs["verdict"], "failed");
  assert.match(silentComparison.stderr, /comparison with the README attachment printed no README video verdict/);
});
