import {promptsMentioning} from "../lib/codex.ts";
import type {ScenarioContext, ScenarioRegistry} from "../lib/scenario.ts";
import {sleep, waitFor} from "../lib/symphony.ts";
import {
  addCard,
  cardList,
  serviceBoard,
  startWorker,
  state,
  stateEntries,
  stopWorker,
  waitForCardIn,
  waitForPrompt,
  type ServiceBoard,
} from "./support.ts";

const QUESTION = "Answer with one sentence. No repository is needed.";
const STATUS_MARKER = "## Symphony Prerequisite Status";
/** Several 1-second poll cycles, enough for the scheduler to have dispatched a card it considered eligible. */
const OBSERVATION_WINDOW_MS = 6_000;

async function cardUrl(context: ScenarioContext, service: ServiceBoard, scenarioId: string, list: string, title: string): Promise<{id: string; url: string}> {
  const card = await context.trello().createCard(service.board, list, `${scenarioId}: ${title}`, QUESTION, scenarioId);
  return {id: card.id, url: card.url};
}

/** Watches a card for the observation window and reports whether it ever ran or retried. */
async function observeNeverDispatched(service: ServiceBoard, cardId: string): Promise<boolean> {
  const deadline = Date.now() + OBSERVATION_WINDOW_MS;
  while (Date.now() < deadline) {
    const current = await state(service);
    if ([...stateEntries(current, "running"), ...stateEntries(current, "retrying")].some((entry) => entry["card_id"] === cardId)) {
      return false;
    }
    await sleep(500);
  }
  return true;
}

async function statusComments(context: ScenarioContext, cardId: string): Promise<string[]> {
  return (await context.trello().comments(cardId)).filter((text) => text.startsWith(STATUS_MARKER));
}

async function mixedChecklist(context: ScenarioContext, secondItem: (dependencyUrl: string) => string): Promise<string> {
  const id = context.scenario.id;
  const service = await serviceBoard(context, id);
  const dependency = await cardUrl(context, service, id, "Done", "finished dependency");
  const target = await addCard(context, service, id, QUESTION, "Ready for Codex", "target");
  await context.trello().addChecklist(service.board, target, "Prerequisites", [dependency.url, secondItem(dependency.url)]);
  await startWorker(context, service);
  try {
    await waitFor("the managed prerequisite status comment", async () => (await statusComments(context, target)).length > 0, 60_000);
    const neverDispatched = await observeNeverDispatched(service, target);
    context.check.that(neverDispatched, "the state API never lists the target as running or retrying");
    context.check.equal(await cardList(context, service, target), "Ready for Codex", "the target card stays in Ready for Codex");
    context.check.equal(promptsMentioning(service.codex, `${id}: target`).length, 0, "fake Codex never sees the target card");
    const comments = await statusComments(context, target);
    context.evidence("status-comments.txt", comments.join("\n---\n"));
    context.check.equal(comments.length, 1, "exactly one managed prerequisite status comment exists");
    context.check.contains(comments[0] ?? "", "Status: waiting for prerequisites.", "the status comment says the card is waiting");
    context.check.contains(comments[0] ?? "", "Checklist mixes prerequisite references with notes or ordinary Trello references.", "the comment shows the mixed-checklist guidance");
    const items = await context.trello().checklistItems(target);
    context.check.that(items.some((item) => item.name === dependency.url && item.state === "incomplete"), "the exact URL item stays incomplete");
  } finally {
    await stopWorker(context, service);
  }
  return "the mixed checklist kept the target in Ready with one managed waiting comment and no dispatch";
}

async function exactPrerequisite(context: ScenarioContext): Promise<string> {
  const id = context.scenario.id;
  const service = await serviceBoard(context, id);
  const prerequisite = await cardUrl(context, service, id, "Blocked", "prerequisite");
  const target = await addCard(context, service, id, QUESTION, "Ready for Codex", "target");
  await context.trello().addChecklist(service.board, target, "Prerequisites", [prerequisite.url]);
  await startWorker(context, service);
  try {
    await waitFor("the waiting status comment", async () => (await statusComments(context, target)).some((text) => text.includes("waiting for prerequisites")), 60_000);
    context.check.that(await observeNeverDispatched(service, target), "the target waits while the prerequisite is open");
    context.check.equal(promptsMentioning(service.codex, `${id}: target`).length, 0, "fake Codex does not see the target while it waits");
    await context.trello().moveCard(service.board, prerequisite.id, "Done");
    await waitForPrompt(service, `${id}: target`);
    await waitForCardIn(context, service, target, "Human Review");
    const items = await context.trello().checklistItems(target);
    context.check.that(items.some((item) => item.name === prerequisite.url && item.state === "complete"), "the checklist item is complete after the prerequisite finished");
    const comments = await statusComments(context, target);
    context.evidence("status-comments.txt", comments.join("\n---\n"));
    context.check.equal(comments.length, 1, "exactly one managed prerequisite status comment exists");
    context.check.contains(comments[0] ?? "", "Status: prerequisites resolved.", "the status comment was updated in place to resolved");
  } finally {
    await stopWorker(context, service);
  }
  return "the target waited for its exact prerequisite, then synced the checklist item, resolved the status comment in place, and ran";
}

async function maxAgentsParallel(context: ScenarioContext): Promise<string> {
  const id = context.scenario.id;
  const service = await serviceBoard(context, id, {edits: [[["agent", "max_concurrent_agents"], 99]], sleepMs: 5_000});
  const cards: string[] = [];
  for (let index = 1; index <= 3; index++) {
    cards.push(await addCard(context, service, id, QUESTION, "Ready for Codex", `parallel ${index}`));
  }
  await startWorker(context, service);
  let peak = 0;
  try {
    const deadline = Date.now() + 60_000;
    while (Date.now() < deadline) {
      peak = Math.max(peak, stateEntries(await state(service), "running").length);
      const lists = await Promise.all(cards.map((card) => cardList(context, service, card)));
      if (lists.every((list) => list === "Human Review")) {
        break;
      }
      await sleep(300);
    }
    context.check.that(peak >= 2, `at least two cards run at the same time (peak ${peak})`);
    for (const card of cards) {
      context.check.equal(await cardList(context, service, card), "Human Review", "every card reaches Human Review");
    }
  } finally {
    await stopWorker(context, service);
  }
  return `three slow cards ran with a peak of ${peak} concurrent agents and all reached Human Review`;
}

async function requiredLabel(context: ScenarioContext): Promise<string> {
  const id = context.scenario.id;
  const service = await serviceBoard(context, id, {edits: [[["tracker", "required_labels"], ["bugbash-ready"]]]});
  const label = await context.trello().request<{id: string}>("POST", "labels", {idBoard: service.board.id, name: "bugbash-ready", color: "green"});
  const labelled = await addCard(context, service, id, QUESTION, "Ready for Codex", "labelled");
  await context.trello().request("POST", `cards/${labelled}/idLabels`, {value: label.id});
  const unlabelled = await addCard(context, service, id, QUESTION, "Ready for Codex", "unlabelled");
  await startWorker(context, service);
  try {
    await waitForCardIn(context, service, labelled, "Human Review");
    await sleep(3_000);
    context.check.equal(await cardList(context, service, unlabelled), "Ready for Codex", "the unlabelled card stays in Ready for Codex");
    context.check.equal(promptsMentioning(service.codex, `${id}: unlabelled`).length, 0, "fake Codex never sees the unlabelled card");
  } finally {
    await stopWorker(context, service);
  }
  return "only the card with the required label was dispatched";
}

async function relationshipContext(context: ScenarioContext, repeated: boolean): Promise<string> {
  const id = context.scenario.id;
  const service = await serviceBoard(context, id);
  const related: Array<{where: string; id: string; url: string}> = [];
  for (const where of ["description", "comment", "checklist", "attachment"]) {
    related.push({where, ...(await cardUrl(context, service, id, "Done", `related via ${where}`))});
  }
  const byWhere = (where: string) => related.find((entry) => entry.where === where) as {id: string; url: string};
  const description = [
    `See [the description reference](${byWhere("description").url}) for background.`,
    repeated ? `The comment card is also relevant: [again](${byWhere("comment").url}).` : "",
    "",
    QUESTION,
  ].join("\n");
  const target = await addCard(context, service, id, description, "Ready for Codex", "target");
  await context.trello().request("POST", `cards/${target}/actions/comments`, {text: `Related: ${byWhere("comment").url}`});
  await context.trello().addChecklist(service.board, target, "Notes", [`[checklist reference](${byWhere("checklist").url})`]);
  await context.trello().request("POST", `cards/${target}/attachments`, {url: byWhere("attachment").url, name: "attachment reference"});
  await startWorker(context, service);
  let prompt: string;
  try {
    prompt = await waitForPrompt(service, `${id}: target`);
  } finally {
    await stopWorker(context, service);
  }
  context.evidence("prompt.txt", prompt);
  const block = referenceBlock(prompt);
  const referenceLines = block.filter((line) => line.startsWith("- source="));
  const expectedSource: Readonly<Record<string, string>> = {description: "description", comment: "comment", checklist: "checklist:Notes", attachment: "attachment"};
  for (const entry of related) {
    const shortLink = entry.url.split("/")[4] ?? entry.id;
    const lines = referenceLines.filter((line) => line.includes(`url=https://trello.com/c/${shortLink} `));
    if (repeated) {
      // The comment card is also linked from the description; the first source wins and the
      // duplicate collapses into one line.
      context.check.equal(lines.length, 1, `the ${entry.where} card appears exactly once`);
    } else {
      context.check.that(
        lines.some((line) => line.startsWith(`- source=${expectedSource[entry.where]} `)),
        `the ${entry.where} reference appears in the relationship context with source ${expectedSource[entry.where]}`,
      );
    }
  }
  context.check.that(referenceLines.every((line) => (line.match(/source=/g) ?? []).length === 1), "no prompt line contains more than one card reference");
  if (repeated) {
    const stray = block.filter((line) => line.trim() !== "" && !line.startsWith("- source="));
    context.evidence("stray-reference-lines.txt", stray.join("\n"));
    context.check.equal(stray.length, 0, "every line of the reference list is one reference bullet");
  }
  return `the prompt listed ${referenceLines.length} relationship reference bullet(s)`;
}

/** Lines between the reference list heading and the review instructions that follow it. */
function referenceBlock(prompt: string): string[] {
  const start = prompt.indexOf("Trello card references found on this card:");
  if (start < 0) {
    return [];
  }
  const end = prompt.indexOf("Before editing, review this context", start);
  return prompt
    .slice(start, end < 0 ? undefined : end)
    .split("\n")
    .slice(1);
}

export const TRELLO_PREREQUISITES_SCENARIOS: ScenarioRegistry = {
  "mixed-prerequisite-checklist-live": (context) => mixedChecklist(context, () => "Keep this note outside prerequisite checklists."),
  "mixed-prerequisite-markdown": (context) => mixedChecklist(context, (url) => `[the same card as a Markdown link](${url})`),
  "prerequisite-status-comment-update-live": exactPrerequisite,
  "max-agents-99-parallel": maxAgentsParallel,
  "required-label-eligibility": requiredLabel,
  "relationship-context-description-comment-attachment-markdown-links": (context) => relationshipContext(context, false),
  "relationship-context-deduplication-and-rendering": (context) => relationshipContext(context, true),
};
