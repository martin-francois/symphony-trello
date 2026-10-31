import {realpathSync} from "node:fs";
import {fileURLToPath} from "node:url";

/** True when the module at `moduleUrl` is the script Node was started with. Node resolves
 * symlinks in `import.meta.url` but not in `process.argv[1]`, so a plain comparison is false when
 * the script is launched through a symlinked directory, and the script then exits 0 without doing
 * anything. Both sides are compared as real paths. */
export function isEntryPoint(moduleUrl: string): boolean {
  const launched = process.argv[1];
  if (launched === undefined || launched === "") {
    return false;
  }
  try {
    return realpathSync(launched) === realpathSync(fileURLToPath(moduleUrl));
  } catch {
    // `node -e`, `node -` and similar starts have no launched file, so no module is the entry.
    return false;
  }
}
