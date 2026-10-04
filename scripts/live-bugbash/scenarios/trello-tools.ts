import {toolResponses, type FakeCodexStep, type ToolResponse} from "../lib/codex.ts";
import type {ScenarioContext, ScenarioRegistry, ScenarioRunner} from "../lib/scenario.ts";
import {sleep, waitFor} from "../lib/symphony.ts";
import type {WorkflowEdit} from "../lib/workflow.ts";
import {addCard, cardList, script, serviceBoard, startWorker, stopWorker, type ServiceBoard} from "./support.ts";

interface ToolFixture {
  id: string;
  edits: readonly WorkflowEdit[];
}

const FIXTURES = {
  restricted: {
    id: "tools-restricted",
    edits: [
      [["trello_tools", "allow_comments"], false],
      [["trello_tools", "allowed_move_list_names"], ["In Progress", "Human Review", "Blocked"]],
      [["trello_tools", "allow_checklists"], true],
      [["trello_tools", "allow_url_attachments"], true],
    ],
  },
  standard: {id: "tools-standard", edits: []},
  readOnly: {id: "tools-read-only", edits: [[["trello_tools", "allow_writes"], false]]},
} satisfies Record<string, ToolFixture>;

const MOVE_TO_REVIEW: FakeCodexStep = {kind: "tool", tool: "trello_move_current_card", arguments: {list_name: "Human Review"}};

interface ToolCase {
  fixture: ToolFixture;
  steps: readonly FakeCodexStep[];
  /** Number of scripted requests whose responses the case waits for. */
  responses: number;
  assert: (context: ScenarioContext, run: CaseRun) => Promise<void> | void;
}

interface CaseRun {
  service: ServiceBoard;
  cardId: string;
  responses: ToolResponse[];
}

function responseFor(run: CaseRun, request: string, index = 0): string {
  return run.responses.filter((entry) => entry.request === request)[index]?.response ?? "";
}

async function reachesReview(context: ScenarioContext, run: CaseRun): Promise<void> {
  const list = await cardList(context, run.service, run.cardId);
  context.check.equal(list, "Human Review", "the card reaches Human Review");
}

function comments(context: ScenarioContext, cardId: string): string[] {
  return context.fakeTrello?.commentsOf(cardId).map((comment) => comment.data.text) ?? [];
}

const CASES: Readonly<Record<string, ToolCase>> = {
  "trello-tool-comment-write-denied": {
    fixture: FIXTURES.restricted,
    steps: [{kind: "tool", tool: "trello_add_comment", arguments: {text: "should not be written"}}, MOVE_TO_REVIEW, {kind: "complete"}],
    responses: 2,
    assert: async (context, run) => {
      context.check.contains(responseFor(run, "trello_add_comment"), "trello_comments_disabled", "the comment call returns trello_comments_disabled");
      const texts = await context.trello().comments(run.cardId);
      context.check.that(!texts.some((text) => text.includes("should not be written")), "the card has no comment from the denied call");
      await reachesReview(context, run);
    },
  },
  "trello-tool-move-allowlist-denied": {
    fixture: FIXTURES.restricted,
    steps: [{kind: "tool", tool: "trello_move_current_card", arguments: {list_name: "Done"}}, MOVE_TO_REVIEW, {kind: "complete"}],
    responses: 2,
    assert: async (context, run) => {
      context.check.contains(responseFor(run, "trello_move_current_card", 0), "trello_move_not_allowed", "the Done move returns trello_move_not_allowed");
      const moves = context.fakeTrello?.state.moves.filter((move) => move.cardId === run.cardId).map((move) => move.toList);
      if (moves !== undefined) {
        context.check.that(!moves.includes("Done"), "the card never enters Done");
      }
      await reachesReview(context, run);
    },
  },
  "unsupported-dynamic-tool-call": {
    fixture: FIXTURES.restricted,
    steps: [{kind: "tool", tool: "trello_delete_board", arguments: {}}, MOVE_TO_REVIEW, {kind: "complete"}],
    responses: 2,
    assert: async (context, run) => {
      context.check.contains(responseFor(run, "trello_delete_board"), "unsupported_tool", "the unadvertised tool returns unsupported_tool");
      await reachesReview(context, run);
    },
  },
  "user-input-request-handling": {
    fixture: FIXTURES.restricted,
    steps: [{kind: "user-input"}, MOVE_TO_REVIEW, {kind: "complete"}],
    responses: 2,
    assert: async (context, run) => {
      context.check.contains(responseFor(run, "item/tool/requestUserInput"), "\"answers\":{}", "Symphony answers the request with an empty answer set");
      await reachesReview(context, run);
    },
  },
  "approval-request-handling": {
    fixture: FIXTURES.restricted,
    steps: [
      {kind: "approval", method: "item/commandExecution/requestApproval"},
      {kind: "approval", method: "item/permissions/requestApproval"},
      MOVE_TO_REVIEW,
      {kind: "complete"},
    ],
    responses: 3,
    assert: async (context, run) => {
      context.check.contains(responseFor(run, "item/commandExecution/requestApproval"), "acceptForSession", "the command approval is accepted for the session");
      context.check.contains(responseFor(run, "item/permissions/requestApproval"), "cancel", "the permissions request is cancelled");
      await reachesReview(context, run);
    },
  },
  "hand-authored-checklist-url-tools": {
    fixture: FIXTURES.restricted,
    steps: [
      {kind: "tool", tool: "trello_upsert_checklist_item", arguments: {checklist_name: "Bug bash", item_name: "Verify harness", complete: true}},
      {kind: "tool", tool: "trello_add_url_attachment", arguments: {url: "https://example.invalid/bugbash/report", name: "Bug bash report"}},
      MOVE_TO_REVIEW,
      {kind: "complete"},
    ],
    responses: 3,
    assert: async (context, run) => {
      context.check.contains(responseFor(run, "trello_upsert_checklist_item"), "\"success\":true", "the checklist call succeeds");
      context.check.contains(responseFor(run, "trello_add_url_attachment"), "\"success\":true", "the URL attachment call succeeds");
      const items = await context.trello().checklistItems(run.cardId);
      context.check.that(items.some((item) => item.name === "Verify harness" && item.state === "complete"), "the checklist item exists and is complete");
      const attachments = await context.trello().request<Array<{url: string}>>("GET", `cards/${run.cardId}/attachments`);
      context.check.that(attachments.some((attachment) => attachment.url === "https://example.invalid/bugbash/report"), "the URL attachment exists");
      await reachesReview(context, run);
    },
  },
  "workpad-upsert-tool-idempotence": {
    fixture: FIXTURES.standard,
    steps: [
      {kind: "tool", tool: "trello_upsert_workpad", arguments: {text: "## Codex Workpad\n\n- First pass."}},
      {kind: "tool", tool: "trello_upsert_workpad", arguments: {text: "## Codex Workpad\n\n- Second pass."}},
      MOVE_TO_REVIEW,
      {kind: "complete"},
    ],
    responses: 3,
    assert: async (context, run) => {
      const workpads = (await context.trello().comments(run.cardId)).filter((text) => text.startsWith("## Codex Workpad"));
      context.check.equal(workpads.length, 1, "the card has exactly one Codex Workpad comment");
      context.check.that(workpads[0]?.includes("Second pass.") === true, "the workpad holds the second text");
      await reachesReview(context, run);
    },
  },
  "trello-writes-disabled-tool-policy": {
    fixture: FIXTURES.readOnly,
    steps: [{kind: "tool", tool: "trello_add_comment", arguments: {text: "read-only check"}}, MOVE_TO_REVIEW, {kind: "complete"}],
    responses: 2,
    assert: async (context, run) => {
      context.check.contains(responseFor(run, "trello_add_comment"), "trello_writes_disabled", "the comment call returns trello_writes_disabled");
      context.check.contains(responseFor(run, "trello_move_current_card"), "trello_writes_disabled", "the move call returns trello_writes_disabled");
      context.check.that(!comments(context, run.cardId).some((text) => text.includes("read-only check")), "the card has no comment from Codex");
      context.check.that((await cardList(context, run.service, run.cardId)) !== "Human Review", "the card never reaches Human Review");
    },
  },
};

async function toolFixture(context: ScenarioContext, fixture: ToolFixture): Promise<Map<string, CaseRun>> {
  return context.fixture(fixture.id, async () => {
    const cases = Object.entries(CASES).filter(([id, entry]) => entry.fixture.id === fixture.id && context.selected.has(id));
    const service = await serviceBoard(context, fixture.id, {edits: [...fixture.edits, [["agent", "max_concurrent_agents"], 4]]});
    const cards = new Map<string, string>();
    for (const [id, entry] of cases) {
      script(service, id, entry.steps);
      cards.set(id, await addCard(context, service, id, "Bug bash tool policy check. No repository is needed."));
    }
    await startWorker(context, service);
    const runs = new Map<string, CaseRun>();
    try {
      for (const [id, entry] of cases) {
        await waitFor(`${entry.responses} scripted responses for ${id}`, () => toolResponses(service.codex, id).length >= entry.responses, 90_000);
      }
      // Let the final moves and Trello writes from the completed turns land before asserting.
      await sleep(2_000);
      for (const [id] of cases) {
        runs.set(id, {service, cardId: cards.get(id) as string, responses: toolResponses(service.codex, id)});
      }
    } finally {
      await stopWorker(context, service);
    }
    return runs;
  });
}

function toolCase(id: string, entry: ToolCase): ScenarioRunner {
  return async (context) => {
    const run = (await toolFixture(context, entry.fixture)).get(id);
    if (run === undefined) {
      throw new Error(`the ${entry.fixture.id} fixture did not run ${id}`);
    }
    context.evidence("tool-responses.json", JSON.stringify(run.responses, null, 2));
    await entry.assert(context, run);
    return `fake Codex made ${run.responses.length} scripted request(s); responses and Trello state matched the ${entry.fixture.id} policy`;
  };
}

export const TRELLO_TOOLS_SCENARIOS: ScenarioRegistry = Object.fromEntries(
  Object.entries(CASES).map(([id, entry]) => [id, toolCase(id, entry)]),
);
