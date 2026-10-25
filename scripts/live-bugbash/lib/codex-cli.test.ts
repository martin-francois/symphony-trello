import assert from "node:assert/strict";
import {existsSync, mkdtempSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import test from "node:test";
import {RunRoot} from "./run-root.ts";
import {runCommand, sandboxEnvironment} from "./symphony.ts";

test("fake discovery shadows an operator Codex CLI and never reads its authentication home", async () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "bugbash-fake-discovery-"));
  const run = new RunRoot(directory, "discovery");
  run.create();
  const called = join(directory, "operator-called");
  writeFileSync(join(directory, "codex"), `#!/bin/sh\ntouch '${called}'\nexit 93\n`, {mode: 0o755});
  writeFileSync(join(directory, "auth.json"), '{"synthetic":"operator"}');
  const previousPath = process.env["PATH"];
  const previousHome = process.env["CODEX_HOME"];
  process.env["PATH"] = `${directory}${delimiter}${previousPath ?? ""}`;
  process.env["CODEX_HOME"] = directory;
  try {
    // when
    const env = sandboxEnvironment(run);
    const version = await runCommand("codex", ["--version"], {cwd: run.root, env});
    const auth = await runCommand("codex", ["login", "status"], {cwd: run.root, env});
    const models = await runCommand("codex", ["app-server"], {
      cwd: run.root, env,
      input: '{"id":1,"method":"initialize"}\n{"method":"initialized"}\n{"id":2,"method":"model/list"}\n',
    });

    // then
    assert.equal(version.status, 0);
    assert.match(version.stdout, /live-bugbash-fake/);
    assert.equal(auth.status, 0);
    assert.equal(models.status, 0);
    const replies = models.stdout.trim().split("\n").map((line: string) => JSON.parse(line));
    assert.equal(replies[1].result.data[0].model, "fake-model");
    assert.equal(existsSync(called), false);
    assert.notEqual(env["CODEX_HOME"], directory);
    assert.equal(existsSync(join(env["CODEX_HOME"]!, "auth.json")), false);
  } finally {
    if (previousPath === undefined) delete process.env["PATH"];
    else process.env["PATH"] = previousPath;
    if (previousHome === undefined) delete process.env["CODEX_HOME"];
    else process.env["CODEX_HOME"] = previousHome;
    rmSync(directory, {recursive: true, force: true});
  }
});


for (const input of ["{", "null", "[]", "42", '"text"']) {
  test(`fake discovery rejects malformed request ${input} without a stack trace`, async () => {
    const directory = mkdtempSync(join(tmpdir(), "bugbash-fake-malformed-"));
    const run = new RunRoot(directory, "malformed");
    run.create();
    try {
      const result = await runCommand("codex", ["app-server"], {
        cwd: run.root, env: sandboxEnvironment(run), input: `${input}\n`,
      });
      assert.equal(result.status, 2);
      assert.equal(result.stdout, "");
      assert.equal(result.stderr, "cannot parse fake Codex request: expected one JSON object per line\n");
    } finally {
      rmSync(directory, {recursive: true, force: true});
    }
  });
}

test("default discovery cannot inherit GitHub tokens or the operator config directory", () => {
  // given
  const directory = mkdtempSync(join(tmpdir(), "bugbash-github-discovery-"));
  const run = new RunRoot(directory, "github-discovery");
  run.create();
  const variables = ["GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GH_CONFIG_DIR"];
  const previous = new Map(variables.map((name) => [name, process.env[name]]));
  for (const name of variables) process.env[name] = `synthetic-${name}`;
  try {
    // when
    const env = sandboxEnvironment(run);

    // then
    for (const name of variables.slice(0, -1)) assert.equal(env[name], undefined);
    assert.equal(env["GH_CONFIG_DIR"], run.path("installer-sandboxes", "gh-config"));
  } finally {
    for (const [name, value] of previous) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
    rmSync(directory, {recursive: true, force: true});
  }
});
