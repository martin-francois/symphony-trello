# Private Context Scanning

GitHub Secret Scanning is the hosted safety net for repository history, pull requests, issues,
reviews, and comments. The public repository should keep built-in secret scanning and push
protection enabled. Symphony-specific custom patterns are not part of the expected GitHub Free-plan
setup; see [GitHub Secret Scanning](github-secret-scanning.md) for the hosted baseline.

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
with redacted matched values. Worktree scans cover regular tracked files and non-ignored untracked
files reported by the current Git worktree, using Git's own ignore rules so generated dependencies
and other ignored build inputs are not mistaken for repository content. Opaque nested repositories
are scanned separately from their own root. The private-context rule file itself is reviewed as
configuration rather than scanned as content, because its detection examples intentionally match
its own rules. The project-specific rules catch:

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

GitHub Secret Scanning remains the post-create safety net for hosted issue, pull request, review,
and comment text that matches GitHub-supported patterns. The local scanner remains required for
exact text copied from diagnostics before a contributor or agent posts it, and for
Symphony-specific private context that GitHub's Free-plan scanner does not cover.
