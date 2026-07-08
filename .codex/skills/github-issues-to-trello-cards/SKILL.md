---
name: github-issues-to-trello-cards
description: >
  Create or synchronize Trello cards from GitHub issues for Symphony for
  Trello. Use when the user asks Codex to create Trello cards from specific
  GitHub issue URLs, issue numbers, or issue lists, optionally for a specific
  Trello board, or when a board should be auto-selected from a workflow whose
  repository configuration matches the GitHub repository.
---

# GitHub Issues To Trello Cards

## Goals

- Create one Trello card per requested GitHub issue.
- Use the GitHub issue title as the Trello card title.
- Use exactly `Implement <issue-url>` as the description for newly created
  cards.
- Avoid duplicate cards by checking the target board for an existing card whose
  description contains the canonical GitHub issue URL as a standalone URL token
  before creating anything.
- For existing cards, update only the Trello title when it differs from the
  current GitHub issue title.
- Report created cards, existing unchanged cards, and existing title-updated
  cards separately.

## Workflow

1. Resolve every requested issue through GitHub before touching Trello. Use
   canonical issue URLs and current titles from GitHub, not user-supplied title
   text. Deduplicate repeated issue inputs by canonical URL while preserving the
   user's order.
2. If the user gave a Trello board selector, use that board.
3. If no board selector was given, auto-select from configured workflows:
   - Inspect local workflow files such as `WORKFLOW.md`, `WORKFLOW.*.md`, and
     configured Symphony for Trello workflow paths.
   - Find workflows whose repository source matches the GitHub repository for
     the issue. Compare both `repository.default_url` and
     `repository.default_path`. For URLs, canonicalize common HTTPS and SSH
     GitHub clone URL forms before comparing. For local paths, resolve the path
     relative to the workflow file when needed, inspect the checkout's Git
     remotes, and canonicalize those remote URLs before comparing.
   - Use the workflow's configured Trello board only when exactly one board
     matches the relevant repository. If there is no match or more than one
     match, stop and ask the user for the board instead of guessing.
4. If the issues span multiple GitHub repositories and the user did not provide
   one board, group issues by repository and apply the same exact-one workflow
   board match per repository. Stop before Trello writes for any ambiguous
   group.
5. Resolve the target list for new cards. Use the open `Inbox` list when it
   exists. If the board has no open `Inbox` list, stop and ask which list to
   use instead of creating cards in another list.
6. Before creating cards, fetch all cards on the target board, including
   archived cards when the Trello API supports it. Match existing cards by the
   canonical GitHub issue URL in the card description only when the URL is
   delimited by the start or end of text, whitespace, or punctuation that cannot
   be part of a GitHub issue URL. Do not treat prefix-sharing URLs as matches,
   such as matching `/issues/1` inside `/issues/12`, and do not rely on Trello
   card title alone.
7. For each issue:
   - If no matching card exists, create a new card in the resolved target list
     with the GitHub issue title and `Implement <issue-url>` description.
   - If one or more matching cards exist and every matching card title already
     equals the GitHub issue title, leave Trello unchanged and report it as
     existing unchanged.
   - If one or more matching cards exist and any matching card title differs
     from the GitHub issue title, update the differing Trello titles and report
     the issue as existing title-updated.
   - If multiple matching cards exist, do not create another card. Report the
     duplicate Trello card URLs so the user can decide whether to merge or
     archive extras.
8. Do not edit GitHub issue bodies, labels, assignees, or milestones while
   creating Trello cards. If the work also requires creating or editing GitHub
   issues, use `issue-sweep` or read
   `../../../docs/agents/github-issue-triage.md` first and apply the canonical
   issue policy there.

## Trello Safety

- Validate Trello credentials and the target board/list before the first write.
- Never print Trello tokens, API keys, private board IDs, or private board
  names in final output unless the user already provided that exact public
  value in the prompt.
- Treat archived matching cards as existing cards. Do not unarchive, move, or
  delete them unless the user explicitly asks.
- If a Trello write fails after earlier cards were created or updated, stop,
  report the partial results truthfully, and do not retry blindly in a way that
  could create duplicates.

## Final Response

Return a concise summary with these sections:

- Created: issue URL, Trello card title, Trello card URL.
- Already existed unchanged: issue URL, Trello card title, Trello card URL.
- Already existed, title updated: issue URL, old title, new title, Trello card
  URL.
- Skipped or blocked: issue URL, reason.

If there were duplicate existing Trello cards for one issue, include every
matching Trello card URL in the relevant existing section.

## Stop Conditions

- GitHub authentication is missing or requested issues cannot be read.
- Trello credentials are missing or the target board/list cannot be resolved.
- Board auto-selection has zero or multiple matches for a GitHub repository.
- The board has no open `Inbox` list and the user did not provide another list.
- The duplicate check cannot be completed before creation.
