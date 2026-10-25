import assert from "node:assert/strict";
import {existsSync, mkdirSync, readFileSync, writeFileSync} from "node:fs";
import {join} from "node:path";
import test from "node:test";
import {scanPublicFiles} from "../lib/private-context.ts";
import {waitFor} from "../lib/symphony.ts";
import {CLEANUP_SCENARIOS} from "./cleanup.ts";
import {withScenario} from "./fixture.test-support.ts";

test("interruption cancels a running scenario scanner while final scanning remains available", async () => {
  await withScenario("private-context-scan-public-reports-and-drafts", async (context) => {
    // given
    const interruption = new AbortController();
    Object.assign(context, {signal: interruption.signal});
    context.run.writePublic("progress.md", "synthetic progress\n");
    const ready = context.run.path("state", "scanner-pid");
    const stopped = context.run.path("state", "scanner-stopped");
    const scripts = join(context.run.repoRoot, "scripts");
    mkdirSync(scripts);
    writeFileSync(join(scripts, "check-private-context"), `#!/usr/bin/env node
const fs = require("node:fs");
if (fs.existsSync(${JSON.stringify(stopped)})) process.exit(0);
process.on("SIGTERM", () => {
  fs.writeFileSync(${JSON.stringify(stopped)}, "stopped");
  process.exit(0);
});
fs.writeFileSync(${JSON.stringify(ready)}, String(process.pid));
setInterval(() => {}, 1000);
`, {mode: 0o755});
    const completed = CLEANUP_SCENARIOS[context.scenario.id]!(context).then(() => null, (error: unknown) => error);
    let pid: number | undefined;
    try {
      pid = await waitFor("the owned scanner PID", () => {
        const value = existsSync(ready) ? Number(readFileSync(ready, "utf8")) : 0;
        return Number.isInteger(value) && value > 0 ? value : null;
      }, 5_000, 10);

      // when
      interruption.abort();

      // then
      await waitFor("the interrupted scanner termination acknowledgement", () => existsSync(stopped) && readFileSync(stopped, "utf8") === "stopped", 2_000, 10);
      assert.equal(await completed, null);
      const finalScan = await scanPublicFiles(context.run, ["progress.md"]);
      assert.equal(finalScan.status, "clean", "final scans must remain usable after the scenario signal aborts");
    } finally {
      if (pid !== undefined) {
        try { process.kill(-pid, "SIGKILL"); } catch { /* The owned scanner already exited. */ }
      }
      await completed;
    }
  });
});
