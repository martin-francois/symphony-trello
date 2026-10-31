import {existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {homedir, userInfo} from "node:os";
import {delimiter, join, parse, sep} from "node:path";
import {freePort} from "../lib/ports.ts";
import {outputOf, type ScenarioContext, type ScenarioRegistry} from "../lib/scenario.ts";
import {sandboxEnvironment, type CliPaths} from "../lib/symphony.ts";
import {LIST_NAMES} from "../lib/trello.ts";
import {addCard, cli, filesIn, serviceBoard, startWorker, stopWorker, waitForCardIn, type ServiceBoard} from "./support.ts";

const PATH_TOKEN = /<path:[0-9a-f]{12}>/g;
const DOCKER_NOTICE_SUFFIX = "to quiet msg.";
const DIAGNOSTICS_SECRET_SENTINEL = "synthetic-diagnostics-adjacent-secret";
const DIAGNOSTICS_KEY = ".symphony-trello-diagnostics-key";

function lookupStatus(output: string): string {
  return /lookup_status:\*{0,2} ?([a-z_]+)/.exec(output)?.[1] ?? "missing";
}

function selectorArgs(paths: CliPaths, workflow: string | null): string[] {
  return [...(workflow === null ? [] : ["--workflow", workflow]), "--config-dir", paths.configDir, "--state-home", paths.stateHome];
}

async function lookup(context: ScenarioContext, name: string, token: string, paths: CliPaths, workflow: string | null, env: Record<string, string> = {}) {
  return cli(context, name, ["diagnostics", "--show-private-context", "--lookup", token, ...selectorArgs(paths, workflow)], {paths, env});
}

/**
 * A `docker` on PATH that prints the Podman shim notice, whose path is outside every known private
 * root, does not exist, and has a space in a directory name. The text after it must stay visible.
 */
function dockerNoticeEnvironment(context: ScenarioContext, paths: CliPaths): {env: Record<string, string>; noticePath: string} {
  const bin = context.run.dir("fakes", "docker-notice", "bin");
  const noticePath = join(parse(context.run.root).root, "synthetic-container-dir", context.run.runId, "outside root", "synthetic-nodocker marker");
  const roots = [homedir(), userInfo().homedir, context.run.root, ...Object.values(paths)];
  context.check.that(roots.every((root) => noticePath !== root && !noticePath.startsWith(root.endsWith(sep) ? root : root + sep)), "the notice path is outside every private root");
  context.check.that(!existsSync(noticePath), "the notice path does not exist");
  const quote = (value: string) => `'${value.replaceAll("'", `'"'"'`)}'`;
  const lines = [`Emulate Docker CLI using podman. Create ${noticePath} ${DOCKER_NOTICE_SUFFIX} token=${DIAGNOSTICS_SECRET_SENTINEL}`, "podman version 0.0.0-live-bugbash"];
  writeFileSync(join(bin, "docker"), `#!/usr/bin/env bash\nprintf '%s\\n' ${lines.map(quote).join(" ")}\n`, {mode: 0o755});
  return {env: {PATH: `${bin}${delimiter}${sandboxEnvironment(context.run)["PATH"] ?? ""}`}, noticePath};
}

/** A board whose worker ran one card, so diagnostics include worker logs and process state. */
async function workedBoard(context: ScenarioContext): Promise<ServiceBoard> {
  return context.fixture("diagnostics-board", async () => {
    const service = await serviceBoard(context, "diagnostics-board");
    const card = await addCard(context, service, "diagnostics-board", "Answer with one sentence. No repository is needed.");
    await startWorker(context, service);
    try {
      await waitForCardIn(context, service, card, LIST_NAMES.humanReview);
    } finally {
      await stopWorker(context, service);
    }
    return service;
  });
}

async function emittedTokensResolve(context: ScenarioContext): Promise<string> {
  const service = await workedBoard(context);
  const {env, noticePath} = dockerNoticeEnvironment(context, service.paths);
  const output = context.run.path("private-evidence", context.scenario.id, "diagnostics.md");
  mkdirSync(context.run.path("private-evidence", context.scenario.id), {recursive: true});
  const report = await cli(context, "diagnostics", ["diagnostics", ...selectorArgs(service.paths, service.workflow), "--output", output], {paths: service.paths, env});
  context.check.exitCode(report, 0, "diagnostics exits 0");
  const text = existsSync(output) ? readFileSync(output, "utf8") : "";
  const tokens = [...new Set(text.match(PATH_TOKEN) ?? [])];
  context.check.that(tokens.length > 0, "the diagnostics report emits path tokens");
  const noticeToken = new RegExp(`Create (<path:[0-9a-f]{12}>) ${DOCKER_NOTICE_SUFFIX.replaceAll(".", "\\.")}`).exec(text)?.[1];
  context.check.that(noticeToken !== undefined, "the docker notice keeps the text after its outside-root path");
  for (const fragment of ["outside root", "synthetic-container-dir", "synthetic-nodocker", "marker"]) {
    context.check.excludes(text, fragment, "the report hides each distinctive outside-root path component");
  }
  context.check.excludes(text, DIAGNOSTICS_SECRET_SENTINEL, "public diagnostics redact the adjacent credential");
  const unresolved: string[] = [];
  for (const [index, token] of tokens.entries()) {
    const resolved = outputOf(await lookup(context, `lookup-${index}`, token, service.paths, service.workflow, env));
    context.check.excludes(resolved, DIAGNOSTICS_SECRET_SENTINEL, "private lookup excludes credential contents");
    const status = lookupStatus(resolved);
    if (status !== "found") {
      unresolved.push(`${token} -> ${status}`);
    } else if (token === noticeToken) {
      context.check.contains(resolved, `| tool | docker | ${token} | ${noticePath} |`, "the docker notice token resolves to the path alone");
    }
  }
  context.evidence("unresolved-tokens.txt", unresolved.join("\n"));
  context.check.that(unresolved.length === 0, `every emitted path token resolves (${unresolved.length} of ${tokens.length} did not)`);
  return `${tokens.length - unresolved.length} of ${tokens.length} emitted path tokens resolved through a focused lookup, and the outside-root docker notice kept its trailing text`;
}

async function negativeLookups(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const malformed = await lookup(context, "lookup-malformed", "path:zz", paths, null);
  context.check.equal(lookupStatus(outputOf(malformed)), "invalid_token", "a malformed token reports lookup_status invalid_token");
  const unknown = await lookup(context, "lookup-unknown", "<path:000000000000>", paths, null);
  context.check.equal(lookupStatus(outputOf(unknown)), "not_found", "an unknown token reports lookup_status not_found");
  const policy = await cli(context, "lookup-without-private-context", ["diagnostics", "--lookup", "<path:000000000000>", ...selectorArgs(paths, null)], {paths});
  context.check.failedExit(policy, "--lookup without --show-private-context fails");
  context.check.contains(outputOf(policy), "setup_failed code=setup_invalid_arguments", "the policy error is setup_invalid_arguments");
  return "focused lookups reported invalid_token and not_found, and --lookup required --show-private-context";
}

async function deepDiagnostics(context: ScenarioContext, running: boolean): Promise<string> {
  const service = await workedBoard(context);
  if (running) {
    await startWorker(context, service);
  }
  try {
    const status = await cli(context, "status", ["status", ...selectorArgs(service.paths, service.workflow)], {paths: service.paths});
    context.check.that(status.stdout.trim().startsWith(running ? "running" : "stopped"), `the worker is ${running ? "running" : "stopped"} before diagnostics`);
    const output = join(context.run.dir("private-evidence", context.scenario.id), "diagnostics.json");
    const report = await cli(context, "diagnostics-deep", ["diagnostics", "--deep", "--json", ...selectorArgs(service.paths, service.workflow), "--output", output], {
      paths: service.paths,
      timeoutMs: 180_000,
    });
    context.check.exitCode(report, 0, "diagnostics --deep --json exits 0");
    const text = existsSync(output) ? readFileSync(output, "utf8") : "";
    let parsed = false;
    try {
      JSON.parse(text);
      parsed = true;
    } catch {
      parsed = false;
    }
    context.check.that(parsed, "the diagnostics output is valid JSON");
    context.check.that(/running|stopped|status/i.test(text), "the report mentions the worker status");
    context.check.excludes(text, context.run.root, "the report tokenizes run-root paths instead of printing them");
  } finally {
    if (running) {
      await stopWorker(context, service);
    }
  }
  return `diagnostics --deep --json for a ${running ? "running" : "stopped"} worker wrote valid JSON without raw run-root paths`;
}

async function secretFileLaunch(context: ScenarioContext, oversized: boolean): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const outside = context.run.dir("workflows", context.scenario.id);
  const secretName = oversized ? "oversized-token.txt" : "missing-token.txt";
  if (oversized) {
    writeFileSync(join(outside, secretName), "x".repeat(70 * 1024));
  }
  const workflow = join(outside, "secret.WORKFLOW.md");
  writeFileSync(
    workflow,
    `---\ntracker:\n  kind: trello\n  api_key: $TRELLO_API_KEY\n  api_token: file:./${secretName}\n  board_id: "bugbash0board"\nserver:\n  port: ${await freePort()}\n---\nBug bash secret-file check.\n`,
  );
  const start = await cli(context, "start", ["start", ...selectorArgs(paths, workflow)], {paths});
  const output = outputOf(start);
  context.check.failedExit(start, "start fails before launch");
  const token = /secret_file_token=(<path:[0-9a-f]{12}>)/.exec(output)?.[1];
  context.check.that(token !== undefined, "the error prints secret_file_token");
  context.check.that(token !== undefined && output.includes(`--lookup '${token}'`), "the error prints a quoted lookup command");
  context.check.excludes(output, join(outside, secretName), "public output has no raw secret file path");
  if (token !== undefined) {
    const resolved = await lookup(context, "printed-lookup", token, paths, null);
    const status = lookupStatus(outputOf(resolved));
    context.check.that(status === "found" && outputOf(resolved).includes("secret_file"), `the printed lookup command resolves the token as secret_file (got ${status})`);
  }
  context.check.that(!existsSync(join(outside, DIAGNOSTICS_KEY)), "no diagnostics key is created in the workflow directory");
  const pidFiles = filesIn(paths.stateHome).filter((file) => file.endsWith(".pid"));
  context.check.equal(pidFiles.length, 0, "no worker pid file is created");
  return `start rejected the ${oversized ? "oversized" : "missing"} secret file before launch with a tokenized lookup hint`;
}

async function validationEdges(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const directory = context.run.dir("workflows", context.scenario.id);
  const port = await freePort();
  const cases: Array<[string, string]> = [
    ["invalid-yaml", "---\ntracker: [unclosed\n---\nPrompt\n"],
    ["list-front-matter", "---\n- just\n- a list\n---\nPrompt\n"],
    ["unsupported-kind", `---\ntracker:\n  kind: jira\n  board_id: "bugbash0board"\nserver:\n  port: ${port}\n---\nPrompt\n`],
    [
      "negative-poll",
      `---\ntracker:\n  kind: trello\n  api_key: $TRELLO_API_KEY\n  api_token: $TRELLO_API_TOKEN\n  board_id: "bugbash0board"\npolling:\n  interval_ms: -5\nserver:\n  port: ${port}\n---\nPrompt\n`,
    ],
  ];
  for (const [name, content] of cases) {
    const workflow = join(directory, `${name}.WORKFLOW.md`);
    writeFileSync(workflow, content);
    const start = await cli(context, `start-${name}`, ["start", ...selectorArgs(paths, workflow)], {paths});
    context.check.failedExit(start, `start fails for ${name}`);
    context.check.contains(outputOf(start), "setup_failed code=setup_workflow_invalid", `${name} reports setup_workflow_invalid`);
  }
  context.check.equal(filesIn(paths.stateHome).filter((file) => file.endsWith(".pid")).length, 0, "no pid file is created");
  return `start rejected ${cases.length} invalid workflow shapes with setup_workflow_invalid before launch`;
}

export const DIAGNOSTICS_SCENARIOS: ScenarioRegistry = {
  "diagnostics-focused-lookup-for-emitted-path-tokens": emittedTokensResolve,
  "diagnostics-lookup-negative-paths": negativeLookups,
  "diagnostics-deep-stopped-workflow": (context) => deepDiagnostics(context, false),
  "diagnostics-deep-running-worker": (context) => deepDiagnostics(context, true),
  "missing-secret-file-launch-diagnostics": (context) => secretFileLaunch(context, false),
  "oversized-secret-file-launch-diagnostics": (context) => secretFileLaunch(context, true),
  "workflow-config-validation-edges": validationEdges,
};
