---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude Code]
consulted:
  - "[GitHub issue #569](https://github.com/martin-francois/symphony-trello/issues/569)"
  - "[GitHub issue #666](https://github.com/martin-francois/symphony-trello/issues/666)"
  - "[ADR 0065](0065-managed-stale-blocker-recheck-status.md)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors, Board users]
---

# Attribute Every Symphony Trello Comment With One Shared Footer

## Context and Problem Statement

Symphony writes several kinds of Trello comments: the Codex workpad, handoff and blocker comments
from `trello_add_comment`, the stale-blocker recheck status, and the prerequisite status. Codex
often posts through the same Trello account as the board owner. Before this decision, only the
blocker-recheck status from [ADR 0065](0065-managed-stale-blocker-recheck-status.md) had a readable
`Managed by Symphony` footer. A board user could not tell the other Symphony comments from comments
that a person wrote.

Each comment family also has its own ownership rule for updates and deletes. How should Symphony
make authorship visible on every comment without letting one family's tool edit another family's
comments, and without rewriting old comments in bulk?

## Decision Drivers

* A board user must see who wrote a comment without reading raw markers or HTML.
* Attribution must not depend on the Trello author account.
* One format and one formatter/parser, so new comment families do not invent their own label.
* Visible attribution must not grant edit or delete rights. Anyone can type the same text.
* Existing blocker-recheck comments must stay recognized byte for byte.
* No bulk migration of historical comments.
* [GitHub issue #666](https://github.com/martin-francois/symphony-trello/issues/666) may reuse the
  same contract later.

## Considered Options

* One shared readable footer for attribution, with ownership kept per family.
* Use the shared footer as the ownership marker for every family.
* Append the footer inside the `TrelloClient` comment write methods.
* Hidden HTML comment markers or opaque identifiers.
* Rely on the Trello author account or a separate bot account.
* Rewrite all existing Symphony comments once to add the footer.

## Decision Outcome

Chosen option: "One shared readable footer for attribution, with ownership kept per family",
because it shows authorship on every Symphony comment while each mutation path keeps its existing,
narrower proof of ownership.

`SymphonyCommentFooter` in the tracker package is the only formatter and parser. The footer is the
last Markdown paragraph of the comment and is exactly `_Managed by Symphony_`. A family that needs
one line of extra visible identity uses `_Managed by Symphony · <detail>_`. The blocker-recheck
status keeps its existing link to the blocker comment as that detail, so its text did not change.

Every write path appends exactly one footer. Tools remove footers that the agent echoed at the end of
its text before appending the canonical one, so retries and copied workpads never stack footers.
Text that is only a footer is rejected before any Trello request.

The footer never decides ownership. Each family still checks its own identity before an update or a
delete: the `## Codex Workpad` heading at the start of the text, the `## Symphony Prerequisite
Status` heading, or the exact blocker-recheck link to a qualifying comment on the current card. A
handoff comment with the plain footer is still an ordinary comment for the stale-blocker classifier,
so a `Blocked:` handoff posted by Codex keeps qualifying for a recheck.

A comment written before this change gains the footer only when its owning path changes its visible
content. Paths that skip unchanged writes, such as the prerequisite sync and the Codex usage section
of the workpad, compare text with the footer removed. An unchanged legacy comment therefore stays
byte for byte the same.

### Consequences

* Good, because every Symphony comment shows who wrote it, also when Codex uses a person's account.
* Good, because one class owns the format, and a new family only calls `append`.
* Good, because a copied or typed footer cannot make a comment editable by any tool.
* Good, because existing blocker-recheck comments and legacy workpads keep working without migration.
* Bad, because old handoff comments, which no path ever updates, keep showing no footer.
* Bad, because a person can still type the footer on their own comment and look like Symphony to
  other readers. Tools ignore it, but people may not.

### Confirmation

This decision is still implemented when:

* every production call to `TrelloClient.addComment` or `TrelloClient.updateComment` passes text
  built with `SymphonyCommentFooter.append`, or restores the exact text that was read earlier;
* `SymphonyCommentFooterTest` covers malformed, misplaced, duplicated, and lookalike footers;
* `TrelloHandoffToolHandlerTest` and `TrelloClientTest` cover each family with footer creation,
  echoed footers, legacy comments, and comments from other families that must stay unchanged;
* the generated workflow, `WORKFLOW.example.md`, and the shipped `trello-handoff` and
  `trello-workpad` skills describe the footer and tell the agent not to write it; and
* `./mvnw -q spotless:check verify` passes.

## Pros and Cons of the Options

### One Shared Readable Footer, Ownership Per Family

Every Symphony comment ends with `_Managed by Symphony_` or `_Managed by Symphony · <detail>_`.
Mutation paths keep their own family check and treat the footer as display text only.

* Good, because the attribution is readable in Trello and in the API text.
* Good, because the ownership rules from [ADR 0019](0019-trello-workpad-comment.md) and
  [ADR 0065](0065-managed-stale-blocker-recheck-status.md) do not change.
* Good, because the detail slot keeps the blocker-recheck link without a second format.
* Bad, because two layers, attribution and ownership, must be explained to contributors.

### Use the Shared Footer as the Ownership Marker

Any comment that ends with the footer would count as Symphony-owned, and tools could update or
delete it.

* Good, because one rule would answer both "who wrote it" and "may I change it".
* Bad, because the workpad tool could then edit a handoff or blocker-recheck comment.
* Bad, because anyone can type the footer, so a person's comment could be changed or deleted.
* Bad, because legacy comments without a footer would lose their owner.

### Append the Footer Inside the TrelloClient Write Methods

`TrelloClient.addComment` and `TrelloClient.updateComment` would add the footer to every text they
send, so callers could not forget it.

* Good, because a new comment family would get the footer without calling the formatter.
* Bad, because the workpad rollback must write back the exact text it read, footer or not.
* Bad, because the blocker-recheck status and the workpad must place their own content, such as
  the manual-cleanup note or the footer detail, before the footer. The low-level client does not
  know these family rules.
* Bad, because the unchanged-content checks run before the write and need the same footer rules,
  so the formatter would still be called from the families.

### Hidden HTML Comment Markers or Opaque Identifiers

Symphony would add a marker such as an HTML comment that people are not supposed to see.

* Good, because the visible comment would stay unchanged.
* Bad, because Trello rendered such raw HTML markers as visible text in the 2026-07-12 live run
  recorded in [live E2E verification](../live-e2e.md), so they are neither hidden nor readable.
* Bad, because a hidden marker does not help a board user see who wrote the comment.

### Rely on the Trello Author Account or a Separate Bot Account

Authorship would come from the Trello member who posted the comment.

* Good, because no text would be added.
* Bad, because the usual setup posts through the board owner's own token, so human and Symphony
  comments have the same author.
* Bad, because a separate bot account needs an extra Trello seat and setup work for every operator.

### Rewrite All Existing Symphony Comments Once

A migration would add the footer to every historical Symphony comment on startup or on the next
poll.

* Good, because the board would look consistent at once.
* Bad, because Symphony cannot reliably tell its old handoff comments from human comments.
* Bad, because it rewrites many comments that no current state change needs, which
  [GitHub issue #569](https://github.com/martin-francois/symphony-trello/issues/569) rules out without
  a separate migration decision.

## More Information

This is a compatible change. No configuration key changes. The blocker-recheck footer is unchanged
byte for byte. A machine reader that compared whole comment texts now sees one extra final
paragraph on new and updated comments.
