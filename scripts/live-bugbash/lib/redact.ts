/**
 * Turns harness text into public-safe text for progress, ledger, and report files. Scenarios already
 * write short summaries without private values; this pass is the last line of defense for values
 * that slip through from product output.
 */
export interface PrivatePath {
  path: string;
  label: string;
}

const TRELLO_URL = /https?:\/\/(?:www\.)?trello\.com\/[^\s)>"'`\]]+/gi;
const TRELLO_TOKEN = /\bATT[A-Za-z0-9_-]{20,}\b/g;
const LONG_HEX = /\b[0-9a-f]{32,}\b/gi;
const TRELLO_ID = /\b[0-9a-f]{24}\b/gi;
const EMAIL = /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g;
const ABSOLUTE_PATH =
  /(?<![\w.:/<-])(?:\/(?:home|Users|var|tmp|root|opt|private|mnt|srv|run|media|workspace|workspaces)(?:\/[^\s'"`)\]>,;]*)?|[A-Za-z]:\\[^\s'"`)\]>,;]*)/g;
const LOCAL_URL_PORT = /\b(127\.0\.0\.1|localhost):\d{2,5}\b/g;

export function publicText(text: string, privatePaths: readonly PrivatePath[] = []): string {
  let result = text;
  const longestFirst = [...privatePaths]
    .filter((entry) => entry.path.length > 1)
    .sort((left, right) => right.path.length - left.path.length);
  for (const entry of longestFirst) {
    result = result.split(entry.path).join(entry.label);
  }
  return result
    .replace(TRELLO_URL, "<trello-url>")
    .replace(TRELLO_TOKEN, "<secret>")
    .replace(LONG_HEX, "<secret>")
    .replace(TRELLO_ID, "<trello-id>")
    .replace(EMAIL, "<email>")
    .replace(ABSOLUTE_PATH, "<path>")
    .replace(LOCAL_URL_PORT, "$1:<port>");
}

/** Collapses a value to one Markdown-table-safe line; backslashes are escaped before pipes. */
export function tableCell(text: string): string {
  return text.replace(/\r?\n+/g, " ").replace(/\\/g, "\\\\").replace(/\|/g, "\\|").trim();
}
