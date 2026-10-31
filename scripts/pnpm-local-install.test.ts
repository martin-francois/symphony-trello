import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";

// Node.js 26 ships no Corepack, and pmOnFail: ignore stops pnpm from switching versions itself, so
// maintainers install the exact packageManager pnpm with this command (ADR 0077).
const PNPM_INSTALL_COMMAND = `npm install --global "$(node -p "require('./package.json').packageManager")"`;
// commitlint-local resolves its root with pwd -P, so compare against the resolved path.
const REPOSITORY_ROOT = realpathSync(fileURLToPath(new URL("../", import.meta.url)));

function developmentSetupSection() {
  const contributing = readFileSync(new URL("../CONTRIBUTING.md", import.meta.url), "utf8");
  const start = contributing.indexOf("\n## Development Setup\n");
  assert.notEqual(start, -1, "CONTRIBUTING.md must keep its Development Setup section");
  const end = contributing.indexOf("\n## ", start + 1);
  return contributing.slice(start, end === -1 ? undefined : end);
}

test("CONTRIBUTING.md installs the exact packageManager pnpm with npm", () => {
  // given
  const section = developmentSetupSection();

  // when
  const npmArgument = PNPM_INSTALL_COMMAND.replace("npm install --global ", "");
  const installedSpec = execFileSync("bash", ["-c", `printf '%s' ${npmArgument}`], {
    cwd: REPOSITORY_ROOT,
    encoding: "utf8",
  });

  // then
  assert.ok(
    section.includes(`\n   ${PNPM_INSTALL_COMMAND}\n`),
    `CONTRIBUTING.md Development Setup must tell maintainers to run: ${PNPM_INSTALL_COMMAND}`,
  );
  assert.match(
    installedSpec,
    /^pnpm@\d+\.\d+\.\d+$/u,
    "the install command must pass npm an exact pnpm version",
  );
});

test("commitlint-local prints the pnpm install command when pnpm is missing", (t) => {
  // given
  const bash = execFileSync("bash", ["-c", "command -v bash"], {encoding: "utf8"}).trim();
  const dirname = execFileSync("bash", ["-c", "command -v dirname"], {encoding: "utf8"}).trim();
  // A PATH with only dirname, which the script needs before its pnpm check, hides any installed pnpm.
  // HOME points at the same empty directory because the script reads it for its cache path.
  const path = mkdtempSync(join(tmpdir(), "pnpm-missing-"));
  t.after(() => rmSync(path, {recursive: true, force: true}));
  symlinkSync(dirname, join(path, "dirname"));

  // when
  const script = join(REPOSITORY_ROOT, "scripts/commitlint-local");
  const result = spawnSync(bash, [script, "title", "fix: no pnpm"], {
    encoding: "utf8",
    env: {HOME: path, PATH: path},
  });

  // then
  assert.ifError(result.error);
  assert.equal(result.status, 2, result.stderr);
  assert.ok(
    result.stderr.includes(`cd '${REPOSITORY_ROOT}' && ${PNPM_INSTALL_COMMAND}\n`),
    `commitlint-local must print the install command from CONTRIBUTING.md, got: ${result.stderr}`,
  );
});
