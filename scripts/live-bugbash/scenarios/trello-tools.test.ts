import assert from "node:assert/strict";
import test from "node:test";
import {LIST_NAMES, STANDARD_LISTS} from "../lib/trello.ts";
import {withScenario} from "./fixture.test-support.ts";
import {TRELLO_TOOLS_SCENARIOS} from "./trello-tools.ts";

for (const unexpectedComment of [false, true]) {
  test(`read-only policy checks API comments without a fake adapter: unexpected=${unexpectedComment}`, async () => {
    const id = "trello-writes-disabled-tool-policy";
    await withScenario(id, async (context, cli, fixtures) => {
      // given
      const board = await context.trello().createBoard(id, STANDARD_LISTS);
      const card = await context.trello().request<{id: string}>("POST", "cards", {idList: board.lists[LIST_NAMES.ready]!, name: "synthetic"});
      if (unexpectedComment) await context.trello().request("POST", `cards/${card.id}/actions/comments`, {text: "read-only check"});
      fixtures.set("tools-read-only", new Map([[id, {
        service: {board, cli}, cardId: card.id,
        responses: ["trello_add_comment", "trello_move_current_card"].map((request) => ({scenario: id, request, response: "trello_writes_disabled"})),
      }]]));

      // when
      await TRELLO_TOOLS_SCENARIOS[id]!(context);

      // then
      assert.deepEqual(context.check.failures, unexpectedComment ? ["the card has no comment from Codex"] : []);
    });
  });
}
