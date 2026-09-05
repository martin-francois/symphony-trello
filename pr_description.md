## Summary

- Problem: Issue #563 requires a complete Java-source reusable-API audit and every worthwhile behavior-preserving replacement.
- Why it matters: Small local utilities can duplicate exact JDK behavior and increase maintenance risk.
- What changed: Replaced a bounded character-search helper with Java 25 `String.indexOf`, added credential-URL redaction coverage, and documented durable exact-API audit guidance.
- What did not change: User-visible behavior, sanitizer semantics, dependencies, configuration, concurrency, and supported interfaces.

## Change Type

Choose all that apply.

- [ ] Bug fix
- [ ] Feature
- [x] Documentation
- [x] Refactor required for this change
- [ ] Chore / infrastructure

## Linked Issue

- Fixes #563
- Related #126, #573

## User-Visible Behavior

None.

## Compatibility Decision

Choose one option.

- [x] Compatible: no previously supported, working usage stops working
- [ ] Breaking: previously supported, working usage stops working
- [ ] Unsure: maintainer decision required

Why did you choose this option?
`Because the Java 25 bounded search preserves the removed helper's validated begin-inclusive, end-exclusive behavior and the regression test confirms credential-bearing URLs remain redacted.`

If you choose `Breaking`, please fill out the following:

What breaks:
`Breaks: `

Migration path:
`Migration: `

Alternative:
`Alternative: `

If this is not a breaking change, you can leave all three fields blank.

## Commit History in Main

Choose one option based on how this pull request should appear in `main`, not on the Git command used
to merge it.

- [x] Combine this pull request into one final commit. The branch commits are review steps and do not
      need to remain separate in `main`. (squash)
- [ ] Keep the individual commits. Each commit is independently meaningful and should remain visible
      in `main`. (rebase)

## Root Cause And Guardrail

For bug fixes or regressions, explain why the issue happened and what now prevents it from coming
back. For non-bug changes, write `N/A`.

- Root cause: N/A
- Test or guardrail added: Focused sanitizer coverage for a URL containing both user and password credentials; durable agent guidance requires exact Java-version API review.
- If no test was added, why not: N/A

## Validation

List the commands, manual checks, or live checks you ran. Include relevant failures that were fixed
during the PR.

- [x] `./mvnw -q spotless:check verify`
- [x] Installer or script checks, if touched
- [x] Documentation lint, if Markdown changed
- [x] Manual or live check, if behavior changed

Details:

```text
./mvnw -q -Dtest=SetupDiagnosticReporterTest#diagnosticOutputRedactsSecretsAndPrivateContext test
PASS
./mvnw -q -Dtest=SetupDiagnosticReporterTest test
PASS
./mvnw -q spotless:check verify
PASS (expected warning-path logs and Jazzer instrumentation only)
corepack pnpm dlx markdownlint-cli2 docs/agents/java-style.md
PASS: 93 files, 0 errors
scripts/check-private-context --worktree
PASS
scripts/check-private-context --git-range origin/main..HEAD
PASS
scripts/commitlint-local range origin/main HEAD
PASS: 0 problems, 0 warnings
Codex review --uncommitted review/fix loop
PASS: no actionable finding; reviewer also ran focused and full verification
Script checks: N/A; no installer or script files changed
```

## Human Verification

Describe what you tried manually and what result you saw. If the change cannot be tried manually,
explain why.

```text
Exercised the existing end-to-end sanitizer test with a credential-bearing HTTPS URL. The output
retained the safe host/repository context while omitting both the username and password. No live
service behavior changed.
```

## Review Checklist

- [x] Docs updated, or N/A
- [x] ADR updated for architecture decisions or tradeoffs, or N/A
- [x] PR title and every commit that will remain in `main` use Conventional Commits and are
      release-note ready
- [x] Compatibility and commit-history choices are complete. For a breaking change, the message
      reaching `main` contains both required breaking markers.
- [x] Live E2E/deployment notes included when behavior or deployment changed, or N/A
- [x] Redaction checked: no Trello credentials, Codex auth files, GitHub tokens, private board links,
      account names, private host paths, or deployment-specific paths

## AI Assistance (if used)

- [x] AI-assisted PR
- [x] I confirm I understand what the code does

<details>
<summary>AI prompts / session logs (optional, but super helpful)</summary>

```text
Prompt: Implement issue #563 in a dedicated worktree, in parallel with issue #565, and run the
Codex review/fix loop before completion.

Trace: The agent confirmed prerequisite #126 was merged/closed, inventoried all 258 tracked Java
paths, reviewed JDK 25 and directly declared dependency opportunities, implemented the single clear
replacement, added focused regression coverage and durable guidance, measured PMD 7.17.0 cognitive
complexity before/after, and prepared the complete audit outside the repository. Candidate areas
already owned by existing issues were not duplicated. No genuinely unowned new-dependency proposal
survived the benefit threshold.

Verification: focused sanitizer test, complete reporter test, full Maven verify, Markdown lint,
private-context scans, commit lint, Java Streams/Optional quality scans, and an independent Codex
review/fix loop all passed. No secrets or private environment paths are included in this trace.
```

</details>
