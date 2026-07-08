# Autonomy & escalation

## Scope

How much to decide independently, when to ask the user, and how to run the Codex review/fix loop on
this repository.

## Deciding versus asking

- Make reasonable implementation decisions without asking for permission for every detail.
- Ask the user only when local context and the spec are insufficient and a wrong assumption would be
  costly or hard to reverse.
- Be direct about residual risk, skipped verification, or parts that are intentionally not covered.

## Review before finalizing

- If a review tool is available, use it before finalizing non-trivial code changes and address
  justified findings.
- When Codex makes repository changes, run the Codex review/fix loop after every completed change
  unless the user explicitly says not to. This requirement is specific to Codex sessions; do not
  impose it on Claude or other AI tools.

## Codex review/fix loop

- For this repository's trusted local Codex review/fix loop, choose the scope explicitly on the first
  run and use `codex --dangerously-bypass-approvals-and-sandbox review ...` when the review needs to
  run the same local tests and socket-binding checks that normal verification uses. Do not use the
  bypass form for untrusted third-party diffs or repositories outside this checkout. Do not pass a
  positional prompt with scoped review flags. The installed Codex CLI rejects that combination even
  when its usage text implies `[PROMPT]` might work. Correct forms are:
  `codex --dangerously-bypass-approvals-and-sandbox review --uncommitted --title "Short review title"`
  for staged, unstaged, or untracked local changes;
  `codex --dangerously-bypass-approvals-and-sandbox review --base origin/main --title "Short review title"`
  for an already committed feature branch; and
  `codex --dangerously-bypass-approvals-and-sandbox review --commit SHA --title "Short review title"`
  for one specific commit. Never run `codex review --uncommitted "prompt"`,
  `codex review "prompt" --uncommitted`, `printf "prompt" | codex review --uncommitted -`, bare
  `codex review`, or a mismatched scope and then correct it later.
- Cap each Codex review run at 30 minutes, for example with
  `timeout 30m codex --dangerously-bypass-approvals-and-sandbox review --base origin/main --title "Short review title"`.
  If the command times out, stop that pass, keep any useful visible findings, and rerun a fresh
  scoped review after addressing or narrowing the cause. Do not leave orphaned review processes
  running in the background.
- After starting a Codex review pass, wait quietly for it to finish. Do not send repeated progress
  updates, polling notes, or "still running" messages unless the user asks for status or the review
  hits the timeout and needs action.
- After a Codex review pass reports justified findings, fix them and rerun Codex review directly.
  Do not run the repository's normal local verification gate between review iterations only to check
  each repair. Once the Codex review loop is clean, run the required verification once for the final
  tree. If that final verification fails, fix the failure, rerun the Codex review loop, and only then
  run final verification again.

## References

- [Default workflow](default-workflow.md)
- [Java style & design preferences](java-style.md)
