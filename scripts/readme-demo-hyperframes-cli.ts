/**
 * Runs the pinned HyperFrames CLI in docs/demo for composition work, so the maintenance commands in
 * docs/demo/README.md never repeat the version that scripts/readme-demo-hyperframes.ts owns.
 *
 * Usage: node scripts/readme-demo-hyperframes-cli.ts <lint|check|preview|...> [options]
 */

import {dirname, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {runHyperframes} from "./readme-demo-hyperframes.ts";

const demoDir = join(resolve(dirname(fileURLToPath(import.meta.url)), ".."), "docs", "demo");
const cliArgs = process.argv.slice(2);

if (cliArgs.length === 0) {
  console.error("Usage: node scripts/readme-demo-hyperframes-cli.ts <lint|check|preview|...> [options]");
  process.exitCode = 2;
} else {
  try {
    runHyperframes(cliArgs, demoDir);
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
