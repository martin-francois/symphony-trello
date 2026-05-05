---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [README.md, AGENTS.md]
informed: [Future maintainers]
---

# Write User Documentation as a Linear Benefits-First Guide

## Context and Problem Statement

Symphony for Trello is useful for people who may be setting up Trello specifically to coordinate
Codex work across projects. The README originally risked leading with technical mechanics, scattered
links, and setup branches that made readers scroll back to decide what to do next.

How should the main user documentation be structured so new users can start successfully while power
users still have the details they need?

## Decision Drivers

* Make the practical value clear before implementation details.
* Support readers who are new to Trello workspaces, API keys, tokens, boards, and columns.
* Keep setup readable from top to bottom without requiring backtracking.
* Put guidance next to the command or step where the reader needs it.
* Avoid duplicating vendor-owned instructions when an official guide explains the current UI.
* Keep advanced safety knobs and workflow contract details available without overwhelming the first
  setup path.

## Considered Options

* Linear benefits-first guide with progressive disclosure.
* Reference-first README with all options and links listed up front.
* Minimal README that points readers to Trello and Codex documentation.

## Decision Outcome

Chosen option: "Linear benefits-first guide with progressive disclosure", because it serves users
who want to get a Trello-backed Codex workflow running without forcing them to understand every
configuration field first.

### Consequences

* Good, because readers first learn who the tool is for and why it helps.
* Good, because browser credential setup appears before board setup paths that require those
  credentials.
* Good, because board creation and board import each explain who the path is for near the commands.
* Good, because advanced fields such as Trello write controls stay in later sections.
* Bad, because some configuration details are repeated lightly where that avoids reader backtracking.
* Bad, because README edits need an extra pass from both the new-board and existing-board
  perspectives.

### Confirmation

Read `README.md` from top to bottom as a new Trello user and as a user importing an existing board.
Each path should introduce prerequisites before use, avoid unexplained config-name restatements, and
leave advanced mechanics for the workflow contract or later sections.

## Pros and Cons of the Options

### Linear benefits-first guide with progressive disclosure

Start with the user problem and practical benefits, then walk through prerequisites, credentials,
board setup, daily use, workflow contract, and advanced operations in that order.

* Good, because it matches how a first-time user makes progress.
* Good, because it avoids convenience links that skip required setup.
* Good, because official Trello links can replace duplicated UI instructions where appropriate.
* Neutral, because the README is longer than a terse reference page.
* Bad, because maintaining the flow requires more care when adding new setup options.

### Reference-first README with all options and links listed up front

Describe every setup path, option, and external reference before the guided setup flow.

* Good, because experienced users can scan options quickly.
* Bad, because new users see details before they know which ones matter.
* Bad, because readers may click into later setup paths before completing required earlier steps.

### Minimal README that points readers to Trello and Codex documentation

Keep only a short project summary and link out for most setup details.

* Good, because it is easy to maintain.
* Bad, because Symphony-specific decisions, such as active columns and handoff workflow, are not
  explained by Trello's docs.
* Bad, because first-time users would still need project-specific handholding outside the README.

## More Information

This ADR covers user-facing structure, not every wording preference. Repository-local writing rules
for future agents live in `AGENTS.md`.
