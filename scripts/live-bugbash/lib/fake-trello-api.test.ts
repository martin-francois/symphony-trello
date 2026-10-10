import assert from "node:assert/strict";
import {mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {FAKE_TRELLO_CREDENTIALS, FakeTrelloApi} from "./fake-trello-api.ts";
import {RunRoot} from "./run-root.ts";
import {boardCreatedAt, TrelloApi, TrelloApiError} from "./trello.ts";
import {patchWorkflow, readFrontMatter} from "./workflow.ts";

const SYNTHETIC_BOARD_ID = "000000000000000000000001";
const SYNTHETIC_CARD_URL = "https://trello.com/c/SYNTH001";

async function withFake(body: (trello: TrelloApi, fake: FakeTrelloApi, run: RunRoot) => Promise<void>): Promise<void> {
  const repo = mkdtempSync(join(tmpdir(), "live-bugbash-"));
  const run = new RunRoot(repo, "test-run");
  run.create();
  const fake = new FakeTrelloApi({stateFile: join(repo, "fake.json")});
  const endpoint = await fake.start();
  try {
    await body(new TrelloApi(run, endpoint, FAKE_TRELLO_CREDENTIALS), fake, run);
  } finally {
    await fake.stop();
  }
}

test("boards are registered before lists are created and archive through the registry", async () => {
  await withFake(async (trello, fake) => {
    // given
    const board = await trello.createBoard("sample-scenario", ["Ready for Codex", "Done"]);

    // when
    await trello.archiveBoard(board.id);

    // then
    assert.equal(board.name, "test-run-sample-scenario");
    assert.deepEqual(trello.registeredBoards().map((entry) => entry.id), [board.id]);
    assert.deepEqual(Object.keys(board.lists), ["Ready for Codex", "Done"]);
    assert.equal(await trello.boardClosed(board.id), true);
    assert.equal(fake.board(board.id)?.closed, true);
  });
});

test("the client refuses to touch a board this run did not register", async () => {
  await withFake(async (trello) => {
    // given
    const unregistered = SYNTHETIC_BOARD_ID;

    // when / then
    await assert.rejects(() => trello.archiveBoard(unregistered), /did not register/);
  });
});

test("cards, moves, comments, and checklists behave like the Trello endpoints Symphony uses", async () => {
  await withFake(async (trello, fake) => {
    // given
    const board = await trello.createBoard("cards", ["Ready for Codex", "Done"]);
    const card = await trello.createCard(board, "Ready for Codex", "title", "desc", "cards");

    // when
    await trello.moveCard(board, card.id, "Done");
    await trello.request("POST", `cards/${card.id}/actions/comments`, {text: "first"});
    await trello.addChecklist(board, card.id, "Prerequisites", [SYNTHETIC_CARD_URL]);

    // then
    assert.deepEqual(fake.state.moves.map((move) => [move.fromList, move.toList]), [["Ready for Codex", "Done"]]);
    assert.deepEqual(await trello.comments(card.id), ["first"]);
    assert.deepEqual(
      (await trello.checklistItems(card.id)).map((item) => [item.name, item.state]),
      [[SYNTHETIC_CARD_URL, "incomplete"]],
    );
    assert.match(card.shortLink, /^(?=.*\d)[A-Za-z0-9]{8}$/, "short links satisfy Symphony's reference parser");
  });
});

test("fake ids carry their creation time like Trello's", async () => {
  await withFake(async (trello) => {
    // given
    const before = Math.floor(Date.now() / 1000);

    // when
    const board = await trello.createBoard("created-at", []);

    // then
    const created = boardCreatedAt(board.id) ?? 0;
    assert.ok(created >= before && created <= Math.floor(Date.now() / 1000), "the id starts with the creation second");
  });
});

test("wrong credentials are rejected and unsupported routes are recorded", async () => {
  await withFake(async (_trello, fake, run) => {
    // given
    const wrong = new TrelloApi(run, fake.endpoint, {key: "wrong", token: "wrong"});
    const right = new TrelloApi(run, fake.endpoint, FAKE_TRELLO_CREDENTIALS);

    // when / then
    await assert.rejects(() => wrong.member(), (error: unknown) => error instanceof TrelloApiError && error.status === 401);
    await assert.rejects(() => right.request("GET", "search"), /HTTP 404/);
    assert.deepEqual(fake.state.unsupportedRoutes, ["GET /search"]);
  });
});

test("state persists to the state file so cleanup-only can archive an earlier run's boards", async () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "live-bugbash-state-"));
  const stateFile = join(directory, "fake.json");
  const first = new FakeTrelloApi({stateFile});
  const endpoint = await first.start();
  const run = new RunRoot(directory, "persist");
  run.create();
  const board = await new TrelloApi(run, endpoint, FAKE_TRELLO_CREDENTIALS).createBoard("persist", []);
  await first.stop();

  // when
  const second = new FakeTrelloApi({stateFile});

  // then
  assert.equal(second.board(board.id)?.name, "persist-persist");
  assert.ok(readFileSync(stateFile, "utf8").includes(board.id));
});

test("workflow patches keep the prompt body and comments", () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "live-bugbash-workflow-"));
  const workflow = join(directory, "WORKFLOW.md");
  writeFileSync(workflow, "---\ntracker:\n  kind: trello # keep me\ncodex:\n  command: codex app-server\n---\nPrompt {{ card.title }}\n");

  // when
  patchWorkflow(workflow, [
    [["codex", "command"], "/fake/app-server"],
    [["tracker", "endpoint"], "http://127.0.0.1:1/1"],
  ]);

  // then
  const text = readFileSync(workflow, "utf8");
  assert.ok(text.endsWith("---\nPrompt {{ card.title }}\n"));
  assert.ok(text.includes("# keep me"));
  assert.deepEqual(readFrontMatter(workflow), {tracker: {kind: "trello", endpoint: "http://127.0.0.1:1/1"}, codex: {command: "/fake/app-server"}});
});
