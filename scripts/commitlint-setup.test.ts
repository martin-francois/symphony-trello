import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import test, { type TestContext } from "node:test";
import { createFakeCommandEnvironment } from "./test-support/fake-command-environment.ts";

const LOCAL_TITLE_CHECK = ["scripts/commitlint-local", "title", "fix: test shared pins"];
const CI_SETUP = ["scripts/prepare-commitlint"];

// Runs the real setup scripts in a copied checkout. Only the package-manager boundary is stubbed, so
// the tests need no network.
function commitlintCheckout(t: TestContext) {
  const commands = createFakeCommandEnvironment("commitlint-setup-", {
    pnpm: `#!/bin/bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_COMMAND_LOG"
mkdir -p "$2/node_modules/.bin" "$2/node_modules/@commitlint/config-conventional"
printf '#!/bin/bash\\ncat >/dev/null\\n' >"$2/node_modules/.bin/commitlint"
chmod +x "$2/node_modules/.bin/commitlint"
`,
  });
  t.after(() => rmSync(commands.directory, { recursive: true, force: true }));
  const root = join(commands.directory, "checkout");
  mkdirSync(join(root, "scripts"), { recursive: true });
  for (const file of [
    "scripts/commitlint-local",
    "scripts/prepare-commitlint",
    "scripts/commitlint-versions",
    "commitlint.config.cjs",
    "commitlint.message.config.cjs",
  ]) {
    writeFileSync(join(root, file), readFileSync(new URL(`../${file}`, import.meta.url)));
  }
  const pins = join(root, "scripts/commitlint-versions");
  const currentPins = readFileSync(pins, "utf8");
  const cache = join(commands.directory, "cache");
  const runner = join(commands.directory, "runner");
  const githubEnv = join(commands.directory, "github-env");
  const env = commands.environment({ XDG_CACHE_HOME: cache, RUNNER_TEMP: runner, GITHUB_ENV: githubEnv });

  return {
    localHome: join(cache, "symphony-trello/commitlint"),
    runnerHome: join(runner, "commitlint"),
    githubEnv,
    pinPackages(cliVersion: string, configVersion: string) {
      writeFileSync(
        pins,
        currentPins
          .replace(/(@commitlint\/cli@)\d+\.\d+\.\d+/u, `$1${cliVersion}`)
          .replace(/(@commitlint\/config-conventional@)\d+\.\d+\.\d+/u, `$1${configVersion}`),
      );
    },
    run(args: readonly string[]) {
      const result = spawnSync("bash", args, { cwd: root, encoding: "utf8", env });
      assert.ifError(result.error);
      assert.equal(result.status, 0, `${args.join(" ")} failed: ${result.stderr}`);
    },
    pnpmCalls() {
      return commands.readLog().trim().split("\n").filter(Boolean);
    },
  };
}

test("CI commitlint setup installs the shared pins into the runner directory", (t) => {
  // given
  const checkout = commitlintCheckout(t);
  checkout.pinPackages("99.1.0", "99.1.0");

  // when
  checkout.run(CI_SETUP);

  // then
  assert.deepEqual(
    checkout.pnpmCalls(),
    [`--dir ${checkout.runnerHome} add --silent @commitlint/cli@99.1.0 @commitlint/config-conventional@99.1.0`],
    "CI setup must install exactly the shared pins into RUNNER_TEMP",
  );
  assert.deepEqual(
    readFileSync(checkout.githubEnv, "utf8").trim().split("\n"),
    [
      `COMMITLINT_BIN=${checkout.runnerHome}/node_modules/.bin/commitlint`,
      `COMMITLINT_TITLE_CONFIG=${checkout.runnerHome}/commitlint.config.cjs`,
      `COMMITLINT_MESSAGE_CONFIG=${checkout.runnerHome}/commitlint.message.config.cjs`,
    ],
    "CI setup must export the tool paths the commitlint workflow reads",
  );
});

test("local commitlint setup installs the shared pins", (t) => {
  // given
  const checkout = commitlintCheckout(t);
  checkout.pinPackages("99.1.0", "99.1.0");

  // when
  checkout.run(LOCAL_TITLE_CHECK);

  // then
  const manifest = JSON.parse(readFileSync(join(checkout.localHome, "package.json"), "utf8"));
  assert.deepEqual(
    manifest.dependencies,
    { "@commitlint/cli": "99.1.0", "@commitlint/config-conventional": "99.1.0" },
    "the local cache manifest must use the shared pins",
  );
});

test("local commitlint setup reuses its cache while the shared pins are unchanged", (t) => {
  // given
  const checkout = commitlintCheckout(t);
  checkout.pinPackages("99.1.0", "99.1.0");
  checkout.run(LOCAL_TITLE_CHECK);

  // when
  checkout.run(LOCAL_TITLE_CHECK);

  // then
  assert.equal(checkout.pnpmCalls().length, 1, "a second run with the same pins must not reinstall");
});

test("local commitlint setup reinstalls after the shared pins change", (t) => {
  // given
  const checkout = commitlintCheckout(t);
  checkout.pinPackages("99.1.0", "99.1.0");
  checkout.run(LOCAL_TITLE_CHECK);
  checkout.pinPackages("99.2.0", "99.1.0");

  // when
  checkout.run(LOCAL_TITLE_CHECK);

  // then
  assert.equal(checkout.pnpmCalls().length, 2, "a changed CLI pin must reinstall the local cache");
  const manifest = JSON.parse(readFileSync(join(checkout.localHome, "package.json"), "utf8"));
  assert.deepEqual(
    manifest.dependencies,
    { "@commitlint/cli": "99.2.0", "@commitlint/config-conventional": "99.1.0" },
    "the reinstall must use the changed pins",
  );
  assert.equal(
    readFileSync(join(checkout.localHome, ".pinned-versions"), "utf8"),
    "@commitlint/cli@99.2.0 @commitlint/config-conventional@99.1.0\n",
    "the cache marker must record the new pins",
  );
});

test("local setup rejects a foreign-owned cache before invoking pnpm", {
  skip: process.platform !== "linux" || process.getuid?.() === 0,
}, (t) => {
  // given
  const commands = createFakeCommandEnvironment("commitlint-foreign-cache-", {
    pnpm: '#!/bin/bash\nprintf "%s\\n" "$*" >>"$FAKE_COMMAND_LOG"\n',
  });
  t.after(() => rmSync(commands.directory, { recursive: true, force: true }));
  const cache = join(commands.directory, "symphony-trello");
  // Bash's -O follows symlinks. /proc is owned by root, and is not a writable cache even if the
  // guard regresses. This exercises the real ownership check without privileged fixture setup.
  symlinkSync("/proc", cache, "dir");

  // when
  const result = spawnSync("bash", ["scripts/commitlint-local", "title", "fix: reject foreign cache"], {
    encoding: "utf8",
    env: commands.environment({ XDG_CACHE_HOME: commands.directory }),
  });

  // then
  assert.ifError(result.error);
  assert.equal(result.status, 2, result.stderr);
  assert.ok(result.stderr.includes(`Cache directory is not owned by the current user: ${cache}`),
    "an existing foreign-owned cache must fail with the ownership diagnostic");
  assert.equal(commands.readLog(), "", "a rejected cache must never invoke the package manager");
});
