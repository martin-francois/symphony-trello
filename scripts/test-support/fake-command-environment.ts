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
