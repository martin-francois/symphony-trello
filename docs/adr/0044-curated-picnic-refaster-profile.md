---
status: accepted
date: 2026-06-01
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #137](https://github.com/martin-francois/symphony-trello/issues/137)"
  - "[GitHub issue #130](https://github.com/martin-francois/symphony-trello/issues/130)"
  - "[ADR 0041](0041-report-only-picnic-error-prone-profile.md)"
  - "pom.xml"
informed: [Future maintainers, Contributors]
---

# Curate the Picnic Refaster Profile

## Context and Problem Statement

[ADR 0041](0041-report-only-picnic-error-prone-profile.md) added a report-only
`picnic-refaster` profile. The current baseline reports 195 rewrite suggestions.

Refaster suggestions are source rewrites, not normal bug checks. Some suggestions are useful
modernization. Others would replace clear JDK code with Guava-specific style or would conflict with
the project's Java Optional guidance.

## Decision Drivers

* Keep `./mvnw -q spotless:check verify` unchanged.
* Review Refaster rewrites before applying them.
* Prefer JDK APIs and current project style unless a Guava API clearly improves the code.
* Keep Java Optional code readable and aligned with the Java Optional guidance.
* Keep the Refaster profile useful as a focused local feedback loop.

## Considered Options

* Apply all Refaster suggestions.
* Keep the profile broad and leave all unaccepted suggestions in the report.
* Disable the Refaster profile.
* Apply the clearly useful rewrites and narrow the profile to the accepted rule families.

## Decision Outcome

Chosen option: "Apply the clearly useful rewrites and narrow the profile to the accepted rule
families", because it keeps the profile actionable without adopting style changes the project does
not want.

The profile now uses `Refaster:NamePattern` to include only these accepted families. The warning
output shows names with dots, but the filter uses Refaster resource names with `$` separators.

* `CollectionRules.CollectionAddAllToCollectionBlock`
* `CollectionRules.CollectionContains`
* `NullRules.RequireNonNullElseGet`
* `PreconditionsRules.CheckArgumentWithMessage`
* `PreconditionsRules.CheckStateWithMessage`
* `PrimitiveRules.MathClampInt`
* `StreamRules.StreamNoneMatch`
* `StringBuilderRules.StringBuilderRepeat`

The accepted rewrites are applied where they appeared in the current baseline. They use direct JDK
APIs or existing Guava validation helpers where the result is clearer, and keep behavior unchanged.

The following families are rejected for this project at this time:

* `ImmutableListRules`, `ImmutableMapRules`, and `ImmutableSetRules`: the current JDK collection
  factories are clear and immutable for these call sites. Replacing them with Guava factories would
  create broad style churn without improving behavior.
* `OptionalRules`: several suggested rewrites make Optional code harder to read or conflict with the
  project's Optional guidance. Optional cleanup remains covered by explicit review and the dedicated
  Optional rules.
* `FileRules.FilesReadString`: keep this Picnic rule outside the Refaster profile because
  [ADR 0068](0068-curated-openrewrite-maintenance-lane.md) now gives the broader, consistently
  measured `RedundantUtf8Charset` OpenRewrite leaf ownership of redundant charset cleanup. ADR 0068
  supersedes this ADR's earlier conclusion that repeating `StandardCharsets.UTF_8` on Java APIs
  whose contract already specifies UTF-8 is clearer.
* `StringRules`, `StreamRules.StreamMapFirst`, `StreamRules.StreamCollectLeastStream`, and
  `ComparatorRules`: the suggested rewrites are not clearly more readable in the current call
  sites.

[ADR 0045](0045-enforce-error-prone-and-picnic-in-verify.md) later promotes the selected Refaster
rule families into normal verification.

### Consequences

* Good, because the accepted Refaster rule families are now clean.
* Good, because future drift in the accepted families remains visible.
* Good, because rejected style families no longer bury useful signal.
* Bad, because rejected families will not be reported unless the pattern is changed.
* Bad, because broader Guava collection style adoption would need a new decision if the project
  wants it later.

### Confirmation

Run:

```bash
./mvnw -q spotless:check verify
```

Normal verification should complete without Refaster suggestions for the accepted rule families.

## Pros and Cons of the Options

### Apply All Refaster Suggestions

Apply every suggested rewrite from the baseline.

* Good, because the report becomes clean.
* Bad, because many changes are style-only.
* Bad, because Guava collection usage would become mixed into the codebase without a separate style
  decision.
* Bad, because some Optional rewrites would make the code less readable.

### Keep the Profile Broad

Leave all Refaster rules enabled and document the rejected families.

* Good, because maintainers can still see every upstream suggestion.
* Bad, because the profile would keep reporting known rejected suggestions.
* Bad, because the high-volume rejected findings would hide future useful findings.

### Disable the Refaster Profile

Remove or stop using the Refaster profile.

* Good, because it removes optional warning output.
* Bad, because the accepted rule families are useful and low-risk.
* Bad, because this would lose a local static-analysis feedback loop.

### Apply Useful Rewrites and Narrow the Profile

Apply only the useful rewrites and include only those rule families in the profile.

* Good, because each accepted rewrite improves readability or directness.
* Good, because the selected Refaster rule set stays actionable.
* Bad, because future maintainers must update the `NamePattern` before evaluating new Refaster
  families.

## More Information

The current baseline before narrowing was 195 suggestions. The largest rejected groups were
`ImmutableListRules`, `ImmutableMapRules`, `ImmutableSetRules`, `OptionalRules`, and
`FileRules.FilesReadString`.

This decision does not reject Guava broadly. The repository already uses Guava where it clearly
helps. It only rejects adopting Guava immutable collection factories as a repository-wide style
through Refaster.
