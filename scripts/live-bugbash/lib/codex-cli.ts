import {writeFileSync} from "node:fs";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";
import type {RunRoot} from "./run-root.ts";

/** Setup's bare `codex` discovery must never fall through to the operator's authenticated CLI. */
export function fakeCodexEnvironment(run: RunRoot): NodeJS.ProcessEnv {
  const bin = run.dir("fakes", "codex", "bin");
  const launcher = join(bin, "codex");
  const source = fileURLToPath(new URL("../fake-codex-cli.mjs", import.meta.url));
  const quote = (value: string) => `'${value.replaceAll("'", `'"'"'`)}'`;
  writeFileSync(launcher, `#!/usr/bin/env bash\nexec ${quote(process.execPath)} ${quote(source)} "$@"\n`, {mode: 0o755});
  return {PATH: `${bin}${delimiter}${process.env["PATH"] ?? ""}`, CODEX_HOME: run.dir("state", "fake-codex-home")};
}
