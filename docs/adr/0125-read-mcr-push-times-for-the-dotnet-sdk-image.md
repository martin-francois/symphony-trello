---
status: accepted
date: 2026-10-05
decision-makers: [François Martin, Claude]
consulted:
  - "[ADR 0008](0008-renovate-and-github-actions-hardening.md)"
  - "[ADR 0054](0054-powershell-installer-verification-runtime.md)"
  - "[ADR 0124](0124-look-up-betterleaks-through-the-github-package-feed.md)"
  - "[Renovate custom datasources](https://docs.renovatebot.com/modules/datasource/custom/)"
  - "[Renovate minimum release age](https://docs.renovatebot.com/key-concepts/minimum-release-age/)"
  - "[Renovate docker datasource](https://docs.renovatebot.com/modules/datasource/docker/)"
  - "[ADR 0126](0126-offer-the-newest-oss-fuzz-build-past-the-cooldown.md)"
  - "[Microsoft Artifact Registry: .NET SDK](https://mcr.microsoft.com/product/dotnet/sdk/tags)"
  - "[.NET 8.0 release metadata](https://builds.dotnet.microsoft.com/dotnet/release-metadata/8.0/releases.json)"
informed: [Future maintainers, Contributors]
---

# Read MCR Push Times For The .NET SDK Image

## Context and Problem Statement

`scripts/pwsh-docker.sh` pins `mcr.microsoft.com/dotnet/sdk:8.0` by digest. Microsoft pushes a new
digest to the `8.0` tag for every .NET servicing release and again when the Debian base image is
patched. Renovate finds those digests but never opens a pull request. The Dependency Dashboard lists
the digest update and the 9.0, 10.0 and 11.0 majors under "Pending Status Checks" for good.

Renovate's docker datasource has release timestamps only for Docker Hub. The repository sets
`minimumReleaseAgeBehaviour: "timestamp-required"`, so an MCR update has no timestamp to age against
and stays pending. ADR 0008 accepted that outcome for registries without timestamps. ADR 0124
replaced it for the GHCR-hosted BetterLeaks image with GitHub's package feed.

The `gcr.io/oss-fuzz-base` images sit in the same list for the same reason.
[ADR 0126](0126-offer-the-newest-oss-fuzz-build-past-the-cooldown.md) covers them, because they need
a different feed; see [More Information](#more-information).

## Decision Drivers

* Renovate must open the .NET SDK update once it is seven days old.
* The seven-day cooldown from ADR 0008 must apply to this image, with no exemption.
* The timestamp must come from the registry's record of the push, not from the image.
  `docs/agents/dependency-updates.md` rejects the OCI `org.opencontainers.image.created` annotation.
* The digest Renovate writes must be the multi-platform digest the tag points to, as today.

## Considered Options

* Read the tags and push times from Microsoft Artifact Registry's catalog API.
* Take release dates from the .NET release metadata JSON.
* Take release dates from GitHub releases of `dotnet/dotnet-docker` or `dotnet/sdk`.
* Keep the docker datasource and accept that the update stays pending.

## Decision Outcome

Chosen option: "Read the tags and push times from Microsoft Artifact Registry's catalog API",
because it is the only source that has a timestamp and a digest for every push to the `8.0` tag,
including the base-image rebuilds between .NET releases.

The custom datasource `mcr-dotnet-sdk` reads
`https://mcr.microsoft.com/api/v1/catalog/dotnet/sdk/tags?reg=mar`, the endpoint the Microsoft
Artifact Registry portal uses for its tag list. It returns one record per tag with the tag's
current `digest`, its `createdDate` (the first push of the tag) and its `lastModifiedDate` (the
latest push). The transform keeps every `major.minor` tag, such as `8.0` or `10.0`, and turns it
into a release with `lastModifiedDate` as the release timestamp and `digest` as the new digest.
Patch tags like `8.0.425`, distribution tags like `8.0-bookworm-slim` and architecture tags are left
out, because the pin follows a `major.minor` tag and docker versioning compares only tags of the
same shape.

For a digest update Renovate ages the update against the timestamp of the tag it follows, so the
`8.0` digest becomes eligible seven days after Microsoft last pushed `8.0`. If Microsoft pushes
again inside those seven days, the clock restarts with the new digest, as it does for a Docker Hub
image.

On 2026-10-05 the catalog listed `8.0` at
`sha256:78235e09001f52b6592c458ac010775ebac6725422e80cd0c1650590f67b2743`, last pushed
2026-09-19T01:55:08Z. The registry returns the same digest for `8.0`, and the patch tag `8.0.425`
shares it with a creation time of 2026-09-08, the September servicing release. The 2026-09-19 push
is a rebuild of the same SDK.

### Consequences

* Good, because the .NET SDK digest update reaches a pull request after the same seven days as
  every other dependency.
* Good, because base-image rebuilds are caught as well as .NET releases.
* Good, because the catalog needs no token, so a local dry run reads the live feed.
* Bad, because the catalog endpoint is what the portal uses, not a documented API. If Microsoft
  changes it, the Dependency Dashboard shows a failed lookup for `custom.mcr-dotnet-sdk` and the
  image stays at its current digest until the datasource is fixed.
* Bad, because the response lists every tag of the repository, about 6,800 records and a few
  megabytes, for each lookup.
* Neutral, because `lastModifiedDate` is Microsoft's record of its own push. Microsoft runs both
  the registry and the image, so no third party vouches for the time. The same holds for GitHub's
  package feed in ADR 0124.

### Confirmation

This decision remains implemented when:

* the .NET SDK custom manager in `renovate.json` uses `datasourceTemplate:
  "custom.mcr-dotnet-sdk"` and `versioningTemplate: "docker"`;
* the script test `Renovate looks up MCR images through the catalog's push times` passes; and
* the Dependency Dashboard lists no failed lookup for `mcr.microsoft.com/dotnet/sdk` and shows the
  .NET SDK digest update as a pull request once its digest is seven days old.

## Pros and Cons of the Options

### Read The Tags And Push Times From Microsoft Artifact Registry's Catalog API

* Good, because each record has the tag, its current digest and its last push time.
* Good, because the time is set by the registry when the tag moves, not by the image build.
* Bad, because the endpoint is undocumented.

### Take Release Dates From The .NET Release Metadata JSON

* Good, because Microsoft documents and versions this file.
* Bad, because it dates SDK releases, not image pushes. The 2026-09-19 rebuild of `8.0` has no
  entry; the newest 8.0 entry is 8.0.31 on 2026-09-08.
* Bad, because it has no image digest, so Renovate would still need the docker datasource for the
  digest and could not tie a date to it.

### Take Release Dates From GitHub Releases Of `dotnet/dotnet-docker` Or `dotnet/sdk`

* Bad, because `dotnet/dotnet-docker` has no GitHub releases or tags, and `dotnet/sdk`
  release dates describe the SDK, not the image push.
* Bad, because the `github-releases` digest is a git commit SHA, which cannot pin an image.

### Keep The Docker Datasource And Accept That The Update Stays Pending

* Good, because nothing changes.
* Bad, because the PowerShell runtime that tests the Windows installer never moves, including
  security rebuilds of its base image.

## More Information

This decision covers only `mcr.microsoft.com/dotnet/sdk`. The `gcr.io/oss-fuzz-base/base-builder-jvm`
and `gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers` pins need a different approach. OSS-Fuzz
rebuilds them every day, so the digest a tag points to is never seven days old, and a timestamp for
the tag would keep their updates pending. The registry keeps every build with its push time,
though, and a build from a week ago is still pullable.
[ADR 0126](0126-offer-the-newest-oss-fuzz-build-past-the-cooldown.md) reads Artifact Registry's
version list and offers each pin the newest `v1` build pushed at least seven days ago.
