---
status: accepted
date: 2026-05-06
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, GitHub issue #30, GitHub CLI documentation, repository-local Codex skills]
informed: [Future maintainers]
---

# Use GitHub CLI Identity for PR Commit Authors

## Context and Problem Statement

Symphony for Trello can ask Codex to make repository changes, push a branch, and create or update a
GitHub pull request. Those commits should be authored as the GitHub account that publishes the PR,
not as a generic local service user. The Java scheduler starts Codex and manages Trello state, but it
does not create Git commits itself.

How should the project ensure PR-bound commits use the right author without moving repository
workflow logic into the scheduler?

## Decision Drivers

* Keep the Java service focused on scheduling, Trello reads, and scoped Trello tools.
* Use the same GitHub authentication context for author lookup, push, and PR creation.
* Avoid raw GitHub tokens in workflow files or deployment environment files.
* Avoid unsafe history rewrites after a branch has been pushed.
* Give Codex clear stop conditions when GitHub identity lookup fails.
* Keep local-only or no-push work simple.

## Considered Options

* Configure Git author identity in the repository-local commit and push-pr skills.
* Add Java runtime support that inspects workspaces and rewrites Git config before Codex commits.
* Require operators to preconfigure one fixed Git author for the service user.
* Let Git use whatever local identity is already configured.

## Decision Outcome

Chosen option: "Configure Git author identity in the repository-local commit and push-pr skills",
because commit creation is agent workflow behavior and the skills already own commit and PR hygiene.
The commit skill resolves the authenticated GitHub user with `gh api user`, configures the task
checkout's Git author before the first PR-bound commit, and uses the public GitHub email when
available or an actual GitHub noreply address returned by the authenticated account's email API
otherwise. It does not guess the noreply format because GitHub accounts can use different noreply
address forms. It checks lookup success before writing Git config so failed auth does not replace a
useful local identity with blank values. The push-pr skill checks PR-bound commit authors before
publishing, fails closed when it cannot resolve the default-branch comparison range, and avoids
force-pushing wrong-author fixes unless a human or workflow explicitly allows it.

If GitHub identity lookup fails before PR-bound commits exist, Codex records a visible Trello blocker
and stops rather than creating commits with a generic fallback author. Local-only and no-push cards
can keep the existing local Git identity because there is no GitHub PR author to match.

### Consequences

* Good, because the scheduler stays out of repository-specific Git operations.
* Good, because the same GitHub CLI account is used for author lookup, push, and PR creation.
* Good, because users without a public GitHub email can still use their real GitHub noreply author
  email when the GitHub CLI auth context can read it.
* Good, because the workflow blocks instead of guessing an email that may not belong to the
  authenticated account.
* Good, because push-time author verification does not silently pass when the default-branch base is
  missing.
* Good, because wrong-author unpublished commits can be fixed before publication.
* Bad, because a prompt/skill instruction cannot enforce authoring as strongly as a dedicated Git
  wrapper would.
* Bad, because private-email accounts need GitHub CLI auth with the `user:email` scope.
* Bad, because existing pushed commits with the wrong author still require human judgment before any
  history rewrite.

### Confirmation

Run `./mvnw -q -Dtest=CodexSkillStructureTest,TrelloBoardSetupTest test` and confirm the generated
workflow and repository-local skills describe GitHub identity resolution, noreply fallback, lookup
failure handling, and safe pushed-branch behavior.

For a live PR-bound card, inspect `git log --format='%an <%ae>'` on the task branch before pushing
and confirm it matches the name from `gh api user` and either the public email from `gh api user` or
an actual noreply email from `gh api user/emails` for the service user.

## Pros and Cons of the Options

### Configure Git author identity in the repository-local commit and push-pr skills

Codex resolves the GitHub identity and configures the task checkout before committing.

* Good, because this is where commit and PR decisions already live.
* Good, because it works for both local and deployed runs that have GitHub CLI auth.
* Good, because it can distinguish PR-bound work from local-only/no-push work.
* Bad, because it depends on Codex following the skill instructions.

### Add Java runtime support that inspects workspaces and rewrites Git config before Codex commits

The scheduler would try to configure Git in the workspace before or during an agent run.

* Good, because Java code can be unit-tested directly.
* Bad, because the scheduler does not know when Codex will commit or whether the card is PR-bound.
* Bad, because it would pull repository workflow policy into the service runtime.
* Bad, because it would still need GitHub CLI auth and repository context from the agent workspace.

### Require operators to preconfigure one fixed Git author for the service user

Deployment docs would tell operators to set global `user.name` and `user.email`.

* Good, because it is simple operationally.
* Bad, because it can drift from the GitHub account that publishes the PR.
* Bad, because it does not handle multiple GitHub identities cleanly.
* Bad, because it can silently produce generic service-user authors.

### Let Git use whatever local identity is already configured

Keep the current behavior.

* Good, because it needs no new logic.
* Bad, because commits can be authored as the wrong local user.
* Bad, because failures are discovered only after a branch or PR exists.
* Bad, because it weakens auditability for PR review.

## More Information

Relevant commands:

* `gh api user --jq '.login'`
* `gh auth refresh -s user:email`
* `gh api user --jq '.email // ""'`
* `gh api user/emails --jq '[.[] | select(.email | endswith("@users.noreply.github.com")) | .email][0] // ""'`
* `git merge-base HEAD origin/<default-branch>`
* `git config user.name`
* `git config user.email`
