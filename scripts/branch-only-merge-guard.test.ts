import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import test from "node:test";

const skill = readFileSync(new URL("../.codex/skills/land/SKILL.md", import.meta.url), "utf8");
const example = /## Branch-Only Merge[\s\S]*?```bash\n([\s\S]*?)```/.exec(skill)?.[1];
assert.ok(example, "branch-only merge example is present");

const gitStub = `
target_branch=main
card_branch=task
card_commit=approved
git() {
  case "$1" in
    fetch)
      fetched_branch=$3
      if [ "$FAILURE" = target_fetch ] && [ "$3" = main ]; then return 128; fi
      if [ "$FAILURE" = card_fetch ] && [ "$3" = task ]; then return 128; fi
      return 0 ;;
    ls-remote)
      if [ "$FAILURE" = remote_probe ]; then return 128; fi
      return "$PROBE_STATUS" ;;
    rev-parse)
      if [ "$FAILURE" = ref_resolution ]; then return 128; fi
      if [ "$FAILURE" = card_ref_resolution ] && [ "$fetched_branch" = task ]; then return 128; fi
      [ "$2" = --verify ] && [ "$3" = 'FETCH_HEAD^{commit}' ] || return 99
      if [ "$fetched_branch" = main ]; then printf target; else printf '%s' "$REMOTE_TIP"; fi ;;
    merge-base)
      printf 'ancestry:%s:%s' "$3" "$4"
      return "$ANCESTRY_STATUS" ;;
    *) return 99 ;;
  esac
}
`;

const scenarios = [
  { name: "unchanged unmerged branch", tip: "approved", probe: 0, ancestry: 1, failure: "", exit: 1, checked: true },
  { name: "advanced unreviewed tip", tip: "unreviewed", probe: 0, ancestry: 1, failure: "", exit: 78, checked: false },
  { name: "rewritten tip already on target", tip: "target", probe: 0, ancestry: 0, failure: "", exit: 78, checked: false },
  { name: "approved commit already merged", tip: "approved", probe: 0, ancestry: 0, failure: "", exit: 0, checked: true },
  { name: "deleted branch already merged", tip: "", probe: 2, ancestry: 0, failure: "", exit: 0, checked: true },
  { name: "deleted branch not merged", tip: "", probe: 2, ancestry: 1, failure: "", exit: 1, checked: true },
  { name: "unverifiable approved commit", tip: "approved", probe: 0, ancestry: 128, failure: "", exit: 128, checked: true },
  { name: "failed target fetch", tip: "approved", probe: 0, ancestry: 0, failure: "target_fetch", exit: 79, checked: false },
  { name: "failed remote probe", tip: "approved", probe: 0, ancestry: 0, failure: "remote_probe", exit: 79, checked: false },
  { name: "failed card fetch", tip: "approved", probe: 0, ancestry: 0, failure: "card_fetch", exit: 79, checked: false },
  { name: "failed target ref resolution", tip: "approved", probe: 0, ancestry: 0, failure: "ref_resolution", exit: 79, checked: false },
  { name: "failed card ref resolution", tip: "approved", probe: 0, ancestry: 0, failure: "card_ref_resolution", exit: 79, checked: false },
];

for (const scenario of scenarios) {
  test(`branch-only merge guard: ${scenario.name}`, () => {
    // The example must stop on failed verification without relying on the caller's errexit setting.
    const result = spawnSync("bash", ["-c", gitStub + example], {
      encoding: "utf8",
      timeout: 10_000,
      env: {
        ...process.env,
        REMOTE_TIP: scenario.tip,
        PROBE_STATUS: String(scenario.probe),
        ANCESTRY_STATUS: String(scenario.ancestry),
        FAILURE: scenario.failure,
      },
    });
    assert.equal(result.error, undefined);
    assert.equal(result.status, scenario.exit, result.stderr);
    assert.equal(result.stdout, scenario.checked ? "ancestry:approved:target" : "");
  });
}
