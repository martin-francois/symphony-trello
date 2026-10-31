/**
 * Prints values that scripts/render-hyperframes-update needs from the TypeScript modules that
 * define them, so that Bash script repeats none of them.
 *
 * Usage:
 *   node scripts/readme-demo-values.ts verdict <log>
 *     `keep`, `re-upload`, or `none`: the README video verdict a render or comparison log holds.
 *   node scripts/readme-demo-values.ts verdict-line <log>
 *     The whole verdict line of such a log, for messages; nothing when it has none.
 *   node scripts/readme-demo-values.ts attachment-limit-bytes
 *     GitHub's video attachment limit in bytes; the render keeps demo.mp4 below it.
 */

import {readFileSync} from "node:fs";
import {GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES} from "./readme-demo-manifest.ts";
import {attachmentVerdictOf, verdictLineOf} from "./readme-demo-visual-change.ts";

const USAGE = "Usage: node scripts/readme-demo-values.ts "
  + "verdict <log> | verdict-line <log> | attachment-limit-bytes";

const [command, ...rest] = process.argv.slice(2);
const [log] = rest;
if (command === "verdict" && rest.length === 1 && log !== undefined) {
  console.log(attachmentVerdictOf(readFileSync(log, "utf8")) ?? "none");
} else if (command === "verdict-line" && rest.length === 1 && log !== undefined) {
  console.log(verdictLineOf(readFileSync(log, "utf8")) ?? "");
} else if (command === "attachment-limit-bytes" && rest.length === 0) {
  console.log(GITHUB_VIDEO_ATTACHMENT_LIMIT_BYTES);
} else {
  console.error(USAGE);
  process.exit(2);
}
