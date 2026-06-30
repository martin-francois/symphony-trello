# Issue Drafts And Reporting

## Issue draft policy

Do not create or update issues in `TARGET_REPO`. Create sanitized local issue drafts under `ISSUE_DRAFT_DIR`. The operator can review and file them manually.

Publishing reviewed drafts is intentionally out of scope for this skill. Do not add a GitHub publication phase to `live-bugbash`. After the run, use the separate `$publish-bugbash-issues` skill or the prompt template in `CONTRIBUTING.md` to post reviewed drafts: create new issues for unique confirmed bugs and comment on existing duplicate issues with sanitized reproduction details.

For every suspected bug:

1. Reproduce it at least twice unless repetition is unsafe, expensive, or the bug is credibly intermittent.
2. Classify severity, area, integration profile, and confidence.
3. Search existing local issue drafts and prior-run issue drafts for duplicates.
4. In `GITHUB_MODE=real-sandbox`, upstream issues may be searched read-only by command, option, error text, feature name, Trello state, access mode, and suspected code path. Do not write to upstream issues.
5. If a duplicate exists, write a local duplicate-detail draft instead of commenting on GitHub.
6. If unique, write a local issue draft.
7. Keep speculative concerns in `final-report.md`, not as issue drafts.

## Current issue template

Before writing each issue draft, read `.github/ISSUE_TEMPLATE/bug_report.yml` from the current checkout. Treat it as the source of truth for title prefix, default labels, fields, required fields, and instructions.

Record either the Git blob SHA or a local SHA-256 hash of that template in each issue draft metadata block. Do not copy the template into this skill.

## Draft file format

Each draft should be a Markdown file with a short kebab-case filename, for example:

```text
issues/bug-setup-local-danger-full-access-ignores-run-scoped-home.md
```

Start with metadata:

```yaml
---
title: "bug: <concise title>"
labels_to_add:
  - bug
  - "source: live-bugbash"
  - "severity: high"
  - "area: installer-onboarding"
confidence: confirmed | intermittent | needs-real-confirmation | needs-triage
run_id: <RUN_ID>
target_repo: martin-francois/symphony-trello
target_commit: <commit SHA>
issue_template_path: .github/ISSUE_TEMPLATE/bug_report.yml
issue_template_sha_or_hash: <blob SHA or sha256>
integration_profile:
  trello: fake | real
  codex: fake | real
  github: fake | real-sandbox
host_profile: standard | hardened
hardened_host_opt_in: <none | natural language phrase | explicit parameter>
real_integration_opt_in: <none | explicit parameter | natural language phrase>
needs_real_confirmation: true | false
requires_hardened_host_confirmation: true | false
duplicate_searches:
  - <query 1>
  - <query 2>
existing_issue_match: null
codex_access_mode: <workspace-write | extra-writable-roots | allow-all-paths | danger-full-access | dangerously-bypass | mixed | not-applicable>
dangerously_bypass_used: <true | false>
external_isolation: <host-run-scoped | hardened-host-run-scoped | container | vm | disposable-user | fake-only | not-applicable>
---
```

Then render the body according to the current issue template. Use the template's current field labels and required fields. Do not include secrets, account names, private Trello board links, private host paths, raw tokens, Codex auth files, or screenshots/logs containing private values.

Suggested label vocabulary for local drafts:

- `bug`
- `source: live-bugbash`
- `severity: critical`, `severity: high`, `severity: medium`, `severity: low`
- `confidence: confirmed`, `confidence: intermittent`, `confidence: needs-real-confirmation`, `confidence: needs-triage`
- `area: integration-mode`
- `area: installer-onboarding`
- `area: local-cli-command`
- `area: running-service`
- `area: deployment-live-run`
- `area: trello`
- `area: codex-app-server`
- `area: github`
- `area: workflow-config`
- `area: parallelism`
- `area: safety-boundary`

## Fake versus real confidence

Use `confidence: confirmed` when the bug is in deterministic local behavior, generated workflow output, CLI behavior, installer behavior, validation, parsing, prompt rendering, access-mode handling, safety-boundary handling, or fake service behavior that directly exercises product code.

Use `confidence: needs-real-confirmation` when the suspected bug depends on external-service behavior that the fake may not model accurately.

Do not claim `real Trello`, `real Codex`, or `real GitHub` reproduction unless that integration mode was actually real for the reproduction. Do not claim hardened-host dangerous-access reproduction unless `HOST_PROFILE=hardened` was active and the dangerous command actually ran.

## Final report

The final report must be safe to read and share internally after private-context scanning. Include:

- run metadata and stop reason
- integration modes, host profile, real opt-in source, and hardened-host opt-in source
- fake services used and their limitations
- real artifacts created and cleanup status, when any real mode was enabled
- coverage summary
- issue drafts and duplicate drafts
- findings needing real confirmation
- scoped blockers
- global blockers
- cleanup summary
- private-context scan result
- recommended next runs, including which rows would benefit from real integrations if the current run used fakes and which rows would benefit from hardened-host execution if the current run used the standard host profile
