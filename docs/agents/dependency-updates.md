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
- Look up a GHCR image through a custom datasource that reads GitHub's container package API, which
  gives each version its push time and manifest digest. Renovate's docker datasource has release
  timestamps only for Docker Hub, so a GHCR image looked up through it never passes the cooldown.
  The script test `Renovate looks up GHCR images through a feed with release timestamps` asserts
  this; see [ADR 0124](../adr/0124-look-up-betterleaks-through-the-github-package-feed.md).
- Look up an MCR image through a custom datasource that reads the Microsoft Artifact Registry tag
  catalog. Its `lastModifiedDate` is the time MCR last pushed the tag, and its `digest` is the tag's
  current digest. The script test `Renovate looks up MCR images through the catalog's push times`
  asserts this; see [ADR 0125](../adr/0125-read-mcr-push-times-for-the-dotnet-sdk-image.md).
- The `gcr.io/oss-fuzz-base` images stay on the docker datasource and their digest updates stay
  pending. OSS-Fuzz pushes them at least daily, so a seven-day-old digest never exists; ADR 0125
  explains why no datasource changes that.
- BetterLeaks image updates automerge like other non-major updates, gated by the required check
  `pinned-betterleaks-image`. It runs the base branch's scanner with the image the pull request
  pins, because the `private-context` job scans with the base branch's image and would never run a
  new BetterLeaks rule before merge. Keep that check in the `Required merge checks` ruleset. The
  script test `BetterLeaks image updates automerge only after a scan with the image they pin`
  asserts the workflow side; see [ADR 0124](../adr/0124-look-up-betterleaks-through-the-github-package-feed.md).
- Apply a seven-day minimum release age to ordinary dependency updates. Renovate security updates
  retain their documented cooldown bypass so disclosed vulnerabilities can be fixed immediately.
- The seven-day release age MUST hold on every path that can move a version, including the paths
  Renovate does not resolve itself. Renovate's `minimumReleaseAge` governs the updates Renovate
  computes; it does not govern `lockFileMaintenance`, which runs the package manager and commits
  whatever that resolves. `pnpm-workspace.yaml` therefore sets `minimumReleaseAge: 10080`, the same
  seven days expressed in minutes, and pnpm refuses at resolution time to select a version younger
  than that cutoff. The script test `The lockfile refresh path enforces the same seven-day cooldown`
  derives the pnpm value from `renovate.json` and fails if the two ever disagree.
- Keep `lockFileMaintenance` enabled on a weekly off-hours schedule with automatic merge. Renovate
  and `vulnerabilityAlerts` reach only the declarations a manifest names, so an advisory against a
  package that exists only inside a lockfile stays open until that lockfile is regenerated. The
  refresh is its own pull request and merges only after required checks pass, and the package
  manager holds the release-age line the refresh would otherwise cross; see
  [ADR 0077](../adr/0077-refresh-lockfiles-on-a-schedule.md). The script test `Renovate refreshes
  lockfiles on a schedule so transitive advisories are fixed` asserts the rule.
- Keep pnpm's `pmOnFail: ignore` while GitHub's static dependency-graph parser cannot read pnpm 12's
  leading package-manager environment document. This preserves a single-document project lockfile
  so vulnerability alerts continue to see transitive dependencies. Because that setting disables
  pnpm's internal version switch, keep `packageManager` exact and make every CI pnpm entry point use
  Corepack to prepare that same version. The script test `pnpm keeps GitHub's dependency graph
  readable while Corepack pins its version` asserts the complete contract.
- Automatic merge MUST occur only through a pull request after required checks pass. Major updates,
  Quarkus migrations, generated source changes, and vendored guidance remain review-required under
  their owning rules. The `Required merge checks` ruleset on the default branch enforces the
  required checks, linear history, and thread resolution, and the script test `major update pull
  requests require manual merge` asserts the major-update policy.
- This public repository groups dependencies by release or compatibility contract. Unrelated
  non-major updates have separate pull requests and no weekly update schedule.
- Keep majors reviewed and separate from non-major updates. Keep the coordinated OpenRewrite
  group and the Quarkus and vendored-guidance review exceptions intact. A passing check MUST NOT
  enable automatic merging for a dependency whose rule requires manual review.
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
- [ADR 0124](../adr/0124-look-up-betterleaks-through-the-github-package-feed.md)
- [ADR 0125](../adr/0125-read-mcr-push-times-for-the-dotnet-sdk-image.md)
