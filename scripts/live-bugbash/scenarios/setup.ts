import {existsSync, writeFileSync} from "node:fs";
import {join} from "node:path";
import {freePort, occupyPort, reservedPorts} from "../lib/ports.ts";
import {outputOf, ProductFailure, SkipScenario, type ScenarioContext, type ScenarioRegistry} from "../lib/scenario.ts";
import {runCommand, sandboxEnvironment, sleep, workerState} from "../lib/symphony.ts";
import {STANDARD_LISTS} from "../lib/trello.ts";
import {getIn} from "../lib/workflow.ts";
import {
  addCard,
  adoptService,
  cardList,
  cli,
  endpointArgs,
  filesIn,
  frontMatter,
  registerProductBoards,
  runBoardName,
  serviceBoard,
  startWorker,
  stopWorker,
  waitForCardIn,
} from "./support.ts";

async function sourceInstall(context: ScenarioContext): Promise<string> {
  const install = context.sourceInstall();
  if (install === null) {
    throw new SkipScenario("--symphony-command selected an existing install; the source install was not exercised");
  }
  const {outcome, command} = await install;
  context.evidence("install.txt", `exit=${String(outcome.status)}\n${outcome.stdout}\n--- stderr ---\n${outcome.stderr}`);
  context.check.exitCode(outcome, 0, "install.sh --from-source exits 0");
  context.check.that(existsSync(command), "the installed symphony-trello wrapper exists");
  if (!existsSync(command)) {
    return "source install did not produce a wrapper";
  }
  const version = await cli(context, "version", ["--version"]);
  context.check.exitCode(version, 0, "symphony-trello --version exits 0");
  context.check.that(/symphony-trello \S+/.test(version.stdout), "--version prints a version line");
  const symphony = await context.cli();
  const credentials = symphony.environment();
  context.check.excludes(outputOf(outcome), credentials["TRELLO_API_TOKEN"] ?? "<none>", "install output does not print the Trello token");
  return `source install of ${context.targetCommit.slice(0, 12)} succeeded; --version printed ${version.stdout.trim().split("\n")[0] ?? ""}`;
}

async function installedWrapperTrelloAuth(context: ScenarioContext): Promise<string> {
  const outcome = await cli(context, "list-workspaces", ["list-workspaces", ...(await endpointArgs(context))]);
  const environment = (await context.cli()).environment();
  context.check.exitCode(outcome, 0, "list-workspaces exits 0");
  context.check.that(outcome.stdout.trim().length > 0, "list-workspaces lists at least one Workspace");
  context.check.excludes(outputOf(outcome), environment["TRELLO_API_TOKEN"] ?? "<none>", "output does not contain the Trello token");
  context.check.excludes(outputOf(outcome), environment["TRELLO_API_KEY"] ?? "<none>", "output does not contain the Trello key");
  return "list-workspaces authenticated with run-scoped credentials and printed no credential";
}

async function newBoard(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "new-board.WORKFLOW.md");
  const name = runBoardName(context);
  const outcome = await cli(context, "new-board", [
    "new-board",
    "--name",
    name,
    "--no-github",
    "--workflow",
    workflow,
    "--workspace-root",
    paths.workspaceRoot,
    "--server-port",
    String(await freePort()),
    ...(await endpointArgs(context)),
  ]);
  const registered = await registerProductBoards(context);
  context.check.exitCode(outcome, 0, "new-board exits 0");
  context.check.equal(registered, 1, "exactly one new run-owned board was created and registered");
  context.check.that(existsSync(workflow), "the workflow is written under the run root");
  if (registered === 1) {
    const board = context.trello().registeredBoards().find((entry) => entry.name === name);
    const lists = board === undefined ? [] : await context.trello().request<Array<{name: string}>>("GET", `boards/${board.id}/lists`, {filter: "open"});
    context.check.that(
      STANDARD_LISTS.every((list) => lists.some((entry) => entry.name === list)),
      "the new board has the recommended Trello-only lists",
    );
  }
  if (existsSync(workflow)) {
    context.check.that(typeof getIn(frontMatter(workflow), ["tracker", "board_id"]) === "string", "the workflow names the new board");
  }
  return "new-board created a registered board with the recommended lists and wrote the workflow under the run root";
}

async function importBoardCustom(context: ScenarioContext): Promise<string> {
  const lists = ["Queue", "Ready, Set", "Doing", "Stuck", "Review", "Shipped"];
  const service = await prepareImport(context, lists, [
    "--active",
    "Queue",
    "--active",
    "Ready, Set",
    "--in-progress",
    "Doing",
    "--blocked",
    "Stuck",
    "--terminal",
    "Review",
    "--terminal",
    "Shipped",
  ]);
  if (service.outcome.status !== 0) {
    context.check.exitCode(service.outcome, 0, "import-board exits 0");
    return "import-board failed for custom lists";
  }
  const tracker = frontMatter(service.workflow)["tracker"] as Record<string, unknown>;
  const active = tracker["active_states"] as string[];
  const terminal = tracker["terminal_states"] as string[];
  context.check.that(active[0] === "Queue" && active[1] === "Ready, Set", "active_states keeps Queue then the comma-containing Ready, Set");
  context.check.that(terminal.includes("Review") && terminal.includes("Shipped"), "terminal_states keeps both repeated terminal selectors");
  context.check.equal(tracker["in_progress_state"], "Doing", "in_progress_state is the exact list name");
  context.check.equal(tracker["blocked_state"], "Stuck", "blocked_state is the exact list name");
  return "import-board kept repeated, comma-containing, in-progress, and blocked selectors exactly";
}

async function prepareImport(
  context: ScenarioContext,
  lists: readonly string[],
  selectors: readonly string[],
): Promise<{workflow: string; outcome: Awaited<ReturnType<typeof cli>>; manifest: string; boardId: string}> {
  const board = await context.trello().createBoard(context.scenario.id, lists, context.options.trelloWorkspaceId);
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "import.WORKFLOW.md");
  const outcome = await cli(context, "import-board", [
    "import-board",
    "--board",
    board.id,
    "--no-github",
    "--force",
    "--workflow",
    workflow,
    "--env",
    paths.envFile,
    "--manifest",
    paths.manifest,
    "--workspace-root",
    paths.workspaceRoot,
    "--server-port",
    String(await freePort()),
    ...selectors,
    ...(await endpointArgs(context)),
  ]);
  return {workflow, outcome, manifest: paths.manifest, boardId: board.id};
}

async function setupLocalCreateBoard(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "setup-local.WORKFLOW.md");
  const outcome = await setupLocal(context, paths, workflow, ["--board-name", runBoardName(context)]);
  await registerProductBoards(context);
  context.check.exitCode(outcome, 0, "setup-local exits 0");
  context.check.that(existsSync(workflow), "the workflow is written under the run root");
  context.check.that(existsSync(paths.manifest), "the connected-board manifest is written under the run root");
  context.check.that(!filesIn(paths.stateHome).some((file) => file.endsWith(".pid")), "no worker pid file exists because --no-start is set");
  return "setup-local created a board, workflow, and manifest under the run root and started no worker";
}

async function setupLocal(
  context: ScenarioContext,
  paths: Awaited<ReturnType<ScenarioContext["cli"]>>["defaultPaths"],
  workflow: string,
  extra: readonly string[],
  name = "setup-local",
): Promise<Awaited<ReturnType<typeof cli>>> {
  return cli(
    context,
    name,
    [
      "setup-local",
      "--non-interactive",
      "--no-start",
      "--no-github",
      "--force",
      "--config-dir",
      paths.configDir,
      "--env",
      paths.envFile,
      "--manifest",
      paths.manifest,
      "--workflow",
      workflow,
      "--workspace-root",
      paths.workspaceRoot,
      "--server-port",
      String(await freePort(paths.manifest)),
      ...extra,
      ...(await endpointArgs(context)),
    ],
    {paths},
  );
}

async function setupLocalCheckRunningBoard(context: ScenarioContext): Promise<string> {
  const service = await serviceBoard(context, context.scenario.id);
  await startWorker(context, service);
  try {
    const outcome = await cli(
      context,
      "setup-local-check",
      ["setup-local", "check", "--non-interactive", "--config-dir", service.paths.configDir, "--manifest", service.paths.manifest, "--endpoint", service.cli.endpoint],
      {paths: service.paths},
    );
    const output = outputOf(outcome);
    context.check.exitCode(outcome, 0, "setup-local check exits 0 for a healthy running board");
    context.check.contains(output, "Workflow:", "the check names the connected workflow");
    context.check.excludes(output, "in use by another process", "the check reports no port conflict for the running worker");
    context.check.excludes(output, "credential check failed", "the check reports no Trello credential failure");
    return "setup-local check reported the running board healthy";
  } finally {
    await stopWorker(context, service);
  }
}

async function missingSelector(context: ScenarioContext, command: "import-board" | "setup-local"): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "selector.WORKFLOW.md");
  const env = join(paths.configDir, ".env");
  const args =
    command === "import-board"
      ? ["import-board", "--board", "bugbash0board", "--active", "--terminal=Released", "--workflow", workflow, "--env", env]
      : ["setup-local", "--non-interactive", "--config-dir", paths.configDir, "--board", "bugbash0board", "--active", "--terminal=Released", "--workflow", workflow, "--env", env];
  const outcome = await cli(context, command, [...args, ...(await endpointArgs(context))], {paths});
  const output = outputOf(outcome);
  context.check.failedExit(outcome, `${command} fails`);
  context.check.contains(output, "setup_failed code=setup_invalid_arguments", "the failure code is setup_invalid_arguments");
  context.check.contains(output, "Expected parameter for option '--active'", "the message names the --active option");
  context.check.that(!existsSync(workflow) && !existsSync(env) && !existsSync(paths.manifest), "no workflow, env, or manifest file is written");
  context.check.excludes(output, "Validating Trello", "no Trello setup output follows the validation failure");
  return `${command} rejected --active before an attached option with setup_invalid_arguments and wrote nothing`;
}

async function optionLikeSelector(context: ScenarioContext, command: "import-board" | "setup-local"): Promise<string> {
  if (command === "import-board") {
    const result = await prepareImport(context, ["--terminal", "Done"], ["--active=--terminal", "--terminal", "Done"]);
    context.check.exitCode(result.outcome, 0, "import-board exits 0");
    if (result.outcome.status === 0) {
      const tracker = frontMatter(result.workflow)["tracker"] as Record<string, unknown>;
      context.check.that((tracker["active_states"] as string[]).includes("--terminal"), "active_states contains the list named --terminal");
      context.check.that((tracker["terminal_states"] as string[]).includes("Done"), "terminal_states contains Done");
    }
    return "import-board treated the attached value --terminal as a list name";
  }
  const board = await context.trello().createBoard(context.scenario.id, ["--terminal", "Done"], context.options.trelloWorkspaceId);
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "option-like.WORKFLOW.md");
  const outcome = await setupLocal(context, paths, workflow, ["--board", board.id, "--active=--terminal", "--terminal", "Done"]);
  context.check.exitCode(outcome, 0, "setup-local exits 0");
  if (outcome.status === 0) {
    const tracker = frontMatter(workflow)["tracker"] as Record<string, unknown>;
    context.check.that((tracker["active_states"] as string[]).includes("--terminal"), "active_states contains the list named --terminal");
  }
  return "setup-local treated the attached value --terminal as a list name";
}

async function invalidBoardSelector(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "invalid.WORKFLOW.md");
  const outcome = await cli(
    context,
    "import-board",
    ["import-board", "--board", "zz9missing", "--no-github", "--workflow", workflow, "--env", paths.envFile, "--manifest", paths.manifest, ...(await endpointArgs(context))],
    {paths},
  );
  context.check.failedExit(outcome, "import-board fails for a board that does not exist");
  context.check.contains(outputOf(outcome), "setup_failed code=", "the failure carries a setup_failed code");
  context.check.that(!existsSync(workflow) && !existsSync(paths.manifest), "no workflow or manifest is written");
  return `import-board rejected an unknown board with ${/setup_failed code=(\S+)/.exec(outputOf(outcome))?.[1] ?? "no code"}`;
}

async function duplicateListSelector(context: ScenarioContext): Promise<string> {
  const result = await prepareImport(context, STANDARD_LISTS, ["--active", "Ready for Codex", "--active", "Ready for Codex", "--terminal", "Done"]);
  context.check.failedExit(result.outcome, "import-board fails for a repeated selector");
  context.check.contains(outputOf(result.outcome), "setup_failed code=setup_duplicate_active_state", "the failure code is setup_duplicate_active_state");
  context.check.that(!existsSync(result.workflow) && !existsSync(result.manifest), "no workflow or manifest is written");
  return "import-board rejected a repeated --active selector before writing";
}

async function checkMissingManifest(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const manifest = join(paths.configDir, "missing-connected-boards.json");
  const outcome = await cli(context, "setup-local-check", ["setup-local", "check", "--non-interactive", "--manifest", manifest, "--config-dir", paths.configDir, ...(await endpointArgs(context))], {paths});
  context.check.exitCode(outcome, 0, "setup-local check exits 0");
  context.check.contains(outputOf(outcome), "No Trello boards connected", "the check warns that no boards are connected");
  context.check.that(!existsSync(manifest), "the missing manifest is not created");
  return "setup-local check reported no connected boards and created no manifest";
}

async function dryRunDangerAccess(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "dry-run.WORKFLOW.md");
  const boardsBefore = (await context.trello().visibleBoards()).length;
  const outcome = await cli(
    context,
    "setup-local-dry-run",
    [
      "setup-local",
      "--dry-run",
      "--non-interactive",
      "--no-start",
      "--no-github",
      "--config-dir",
      paths.configDir,
      "--env",
      paths.envFile,
      "--manifest",
      paths.manifest,
      "--workflow",
      workflow,
      "--workspace-root",
      paths.workspaceRoot,
      "--board-name",
      runBoardName(context),
      "--add-path",
      "/",
      "--allow-all-paths",
      "--danger-full-access",
      ...(await endpointArgs(context)),
    ],
    {paths},
  );
  const output = outputOf(outcome);
  context.check.exitCode(outcome, 0, "the dry run exits 0");
  context.check.contains(output, "WOULD write workflow", "the plan includes the workflow write");
  context.check.contains(output, "WOULD update connected-board manifest", "the plan includes the manifest update");
  context.check.contains(output, "danger-full-access", "the plan names danger-full-access");
  context.check.that(!existsSync(workflow) && !existsSync(paths.manifest) && !existsSync(paths.envFile), "no file is written");
  context.check.equal((await context.trello().visibleBoards()).length, boardsBefore, "no Trello board is created");
  return "setup-local --dry-run described all-path and danger access and changed nothing";
}

async function maxAgentsBoundary(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const boardsBefore = (await context.trello().visibleBoards()).length;
  const rejected = join(paths.configDir, "max-100.WORKFLOW.md");
  const tooMany = await setupLocal(context, paths, rejected, ["--max-agents", "100", "--board-name", runBoardName(context, "-100")], "setup-local-100");
  context.check.failedExit(tooMany, "--max-agents 100 fails");
  context.check.contains(outputOf(tooMany), "setup_failed code=setup_invalid_max_agents", "the failure code is setup_invalid_max_agents");
  context.check.that(!existsSync(rejected), "no workflow is written for 100");
  context.check.equal((await context.trello().visibleBoards()).length, boardsBefore, "no board is created for 100");
  const accepted = join(paths.configDir, "max-99.WORKFLOW.md");
  const ok = await setupLocal(context, paths, accepted, ["--max-agents", "99", "--board-name", runBoardName(context, "-99")], "setup-local-99");
  await registerProductBoards(context);
  context.check.exitCode(ok, 0, "--max-agents 99 exits 0");
  if (existsSync(accepted)) {
    context.check.equal(getIn(frontMatter(accepted), ["agent", "max_concurrent_agents"]), 99, "agent.max_concurrent_agents is 99");
  } else {
    context.check.that(false, "the workflow for 99 is written");
  }
  return "--max-agents 100 failed before any write and 99 wrote max_concurrent_agents 99";
}

async function noInProgressDispatch(context: ScenarioContext): Promise<string> {
  const symphony = await context.cli();
  const paths = symphony.paths(context.scenario.id);
  const workflow = join(paths.configDir, "no-in-progress.WORKFLOW.md");
  symphony.writeEnvFile(paths);
  const setup = await setupLocal(context, paths, workflow, ["--no-in-progress", "--board-name", runBoardName(context)]);
  await registerProductBoards(context);
  if (setup.status !== 0) {
    throw new ProductFailure(`setup-local --no-in-progress failed (exit ${String(setup.status)})`);
  }
  context.check.equal(getIn(frontMatter(workflow), ["tracker", "in_progress_state"]), undefined, "the workflow has no tracker.in_progress_state");
  const boardId = context.trello().registeredBoards().find((board) => board.name === runBoardName(context))?.id;
  if (boardId === undefined) {
    throw new ProductFailure("setup-local did not create the named board");
  }
  const service = await adoptService(context, context.scenario.id, workflow, paths, boardId);
  context.check.that(!("In Progress" in service.board.lists), "the board has no In Progress list");
  const cardId = await addCard(context, service, context.scenario.id, "Answer with one sentence. No repository is needed.");
  await startWorker(context, service);
  try {
    await waitForCardIn(context, service, cardId, "Human Review");
    const visited = context.fakeTrello?.state.moves.filter((move) => move.cardId === cardId).map((move) => move.toList) ?? [];
    context.check.that(!visited.includes("In Progress"), "the card never entered In Progress");
  } finally {
    await stopWorker(context, service);
  }
  return "setup-local --no-in-progress produced no In Progress list and the card went from Ready to Human Review";
}

async function noHandoffDestination(context: ScenarioContext): Promise<string> {
  const result = await prepareImport(context, ["Ready for Codex", "Done"], []);
  context.check.exitCode(result.outcome, 0, "import-board exits 0");
  if (result.outcome.status === 0) {
    const document = frontMatter(result.workflow);
    context.check.equal(getIn(document, ["tracker", "blocked_state"]), undefined, "the workflow has no blocked_state");
    const moves = getIn(document, ["trello_tools", "allowed_move_list_names"]) as string[];
    context.check.that(moves.length === 1 && moves[0] === "Done", "moves are allowed only to Done");
  }
  return "import-board of a queue-and-Done board wrote a workflow that allows moves only to Done";
}

async function terminalDoneHandoff(context: ScenarioContext): Promise<string> {
  const service = await serviceBoard(context, context.scenario.id, {lists: ["Ready for Codex", "Done"], reviewList: "Done"});
  const cardId = await addCard(context, service, context.scenario.id, "Answer with one sentence. No repository is needed.");
  await startWorker(context, service);
  try {
    await waitForCardIn(context, service, cardId, "Done");
    await sleep(3_000);
    const current = await workerState(service.port);
    const retrying = (current?.retrying ?? []).some((entry) => entry["card_id"] === cardId);
    context.check.that(!retrying, "the worker does not retry the card after the terminal move");
    context.check.equal(await cardList(context, service, cardId), "Done", "the card stays in Done");
  } finally {
    await stopWorker(context, service);
  }
  return "fake Codex handed the card to Done, which is terminal, and the worker did not retry it";
}

async function repairPortOccupied(context: ScenarioContext): Promise<string> {
  const paths = (await context.cli()).paths(context.scenario.id);
  const workflow = join(paths.configDir, "repair-port.WORKFLOW.md");
  const setup = await setupLocal(context, paths, workflow, ["--board-name", runBoardName(context)]);
  await registerProductBoards(context);
  if (setup.status !== 0) {
    throw new ProductFailure(`setup-local failed (exit ${String(setup.status)})`);
  }
  const originalPort = getIn(frontMatter(workflow), ["server", "port"]) as number;
  const holder = await occupyPort(originalPort);
  try {
    const check = await cli(context, "setup-local-check", ["setup-local", "check", "--non-interactive", "--config-dir", paths.configDir, "--manifest", paths.manifest, ...(await endpointArgs(context))], {paths});
    context.check.contains(outputOf(check), "is in use by another process", "setup-local check reports the occupied port");
    context.check.contains(outputOf(check), "repair-port", "setup-local check suggests repair-port");
    const repair = await cli(context, "repair-port", ["setup-local", "repair-port", "--non-interactive", "--board", runBoardName(context), "--config-dir", paths.configDir, "--manifest", paths.manifest], {paths});
    context.check.exitCode(repair, 0, "repair-port exits 0");
    const newPort = getIn(frontMatter(workflow), ["server", "port"]);
    context.check.that(typeof newPort === "number" && newPort !== originalPort, "the workflow uses a different port");
    context.check.that(reservedPorts(paths.manifest).includes(newPort as number), "the manifest records the new port");
  } finally {
    await holder.release();
  }
  return "setup-local check flagged the occupied port and repair-port moved the board to a free one";
}

async function installDryRun(context: ScenarioContext): Promise<string> {
  const prefix = context.run.path("installer-sandboxes", "dry-run-prefix");
  const binDir = context.run.path("installer-sandboxes", "dry-run-bin");
  const outcome = await runCommand(
    "bash",
    [join(context.run.repoRoot, "install.sh"), "--dry-run", "--no-onboard", "--no-update-path", "--prefix", prefix, "--bin-dir", binDir],
    {cwd: context.run.root, env: sandboxEnvironment(context.run)},
  );
  context.evidence("install-dry-run.txt", `exit=${String(outcome.status)}\n${outcome.stdout}\n${outcome.stderr}`);
  context.check.exitCode(outcome, 0, "install.sh --dry-run exits 0");
  context.check.contains(outcome.stdout, "WOULD", "the dry run prints WOULD lines");
  context.check.that(!existsSync(prefix) && !existsSync(binDir), "neither the prefix nor the bin dir is created");
  return "install.sh --dry-run with a custom prefix printed its plan and created nothing";
}

async function uninstallDryRun(context: ScenarioContext): Promise<string> {
  const prefix = context.run.dir("installer-sandboxes", "uninstall-prefix");
  const binDir = context.run.dir("installer-sandboxes", "uninstall-bin");
  const markers = [join(prefix, ".symphony-trello-install"), join(prefix, "marker.txt"), join(binDir, "symphony-trello")];
  for (const marker of markers) {
    writeFileSync(marker, "bug bash marker\n");
  }
  const outcome = await runCommand("bash", [join(context.run.repoRoot, "uninstall.sh"), "--dry-run", "--prefix", prefix, "--bin-dir", binDir], {
    cwd: context.run.root,
    env: sandboxEnvironment(context.run),
  });
  context.evidence("uninstall-dry-run.txt", `exit=${String(outcome.status)}\n${outcome.stdout}\n${outcome.stderr}`);
  context.check.exitCode(outcome, 0, "uninstall.sh --dry-run exits 0");
  context.check.that(markers.every((marker) => existsSync(marker)), "every marker file still exists");
  return "uninstall.sh --dry-run with a custom prefix removed nothing";
}

export const SETUP_SCENARIOS: ScenarioRegistry = {
  "source-install": sourceInstall,
  "installed-wrapper-trello-auth": installedWrapperTrelloAuth,
  "new-board-real-trello": newBoard,
  "import-board-custom-real-trello": importBoardCustom,
  "setup-local-create-board": setupLocalCreateBoard,
  "setup-local-check-running-board": setupLocalCheckRunningBoard,
  "import-board-missing-list-selector-before-attached-option": (context) => missingSelector(context, "import-board"),
  "setup-local-missing-list-selector-before-attached-option": (context) => missingSelector(context, "setup-local"),
  "import-board-option-like-list-selector": (context) => optionLikeSelector(context, "import-board"),
  "setup-local-option-like-list-selector": (context) => optionLikeSelector(context, "setup-local"),
  "import-board-invalid-board-selector": invalidBoardSelector,
  "import-board-duplicate-list-selector": duplicateListSelector,
  "setup-local-check-missing-manifest": checkMissingManifest,
  "setup-local-dry-run-all-paths-danger-access": dryRunDangerAccess,
  "setup-max-agents-boundary-99-and-100": maxAgentsBoundary,
  "setup-local-no-in-progress-dispatch": noInProgressDispatch,
  "import-board-no-handoff-destination": noHandoffDestination,
  "import-board-terminal-done-handoff": terminalDoneHandoff,
  "setup-local-repair-port-occupied-port": repairPortOccupied,
  "install-dry-run-custom-prefix": installDryRun,
  "uninstall-dry-run-custom-prefix-safety": uninstallDryRun,
};
