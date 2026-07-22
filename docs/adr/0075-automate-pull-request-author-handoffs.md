---
status: accepted
date: 2026-07-22
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #618](https://github.com/martin-francois/symphony-trello/issues/618)"
  - "[GitHub pull_request_target documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request_target)"
  - "[GitHub issue_comment documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#issue_comment)"
  - "[GitHub timeline-events API](https://docs.github.com/en/rest/issues/timeline#list-timeline-events-for-an-issue)"
  - "[GitHub Script](https://github.com/actions/github-script/tree/3a2844b7e9c422d3c10d287c895573f7108da1b3)"
informed: [Future maintainers, Contributors]
---

# Automate Pull Request Author Handoffs With Explicit Signals

## Context and Problem Statement

[GitHub issue #618](https://github.com/martin-francois/symphony-trello/issues/618) requires a durable
way to distinguish pull requests waiting for their external author from pull requests that need
maintainer attention. A maintainer can apply a waiting label after leaving feedback, but manually
checking every labeled pull request for new commits or discussion recreates the tracking work that
the label is intended to remove.

An arbitrary new comment is not a reliable completion signal. An author can post a schedule update,
ask a clarifying question, or answer one automated inline finding while other feedback remains open.
Conversely, an inline reply that explains a false positive is useful activity, but GitHub Actions
does not provide a privileged default-branch route for every pull-request review-comment event that
can safely relabel external-fork pull requests. Which signals should move the pull request back to
maintainer attention, and which automation should own the transition?

## Decision Drivers

* Keep the maintainer's waiting state visible on the pull request.
* Notify maintainers only after an author gives a pull-request-wide readiness signal.
* Do not interpret progress updates, questions, or isolated inline replies as completed work.
* Give authors a memorable escape hatch after resolving feedback without a new commit.
* Support pull requests from forks without executing pull-request code in a privileged context.
* Avoid a hosted service, new credentials, and third-party workflow dependencies.
* Preserve unrelated labels and make state transitions idempotent.
* Test the exact JavaScript deployed in the workflow.

## Considered Options

* Use two repository labels and repository-owned GitHub Actions driven by author commits, review
  state events, and an explicit top-level `/ready` comment.
* Remove the waiting label after any author comment or inline review reply.
* Operate a GitHub App that observes every review-thread event.
* Keep the waiting state and author handoff entirely manual.

## Decision Outcome

Chosen option: use two repository labels and repository-owned GitHub Actions driven by explicit
author signals.

`waiting-for-author` means maintainer feedback requires author action or a response.
`needs-maintainer-review` means the pull-request author has signaled that maintainer attention is
needed. The labels are mutually exclusive. Applying `waiting-for-author` removes
`needs-maintainer-review` and posts one standard notice that lists the author handoff signals.

While the waiting label is present, an event moves the pull request to `needs-maintainer-review`
only when the pull-request author pushes a commit, marks the pull request ready for review,
re-requests review, or posts a top-level comment whose complete body is `/ready`. The
transition adds the review label before removing the waiting label. A failed addition therefore
preserves the actionable waiting state.

Other comments and inline review replies do not change the labels. After answering inline feedback,
including explaining why an automated finding is a false positive, the author posts `/ready` to
declare that the complete pull request is ready. This explicit signal avoids guessing whether one
reply resolves all maintainer feedback.

The workflow uses `pull_request_target` for trusted pull-request metadata and `issue_comment` for
the top-level command. Both workflows execute code from the default branch. The job has only issue
write and pull-request read permissions, uses the repository's full-SHA-pinned GitHub Script action,
and never checks out or executes pull-request code. The workflow validates the event actor against
the pull-request author and requires the waiting label in both the event-time payload and current
repository state before changing a waiting pull request. The job-level condition admits only an
application of the waiting label or an exact author-owned review signal to per-pull-request
concurrency, and it requires the waiting label in author-event payloads before the job enters that
group. GitHub's `queue: max` mode preserves up to 100 pending jobs for the pull request rather than
replacing one pending event with the next. The two-state checks make superseded, delayed, or
reordered deliveries inert. Unrelated labels, comments, and non-waiting author events do not enter
the concurrency group.

Before applying an author transition, the workflow reads the pull request's ordered timeline and
finds the latest `waiting-for-author` label application. The exact signal must appear after that
timeline item: a synchronize event matches its pushed head SHA, `/ready` matches its comment ID, and
review-state events match their event type, author, and requested reviewer where applicable. A
synchronize event accepts either a new committed item or a force-push item for an existing commit.
Timeline order remains unambiguous when multiple events share GitHub's second-resolution timestamp.
A delayed or manually rerun signal from an earlier waiting cycle therefore cannot consume a newer
maintainer request even though both payloads contain the same label name.

The label event's unique timeline node ID is the internal reminder-cycle identifier. A delayed
labeled-event run reconciles whichever waiting cycle is current rather than replaying its old cycle.
Before posting, the workflow checks existing `github-actions[bot]` comments for the current marker.
A retry or duplicate delivery therefore does not post the same reminder twice, while a later waiting
cycle receives a new notice. Every mutation path also reads the current pull request and stops when
it has closed or merged since the webhook event was queued.

### Consequences

* Good, because maintainers can filter on one label without repeatedly reopening unchanged pull
  requests.
* Good, because the standard reminder means maintainers do not need to remember to explain `/ready`
  whenever they apply the waiting label.
* Good, because commits and normal review-state events require no extra author comment.
* Good, because arbitrary comments do not create false maintainer notifications.
* Good, because external-fork pull requests receive the same label transitions without privileged
  execution of their code.
* Good, because delayed deliveries and workflow retries do not reverse completed handoffs or
  duplicate reminder comments.
* Bad, because resolving only inline review threads requires the author to post an additional
  top-level `/ready` comment.
* Bad, because API failures leave the last durable state in place and require a later event or a
  manual label correction; workflow warnings expose the failed transition.

### Confirmation

`scripts/pull-request-author-handoff.test.ts` executes the exact GitHub Script block from the
workflow. It covers label application, every accepted author event, the `/ready` command, inert
comments and actors, idempotency, least-privilege workflow structure, and API failure ordering.
Repository maintainers can also verify the live path on a disposable pull request by applying
`waiting-for-author` and sending each documented author signal. The tests also cover a delayed
signal from an earlier waiting cycle, same-second ordering, and timeline API failures.

## Pros and Cons of the Options

### Repository labels with explicit author signals

* Good, because GitHub-native labels remain visible and filterable without another service.
* Good, because a push, ready-for-review event, or review request already expresses author intent.
* Good, because `/ready` handles completed discussion that does not require a code change.
* Bad, because authors must learn one repository command for discussion-only handoffs.

### Reset after any author comment or inline reply

* Good, because every visible author interaction can trigger a transition without a command.
* Bad, because schedule updates, questions, and partial replies incorrectly notify maintainers that
  the complete review is ready.
* Bad, because privileged label writes from external-fork review-comment events are not available
  through the same repository-owned default-branch Actions path.

### Hosted GitHub App

* Good, because an app can observe all pull-request, issue-comment, and review-thread events with
  repository-approved write permissions.
* Bad, because it requires hosting, credential rotation, installation, monitoring, and a larger
  security boundary for two label transitions.
* Bad, because event access does not solve the semantic ambiguity of a partial inline reply.

### Entirely manual handoff

* Good, because it adds no automation code or permissions.
* Bad, because maintainers must keep checking labeled pull requests or authors must know which label
  to change.
* Bad, because every maintainer must remember to explain the handoff protocol independently.

## More Information

This decision changes contributor workflow only. It does not change application runtime behavior,
Trello behavior, or the product contract, so `SPEC.md` does not need an update.
