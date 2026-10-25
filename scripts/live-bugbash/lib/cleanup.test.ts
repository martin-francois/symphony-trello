import assert from "node:assert/strict";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {runScopedCodexHome} from "./codex.ts";
import {cleanupRun} from "./cleanup.ts";
import {FAKE_TRELLO_CREDENTIALS, FakeTrelloApi} from "./fake-trello-api.ts";
import {cleanupIsClean, exitCodeFor} from "./report.ts";
import {RunRoot} from "./run-root.ts";
import {TrelloApi} from "./trello.ts";

async function withRun(body: (run: RunRoot, trello: TrelloApi, fake: FakeTrelloApi, directory: string) => Promise<void>): Promise<void> {
  const directory = mkdtempSync(join(tmpdir(), "live-bugbash-cleanup-"));
  const run = new RunRoot(directory, "cleanup-run");
  run.create();
  const fake = new FakeTrelloApi();
  const endpoint = await fake.start();
  try {
    const trello = new TrelloApi(run, endpoint, FAKE_TRELLO_CREDENTIALS);
    await trello.captureInitialBoards();
    await body(run, trello, fake, directory);
  } finally {
    await fake.stop();
  }
}

test("cleanup archives registered boards and a second pass changes nothing", async () => {
  await withRun(async (run, trello, fake) => {
    // given
    const first = await trello.createBoard("first", []);
    const second = await trello.createBoard("second", []);

    // when
    const pass = await cleanupRun(run, null, trello);
    const again = await cleanupRun(run, null, trello);

    // then
    assert.deepEqual(
      {registered: pass.boardsRegistered, archived: pass.boardsArchived, open: pass.boardsStillOpen},
      {registered: 2, archived: 2, open: 0},
    );
    assert.equal(again.boardsArchived, 0);
    assert.deepEqual([fake.board(first.id)?.closed, fake.board(second.id)?.closed], [true, true]);
  });
});

test("cleanup registers and archives a run-named board that nobody registered", async () => {
  await withRun(async (run, trello, fake) => {
    // given
    const leaked = await trello.request<{id: string}>("POST", "boards", {name: "cleanup-run-setup-local"});
    const foreign = await trello.request<{id: string}>("POST", "boards", {name: "someone-else"});

    // when
    const pass = await cleanupRun(run, null, trello);

    // then
    assert.equal(pass.boardsArchived, 1);
    assert.equal(fake.board(leaked.id)?.closed, true);
    assert.equal(fake.board(foreign.id)?.closed, false, "a board without the run-id prefix is never touched");
    assert.match(pass.notes.join("\n"), /1 run-named board\(s\) were missing from the registry/);
  });
});

test("cleanup deletes registered sensitive paths inside the run root only", async () => {
  await withRun(async (run, trello, _fake, directory) => {
    // given
    const inside = join(run.dir("state", "codex-home"), "auth.json");
    writeFileSync(inside, "{}");
    run.registerSensitivePath(join(run.root, "state", "codex-home"));
    const outside = join(directory, "outside");
    mkdirSync(outside);
    run.registerSensitivePath(outside);

    // when
    const pass = await cleanupRun(run, null, trello);

    // then
    assert.equal(existsSync(inside), false);
    assert.equal(existsSync(outside), true);
    assert.equal(pass.sensitivePathsDeleted, 1);
    assert.match(pass.notes.join("\n"), /outside the run root was left untouched/);
  });
});

test("cleanup never claims a recent pre-existing board whose name shares the run prefix", async () => {
  await withRun(async (run, trello, fake) => {
    // given
    const preexisting = await trello.request<{id: string}>("POST", "boards", {name: `${run.runId}-notes`});
    run.privateEvidence("run", "preexisting-trello-boards.json", JSON.stringify(await trello.visibleBoards()));
    const leaked = await trello.request<{id: string}>("POST", "boards", {name: `${run.runId}-setup-local`});
    await trello.captureInitialBoards();

    // when
    const pass = await cleanupRun(run, null, trello);

    // then
    assert.equal(fake.board(preexisting.id)?.closed, false);
    assert.equal(fake.board(leaked.id)?.closed, true);
    assert.equal(pass.boardsRegistered, 1);
  });
});

test("intermediate cleanup preserves refreshed Codex authentication until final cleanup", async () => {
  await withRun(async (run, trello, _fake, directory) => {
    // given
    const previousHome = process.env["CODEX_HOME"];
    process.env["CODEX_HOME"] = directory;
    writeFileSync(join(directory, "auth.json"), '{"synthetic":"original"}');
    try {
      const home = runScopedCodexHome(run);
      const copy = join(home, "auth.json");
      writeFileSync(copy, '{"synthetic":"refreshed"}');

      // when
      await cleanupRun(run, null, trello, {preserveCodexAuth: true});

      // then
      assert.equal(readFileSync(join(runScopedCodexHome(run), "auth.json"), "utf8"), '{"synthetic":"refreshed"}');
      await cleanupRun(run, null, trello);
      assert.equal(existsSync(home), false);
      assert.equal(readFileSync(join(directory, "auth.json"), "utf8"), '{"synthetic":"original"}');
    } finally {
      if (previousHome === undefined) delete process.env["CODEX_HOME"];
      else process.env["CODEX_HOME"] = previousHome;
    }
  });
});

test("failed board discovery keeps cleanup incomplete until a later recovery pass", async (t) => {
  await withRun(async (run, trello, fake) => {
    // given
    const leaked = await trello.request<{id: string}>("POST", "boards", {name: `${run.runId}-unregistered`});
    const discovery = t.mock.method(trello, "visibleBoards", async () => {
      throw new Error("synthetic discovery failure");
    });

    // when
    const failed = await cleanupRun(run, null, trello);

    // then
    assert.equal(fake.board(leaked.id)?.closed, false);
    assert.equal(cleanupIsClean(failed), false, "failed discovery cannot prove there are no leaked boards");
    assert.equal(exitCodeFor([], failed), 1, "cleanup-only must require another recovery attempt");
    assert.match(failed.notes.join("\n"), /could not list visible boards/);
    assert.equal(existsSync(run.path("private-evidence", "run", "preexisting-trello-boards.json")), true);
    discovery.mock.restore();
    const recovered = await cleanupRun(run, null, trello);
    assert.equal(fake.board(leaked.id)?.closed, true);
    assert.equal(cleanupIsClean(recovered), true);
  });
});
