import {spawnSync} from "node:child_process";
import {chmodSync, mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";

export function createFakeCommandEnvironment(prefix: string, commands: Readonly<Record<string, string>>) {
  const directory = mkdtempSync(join(tmpdir(), prefix));
  const log = join(directory, "commands.log");
  writeFileSync(log, "");

  for (const [name, contents] of Object.entries(commands)) {
    const command = join(directory, name);
    writeFileSync(command, contents);
    chmodSync(command, 0o755);
  }

  return {
    directory,
    environment(variables: Readonly<Record<string, string>> = {}) {
      return {
        ...process.env,
        FAKE_COMMAND_LOG: log,
        PATH: `${directory}:${process.env.PATH}`,
        ...variables,
      };
    },
    readLog() {
      return readFileSync(log, "utf8");
    },
  };
}

export function runWithRetryingGh(
  script: string,
  prefix: string,
  failures: number,
  variables: Readonly<Record<string, string>> = {},
) {
  const fakeCommands = createFakeCommandEnvironment(prefix, {
    gh: `#!/bin/bash
set -euo pipefail
attempt="$(( $(cat "$ATTEMPTS_FILE") + 1 ))"
printf '%s' "$attempt" >"$ATTEMPTS_FILE"
printf '%s\\n' "$*" >>"$FAKE_COMMAND_LOG"
if ((attempt <= FAILURES)); then
  exit 1
fi
`,
    sleep: "#!/bin/bash\nexit 0\n",
  });
  const attemptsFile = join(fakeCommands.directory, "attempts");
  writeFileSync(attemptsFile, "0");

  const result = spawnSync("bash", [script], {
    encoding: "utf8",
    env: fakeCommands.environment({
      ATTEMPTS_FILE: attemptsFile,
      FAILURES: String(failures),
      GH_TOKEN: "test-token",
      GITHUB_REPOSITORY: "owner/project",
      ...variables,
    }),
  });

  return {
    attempts: Number.parseInt(readFileSync(attemptsFile, "utf8"), 10),
    log: fakeCommands.readLog(),
    result,
  };
}
