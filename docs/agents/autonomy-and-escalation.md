# Autonomy & escalation

## Scope

How much to decide independently, when to ask the user, and how to run the Codex review/fix loop on
this repository.

## Deciding versus asking

- Make reasonable implementation decisions without asking for permission for every detail.
- Ask the user only when local context and the spec are insufficient and a wrong assumption would be
  costly or hard to reverse.
- Be direct about residual risk, skipped verification, or parts that are intentionally not covered.
- In every user-facing response after a tool, dependency, environment, permission, or workflow step
  does not work the first time and requires a workaround, fallback, retry, or manual adaptation,
  mention unresolved friction again instead of saving it only for the final response. Once the
  friction is durably resolved or no longer applies, stop repeating it; mention the resolution only
  in the response where the fix is made or confirmed. State what happened, what workaround was used,
  whether the result is still trustworthy, and what could make that path work the first time in
  future runs. Offer to make those improvements when they are in repository or local-environment
  scope. When offering more than one improvement, label the options with letters and say the user can
  reply with letters such as `A` or `AD` to choose multiple options. If Codex cannot make an
  improvement itself, explain the exact user action needed instead of only saying it cannot be done.
- Treat an inability to complete repository-required verification because a tool, dependency,
  environment capability, permission, or workflow facility is unavailable or needs a workaround as
  unresolved friction. Also treat an unexpected warning or error from a tool, dependency,
  environment, permission, or workflow step as friction until it is fixed or explained, even when
  the step exits successfully. Expected diagnostics deliberately induced and asserted by a test are
  not friction merely because they contain warning or error text. Likewise, a normal test or
  assertion failure is not automatically friction; treat it as a defect or finding to diagnose and
  fix. Classify a nonzero command as friction only after evidence separates an execution-environment
  limitation from a code or test failure. Do not silently ignore affected checks or unexpected
  diagnostics, call the complete gate passed, or hide the gap under a generic "environmental
  limitation." State the command, affected checks or diagnostics, evidence for the classification,
  substitute evidence used, and remaining verification gap. When practical, compare the unchanged
  base in the same environment or inspect the trusted hosted equivalent on the exact tested tree.
  Then adapt the environment or keep the limitation explicit through handoff; a green substitute
  check does not retroactively turn the locally incomplete verification into a pass.

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
- After launching Codex review, wait for the command to finish before continuing the workflow or
  reporting progress to the user. Do not send progress updates merely because the review process is
  still running or because the session was polled. Report again only when the review completes, fails,
  or needs external input.
- When a Codex review/fix loop changes an already committed branch, fix each justified finding into
  the commit that introduced or owns the reviewed code. Use `git commit --fixup` plus autosquash,
  amend the owning commit, or otherwise rewrite the branch so the final history does not leave
  generic "review fixes" commits behind. If a finding spans multiple existing commits, split the
  repair by ownership when practical; otherwise put the fix in the earliest commit that needs the
  corrected behavior and keep the resulting commit focused.

## References

- [Default workflow](default-workflow.md)
- [Java style & design preferences](java-style.md)
