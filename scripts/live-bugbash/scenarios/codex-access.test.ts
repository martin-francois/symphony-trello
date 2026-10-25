import assert from "node:assert/strict";
import {readFileSync, writeFileSync} from "node:fs";
import {delimiter, join} from "node:path";
import test from "node:test";
import {GITHUB_AUTH_VARIABLES} from "../lib/symphony.ts";
import {CODEX_ACCESS_SCENARIOS} from "./codex-access.ts";
import {withScenario} from "./fixture.test-support.ts";

for (const id of ["direct-real-codex-sentinel", "direct-codex-app-server-json-rpc-protocol-probe"]) {
  for (const github of ["fake", "real-sandbox"] as const) {
    test(`${id} isolates HOME and only restores explicitly selected GitHub authentication (${github})`, async () => {
      await withScenario(id, async (context) => {
        // given
        context.options.codex = "real";
        context.options.github = github;
        const operator = context.run.dir("synthetic-operator");
        const record = join(operator, "child-environment.json");
        writeFileSync(join(operator, "auth.json"), '{"synthetic":"operator"}');
        writeFileSync(join(operator, "codex"), `#!/usr/bin/env node
const fs = require("node:fs");
const names = ["HOME", "XDG_CONFIG_HOME", "CODEX_HOME", "GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_CONFIG_DIR"];
fs.writeFileSync(process.env.TEST_CODEX_ENV, JSON.stringify(Object.fromEntries(names.map(name => [name, process.env[name]]))));
if (process.argv[2] === "exec") {
  const line = process.argv.at(-1).match(/text "([^"]+)"/)[1];
  fs.appendFileSync("sentinel.txt", line + "\\n");
} else {
  require("node:readline").createInterface({input: process.stdin}).on("line", line => {
    const request = JSON.parse(line);
    process.stdout.write(JSON.stringify({id: request.id, result: {userAgent: "synthetic"}}) + "\\n");
  });
}
`, {mode: 0o755});
        const changes = {
          HOME: operator, XDG_CONFIG_HOME: operator, CODEX_HOME: operator,
          PATH: `${operator}${delimiter}${process.env["PATH"] ?? ""}`, TEST_CODEX_ENV: record,
          ...Object.fromEntries(GITHUB_AUTH_VARIABLES.map((name) => [name, `synthetic-${name}`])),
        };
        const previous = new Map(Object.keys(changes).map((name) => [name, process.env[name]]));
        Object.assign(process.env, changes);
        try {
          // when
          await CODEX_ACCESS_SCENARIOS[id]!(context);

          // then
          assert.deepEqual(context.check.failures, []);
          const environment = JSON.parse(readFileSync(record, "utf8")) as Record<string, string | undefined>;
          assert.equal(environment["HOME"], context.run.path("installer-sandboxes", "home"));
          assert.equal(environment["XDG_CONFIG_HOME"], context.run.path("installer-sandboxes", "xdg-config"));
          assert.equal(environment["CODEX_HOME"], context.run.path("state", "codex-home"));
          for (const name of GITHUB_AUTH_VARIABLES) {
            const expected = github === "real-sandbox" ? `synthetic-${name}` : name === "GH_CONFIG_DIR" ? context.run.path("installer-sandboxes", "gh-config") : undefined;
            assert.equal(environment[name], expected);
          }
          assert.equal(readFileSync(join(operator, "auth.json"), "utf8"), '{"synthetic":"operator"}');
        } finally {
          for (const [name, value] of previous) {
            if (value === undefined) delete process.env[name];
            else process.env[name] = value;
          }
        }
      });
    });
  }
}
