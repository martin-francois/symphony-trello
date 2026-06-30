---
name: publish-bugbash-issues
description: Explicit-only publisher for reviewed live-bugbash issue drafts. Creates new GitHub issues for unique confirmed findings and comments on duplicate issues with sanitized reproduction details. Never runs the bug bash.
---

# Publish bug-bash issues

Use this skill only after a person has reviewed local issue drafts created by `$live-bugbash` and
explicitly asks to post them to GitHub. The reviewer must also complete each selected draft's
`AI Assistance (if used)` human-review confirmation field before publication.

Do not use this skill for bug discovery. Do not run Symphony. Do not create Trello boards. Do not run
Codex app-server. Do not create GitHub sandbox repositories. This skill only reads local issue drafts
and posts reviewed results to the target GitHub repository.

Implicit invocation is disabled in `agents/openai.yaml`; do not select this skill accidentally.

## Normal command

```text
/goal Use $publish-bugbash-issues for RUN_ID=live-bugbash-20260629T103000Z. I reviewed the drafts and approve posting them to martin-francois/symphony-trello.
```

## Defaults

Unless explicitly overridden:

```text
TARGET_REPO=martin-francois/symphony-trello
RUN_ROOT=target/live-bugbash/<RUN_ID>
DRAFT_DIR=target/live-bugbash/<RUN_ID>/issues
DUPLICATE_ACTION=comment-with-reproduction-details
LABEL_POLICY=use-existing-labels-only
PUBLISH_SELECTION=confirmed-only
```

Before reading drafts or writing publication metadata, validate `RUN_ID` as one safe path segment:
ASCII letters, digits, `.`, `_`, and `-` only; not empty; not `.` or `..`; no slash, backslash,
control character, shell metacharacter, or percent-encoded separator. Resolve and normalize
`RUN_ROOT` and `DRAFT_DIR`; `DRAFT_DIR` must stay under `RUN_ROOT`, and default run roots must stay
under `target/live-bugbash/`. Stop if an override points outside the reviewed run-owned location.

## Publication guard

Posting to `TARGET_REPO` is forbidden unless the goal affirmatively contains both:

1. A clear posting instruction, such as `post these drafts`, `publish these reviewed drafts`, or
   `create GitHub issues from these drafts`.
2. A human-review approval phrase, such as `I reviewed the drafts` or `I have reviewed the drafts`.

The skill name, default prompt text, or a denied phrase such as `do not publish`, `do not comment`,
`preview only`, or `dry run` does not count as posting approval. If either signal is missing or
negated, do not publish, do not comment, and explain the exact command the operator can use after
reviewing the drafts. Never infer approval from the existence of issue drafts.

## Required references

Before posting anything, read:

1. `references/publication-runbook.md`
2. `../../../.github/ISSUE_TEMPLATE/bug_report.yml`
3. `../../../SECURITY.md`
4. `$RUN_ROOT/final-report.md`, if present
5. all selected drafts under the resolved `$DRAFT_DIR`

## Workflow

1. Resolve `RUN_ID`, `TARGET_REPO`, `RUN_ROOT`, and `DRAFT_DIR`.
2. Confirm the publication guard is satisfied.
3. Read the current bug report issue template and record its current SHA or hash.
4. Parse all selected local issue drafts.
5. Validate draft eligibility, confidence, security classification, sanitation, template coverage,
   labels, and duplicate status.
6. Search existing open and closed issues in `TARGET_REPO` for duplicates using read-only GitHub CLI
   commands.
7. For each unique eligible draft, create a new issue.
8. For each likely duplicate eligible draft, prepare one concise sanitized comment for the best
   matching issue. Post it only when the exact body was already reviewed or the operator explicitly
   approves that exact body before `gh issue comment`.
9. Apply only labels that already exist in the target repository. Do not create labels.
10. Write `publication-report.md` and `published-issues.json`.
11. Add created issue URLs, numbers, or duplicate-comment URLs to local publication metadata.

## Duplicate comment policy

When a reviewed confirmed draft appears to duplicate an existing issue:

- Do not create a second issue.
- Add one comment to the best matching existing issue.
- The comment must say that the bug bash also reproduced the issue.
- Include the target commit, sanitized environment, reproduction summary, expected and actual
  behavior, and any additional logs or suspected code path that are not already obvious from the
  issue.
- Keep the comment concise and sanitized. Do not paste secrets, account names, private Trello board
  links, private host paths, raw tokens, Codex auth files, or screenshots/logs containing private
  values.
- Avoid duplicate comments from the same `RUN_ID`. If this run already commented on the issue, record
  that and skip the second comment.
- Do not relabel, close, reopen, assign, edit, or otherwise modify the existing issue.

## Safety rules

- Never modify repository files in `TARGET_REPO`.
- Never push branches, create pull requests, edit settings, create labels, modify milestones, modify
  projects, edit workflows, change secrets, or change collaborators.
- Never edit, relabel, close, reopen, delete, or assign existing issues.
- Commenting on an existing issue is allowed only for likely duplicates of reviewed confirmed drafts
  and only with a sanitized duplicate-comment body the operator already reviewed or explicitly
  approved.
- Never post speculative findings, unsanitized findings, private data, secrets, account names,
  private Trello links, private host paths, raw logs containing secrets, or drafts marked
  `needs_real_confirmation` or `needs_hardened_host_confirmation`. Confirm and review those drafts
  first.
- Never create a public issue or public duplicate comment for a draft that may describe a
  vulnerability, leaked credential, credential-handling weakness, token exposure, authentication
  bypass, authorization bypass, or other SECURITY.md report. Skip public publication for that draft,
  record the private reporting route in `publication-report.md`, and tell the operator to follow
  `SECURITY.md`.
- If a command would exceed these permissions, stop that draft and record a scoped blocker.

## Output standard

The final response must include:

- run ID and draft directory
- number of drafts scanned
- number eligible
- number skipped and why
- number of new issues created
- number of duplicate issues commented on
- created issue URLs
- duplicate issue URLs and comment URLs
- labels applied and labels skipped
- path to `publication-report.md`
