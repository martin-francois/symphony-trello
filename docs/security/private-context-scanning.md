# Private Context Scanning

GitHub Secret Scanning with repository custom patterns is the primary protection for
GitHub-hosted issue, pull request, review, and comment text. It owns hosted-text alerting, push
protection, and secret-scanning workflows where those features are configured.

Symphony for Trello complements that with a local and CI BetterLeaks guardrail for content that can
be checked before it reaches GitHub-hosted text or repository history. The repository-specific rules
live in `config/betterleaks/private-context.toml`. The tool choice and rejected alternatives are
recorded in [ADR 0047](../adr/0047-betterleaks-private-context-scanning.md).

Run the local scanner before committing files or manually posting copied diagnostic text:

```bash
scripts/check-private-context --worktree
scripts/check-private-context --git-range origin/main..HEAD
printf '%s\n' 'text to post' | scripts/check-private-context --stdin --label github-body
```

The scanner checks BetterLeaks' built-in rules and the project-specific rules. Findings are printed
with redacted matched values. The project-specific rules catch:

- Trello board and card URLs with non-synthetic short links.
- Live-looking 24-character Trello ids.
- Trello, GitHub, and OpenAI token-shaped values.
- Private host paths that should not be copied into committed files or hosted text.

Use clearly synthetic same-shape fixtures when tests need Trello-shaped values:

```text
SYNTH001
000000000000000000000001
https://trello.com/b/SYNTH001/synthetic-board
```

Pull request guardrail workflows scan untrusted pull request content with trusted scanner code. The
private-context workflow checks out the pull request source and the trusted base-branch scanner into
separate directories, then runs the trusted scanner against the pull request worktree and commit
range. Pushes to `main` run the scanner from the trusted post-merge checkout.

The local command intentionally does not wrap `gh`. GitHub CLI parsing is broad and changes over
time, so contributors and agents should scan the exact text before manually posting copied
diagnostic output:

```bash
printf '%s\n' 'text to post' | scripts/check-private-context --stdin --label github-body
```

GitHub Secret Scanning remains the primary post-create safety net for hosted issue, pull request,
review, and comment text.
