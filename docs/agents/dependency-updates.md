# Dependency Updates

## Scope

This page defines dependency declaration, Renovate ownership, release-age, and automatic-merge
requirements.

## Rules

- Pin downloaded dependencies and tools to an exact version, immutable commit, verified checksum,
  or container digest wherever the ecosystem supports it. Keep a readable version tag beside an
  image digest. Compatibility declarations MAY remain ranges when they are not downloaded artifacts.
- Every active third-party version declaration MUST be owned by a built-in Renovate manager or an
  explicit custom manager. The Renovate config validator and script tests enforce native and
  nonstandard declarations. A new nonstandard declaration MUST add manager and regression coverage
  in the same change.
- Keep `pinDigests` enabled. Renovate MUST keep digest updates enabled and use the matched version's
  release timestamp when its datasource provides one. With
  `minimumReleaseAgeBehaviour: "timestamp-required"`, a digest update without that evidence remains
  pending. Do not use the publisher-controlled OCI `org.opencontainers.image.created` annotation as
  release-age evidence. The script test `Renovate enforces the repository-wide seven-day dependency
  cooldown` asserts `pinDigests` and that `digest`, `pin`, and `pinDigest` remain enabled.
- Apply a seven-day minimum release age to ordinary dependency updates. Renovate security updates
  retain their documented cooldown bypass so disclosed vulnerabilities can be fixed immediately.
- Automatic merge MUST occur only through a pull request after required checks pass. Major updates,
  Quarkus migrations, generated source changes, and vendored guidance remain review-required under
  their owning rules. The `Required merge checks` ruleset on the default branch enforces the
  required checks, linear history, and thread resolution, and the script test `major update pull
  requests require manual merge` asserts the major-update policy.
- Group dependencies by release or compatibility contract, with one deliberate exception: the
  repository-wide non-major bundle described in
  [ADR 0076](../adr/0076-separate-major-updates-from-the-automergeable-bundle.md). That bundle is a
  cost decision, not a compatibility claim, and it is deliberately limited to non-major updates so
  the blast radius of a failure stays small.
- A group MUST NOT hide which dependency caused a failed check. Automation cannot prove this rule;
  pull-request review of the failing check's attribution is the required evidence before merging a
  group. When the non-major bundle fails, attribute the failure by bisecting the bundle locally
  rather than by re-running the pull request, which is both faster and free.
- Major updates MUST NOT share a branch with the automergeable non-major bundle, because a single
  review-required major would otherwise suppress automatic merging for every routine update
  travelling with it. A package that requires a manual merge MUST leave that bundle through
  `groupName: null` rather than through `automerge: false` alone, for the same reason. The script
  tests `major updates never join the automergeable non-major bundle` and `a manual-merge package is
  excluded from the automergeable bundle` enforce both rules; see
  [ADR 0076](../adr/0076-separate-major-updates-from-the-automergeable-bundle.md).
- For each direct dependency and build plugin, document its failure surface and the required check
  that detects a bad update. A dependency is eligible for automatic merge only when that evidence is
  a required check. The script test enforces complete coverage in
  [Dependency upgrade confidence](../testing/dependency-upgrade-confidence.md).
- Re-measure verification concurrency and cache behavior after material runner, toolchain, or suite
  changes. Choose the fastest repeatably passing configuration; do not trade away determinism or
  coverage for a higher worker count.

## References

- [Static analysis policy](static-analysis.md)
- [Testing](testing.md)
- [ADR 0008](../adr/0008-renovate-and-github-actions-hardening.md)
