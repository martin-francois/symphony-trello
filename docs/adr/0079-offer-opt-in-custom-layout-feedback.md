---
status: accepted
date: 2026-08-30
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #668](https://github.com/martin-francois/symphony-trello/issues/668)"
  - "[GitHub PR #669](https://github.com/martin-francois/symphony-trello/pull/669)"
  - "[ADR 0063](0063-microos-and-xdg-installer-layout.md)"
informed: [Future maintainers, Contributors]
---

# Offer Opt-In Custom Layout Feedback

## Context and Problem Statement

Explicit installer paths are necessary for hosts whose storage convention is not recognized. They
also hide evidence that a reusable platform, package-manager, deployment, or organization layout
could become an automatic default for similar users. Asking users to remember a separate feedback
process makes that evidence unlikely to reach maintainers.

How should fresh guided setup invite useful layout feedback without collecting private machine data,
blocking installation, or adding work for users with standard layouts?

## Decision Drivers

* Keep recognized layouts and ordinary installs free of extra prompts.
* Ask only after setup and the existing GitHub integration step succeed.
* Let the user decide whether a layout represents a reusable convention.
* Show exactly what will be submitted before any network write.
* Reuse authenticated GitHub CLI access when it is available.
* Provide a low-effort path when GitHub CLI is unavailable or the user declines creation.
* Exclude credentials and identifying machine or account data.
* Never turn optional feedback into an installation failure.

## Considered Options

* Offer reviewed, sanitized feedback after successful fresh explicit-layout setup.
* Send automatic layout telemetry.
* Print a generic documentation link for every custom layout.
* Do not request feedback and keep every unknown layout manual.

## Decision Outcome

Chosen option: "Offer reviewed, sanitized feedback after successful fresh explicit-layout setup",
because it gives maintainers concrete evidence while keeping disclosure and issue creation under the
user's control.

The installer passes process-local context only when a fresh interactive install used an explicit
layout variable. After setup has completed its GitHub integration step, Java asks whether the layout
follows a reusable convention. A negative answer ends the flow.

For an affirmative answer, setup prints the complete proposed issue title and body. The body includes
the operating-system name and version, architecture, wrapper shell, approved explicit variable
names, sanitized config and state paths, and whether those paths share a parent. Home-relative paths
replace the home prefix with `$HOME`. They retain only approved structural components such as
`.config`, `.local`, `state`, and `workspaces`; other child components become `<redacted>`. Paths
outside the home become `<outside-home>`. The report excludes credentials, usernames, hostnames,
and account details.

When `gh auth status` succeeds, setup asks for separate consent to create the displayed issue. When
authentication is unavailable, creation is declined, or creation fails, setup prints a prefilled
browser URL. Any feedback error is a warning and setup remains successful. Updates, dry runs,
completion-only invocations, and installs without explicit layout variables do not enter this flow.

### Consequences

* Good, because users with ordinary setups see no additional question.
* Good, because contributors can submit a useful request without retyping environment details.
* Good, because the complete payload is visible before issue creation.
* Good, because an authenticated GitHub CLI avoids another login or browser workflow.
* Bad, because a successful custom install has one or two additional optional questions.
* Bad, because aggressive redaction can omit details needed to recognize a convention.
* Neutral, because users can edit the prefilled browser request before submitting it.

### Confirmation

This decision is still implemented when tests prove that:

* the offer is absent without fresh explicit-layout context;
* declining the reusable-convention question performs no GitHub command;
* the displayed and submitted content contains only approved fields and sanitized paths;
* authenticated GitHub CLI creation requires a second affirmative answer;
* unavailable, declined, or failed CLI creation prints a prefilled browser URL; and
* feedback failures do not change setup's success result.

## Pros and Cons of the Options

### Offer Reviewed, Sanitized Feedback

Fresh successful explicit-layout setup asks whether the layout is reusable, shows a sanitized issue,
and offers CLI creation or a prefilled browser link.

* Good, because the prompt appears where the relevant context is available.
* Good, because submission is explicit and reviewable.
* Bad, because the setup code owns another optional interaction.

### Send Automatic Layout Telemetry

The installer reports path-layout facts without asking the user to create an issue.

* Good, because maintainers receive more complete aggregate evidence.
* Bad, because installation would disclose machine information without an explicit reviewed payload.
* Bad, because the project would need telemetry transport, retention, and privacy policies.

### Print A Generic Documentation Link

Every custom-layout install prints a page that explains how to file a feature request.

* Good, because implementation and privacy risk are small.
* Bad, because users must reconstruct and type the relevant setup details.
* Bad, because the generic message does not distinguish personal layouts from reusable conventions.

### Keep Unknown Layouts Manual

The installer supports explicit variables but never requests feedback about them.

* Good, because setup has no additional prompts.
* Bad, because maintainers lack evidence needed to make future installs simpler.
* Bad, because each user with the same convention must discover and configure it independently.

## More Information

This flow is product feedback, not diagnostics or telemetry. Diagnostic issue reporting remains a
separate explicit interaction with its own content and consent.
