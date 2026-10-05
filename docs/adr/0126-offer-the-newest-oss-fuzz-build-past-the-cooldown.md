---
status: accepted
date: 2026-10-05
decision-makers: [François Martin, Claude]
consulted:
  - "[ADR 0008](0008-renovate-and-github-actions-hardening.md)"
  - "[ADR 0078](0078-github-actions-continuous-fuzzing.md)"
  - "[ADR 0124](0124-look-up-betterleaks-through-the-github-package-feed.md)"
  - "[ADR 0125](0125-read-mcr-push-times-for-the-dotnet-sdk-image.md)"
  - "[Renovate custom datasources](https://docs.renovatebot.com/modules/datasource/custom/)"
  - "[Renovate minimum release age](https://docs.renovatebot.com/key-concepts/minimum-release-age/)"
  - "[Artifact Registry API: list versions](https://cloud.google.com/artifact-registry/docs/reference/rest/v1/projects.locations.repositories.packages.versions/list)"
  - "[OSS-Fuzz base image build](https://github.com/google/oss-fuzz/blob/master/infra/build/functions/base_images.py)"
  - "[OSS-Fuzz ClusterFuzzLite image build](https://github.com/google/oss-fuzz/blob/master/infra/cifuzz/cloudbuild.yaml)"
informed: [Future maintainers, Contributors]
---

# Offer The Newest OSS-Fuzz Build Past The Cooldown

## Context and Problem Statement

Three `FROM` lines pin images that OSS-Fuzz publishes on `gcr.io`:

* `.clusterfuzzlite/Dockerfile` and `oss-fuzz/Dockerfile` pin
  `gcr.io/oss-fuzz-base/base-builder-jvm@sha256:...` by digest, without a tag. The digest is a
  build OSS-Fuzz pushed on 2026-07-13 and tagged `v1` and `latest`.
* `.clusterfuzzlite/coverage-runner.Dockerfile` pins
  `gcr.io/oss-fuzz-base/clusterfuzzlite-run-fuzzers:v1@sha256:...`, a build from 2026-08-30.

Renovate looked both images up through its docker datasource, which has release timestamps only for
Docker Hub. Under `minimumReleaseAgeBehaviour: "timestamp-required"` a Renovate 44.116.1 dry run
on `main` logs `digest update of gcr.io/oss-fuzz-base/base-builder-jvm has no releaseTimestamp to
age against` and marks both digest updates `pendingChecks`. The Dependency Dashboard lists them as
pending for good.

A timestamp for the tag alone would not help. OSS-Fuzz rebuilds both images every day, so the
digest `v1` points to is always less than a day old and would never pass a seven-day wait. The
registry keeps every build it was given, though. On 2026-10-05 the version list of
`base-builder-jvm` reached back to 2021, and a manifest request for a build 7, 30, 90, 365 or
1,000 days old returned 200 for both images. A build pushed a week ago is still pullable, so each
push can be treated as a release with its own date.

## Decision Drivers

* Renovate must move each pin to a build that is at least seven days old.
* The seven-day cooldown from ADR 0008 must apply to these images, with no exemption.
* The timestamp must be the registry's record of the push, not a field of the image.
* The pin must stay on the build `v1` stands for. OSS-Fuzz pushes other builds of the same images
  every day: `base-builder-jvm` also gets an `ubuntu-20-04` and an `ubuntu-24-04` build, and
  `clusterfuzzlite-run-fuzzers` an `ubuntu-24-04` build. The pinned runner is Ubuntu 20.04, and an
  Ubuntu 24.04 builder would build fuzzers against newer system libraries than the runner has.
* Renovate must change only the digest. The builder keeps its untagged `FROM` line and the runner
  its `:v1` tag.
* The new digest must be the same kind as the pinned one: a single-platform image manifest, not an
  index.

## Considered Options

* A custom datasource on Artifact Registry's version list that offers the newest `v1` build at
  least seven days old as release `v1`.
* A custom datasource that lists every `v1` build as its own release, versioned by push time, so
  that Renovate's `internalChecksFilter` picks the newest one past the cooldown.
* The same datasource on the registry's `tags/list` endpoint instead of Artifact Registry.
* A repository-owned record of the digests `v1` pointed to, written by a scheduled workflow.
* Keep the docker datasource and accept that the updates stay pending.

## Decision Outcome

Chosen option: "A custom datasource on Artifact Registry's version list that offers the newest
`v1` build at least seven days old as release `v1`", because it is the only option that Renovate can
apply to a `FROM` line without a version and that tells the `v1` builds apart from the others.

Each image has its own datasource, `oss-fuzz-base-builder-jvm` and
`oss-fuzz-clusterfuzzlite-run-fuzzers`. Both read
`https://artifactregistry.googleapis.com/v1/projects/oss-fuzz-base/locations/us/repositories/gcr.io/packages/<image>/versions?view=FULL&orderBy=create_time+desc&pageSize=1000`.
`gcr.io` is served from that Artifact Registry repository, and the API answers without a token for
this public repository. Each record has the digest, the `mediaType`, `createTime`, `updateTime` and
the tags it carries now. `createTime` is the push time; it matches the `timeUploadedMs` the registry
reports for the same digest.

The transform keeps image manifests (`application/vnd.docker.distribution.manifest.v2+json`), the
kind both pins are. That leaves out the build-provenance attachments Cloud Build pushes beside the
runner. It then selects the `v1` builds:

* `base-builder-jvm`: OSS-Fuzz pushes the `ubuntu-20-04` and `ubuntu-24-04` builds with one tag
  each. When the push moves that tag, Artifact Registry stamps the previous build's `updateTime`
  with the new build's `createTime`, equal to the microsecond. The `v1` build carries `v1` and `latest`, and the second tag moves minutes after the
  push. The `v1` builds are therefore the image manifests whose `createTime` is no other version's
  `updateTime`.
* `clusterfuzzlite-run-fuzzers`: both builds carry two tags, so the update times do not separate
  them. OSS-Fuzz's Cloud Build lists the `v1` images before the `ubuntu-24-04` ones, and every
  build pushed them 0.5 to 2 seconds apart, with at least 8 seconds to the next build. The `v1`
  builds are the image manifests that start such a pair: the previous image manifest is more than
  5 seconds older and the next one at most 5 seconds newer.

Images that carry any tag other than `v1` or `latest`, such as OSS-Fuzz's `testing-*` builds, are
left out. The rules were checked against the image configurations, whose Ubuntu version label and
build-script names identify the lineage. For `base-builder-jvm` all 87 image manifests since
2026-07-13 matched; OSS-Fuzz pushed none between 2026-07-30 and 2026-09-20. For
`clusterfuzzlite-run-fuzzers` all 286 since 2026-08-22 matched.

The newest selected build must be the one tagged `v1`. If it is not, the transform raises an error,
the Dependency Dashboard reports a failed lookup for the datasource, and no update is offered. A
change in how OSS-Fuzz tags or pushes these images therefore stops the updates instead of moving
the pin to another lineage.

From the selected builds the transform takes the newest one pushed at least seven days ago and
returns it as release `v1`, with `createTime` as the release timestamp and its digest. The custom
managers give both pins the current value `v1`: the runner's line carries it, and the builder's
manager sets it with `currentValueTemplate`. Renovate makes a digest update for `v1`, writes the new
digest and leaves the rest of the line alone. It still ages that update against the release
timestamp, so a feed that returned a younger build would be held as pending. The cutoff lives in
the transform as `$minimumAgeMs`. The script test `Renovate offers each OSS-Fuzz base image's
newest v1 build past the cooldown` fails if it stops matching `minimumReleaseAge`.

The built-in dockerfile manager also extracts these `FROM` lines. A package rule disables it for
the two images, because its docker datasource lookup would add a second digest update that stays
pending forever.

A new `v1` build passes the seven days every day, so Renovate would open an automerged digest pull
request for each image every day. A second package rule, scoped to the two datasources, gives them
the `lockFileMaintenance` window, `schedule: ["* 0-4 * * 5"]` (Friday between 00:00 and 04:59 in
the bot's timezone), which the maintainer's other repositories also use for dependency work. This
is the one exception to this repository's rule that ordinary updates have no weekly schedule
([ADR 0076](0076-separate-major-updates-from-the-automergeable-bundle.md)). The rule only decides
when Renovate opens the pull request. The seven-day wait and automerge apply as to any digest
update, and automerge is not limited to the window.

A Renovate 44.116.1 lookup dry run on 2026-10-05 at 19:02 UTC read the live feeds:

| Image                         | Pinned build | Proposed build                       | Held, younger than seven days                         |
| ----------------------------- | ------------ | ------------------------------------ | ----------------------------------------------------- |
| `base-builder-jvm`            | 2026-07-13   | `d0c73262bd55`, 2026-09-28 04:04 UTC | 7 `v1` builds, 2026-09-29 to 2026-10-05               |
| `clusterfuzzlite-run-fuzzers` | 2026-08-30   | `f879183737d9`, 2026-09-28 16:22 UTC | 22 `v1` builds, 2026-09-29 to 2026-10-05              |

Both proposed digests return 200 with media type
`application/vnd.docker.distribution.manifest.v2+json`, and their configurations show the Ubuntu
20.04 `v1` build. With the cutoff set to zero, the same run offered that day's `v1` builds and
Renovate marked both updates `pendingChecks`.

### Consequences

* Good, because the OSS-Fuzz images move again, each to a build that has been public for at least
  seven days.
* Good, because the update changes only the digest in the `FROM` line.
* Good, because a change in OSS-Fuzz's tagging stops the updates and shows a failed lookup on the
  Dependency Dashboard instead of switching lineage.
* Good, because the Friday window turns a daily automerged pull request per image into a weekly
  one. The build it proposes is still the newest one at least seven days old on that Friday.
* Bad, because a build that passed the cooldown on Saturday waits up to six more days. A security
  rebuild of these images has no vulnerability alert to bypass the window, so it also waits for
  Friday.
* Bad, because the lineage rules depend on how OSS-Fuzz tags and pushes the images and on how
  Artifact Registry records tag moves, none of which is a documented contract. The check on the
  newest build catches a change once it reaches the newest build. A one-off push under a new tag
  that is later removed could still pass as a `v1` build.
* Bad, because the cooldown appears twice: in `renovate.json` and as `$minimumAgeMs` in both
  transforms. The script test ties them together.
* Bad, because each lookup reads up to 1,000 version records, about 700 KB. For the runner that
  covers about 50 days, enough for a seven-day cutoff.
* Neutral, because the digest update automerges after the required `test` check like other digest
  updates. The ClusterFuzzLite job runs on the pull request but is not a required check.

### Confirmation

This decision remains implemented when:

* the custom managers for the three `FROM` lines use `custom.oss-fuzz-base-builder-jvm` and
  `custom.oss-fuzz-clusterfuzzlite-run-fuzzers` with current value `v1`, and the dockerfile manager
  is disabled for both images;
* the only package rule with a schedule matches exactly those two datasources and uses the
  `lockFileMaintenance` window, and the top level sets no schedule;
* the script test `Renovate offers each OSS-Fuzz base image's newest v1 build past the cooldown`
  passes; and
* the Dependency Dashboard lists no failed lookup for either datasource and opens digest pull
  requests for both images.

## Pros and Cons of the Options

### A Custom Datasource That Offers The Newest `v1` Build Past The Cooldown As Release `v1`

* Good, because Renovate replaces only the digest, as for any digest update.
* Good, because Renovate's own cooldown still checks the release timestamp of the offered build.
* Bad, because the transform holds a copy of the cooldown.
* Bad, because builds younger than seven days are not listed, so the Dependency Dashboard shows
  no pending update for them.

### A Custom Datasource That Lists Every `v1` Build As A Release Versioned By Push Time

* Good, because `internalChecksFilter: "strict"` would pick the newest build past the cooldown, with
  no copy of the cooldown in the transform.
* Bad, because Renovate cannot apply such an update to these lines. It compares versions against
  the current value, which a `FROM` line without a version cannot supply, so the value would be a
  constant. After writing the file Renovate extracts it again and requires the current value to
  equal the new version. Renovate 44.116.1's `doAutoReplace` with a new version on this
  `FROM` line fails with `update-failure`.
* Bad, because putting the push time in a comment beside the `FROM` line would make it work only by
  adding a second value that must match the digest by hand.

### The Same Datasource On The Registry's `tags/list` Endpoint

* Good, because it is the registry's own endpoint and lists every digest with `timeUploadedMs`.
* Bad, because it has no update times. The `ubuntu-20-04` and `v1` builds of `base-builder-jvm` are
  both Ubuntu 20.04 images of almost the same size, and nothing else in the response separates
  them.
* Bad, because the response for the runner is 3.8 MB.

### A Repository-Owned Record Of The Digests `v1` Pointed To

* Good, because the lineage would be observed, not inferred.
* Bad, because it needs a scheduled workflow with write access and a place to publish the record.
* Bad, because a gap in the schedule loses builds, and the record starts empty.

### Keep The Docker Datasource

* Good, because nothing changes.
* Bad, because the builder and runner stay on July and August builds for good, including any
  security rebuild OSS-Fuzz pushed since.

## More Information

To recheck the lineage rules, fetch each image manifest's configuration from
`https://gcr.io/v2/oss-fuzz-base/<image>/blobs/<config digest>`. The `v1` builds carry the label
`org.opencontainers.image.version` with value `20.04`, and the `base-builder-jvm` `v1` build runs
`checkout_build_install_llvm.sh` and `install_deps.sh`, where the `ubuntu-20-04` build runs
`checkout_build_install_llvm_ubuntu_20_04.sh` and `install_deps_ubuntu-20-04.sh`.
