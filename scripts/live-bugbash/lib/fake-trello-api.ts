import {randomBytes} from "node:crypto";
import {existsSync, readFileSync, writeFileSync} from "node:fs";
import {createServer, type IncomingMessage, type Server, type ServerResponse} from "node:http";
import type {AddressInfo} from "node:net";

/**
 * Stateful local stand-in for the Trello REST API subset that Symphony and the harness use. It lets
 * every Trello scenario run end to end without credentials. It is not a Trello emulator: anything it
 * does not model returns 404 and is recorded in `unsupportedRoutes` so a scenario can report the gap.
 */
export interface FakeBoard {
  id: string;
  name: string;
  closed: boolean;
  shortLink: string;
  url: string;
  shortUrl: string;
  idOrganization: string;
}

export interface FakeList {
  id: string;
  name: string;
  idBoard: string;
  closed: boolean;
  pos: number;
}

export interface FakeCheckItem {
  id: string;
  name: string;
  state: "complete" | "incomplete";
  pos: number;
}

export interface FakeChecklist {
  id: string;
  name: string;
  idCard: string;
  idBoard: string;
  checkItems: FakeCheckItem[];
}

export interface FakeAttachment {
  id: string;
  name: string;
  url: string;
}

export interface FakeLabel {
  id: string;
  name: string;
  color: string | null;
  idBoard: string;
}

export interface FakeCard {
  id: string;
  name: string;
  desc: string;
  idList: string;
  idBoard: string;
  closed: boolean;
  idShort: number;
  shortLink: string;
  shortUrl: string;
  url: string;
  labels: FakeLabel[];
  dateLastActivity: string;
  due: string | null;
  dueComplete: boolean;
  pos: number;
  attachments: FakeAttachment[];
  commentIds: string[];
}

export interface FakeComment {
  id: string;
  type: "commentCard";
  date: string;
  data: {text: string; card: {id: string}};
  memberCreator: {id: string; username: string; fullName: string};
}

export interface FakeMove {
  at: string;
  cardId: string;
  fromList: string;
  toList: string;
}

export interface FakeTrelloState {
  boards: Record<string, FakeBoard>;
  lists: Record<string, FakeList>;
  cards: Record<string, FakeCard>;
  comments: Record<string, FakeComment>;
  checklists: Record<string, FakeChecklist>;
  labels: Record<string, FakeLabel>;
  moves: FakeMove[];
  unsupportedRoutes: string[];
  requestCount: number;
}

export interface FakeTrelloCredentials {
  key: string;
  token: string;
}

/** Synthetic values that are obviously fake, so scanners and reviewers do not mistake them for secrets. */
export const FAKE_TRELLO_CREDENTIALS: FakeTrelloCredentials = {
  key: "fake-bugbash-trello-key",
  token: "fake-bugbash-trello-token",
};

const MEMBER = {id: "fake-member", username: "bugbash-fake-member", fullName: "Bug Bash Fake Member"};
const ORGANIZATION = {
  id: "fake-workspace",
  name: "bugbash-fake-workspace",
  displayName: "Bug Bash Fake Workspace",
  url: "https://trello.com/w/bugbash-fake-workspace",
};

type Query = Record<string, string>;
interface Reply {
  status: number;
  body: unknown;
}

export class FakeTrelloApi {
  readonly state: FakeTrelloState;
  private server: Server | null = null;
  private readonly stateFile: string | null;
  private readonly credentials: FakeTrelloCredentials;

  constructor(options: {stateFile?: string; credentials?: FakeTrelloCredentials} = {}) {
    this.stateFile = options.stateFile ?? null;
    this.credentials = options.credentials ?? FAKE_TRELLO_CREDENTIALS;
    this.state =
      this.stateFile !== null && existsSync(this.stateFile)
        ? (JSON.parse(readFileSync(this.stateFile, "utf8")) as FakeTrelloState)
        : emptyState();
  }

  async start(port = 0): Promise<string> {
    const server = createServer((request, response) => {
      void this.handle(request, response);
    });
    await new Promise<void>((resolve) => server.listen(port, "127.0.0.1", resolve));
    this.server = server;
    return this.endpoint;
  }

  get endpoint(): string {
    const address = this.server?.address() as AddressInfo | null | undefined;
    if (address === null || address === undefined) {
      throw new Error("fake Trello API is not running");
    }
    return `http://127.0.0.1:${address.port}/1`;
  }

  async stop(): Promise<void> {
    const server = this.server;
    this.server = null;
    if (server !== null) {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }

  private async handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const body = await readBody(request);
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    const query: Query = Object.fromEntries(url.searchParams.entries());
    Object.assign(query, parseBody(body, request.headers["content-type"]));
    const segments = url.pathname.split("/").filter((segment) => segment !== "").map(decodeURIComponent);
    if (segments[0] === "1") {
      segments.shift();
    }
    const method = request.method ?? "GET";
    let reply: Reply;
    if (!this.authorized(request, query)) {
      reply = {status: 401, body: "invalid key"};
    } else {
      try {
        reply = this.route(method, segments, query);
      } catch (error) {
        reply = {status: 400, body: {message: error instanceof Error ? error.message : String(error)}};
      }
    }
    this.state.requestCount += 1;
    if (reply.status === 404) {
      this.state.unsupportedRoutes.push(`${method} /${segments.map((segment) => (isOpaqueId(segment) ? ":id" : segment)).join("/")}`);
    }
    if (method !== "GET" || reply.status >= 400) {
      this.persist();
    }
    const payload = typeof reply.body === "string" ? reply.body : JSON.stringify(reply.body);
    response.writeHead(reply.status, {
      "content-type": typeof reply.body === "string" ? "text/plain" : "application/json",
      "content-length": Buffer.byteLength(payload),
    });
    response.end(payload);
  }

  private authorized(request: IncomingMessage, query: Query): boolean {
    const header = request.headers.authorization ?? "";
    const key = /oauth_consumer_key="([^"]*)"/.exec(header)?.[1] ?? query["key"];
    const token = /oauth_token="([^"]*)"/.exec(header)?.[1] ?? query["token"];
    return key === this.credentials.key && token === this.credentials.token;
  }

  persist(): void {
    if (this.stateFile !== null) {
      writeFileSync(this.stateFile, JSON.stringify(this.state));
    }
  }

  // Public helpers for scenarios that act as "a human on the board".

  board(idOrShortLink: string): FakeBoard | undefined {
    return this.state.boards[idOrShortLink] ?? Object.values(this.state.boards).find((board) => board.shortLink === idOrShortLink);
  }

  card(idOrShortLink: string): FakeCard | undefined {
    return this.state.cards[idOrShortLink] ?? Object.values(this.state.cards).find((card) => card.shortLink === idOrShortLink);
  }

  listName(listId: string): string | undefined {
    return this.state.lists[listId]?.name;
  }

  commentsOf(cardId: string): FakeComment[] {
    const card = this.card(cardId);
    return card === undefined
      ? []
      : card.commentIds.map((id) => this.state.comments[id]).filter((comment): comment is FakeComment => comment !== undefined);
  }

  checklistsOf(cardId: string): FakeChecklist[] {
    return Object.values(this.state.checklists).filter((checklist) => checklist.idCard === cardId);
  }

  private route(method: string, path: string[], query: Query): Reply {
    const [resource, id, child, grandchild] = path;
    switch (resource) {
      case "members":
        return this.members(method, id, child);
      case "boards":
        return id === undefined || id === "" ? this.createBoard(method, query) : this.boardRoute(method, id, child, grandchild, query);
      case "lists":
        return id === undefined ? this.createList(method, query) : this.listRoute(method, id, child, query);
      case "cards":
        return id === undefined ? this.createCard(method, query) : this.cardRoute(method, id, path.slice(2), query);
      case "actions":
        return id === undefined ? notFound() : this.actionRoute(method, id, child, query);
      case "checklists":
        return this.checklistRoute(method, id, child, query);
      case "labels":
        return method === "POST" && id === undefined ? this.createLabel(query) : notFound();
      default:
        return notFound();
    }
  }

  private members(method: string, id: string | undefined, child: string | undefined): Reply {
    if (method !== "GET" || id !== "me") {
      return notFound();
    }
    if (child === undefined) {
      return ok(MEMBER);
    }
    if (child === "organizations") {
      return ok([ORGANIZATION]);
    }
    if (child === "boards") {
      return ok(Object.values(this.state.boards));
    }
    return notFound();
  }

  private createBoard(method: string, query: Query): Reply {
    if (method !== "POST") {
      return notFound();
    }
    const id = objectId();
    const shortLink = shortLinkId();
    const name = required(query, "name");
    const board: FakeBoard = {
      id,
      name,
      closed: false,
      shortLink,
      url: `https://trello.com/b/${shortLink}/${slug(name)}`,
      shortUrl: `https://trello.com/b/${shortLink}`,
      idOrganization: query["idOrganization"] ?? ORGANIZATION.id,
    };
    this.state.boards[id] = board;
    return ok(board);
  }

  private boardRoute(method: string, id: string, child: string | undefined, grandchild: string | undefined, query: Query): Reply {
    const board = this.board(id);
    if (board === undefined) {
      return {status: 404, body: "The requested resource was not found."};
    }
    if (child === undefined) {
      if (method === "PUT") {
        if (query["name"] !== undefined) {
          board.name = query["name"];
        }
        if (query["closed"] !== undefined) {
          board.closed = query["closed"] === "true";
        }
      }
      return method === "GET" || method === "PUT" ? ok(board) : notFound();
    }
    if (child === "closed" && method === "PUT") {
      board.closed = query["value"] === "true";
      return ok(board);
    }
    if (method !== "GET") {
      return notFound();
    }
    if (child === "lists") {
      const lists = Object.values(this.state.lists)
        .filter((list) => list.idBoard === board.id)
        .filter((list) => query["filter"] !== "open" || !list.closed)
        .sort((left, right) => left.pos - right.pos);
      return ok(lists);
    }
    if (child === "cards") {
      const filter = grandchild ?? query["filter"] ?? "open";
      const cards = Object.values(this.state.cards)
        .filter((card) => card.idBoard === board.id)
        .filter((card) => filter === "all" || !card.closed)
        .sort((left, right) => left.pos - right.pos)
        .map((card) => this.cardJson(card, {}));
      return ok(cards);
    }
    if (child === "labels") {
      return ok(Object.values(this.state.labels).filter((label) => label.idBoard === board.id));
    }
    return notFound();
  }

  private createList(method: string, query: Query): Reply {
    if (method !== "POST") {
      return notFound();
    }
    const board = this.board(required(query, "idBoard"));
    if (board === undefined) {
      return {status: 400, body: "invalid value for idBoard"};
    }
    const siblings = Object.values(this.state.lists).filter((list) => list.idBoard === board.id);
    const list: FakeList = {
      id: objectId(),
      name: required(query, "name"),
      idBoard: board.id,
      closed: false,
      pos: siblings.reduce((max, sibling) => Math.max(max, sibling.pos), 0) + 16_384,
    };
    this.state.lists[list.id] = list;
    return ok(list);
  }

  private listRoute(method: string, id: string, child: string | undefined, query: Query): Reply {
    const list = this.state.lists[id];
    if (list === undefined) {
      return notFound();
    }
    if (child === undefined) {
      if (method === "PUT") {
        if (query["name"] !== undefined) {
          list.name = query["name"];
        }
        if (query["closed"] !== undefined) {
          list.closed = query["closed"] === "true";
        }
      }
      return ok(list);
    }
    if (child === "closed" && method === "PUT") {
      list.closed = query["value"] === "true";
      return ok(list);
    }
    if (child === "cards" && method === "GET") {
      return ok(
        Object.values(this.state.cards)
          .filter((card) => card.idList === list.id)
          .filter((card) => query["filter"] === "all" || !card.closed)
          .map((card) => this.cardJson(card, {})),
      );
    }
    return notFound();
  }

  private createCard(method: string, query: Query): Reply {
    if (method !== "POST") {
      return notFound();
    }
    const list = this.state.lists[required(query, "idList")];
    if (list === undefined) {
      return {status: 400, body: "invalid value for idList"};
    }
    const idShort = Object.values(this.state.cards).filter((card) => card.idBoard === list.idBoard).length + 1;
    const shortLink = shortLinkId();
    const name = query["name"] ?? "";
    const card: FakeCard = {
      id: objectId(),
      name,
      desc: query["desc"] ?? "",
      idList: list.id,
      idBoard: list.idBoard,
      closed: false,
      idShort,
      shortLink,
      shortUrl: `https://trello.com/c/${shortLink}`,
      url: `https://trello.com/c/${shortLink}/${idShort}-${slug(name)}`,
      labels: [],
      dateLastActivity: now(),
      due: null,
      dueComplete: false,
      pos: idShort * 16_384,
      attachments: [],
      commentIds: [],
    };
    for (const labelId of (query["idLabels"] ?? "").split(",").filter((value) => value !== "")) {
      const label = this.state.labels[labelId];
      if (label !== undefined) {
        card.labels.push(label);
      }
    }
    this.state.cards[card.id] = card;
    return ok(this.cardJson(card, {}));
  }

  private cardRoute(method: string, id: string, rest: string[], query: Query): Reply {
    const card = this.card(id);
    if (card === undefined) {
      return {status: 404, body: "The requested resource was not found."};
    }
    const [child, grandchild] = rest;
    if (child === undefined) {
      if (method === "GET") {
        return ok(this.cardJson(card, query));
      }
      if (method === "PUT") {
        if (query["idList"] !== undefined) {
          this.move(card, query["idList"]);
        }
        if (query["closed"] !== undefined) {
          card.closed = query["closed"] === "true";
        }
        if (query["name"] !== undefined) {
          card.name = query["name"];
        }
        if (query["desc"] !== undefined) {
          card.desc = query["desc"];
        }
        if (query["pos"] !== undefined && Number.isFinite(Number(query["pos"]))) {
          card.pos = Number(query["pos"]);
        }
        card.dateLastActivity = now();
        return ok(this.cardJson(card, {}));
      }
      if (method === "DELETE") {
        delete this.state.cards[card.id];
        return ok({});
      }
      return notFound();
    }
    if (child === "idList" && method === "PUT") {
      this.move(card, required(query, "value"));
      return ok(this.cardJson(card, {}));
    }
    if (child === "closed" && method === "PUT") {
      card.closed = query["value"] === "true";
      return ok(this.cardJson(card, {}));
    }
    if (child === "actions" && grandchild === "comments" && method === "POST") {
      const comment: FakeComment = {
        id: objectId(),
        type: "commentCard",
        date: now(),
        data: {text: required(query, "text"), card: {id: card.id}},
        memberCreator: MEMBER,
      };
      this.state.comments[comment.id] = comment;
      card.commentIds.push(comment.id);
      card.dateLastActivity = comment.date;
      return ok(comment);
    }
    if (child === "actions" && method === "GET") {
      return ok(this.commentsOf(card.id).reverse());
    }
    if (child === "checklists") {
      if (method === "GET") {
        return ok(this.checklistsOf(card.id));
      }
      if (method === "POST") {
        return ok(this.addChecklist(card, required(query, "name")));
      }
      return notFound();
    }
    if (child === "checkItem" && grandchild !== undefined && method === "PUT") {
      const item = this.checklistsOf(card.id)
        .flatMap((checklist) => checklist.checkItems)
        .find((candidate) => candidate.id === grandchild);
      if (item === undefined) {
        return notFound();
      }
      if (query["state"] === "complete" || query["state"] === "incomplete") {
        item.state = query["state"];
      }
      if (query["name"] !== undefined) {
        item.name = query["name"];
      }
      return ok(item);
    }
    if (child === "attachments") {
      if (method === "GET") {
        return ok(card.attachments);
      }
      if (method === "POST") {
        const url = required(query, "url");
        const attachment: FakeAttachment = {id: objectId(), name: query["name"] ?? url, url};
        card.attachments.push(attachment);
        return ok(attachment);
      }
      return notFound();
    }
    if (child === "idLabels" && method === "POST") {
      const label = this.state.labels[required(query, "value")];
      if (label === undefined) {
        return {status: 400, body: "invalid value for value"};
      }
      card.labels = [...card.labels.filter((existing) => existing.id !== label.id), label];
      return ok(card.labels.map((existing) => existing.id));
    }
    if (child === "idLabels" && grandchild !== undefined && method === "DELETE") {
      card.labels = card.labels.filter((existing) => existing.id !== grandchild);
      return ok({});
    }
    return notFound();
  }

  private actionRoute(method: string, id: string, child: string | undefined, query: Query): Reply {
    const comment = this.state.comments[id];
    if (comment === undefined) {
      return notFound();
    }
    if (method === "GET" && child === undefined) {
      return ok(comment);
    }
    if (method === "PUT" && (child === "text" || child === undefined)) {
      comment.data.text = query["value"] ?? query["text"] ?? comment.data.text;
      return ok(comment);
    }
    if (method === "DELETE" && child === undefined) {
      delete this.state.comments[id];
      for (const card of Object.values(this.state.cards)) {
        card.commentIds = card.commentIds.filter((commentId) => commentId !== id);
      }
      return ok({});
    }
    return notFound();
  }

  private checklistRoute(method: string, id: string | undefined, child: string | undefined, query: Query): Reply {
    if (id === undefined) {
      if (method !== "POST") {
        return notFound();
      }
      const card = this.card(required(query, "idCard"));
      return card === undefined ? {status: 400, body: "invalid value for idCard"} : ok(this.addChecklist(card, required(query, "name")));
    }
    const checklist = this.state.checklists[id];
    if (checklist === undefined) {
      return notFound();
    }
    if (child === undefined && method === "GET") {
      return ok(checklist);
    }
    if (child === "checkItems" && method === "POST") {
      const item: FakeCheckItem = {
        id: objectId(),
        name: required(query, "name"),
        state: query["checked"] === "true" ? "complete" : "incomplete",
        pos: (checklist.checkItems.length + 1) * 16_384,
      };
      checklist.checkItems.push(item);
      return ok(item);
    }
    return notFound();
  }

  private createLabel(query: Query): Reply {
    const board = this.board(required(query, "idBoard"));
    if (board === undefined) {
      return {status: 400, body: "invalid value for idBoard"};
    }
    const label: FakeLabel = {id: objectId(), name: query["name"] ?? "", color: query["color"] ?? null, idBoard: board.id};
    this.state.labels[label.id] = label;
    return ok(label);
  }

  private addChecklist(card: FakeCard, name: string): FakeChecklist {
    const checklist: FakeChecklist = {id: objectId(), name, idCard: card.id, idBoard: card.idBoard, checkItems: []};
    this.state.checklists[checklist.id] = checklist;
    return checklist;
  }

  private move(card: FakeCard, listId: string): void {
    const target = this.state.lists[listId];
    if (target === undefined || target.idBoard !== card.idBoard) {
      throw new Error("invalid value for idList");
    }
    this.state.moves.push({at: now(), cardId: card.id, fromList: this.listName(card.idList) ?? "", toList: target.name});
    card.idList = target.id;
    card.dateLastActivity = now();
  }

  private cardJson(card: FakeCard, query: Query): Record<string, unknown> {
    const comments = this.commentsOf(card.id).sort((left, right) => right.date.localeCompare(left.date));
    const checklists = this.checklistsOf(card.id);
    const json: Record<string, unknown> = {
      id: card.id,
      name: card.name,
      desc: card.desc,
      idList: card.idList,
      idBoard: card.idBoard,
      closed: card.closed,
      idShort: card.idShort,
      shortLink: card.shortLink,
      shortUrl: card.shortUrl,
      url: card.url,
      labels: card.labels,
      dateLastActivity: card.dateLastActivity,
      due: card.due,
      dueComplete: card.dueComplete,
      pos: card.pos,
      badges: {
        comments: comments.length,
        checkItems: checklists.reduce((count, checklist) => count + checklist.checkItems.length, 0),
        checkItemsChecked: checklists.reduce(
          (count, checklist) => count + checklist.checkItems.filter((item) => item.state === "complete").length,
          0,
        ),
        attachments: card.attachments.length,
      },
    };
    if (query["actions"] === "commentCard") {
      json["actions"] = comments.slice(0, Number(query["actions_limit"] ?? "50"));
    }
    if (query["attachments"] === "true") {
      json["attachments"] = card.attachments;
    }
    if (query["checklists"] === "all") {
      json["checklists"] = checklists;
    }
    return json;
  }
}

function emptyState(): FakeTrelloState {
  return {boards: {}, lists: {}, cards: {}, comments: {}, checklists: {}, labels: {}, moves: [], unsupportedRoutes: [], requestCount: 0};
}

function ok(body: unknown): Reply {
  return {status: 200, body};
}

function notFound(): Reply {
  return {status: 404, body: "The requested resource was not found."};
}

function required(query: Query, name: string): string {
  const value = query[name];
  if (value === undefined) {
    throw new Error(`missing ${name}`);
  }
  return value;
}

/** Like Trello's ids: the first eight hex digits are the creation time in seconds. */
function objectId(): string {
  return Math.floor(Date.now() / 1000).toString(16).padStart(8, "0") + randomBytes(8).toString("hex");
}

/** Trello short links are eight alphanumerics; Symphony's reference parser also expects a digit. */
function shortLinkId(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  const letters = Array.from(randomBytes(7), (byte) => alphabet[byte % alphabet.length]).join("");
  return `${letters}${randomBytes(1)[0]! % 10}`;
}

function slug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40) || "card";
}

function isOpaqueId(segment: string): boolean {
  return /^[0-9a-f]{24}$/.test(segment) || /^(?=.*\d)[A-Za-z0-9]{8}$/.test(segment);
}

function now(): string {
  return new Date().toISOString();
}

async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function parseBody(body: string, contentType: string | undefined): Query {
  if (body.trim() === "") {
    return {};
  }
  if (contentType?.includes("application/json")) {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    return Object.fromEntries(Object.entries(parsed).map(([key, value]) => [key, String(value)]));
  }
  return Object.fromEntries(new URLSearchParams(body).entries());
}
