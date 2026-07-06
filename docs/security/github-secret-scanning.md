# GitHub Secret Scanning

GitHub Secret Scanning and push protection are hosted repository settings. They are not represented
by committed files in this repository.

For this project, treat GitHub's built-in public-repository secret scanning and push protection as
the hosted safety net. Do not rely on GitHub repository custom patterns for Symphony-specific private
context. Custom patterns require GitHub Secret Protection or another supported paid plan, and the
project does not expect to depend on that plan.

## Verify Hosted Settings

Use the GitHub UI and the repository REST API to verify the repository state:

```bash
gh api repos/martin-francois/symphony-trello \
  --jq '.visibility, .private, .security_and_analysis'
```

The hosted baseline should have:

- repository visibility `public`;
- `secret_scanning.status` set to `enabled`;
- `secret_scanning_push_protection.status` set to `enabled`.

If GitHub exposes secret-scanning alerts through the repository UI or REST API for the current plan
and token, confirm there are no unresolved alerts there. Do not treat a missing custom-patterns or
alerts API endpoint as a repository-owned scanning failure when the built-in repository settings are
enabled and BetterLeaks remains the project-specific scanner.

The current expected GitHub organization plan does not provide Symphony-specific repository custom
patterns. If the UI or API exposes custom patterns in the future, update this page and
[ADR 0047](../adr/0047-betterleaks-private-context-scanning.md) before configuring them.

## Responsibility Split

GitHub Secret Scanning protects hosted repository history and GitHub-hosted text for patterns GitHub
supports. This includes built-in provider patterns and push protection for those supported patterns.

BetterLeaks remains the repository-owned scanner for project-specific private context:

- Trello board and card URLs with non-synthetic short links.
- Live-looking 24-character Trello ids.
- Trello, GitHub, and OpenAI token-shaped values not caught earlier.
- Private host paths that should not be copied into committed files or hosted text.

The BetterLeaks rules live in `config/betterleaks/private-context.toml`. Run the local scanner before
committing files or manually posting copied diagnostic text:

```bash
scripts/check-private-context --worktree
scripts/check-private-context --git-range origin/main..HEAD
printf '%s\n' 'text to post' | scripts/check-private-context --stdin --label github-body
```

If the scanner reports Trello-shaped values that are only test data, make them clearly synthetic
rather than suppressing the finding.
