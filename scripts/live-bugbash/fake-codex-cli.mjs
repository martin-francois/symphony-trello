import {createInterface} from "node:readline";

const [command, subcommand] = process.argv.slice(2);
if (command === "--version") {
  process.stdout.write("codex-cli 0.0.0-live-bugbash-fake\n");
} else if (command === "login" && subcommand === "status") {
  process.stdout.write("Logged in using synthetic harness authentication\n");
} else if (command === "app-server") {
  const lines = createInterface({input: process.stdin});
  for await (const line of lines) {
    let request;
    try {
      request = JSON.parse(line);
    } catch {
      process.stderr.write("cannot parse fake Codex request: expected one JSON object per line\n");
      process.exitCode = 2;
      break;
    }
    if (typeof request !== "object" || request === null || Array.isArray(request)) {
      process.stderr.write("cannot parse fake Codex request: expected one JSON object per line\n");
      process.exitCode = 2;
      break;
    }
    if (request.id === undefined) continue;
    const result = request.method === "initialize"
      ? {userAgent: "live-bugbash-fake-codex"}
      : request.method === "model/list"
        ? {data: [{id: "fake-model", model: "fake-model", displayName: "Harness model", isDefault: true,
          defaultReasoningEffort: "medium", supportedReasoningEfforts: [{reasoningEffort: "medium", description: "Harness effort"}]}], nextCursor: null}
        : null;
    const response = result === null
      ? {id: request.id, error: {code: -32601, message: "cannot execute fake Codex request: use a supported discovery method"}}
      : {id: request.id, result};
    process.stdout.write(`${JSON.stringify(response)}\n`);
  }
} else {
  process.stderr.write("cannot execute fake Codex command: use version, login status, or app-server discovery\n");
  process.exitCode = 2;
}
