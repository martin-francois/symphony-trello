# Specification & ADR discipline

## Scope

How `SPEC.md` governs behavior and when and how to write Architecture Decision Records. Issue-triage
label mechanics live in [GitHub issue triage](github-issue-triage.md).

## SPEC.md as the contract

- Before making code changes, check the relevant `SPEC.md` contract. If the requested change would
  break or narrow conformance to the adapted Symphony-for-Trello spec or the original upstream
  Symphony intent, stop and ask the user for explicit confirmation before implementing it.
- Treat `SPEC.md` as the normative contract for this project. Keep spec-defined concepts as close as
  practical to the upstream Symphony specification, adapted only where Trello requires a different
  tracker model. The upstream reference implementation is useful evidence for intent and edge cases.
  A difference from it is acceptable when the Java behavior still follows `SPEC.md`, fits Trello, and
  is covered by project ADRs. Do not copy reference behavior merely because it exists.
- Keep `SPEC.md` wording close to the original Symphony specification style so the adapted spec reads
  consistently with upstream. Do not rewrite spec text into the simpler README or contributor-doc
  style unless the existing wording is unclear or wrong. When simplifying spec wording, preserve the
  formal contract shape and the upstream terminology that still applies.
- Use `SPEC.md` for normative behavior and compatibility requirements. Use ADRs to explain why a
  decision was made, which alternatives were considered, and how to confirm the decision still holds.
  When a decision changes required behavior, update both: `SPEC.md` for the contract and an ADR for
  the rationale. If `SPEC.md` and an ADR conflict, treat `SPEC.md` as authoritative and update the
  ADR or create an issue for the mismatch.
- If Symphony for Trello intentionally differs from the upstream Symphony specification for a reason
  other than Trello adaptation, update `SPEC.md` with the local contract and add or update an ADR
  that labels the decision as an intentional upstream divergence, explains why it is needed, and
  states what would need to change before realigning with upstream.
- When the spec is ambiguous, compare plausible options and choose the least surprising option that
  preserves conformance.
- Do not silently reinterpret `SPEC.md`. If the implementation needs a clarified adaptation, update
  the spec wording carefully without breaking conformance to the original Symphony intent.
- When adding or changing behavior that extends beyond `SPEC.md` but does not conflict with it,
  append the extension contract to `SPEC.md` in the same change. Keep implementation-specific
  extensions clearly labeled as optional or Java implementation extensions so the core adapted
  Symphony contract stays readable.
- When asked to audit specification alignment, compare `SPEC.md` with the current conversation, ADRs,
  README, generated workflow, skills, deployment docs, and implementation in cycles. In each cycle,
  update `SPEC.md` for any supported behavior or optional extension that is missing from the
  contract. Stop only after a full pass finds nothing else to add. If the implementation violates the
  updated spec, create or update a GitHub issue with the exact mismatch, affected behavior, and
  acceptance criteria instead of silently changing the contract to fit the code.
- When drafting or refining GitHub issues for behavior that `SPEC.md` leaves implementation-defined,
  describe the desired outcome and explicitly compare plausible implementation layers. Do not assume
  the Java scheduler/service must own behavior that may fit better in generated workflow text,
  repository-local skills, or scoped agent tools.

## When to write an ADR

- If a decision explains why an obvious alternative was not chosen, record it in `docs/adr/` using
  the official MADR template structure: YAML front matter with `status`, `date`, `decision-makers`,
  `consulted`, and `informed`; then `Context and Problem Statement`, `Decision Drivers`,
  `Considered Options`, `Decision Outcome`, `Consequences`, `Confirmation`,
  `Pros and Cons of the Options`, and `More Information` when relevant. Fill the sections with
  concrete project-specific content; do not leave template placeholders or write a short free-form
  note.
- Create or update an ADR whenever the user and agent discuss multiple implementation options and
  decide on one, or whenever implementation work requires choosing between meaningful alternatives.
  The ADR must make the selected approach explicit, list the alternatives considered, and explain why
  they were not chosen. Do not leave these decisions only in chat, PR comments, or commit messages.
- Treat evaluated tool, dependency, scanner, analyzer, CI gate, and wrapper-vs-premade decisions as
  ADR-worthy when more than one plausible maintained option exists. This especially applies when a
  user rejects an in-house or hand-rolled implementation in favor of a premade solution. Record the
  chosen tool, the rejected tools or custom approach, and the operational tradeoffs in the same PR.
- Treat CI runner and execution-runtime choices as ADR-worthy when more than one practical option
  exists. Examples include choosing native Windows runners instead of Linux Docker for PowerShell,
  moving checks between jobs, deciding whether local fallbacks remain supported, or deciding that a
  best-effort platform should warn instead of block. Record the options, the chosen runtime, the
  local verification fallback, and the reason rejected runtimes were not chosen.
- When an implementation approach, refactor, dependency, tool, API shape, user-flow behavior, or
  testing strategy is attempted or seriously considered and then rejected for non-obvious reasons,
  create or update an ADR that records the rejected approach and why. The goal is to prevent future
  maintainers or agents from repeating attractive but unsuitable work and rediscovering the same
  problem.
- When a conversation resolves a durable tradeoff that future maintainers would otherwise have to
  rediscover, add or update an ADR in the same change. Signals include choosing one reasonable
  approach over another, removing or narrowing a supported path, accepting a user-facing tradeoff, or
  explaining why an obvious alternative should not be used.
- When implementation code needs a rationale that cannot be made obvious with names, types, or a
  narrow local comment, treat that as an ADR signal. Local comments can explain immediate mechanics;
  ADRs should capture durable reasons, rejected alternatives, and tradeoffs that future maintainers
  would otherwise rediscover.
- When a session contains multiple durable design decisions, review the recent conversation and
  commit history before finishing so relevant decisions are captured in ADRs instead of living only
  in chat.
- If the user has to ask for an ADR after a deliberate design decision was already made or explained,
  do not treat that as a one-off miss. Add or update the ADR, then strengthen the agent instructions
  that should have triggered the ADR check so future agents perform it before being asked.
- Treat naming decisions for public CLI flags, commands, config fields, workflow fields, API fields,
  labels, or other user-visible contract terms as ADR-worthy when multiple plausible names were
  discussed or rejected. Capture the chosen name, rejected names, why each was rejected, and the
  decision criteria in the same change.

## How to write ADRs

- Before adding an ADR, inspect `docs/adr/` and use the next unused numeric prefix for the target
  branch. Do not create duplicate ADR numbers.
- Follow MADR status semantics. New decisions normally use `status: accepted`. When a later ADR
  supersedes an earlier accepted decision, update the earlier ADR status to
  `superseded by [ADR 0000](0000-short-name.md)` and add a short note near the top explaining what
  superseded it. Do not leave an ADR as simply `accepted` when the decision is no longer current.
- In the `Pros and Cons of the Options` section, start every option subsection with one or two
  sentences that describe what the option concretely is or does before any Good/Bad bullets. A reader
  must be able to understand the chosen approach from its option subsection alone; bullets judge an
  approach, they do not define it.
- Write ADRs in simple, factual language. Avoid flowery wording, vague emphasis, and unnecessarily
  long sentences. Use words and sentence structures that are clear for non-native English speakers,
  while still following the MADR template exactly. Prefer clear, unambiguous user-manual style over
  polished prose; readers should not have to infer what the decision means.
- In ADRs, reference GitHub issues and pull requests with descriptive full Markdown links, not
  shorthand references. Use
  `[GitHub issue #6](https://github.com/martin-francois/symphony-trello/issues/6)` or
  `[GitHub PR #116](https://github.com/martin-francois/symphony-trello/pull/116)`. Apply the same
  format in ADR front matter such as `consulted`; quote Markdown links in YAML arrays. Do not use
  bare `#6`, `issue #6`, `PR #116`, or bare GitHub URLs in ADRs.
- When adding or updating ADRs, document the options that were seriously considered, why the chosen
  option won, what becomes easier, what becomes worse, and how future maintainers can confirm the
  decision is still implemented.
- Run markdownlint when changing Markdown, especially ADRs. The CI lint job is based on MADR's
  markdownlint workflow and uses `.markdownlint-cli2.yaml`; do not disable rules inline unless the
  exception is narrow and justified.

## References

- [GitHub issue triage](github-issue-triage.md)
- [Default workflow](default-workflow.md)
- [Documentation & README](documentation-and-readme.md)
