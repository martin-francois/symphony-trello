import {spawnSync} from "node:child_process";

/** Runs one README demo render step with inherited output and fails on a nonzero exit. */
export function runRenderStep(command: string, args: readonly string[], cwd: string): void {
  console.log(`\n$ ${command} ${args.join(" ")}`);
  // Windows installs pnpm as a .cmd shim, which spawnSync cannot run without a shell.
  const windowsPnpm = process.platform === "win32" && command === "pnpm";
  const result = spawnSync(
    windowsPnpm ? "cmd.exe" : command,
    windowsPnpm ? ["/d", "/s", "/c", "pnpm.cmd", ...args] : args,
    {cwd, stdio: "inherit"},
  );
  if (result.status !== 0) {
    const detail = result.error?.message;
    throw new Error(
      `${command} ${args[0] ?? ""} failed with status ${result.status}`
      + (detail === undefined ? "" : `: ${detail}`),
    );
  }
}
