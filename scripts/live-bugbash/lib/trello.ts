import type {RunRoot} from "./run-root.ts";

export const REAL_TRELLO_ENDPOINT = "https://api.trello.com/1";
/** Tolerance between this host's clock and Trello's when comparing board creation times. */
const CLOCK_SKEW_SECONDS = 300;

/** Trello ids are MongoDB ObjectIds: the first eight hex digits are the creation time in seconds. */
export function boardCreatedAt(id: string): number | null {
  return /^[0-9a-f]{24}$/i.test(id) ? Number.parseInt(id.slice(0, 8), 16) : null;
}

/** The recommended Trello-only lists, in board order; also the lists of a standard disposable board. */
export const STANDARD_LISTS = ["Ready for Codex", "In Progress", "Blocked", "Human Review", "Done"] as const;

export interface TrelloCredentials {
  key: string;
  token: string;
}

export interface CreatedBoard {
  id: string;
  name: string;
  shortLink: string;
  url: string;
  lists: Record<string, string>;
}

export interface RegisteredBoard {
  id: string;
  name: string;
  scenario: string;
  url: string;
}

export class TrelloApiError extends Error {
  override name = "TrelloApiError";
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/**
 * Minimal Trello REST client for disposable run-scoped resources. Credentials travel in the OAuth
 * Authorization header, never in URLs, so request logs cannot leak them. Every board is registered in
 * `created-trello-boards.jsonl` before anything else touches it, and cards can only be created on
 * registered boards, which keeps every write run-owned.
 */
export class TrelloApi {
  readonly endpoint: string;
  private readonly credentials: TrelloCredentials;
  private readonly run: RunRoot;

  constructor(run: RunRoot, endpoint: string, credentials: TrelloCredentials) {
    this.run = run;
    this.endpoint = endpoint.replace(/\/+$/, "");
    this.credentials = credentials;
  }

  async request<T>(method: string, path: string, params: Record<string, string> = {}): Promise<T> {
    const url = new URL(`${this.endpoint}/${path.replace(/^\/+/, "")}`);
    const init: RequestInit = {
      method,
      headers: {
        Accept: "application/json",
        Authorization: `OAuth oauth_consumer_key="${this.credentials.key}", oauth_token="${this.credentials.token}"`,
      },
      signal: AbortSignal.timeout(30_000),
    };
    if (method === "GET") {
      for (const [key, value] of Object.entries(params)) {
        url.searchParams.set(key, value);
      }
    } else {
      init.body = new URLSearchParams(params);
    }
    const response = await fetch(url, init);
    const text = await response.text();
    if (!response.ok) {
      throw new TrelloApiError(response.status, `Trello ${method} ${path.split("/")[0]} failed with HTTP ${response.status}`);
    }
    return (text === "" ? {} : JSON.parse(text)) as T;
  }

  async member(): Promise<{id: string}> {
    return this.request("GET", "members/me", {fields: "id"});
  }

  async visibleBoards(): Promise<Array<{id: string; name: string; closed: boolean}>> {
    return this.request("GET", "members/me/boards", {fields: "id,name,closed"});
  }

  /** Creates a board named `<run-id>-<scenario-id>` and registers it before creating any list. */
  async createBoard(
    scenarioId: string,
    lists: readonly string[] = STANDARD_LISTS,
    workspaceId: string | null = null,
    nameSuffix = "",
  ): Promise<CreatedBoard> {
    const name = `${this.run.runId}-${scenarioId}${nameSuffix}`;
    const params: Record<string, string> = {name, defaultLists: "false", defaultLabels: "false"};
    if (workspaceId !== null) {
      params["idOrganization"] = workspaceId;
    }
    const board = await this.request<{id: string; name: string; shortLink: string; url: string}>("POST", "boards", params);
    this.run.register("trelloBoards", {id: board.id, name: board.name, scenario: scenarioId, url: board.url});
    const created: CreatedBoard = {id: board.id, name: board.name, shortLink: board.shortLink, url: board.url, lists: {}};
    for (const listName of lists) {
      created.lists[listName] = (await this.createList(board.id, listName)).id;
    }
    return created;
  }

  async createList(boardId: string, name: string): Promise<{id: string}> {
    this.requireRegistered(boardId);
    return this.request("POST", "lists", {idBoard: boardId, name, pos: "bottom"});
  }

  async createCard(
    board: CreatedBoard,
    listName: string,
    name: string,
    desc: string,
    scenarioId: string,
  ): Promise<{id: string; shortLink: string; url: string}> {
    this.requireRegistered(board.id);
    const listId = board.lists[listName];
    if (listId === undefined) {
      throw new Error(`board ${board.name} has no list ${listName}`);
    }
    const card = await this.request<{id: string; shortLink: string; url: string}>("POST", "cards", {idList: listId, name, desc});
    this.run.register("trelloCards", {id: card.id, board: board.id, scenario: scenarioId, url: card.url});
    return card;
  }

  async card(cardId: string): Promise<{id: string; idList: string; closed: boolean}> {
    return this.request("GET", `cards/${encodeURIComponent(cardId)}`, {fields: "id,idList,closed"});
  }

  async comments(cardId: string): Promise<string[]> {
    const actions = await this.request<Array<{data?: {text?: string}}>>("GET", `cards/${encodeURIComponent(cardId)}/actions`, {
      filter: "commentCard",
    });
    return actions.map((action) => action.data?.text ?? "");
  }

  async moveCard(board: CreatedBoard, cardId: string, listName: string): Promise<void> {
    this.requireRegistered(board.id);
    const listId = board.lists[listName];
    if (listId === undefined) {
      throw new Error(`board ${board.name} has no list ${listName}`);
    }
    await this.request("PUT", `cards/${encodeURIComponent(cardId)}`, {idList: listId});
  }

  async addChecklist(board: CreatedBoard, cardId: string, name: string, items: readonly string[]): Promise<void> {
    this.requireRegistered(board.id);
    const checklist = await this.request<{id: string}>("POST", "checklists", {idCard: cardId, name});
    for (const item of items) {
      await this.request("POST", `checklists/${checklist.id}/checkItems`, {name: item, checked: "false"});
    }
  }

  async checklistItems(cardId: string): Promise<Array<{name: string; state: string}>> {
    const checklists = await this.request<Array<{checkItems: Array<{name: string; state: string}>}>>(
      "GET",
      `cards/${encodeURIComponent(cardId)}/checklists`,
    );
    return checklists.flatMap((checklist) => checklist.checkItems);
  }

  async boardClosed(boardId: string): Promise<boolean> {
    const board = await this.request<{closed: boolean}>("GET", `boards/${encodeURIComponent(boardId)}`, {fields: "closed"});
    return board.closed;
  }

  /** Archives a registered board. Unregistered ids are refused so cleanup can never touch other boards. */
  async archiveBoard(boardId: string): Promise<void> {
    this.requireRegistered(boardId);
    await this.request("PUT", `boards/${encodeURIComponent(boardId)}/closed`, {value: "true"});
  }

  /**
   * Registers boards that something else created for this run, such as `setup-local` or a scenario
   * that stopped before registering. A board qualifies only when its name starts with the run id
   * and its id says it was created after the run started, so a short custom run id cannot claim an
   * older board that happens to share the prefix.
   */
  async sweepRunBoards(scenarioId: string): Promise<number> {
    const known = new Set(this.registeredBoards().map((board) => board.id));
    const prefix = `${this.run.runId}-`;
    const earliest = Math.floor(this.run.startedAt.getTime() / 1000) - CLOCK_SKEW_SECONDS;
    let added = 0;
    for (const board of await this.visibleBoards()) {
      const created = boardCreatedAt(board.id);
      if (board.name.startsWith(prefix) && !known.has(board.id) && created !== null && created >= earliest) {
        this.run.register("trelloBoards", {id: board.id, name: board.name, scenario: scenarioId, url: ""});
        added += 1;
      }
    }
    return added;
  }

  registeredBoards(): RegisteredBoard[] {
    return this.run.entries<RegisteredBoard>("trelloBoards");
  }

  private requireRegistered(boardId: string): void {
    if (!this.registeredBoards().some((board) => board.id === boardId)) {
      throw new Error("refusing to touch a Trello board that this run did not register");
    }
  }
}
