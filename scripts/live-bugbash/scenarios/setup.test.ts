import assert from "node:assert/strict";
import test from "node:test";
import {writeFileSync} from "node:fs";
import {join} from "node:path";
import {LIST_NAMES, STANDARD_LISTS} from "../lib/trello.ts";
import {withScenario} from "./fixture.test-support.ts";
import {SETUP_SCENARIOS} from "./setup.ts";

for (const id of ["new-board-real-trello", "setup-local-create-board"]) {
  test(`${id} passes the selected workspace to the product CLI`, async (t) => {
    await withScenario(id, async (context, cli) => {
      // given
      context.options.trelloWorkspaceId = "synthetic-selected-workspace";
      const captured: string[][] = [];
      t.mock.method(cli, "exec", async (args: readonly string[]) => {
        captured.push([...args]);
        return {status: 2, stdout: "", stderr: "synthetic command stopped after argument capture", timedOut: false};
      });

      // when
      await SETUP_SCENARIOS[id]!(context);

      // then
      assert.equal(captured.length, 1);
      const args = captured[0]!;
      assert.equal(args[args.indexOf("--workspace-id") + 1], "synthetic-selected-workspace");
    });
  });
}


test("resuming new-board explicitly replaces its previous run-owned workflow", async (t) => {
  const id = "new-board-real-trello";
  await withScenario(id, async (context, cli) => {
    // given
    const workflow = join(cli.paths(id).configDir, "new-board.WORKFLOW.md");
    writeFileSync(workflow, "---\ntracker:\n  board_id: previous-board\n---\nprevious body\n");
    let command: readonly string[] = [];
    t.mock.method(cli, "exec", async (args: readonly string[]) => {
      command = args;
      return {status: 2, stdout: "", stderr: "synthetic command stopped after argument capture", timedOut: false};
    });

    // when
    await SETUP_SCENARIOS[id]!(context);

    // then
    assert.equal(command[command.indexOf("--workflow") + 1], workflow);
    assert.equal(command.includes("--force"), true);
  });
});

test("resumed no-in-progress dispatch selects the board from the new workflow", async (t) => {
  const id = "setup-local-no-in-progress-dispatch";
  await withScenario(id, async (context, cli) => {
    // given
    const api = context.trello();
    const previous = await api.createBoard(id, STANDARD_LISTS);
    await api.archiveBoard(previous.id);
    const current = await api.createBoard(id, STANDARD_LISTS.filter((name) => name !== LIST_NAMES.inProgress));
    const workflow = join(cli.paths(id).configDir, "no-in-progress.WORKFLOW.md");
    t.mock.method(cli, "exec", async () => {
      writeFileSync(workflow, `---\ntracker:\n  board_id: "${current.shortLink}"\n---\nbody\n`);
      return {status: 0, stdout: "", stderr: "", timedOut: false};
    });
    const request = api.request.bind(api);
    let selectedPath = "";
    const captured = new Error("configured board captured before compiling a fake worker");
    t.mock.method(api, "request", async (method: string, path: string, params: Record<string, string>) => {
      if (method === "GET" && path.endsWith("/lists")) {
        selectedPath = path;
        throw captured;
      }
      return request(method, path, params);
    });

    // when
    await assert.rejects(() => SETUP_SCENARIOS[id]!(context), captured);

    // then
    assert.equal(selectedPath, `boards/${current.id}/lists`);
    assert.equal(await api.boardClosed(previous.id), true);
  });
});

test("resumed new-board validates lists on the newly written workflow's board", async (t) => {
  const id = "new-board-real-trello";
  await withScenario(id, async (context, cli) => {
    // given
    const api = context.trello();
    const previous = await api.createBoard(id, ["Old custom list"]);
    await api.archiveBoard(previous.id);
    const workflow = join(cli.paths(id).configDir, "new-board.WORKFLOW.md");
    t.mock.method(cli, "exec", async () => {
      const current = await api.request<{id: string; shortLink: string}>("POST", "boards", {name: `${context.run.runId}-${id}`});
      for (const name of STANDARD_LISTS) await api.request("POST", "lists", {idBoard: current.id, name});
      writeFileSync(workflow, `---\ntracker:\n  board_id: "${current.shortLink}"\n---\nbody\n`);
      return {status: 0, stdout: "", stderr: "", timedOut: false};
    });

    // when
    await SETUP_SCENARIOS[id]!(context);

    // then
    assert.deepEqual(context.check.failures, []);
    assert.equal(await api.boardClosed(previous.id), true);
  });
});
