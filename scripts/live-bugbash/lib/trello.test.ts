import assert from "node:assert/strict";
import {mkdtempSync, rmSync} from "node:fs";
import {createServer} from "node:http";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {FAKE_TRELLO_CREDENTIALS} from "./fake-trello-api.ts";
import {RunRoot} from "./run-root.ts";
import {TrelloApi} from "./trello.ts";

test("cancelling a board query closes its blocked HTTP request", {timeout: 5_000}, async () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "bugbash-trello-cancel-"));
  const run = new RunRoot(directory, "cancel-query");
  run.create();
  const started = Promise.withResolvers<void>();
  const closed = Promise.withResolvers<void>();
  const server = createServer((_request, response) => {
    response.on("close", () => closed.resolve());
    started.resolve();
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address !== null && typeof address !== "string");
  const api = new TrelloApi(run, `http://127.0.0.1:${address.port}`, FAKE_TRELLO_CREDENTIALS);
  const controller = new AbortController();
  const request = api.boardClosed("synthetic-board", controller.signal);
  const rejection = assert.rejects(request, {name: "AbortError"});
  try {
    await started.promise;

    // when
    controller.abort();

    // then
    await rejection;
    await closed.promise;
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    rmSync(directory, {recursive: true, force: true});
  }
});
