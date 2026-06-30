# Publication Runbook

This runbook is for the `publish-bugbash-issues` skill. The bug bash creates local drafts. This
skill posts reviewed drafts to GitHub by creating new issues for unique bugs and commenting on
existing duplicate issues with additional reproduction evidence.

## Inputs

Defaults:

```text
TARGET_REPO=martin-francois/symphony-trello
RUN_ROOT=target/live-bugbash/<RUN_ID>
DRAFT_DIR=target/live-bugbash/<RUN_ID>/issues
DUPLICATE_ACTION=comment-with-reproduction-details
LABEL_POLICY=use-existing-labels-only
PUBLISH_SELECTION=confirmed-only
```

`RUN_ID` is required. Do not publish from an inferred run ID.

Validate `RUN_ID` as one safe path segment before resolving paths. Resolve and normalize `RUN_ROOT`
and `DRAFT_DIR` before reading drafts or writing reports; `DRAFT_DIR` must stay under `RUN_ROOT`, and
default run roots must stay under `target/live-bugbash/`. Stop on path traversal, separators,
controls, shell metacharacters, percent-encoded separators, or overrides outside the reviewed
run-owned location.

Posting requires both of these signals:

1. An explicit posting instruction, such as `post`, `publish`, or `create GitHub issues`.
2. A human-review approval phrase, such as `I reviewed the drafts` or `I have reviewed the drafts`.

If either signal is missing, do not publish and do not comment. Explain the command the operator can
use after reviewing the drafts.

## Draft eligibility

A draft is postable only when all of these are true:

- It is under `DRAFT_DIR` for the selected `RUN_ID`.
- Its front matter parses successfully.
- `confidence` is `confirmed` or the operator explicitly allowed another confidence value.
- `needs_real_confirmation` is absent or false.
- `needs_hardened_host_confirmation` is absent or false.
- `labels_to_add` is present.
- The issue body follows the current `.github/ISSUE_TEMPLATE/bug_report.yml` structure well enough
  for a maintainer to review it.
- The issue body includes the template's AI-assistance fields with the AI-assisted issue disclosed
  and human-review confirmation completed.
- The draft is not a suspected vulnerability, leaked credential, credential-handling weakness, token
  exposure, authentication bypass, authorization bypass, or other report covered by `SECURITY.md`.
- The front matter, title, labels, duplicate-search queries, body, and any generated GitHub command
  arguments are sanitized and do not contain Trello API keys, Trello tokens, Codex auth files, GitHub
  tokens, private Trello board links, account names, private host paths, deployment-specific host
  paths, screenshots/logs containing those values, or unrelated host/private data.

Skip ineligible drafts and explain why in `publication-report.md`. For SECURITY.md-covered drafts,
do not create a public issue and do not comment on a public issue; record that the operator must use
the repository's private security-reporting route instead.

## Template validation

Before posting, read `.github/ISSUE_TEMPLATE/bug_report.yml` from the current checkout. Treat it as
the source of truth for issue body expectations. Do not copy the template into this skill.

For each draft, compare the recorded template SHA or hash to the current template. A mismatch does
not automatically block posting, but Codex must check that the already-reviewed draft still satisfies
the current required fields. If it does not, skip it and record that the draft needs repair and a new
human review before publication. Do not publish a draft that Codex modified after the approval
phrase was given.

## Duplicate search

Before posting each eligible draft, search existing open and closed issues in `TARGET_REPO` using
several focused sanitized queries. Include at least:

- exact error text or exception class
- affected command, route, installer option, workflow key, or API endpoint
- feature or component name
- suspected root cause
- affected `SPEC.md` section if known

Use read-only issue search first. Do not pass raw draft front matter, raw private diagnostics, host
paths, board names, or token-like text to `gh issue list --search`; rewrite or skip unsafe search
terms and record the omission in `publication-report.md`.

If no likely duplicate is found, create a new issue.

If a likely duplicate is found, do not create a second issue. Add a concise sanitized comment to the
best matching existing issue only when the exact duplicate-comment body was already reviewed by the
operator or the operator explicitly approves that exact generated body before `gh issue comment`.
The comment should say that this bug bash also reproduced the issue and include any useful new
reproduction steps, target commit, environment, logs, or suspected code path from the draft.

Avoid duplicate comments from the same run. Before commenting, check existing issue comments when
practical for the current `RUN_ID` or source draft filename. If the run already commented there,
record it and skip another comment.

Do not edit existing issues, relabel them, close or reopen them, modify milestones, edit projects,
or create labels.

## Label policy

Use `labels_to_add` from the draft for newly created issues, but do not create missing labels. Check
which labels exist in the target repository. Apply only existing labels. If a requested label does
not exist, omit it and record it in `publication-report.md`.

The default `bug` label should be applied when it exists because the repository's bug report template
uses that label. If `bug` is missing, create the issue without labels only if the operator explicitly
allowed that fallback in the goal; otherwise skip and report the blocker.

Duplicate comments do not apply labels.

## Commands

Representative commands:

```bash
gh issue list \
  --repo "$TARGET_REPO" \
  --state all \
  --search "$QUERY" \
  --json number,title,state,url,labels,body \
  --limit 20
```

```bash
gh issue view "$ISSUE_NUMBER" \
  --repo "$TARGET_REPO" \
  --comments \
  --json number,title,url,comments
```

```bash
scripts/check-private-context --stdin --label github-body < "$PUBLISH_BODY_FILE"
```

```bash
gh issue create \
  --repo "$TARGET_REPO" \
  --title "$TITLE" \
  --body-file "$PUBLISH_BODY_FILE" \
  --label bug
```

Add one `--label` flag for each additional existing label selected for publication. Do not include
missing labels.

```bash
scripts/check-private-context --stdin --label github-body < "$DUPLICATE_COMMENT_FILE"
```

```bash
gh issue comment "$ISSUE_NUMBER" \
  --repo "$TARGET_REPO" \
  --body-file "$DUPLICATE_COMMENT_FILE"
```

## Body file preparation

The local draft starts with YAML front matter followed by the issue body. For new GitHub issues:

1. Keep the title from draft front matter.
2. Strip the YAML front matter from the body file passed to `gh issue create`.
3. Keep the issue-template field headings and answers from the draft body.
4. Keep any sanitized provenance note that was already present in the reviewed draft. Do not add new
   body text after approval unless the operator explicitly approves that exact generated body.
5. Do not include local-only metadata that exposes private paths or private service links.
6. Run `scripts/check-private-context --stdin --label github-body` on the exact body file before
   passing it to `gh issue create`.

For duplicate comments, use a concise reviewed body such as:

```markdown
This was also reproduced during live bug bash `<RUN_ID>` against commit `<commit SHA>`.

Additional sanitized reproduction details:

1. ...
2. ...

Observed result: ...
Expected result: ...

Additional evidence: ...
```

Keep the comment shorter than the full issue draft unless the existing issue lacks essential
reproduction details. If Codex generates or changes this comment during publication, stop and ask the
operator to approve the exact body before posting it. Run
`scripts/check-private-context --stdin --label github-body` on the exact comment file before passing
it to `gh issue comment`.

## Outputs

Write these files under `RUN_ROOT`:

- `publication-report.md` after any posting attempt.
- `published-issues.json` containing created issue numbers, created issue URLs, duplicate issue
  numbers, duplicate comment URLs, source draft filenames, labels applied, labels skipped, and skip
  reasons.

After creating an issue or duplicate comment, update the local draft or a sidecar metadata file with
the created issue URL, issue number, duplicate issue URL, or duplicate comment URL. Do not rewrite
evidence or change the original reproduction content except to add safe publication metadata.
