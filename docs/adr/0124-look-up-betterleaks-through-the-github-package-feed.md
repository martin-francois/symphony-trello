---
status: accepted
date: 2026-10-05
decision-makers: [François Martin, Claude]
consulted:
  - "[ADR 0008](0008-renovate-and-github-actions-hardening.md)"
  - "[ADR 0047](0047-betterleaks-private-context-scanning.md)"
  - "[Renovate custom datasources](https://docs.renovatebot.com/modules/datasource/custom/)"
  - "[Renovate minimum release age](https://docs.renovatebot.com/key-concepts/minimum-release-age/)"
  - "[Renovate docker datasource](https://docs.renovatebot.com/modules/datasource/docker/)"
  - "[GitHub REST API: list package versions for a user's package](https://docs.github.com/en/rest/packages/packages#list-package-versions-for-a-package-owned-by-a-user)"
  - "[BetterLeaks generic-credential-uri rule](https://github.com/betterleaks/betterleaks/blob/v1.9.0/cmd/generate/config/rules/generic_credential_uri.go)"
  - "[BetterLeaks configuration](https://github.com/betterleaks/betterleaks/blob/v1.9.0/docs/config.md)"
  - "[GitHub Security Lab: Preventing pwn requests](https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/)"
informed: [Future maintainers, Contributors]
---

# Look Up BetterLeaks Through The GitHub Package Feed

## Context and Problem Statement

`scripts/betterleaks-docker.sh` pins `ghcr.io/betterleaks/betterleaks:v1.4.1` by digest. Renovate
never proposed an update, although BetterLeaks has released eleven newer stable versions, up to 1.9.0.
The Dependency Dashboard reported `Could not determine new digest for update (docker package
ghcr.io/betterleaks/betterleaks)`.

Two independent faults keep the image where it is. A local Renovate 44.115.13 dry run shows both:

1. The custom manager captures the version without its `v` (`1.4.1`), while every GHCR tag carries
   one (`v1.4.1`). The docker versioning still recognizes `v1.9.0` as a newer version, but Renovate
   then asks the registry for the digest of tag `1.9.0`, receives 404, and drops the update with
   the warning above. The same happens for the current version.
2. Renovate's docker datasource has release timestamps only for Docker Hub. The repository sets
   `minimumReleaseAgeBehaviour: "timestamp-required"`, so the dry run marks all 11 newer GHCR
   releases as pending. Fixing the prefix alone would turn the warning into an update that waits
   forever.

Unlocking the update exposes a third problem. BetterLeaks 1.8.0 added the built-in rule
`generic-credential-uri`. It reports six synthetic credential URIs in the test sources, such as
`token:secret@example.invalid`, at low confidence. The private-context check on a
pull request runs the scanner from the base branch, so a Renovate pull request with the new image
passes and automerges, and the first scan with the new image runs on `main`. That scan fails, and
from then on every pull request fails too, because the base scanner is now the new one.

## Decision Drivers

* Renovate must propose BetterLeaks updates again.
* The seven-day cooldown from ADR 0008 must apply to this image, with no exemption.
* The release timestamp must come from the registry operator, not from the image publisher.
  `docs/agents/dependency-updates.md` already rejects the OCI `org.opencontainers.image.created`
  annotation for that reason.
* The scanner's trust boundary from ADR 0047 stays as it is: pull requests are scanned by the base
  branch's scanner.
* A dependency automerges only when a required check exercises the new version.
* A BetterLeaks update should automerge like every other non-major update, with no manual step.
* A job that runs an image chosen by a pull request must hold no secret and no write token.

## Considered Options

* Look the image up through a custom datasource that reads GitHub's container package API.
* Keep the docker datasource and only capture the `v` prefix.
* Keep the docker datasource and set `minimumReleaseAgeBehaviour: "timestamp-optional"` for the
  image.
* Take versions from the `github-releases` datasource for `betterleaks/betterleaks`.

## Decision Outcome

Chosen option: "Look the image up through a custom datasource that reads GitHub's container
package API", because it is the only option that both finds the updates and gives each one a
timestamp the cooldown can use.

The custom datasource `betterleaks-ghcr` reads
`https://api.github.com/users/betterleaks/packages/container/betterleaks/versions?per_page=100`.
GitHub returns one record per package version, with its tags, its manifest digest (`name`) and its
`created_at`. The transform turns every exact `vX.Y.Z` tag into release `X.Y.Z` with that timestamp
and digest. Release candidates such as `v2.0.0-rc.1`, the `v1` alias, `latest` and the `sha256-*`
signature tags are left out. The custom manager keeps capturing the version without its `v`, so
Renovate replaces `1.4.1` inside `v1.4.1` and the script keeps the registry's tag spelling.

foodOrganizationApp uses the same endpoint and transform for the OpenTofu image under the hosted
Renovate app. Its Dependency Dashboard lists the OpenTofu 1.13.0 update found through that feed and
reports no failed lookup.

The datasource reads the newest 100 records. Each BetterLeaks release creates about six: the
multi-architecture index, two platform images, two attestation manifests and a signature. The
window therefore covers roughly the last 16 releases.

Two changes keep the unlocked update from breaking `main`:

* The six flagged test fixtures carry an inline `betterleaks:allow` marker, which BetterLeaks honors
  on the line it appears on. The one fixture inside a Java text block, where a marker would become
  test data, uses `user:password@example.invalid` instead, a form the rule discards as synthetic.
* A new job, `pinned-betterleaks-image` in the private-context workflow, scans a pull request with
  the image it pins, and the `Required merge checks` ruleset requires it. The job reads the image
  `scripts/betterleaks-docker.sh` runs on the base and on the head: the default of its single
  `image=` assignment, which must be a digest-pinned `ghcr.io/betterleaks/betterleaks` reference,
  with `"$image"` passed to `exec`. A wrapper in any other form fails the job, because the image it
  runs cannot be read from it. When the two images differ, it runs the base branch's
  `scripts/check-private-context --worktree` and rule file against the pull request worktree, with
  `SYMPHONY_TRELLO_BETTERLEAKS_IMAGE` set to the head image. When they match, the `private-context`
  job already covers the image and the new job only reports success. The BetterLeaks update keeps
  the ordinary non-major automerge, and GitHub merges it only after this check passes.

The job runs on `pull_request`, never on `pull_request_target`, with `contents: read` and checkouts
that keep no credentials. It references no secret, and it rejects any image outside the BetterLeaks
repository, so a pull request can choose among images the BetterLeaks project published but cannot
supply its own. It adds a scan and replaces none: the `private-context` job still scans every pull
request with the base branch's image.

### Consequences

* Good, because Renovate proposes BetterLeaks updates again, starting with 1.8.1 while 1.9.0 is
  inside the cooldown.
* Good, because the update waits for the same seven days as every other dependency, measured from
  the time GitHub recorded the image push.
* Good, because the digest Renovate writes is the multi-architecture index digest, the same kind of
  digest the script pins today.
* Good, because a new BetterLeaks rule that flags existing files is caught before merge rather than
  on `main`.
* Good, because a BetterLeaks update that passes the scan with its own image merges without a
  maintainer.
* Bad, because the required check lives in the repository ruleset, outside version control. The
  script test covers the workflow, not the ruleset.
* Bad, because the GitHub package API requires a token with package read access. The hosted Renovate
  app has one; a local dry run with a token that lacks `read:packages` gets 403 and has to point the
  datasource at a fixture.
* Bad, because the datasource only sees the newest 100 package records. A release older than that
  window cannot be selected, which matters only if the pin falls about 16 releases behind.
* Neutral, because other images in registries without release timestamps keep the behavior ADR 0008
  describes until a decision of their own changes it.
  [ADR 0125](0125-read-mcr-push-times-for-the-dotnet-sdk-image.md) covers
  `mcr.microsoft.com/dotnet/sdk`, and
  [ADR 0126](0126-offer-the-newest-oss-fuzz-build-past-the-cooldown.md) the `gcr.io/oss-fuzz-base`
  images.

### Confirmation

This decision remains implemented when:

* the BetterLeaks custom manager in `renovate.json` uses `datasourceTemplate:
  "custom.betterleaks-ghcr"` and `versioningTemplate: "semver"`;
* the script test `Renovate looks up GHCR images through a feed with release timestamps` passes,
  which requires every custom manager for a `ghcr.io` image to use a custom datasource reading
  GitHub's package API;
* the script test `BetterLeaks image updates automerge only after a scan with the image they pin`
  passes;
* `ContainerRuntimeScriptTest` shows that `scripts/betterleaks-docker.sh` runs the image named by
  `SYMPHONY_TRELLO_BETTERLEAKS_IMAGE`;
* `gh api repos/martin-francois/symphony-trello/rulesets --jq '.[].name'` lists `Required merge
  checks`, and that ruleset's `required_status_checks` include `pinned-betterleaks-image`; and
* the Dependency Dashboard lists no failed lookup for `ghcr.io/betterleaks/betterleaks`.

## Pros and Cons of the Options

### Look The Image Up Through A Custom Datasource That Reads GitHub's Container Package API

* Good, because every release gets a timestamp, so the cooldown applies unchanged.
* Good, because every release gets its digest from the same record, so no registry call can miss
  the tag spelling.
* Good, because it reuses a transform that already runs under the hosted Renovate app in
  foodOrganizationApp.
* Bad, because the endpoint, the transform and the owner name are repository configuration that has
  to be kept correct by hand.

### Keep The Docker Datasource And Only Capture The `v` Prefix

* Good, because it is a one-line change.
* Bad, because the docker datasource has no timestamp for GHCR, so under `timestamp-required` every
  release stays pending. The lookup error would disappear and no pull request would follow.

### Keep The Docker Datasource And Set `minimumReleaseAgeBehaviour: "timestamp-optional"` For The Image

* Good, because updates would flow without a custom datasource.
* Bad, because a release without a timestamp would then pass the cooldown at once, so the image
  would update with no wait at all.
* Bad, because the script test `Nothing quietly exempts a package from the seven-day cooldown`
  rejects any package rule that sets `minimumReleaseAgeBehaviour`.

### Take Versions From The `github-releases` Datasource For `betterleaks/betterleaks`

* Good, because GitHub releases carry a publication timestamp.
* Bad, because that timestamp describes the release page, not the image push.
* Bad, because the datasource's digest is a git commit SHA, which cannot pin a container image.

### Options For Testing A New Image Before It Merges

* Turn automerge off for the image and ask the maintainer to run `scripts/check-private-context
  --worktree` on the Renovate branch. It works, but every BetterLeaks release then waits for a
  person, and nothing checks that the scan ran. Rejected in favor of the required job.
* Scan with the head image inside the existing `private-context` job and make that job required.
  It would also turn the base-image scan into a merge requirement, a policy change beyond this
  decision. A separate job keeps the two concerns apart and has a name that says what it gates.
* Scan inside the required `test` job. That job runs the pull request's own scripts on a paid
  Blacksmith runner, so the scan would use pull-request-controlled scanner code and add cost to
  every pull request. Rejected.
* Run the scan on `pull_request_target`. That event gives the job the base repository's secrets
  and a token that can write, while the image comes from the pull request. Rejected.

### Options For The Fixtures Newer BetterLeaks Versions Flag

These were compared for the six fixtures flagged by `generic-credential-uri`:

* `--confidence medium` on the built-in pass would drop every low-confidence finding of every rule.
  `generic-api-key` starts at low confidence and raises a finding to medium only when the key
  name has a separated `token` suffix such as `api_token`, so the scan would lose its other findings. Rejected because it
  narrows coverage.
* A trusted config that extends the built-in rules and adds a global `filter` would replace the
  built-in global filter instead of adding to it, because BetterLeaks keeps the extending config's
  filter when both set one. Overriding the rule's own filter has the same problem. Rejected.
* A `.betterleaksignore` file lists findings by path, rule and line number, so any edit above a
  fixture would bring the finding back. Rejected.
* Rewriting every fixture to `user:password@example.invalid` would change test data that asserts on
  `secret` and on the `git` and `token` user names. Used only for the text block, where an inline
  marker is not possible.

## More Information

[ADR 0008](0008-renovate-and-github-actions-hardening.md) set the seven-day cooldown and the
`timestamp-required` behavior this decision keeps.
[ADR 0047](0047-betterleaks-private-context-scanning.md) chose BetterLeaks and the trusted-scanner
workflow whose base-branch scan is the reason a new image needs its own required job.
