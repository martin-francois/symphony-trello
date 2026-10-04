---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #69](https://github.com/martin-francois/symphony-trello/issues/69)"
  - "[GitHub issue #364](https://github.com/martin-francois/symphony-trello/issues/364)"
  - "[ADR 0049](0049-release-archive-installer.md)"
  - "[ADR 0103](0103-verify-release-signatures-with-github-attestations.md)"
  - "[JReleaser Maven Central deployer](https://jreleaser.org/guide/latest/reference/deploy/maven/maven-central.html)"
  - "[Sonatype Central Portal publishing API](https://central.sonatype.org/publish/publish-portal-api/)"
  - "[Maven Central requirements](https://central.sonatype.org/publish/requirements/)"
informed: [Future maintainers, Contributors]
---

# Publish Maven Artifacts to Maven Central With JReleaser

## Context and Problem Statement

Release Please creates the version, changelog, tag, and draft GitHub Release. The release workflow
then builds the installer archives, writes the SHA3-256 `checksums.txt`, signs every asset with
`actions/attest`, uploads the assets, and publishes the release
([ADR 0049](0049-release-archive-installer.md), [ADR 0103](0103-verify-release-signatures-with-github-attestations.md)).
`install.sh` and `install.ps1` depend on those asset names and on `checksums.txt`. Since
[ADR 0103](0103-verify-release-signatures-with-github-attestations.md) they also verify the archive
against the attestation bundle and pin the signer identity of `.github/workflows/release-please.yml`
on `main`.

[GitHub issue #69](https://github.com/martin-francois/symphony-trello/issues/69) asks for Maven
Central publication of the Maven artifacts: staging, PGP signing, checksums, Maven Central
requirement checks, and a review step before the first release goes public. Release Please must stay
the version source of truth, and the publication must run only after Release Please created a
release. The issue also asks whether JReleaser should take over packaged distributions.

Publishing to Maven Central needs a Central Portal account, a verified `ch.fmartin` namespace, a
portal token, and a PGP key. Only the repository owner can create those.

How should Maven Central publication fit into the release workflow without changing what the
installers download and verify?

## Decision Drivers

* Keep Release Please the only source of the version, tag, changelog, and GitHub Release.
* Leave the GitHub Release assets, `checksums.txt`, the attestation bundle, and the signer identity
  unchanged, so the installers keep working.
* Start Maven Central publication only after the GitHub Release is published.
* Give the first Maven Central release a manual review step before it becomes public.
* Keep publication secrets away from build steps that run project code.
* Keep the feature off until the owner has created the account, namespace, token, and key.
* Use Maven tooling that Renovate already updates.

## Considered Options

Publication tool:

* JReleaser Maven plugin, deploy step only.
* Sonatype `central-publishing-maven-plugin` with `maven-gpg-plugin`.
* JReleaser CLI or GitHub Action with a `jreleaser.yml` file.

GitHub Release assets:

* Keep the existing release scripts for GitHub Release assets.
* Move archives, checksums, and the GitHub Release upload to JReleaser assemblers and its release
  step.

Workflow placement:

* A second job in `release-please.yml` that needs the release job.
* Extra steps at the end of the existing release job.
* A separate workflow triggered by `release: published`.

## Decision Outcome

Chosen options: the JReleaser Maven plugin runs only its deploy step, the existing scripts keep
building and publishing GitHub Release assets, and a second job in `release-please.yml` runs the
publication after the release job.

The build attaches a sources jar and a Javadoc jar to every `package` run, so a broken Javadoc
comment fails the normal `./mvnw verify` instead of the release. The `pom.xml` carries the project
URL, license, developer, SCM, and issue tracker that Maven Central requires.

The `maven-central` Maven profile does two things. It points `altDeploymentRepository` at
`target/staging-deploy`, and it configures JReleaser:

* `signing.pgp` signs every staged file with an armored PGP signature.
* `deploy.maven.mavenCentral.sonatype` uploads the staged files to the Central Portal API, adds
  MD5, SHA-1, SHA-256, and SHA-512 checksums, and runs JReleaser's Maven Central rules: POM checks,
  sources and Javadoc jars present, signatures present.
* `stage` is `UPLOAD` in `pom.xml`. JReleaser uploads the bundle, waits until the portal has
  validated it, and stops. The deployment then waits in the Central Portal until a maintainer
  publishes or drops it. In the workflow, `MAVEN_CENTRAL_STAGE` always sets the stage, because
  JReleaser lets the `JRELEASER_MAVENCENTRAL_STAGE` environment variable override the POM value. The
  POM value covers a direct `./mvnw -Pmaven-central jreleaser:deploy` run.
* `release.github.skipTag` and `skipRelease` are set, so even `jreleaser:full-release` never
  creates a tag or touches the GitHub Release.
* No JReleaser files, distributions, assemblers, or checksum files are configured. JReleaser's
  checksums only exist inside the Maven Central bundle and never replace `checksums.txt`.

The release workflow gets a `maven-central` job:

1. It needs the `release-please` job and runs only when that job built and published release assets
   (`upload_assets == 'true'`) and the repository variable `MAVEN_CENTRAL_STAGE` is set. Without the
   variable the job is skipped, which is the disabled switch.
2. It uses the `maven-central` GitHub environment and only `contents: read`.
3. It checks out the release tag without persisted credentials.
4. `scripts/stage-maven-central-artifacts` runs `package` and `deploy:deploy` into the staging
   directory. This step has no secrets.
5. `scripts/deploy-maven-central` accepts only `UPLOAD` or `FULL` from `MAVEN_CENTRAL_STAGE` and
   runs `jreleaser:deploy`. Only this step receives the portal token and the PGP key.

`UPLOAD` is the first-release review path. After the owner has checked a few deployments in the
portal, `FULL` lets JReleaser publish automatically.

### Consequences

* Good, because the GitHub Release assets, `checksums.txt`, the attestation bundle, and the signer
  identity stay exactly as [ADR 0103](0103-verify-release-signatures-with-github-attestations.md)
  describes. The publication job adds nothing to the release.
* Good, because Release Please stays the version source. JReleaser reads the version from the tagged
  `pom.xml`.
* Good, because a Maven Central failure cannot block or alter the GitHub Release. The job can be
  re-run on its own with "Re-run failed jobs". A re-run uploads a new bundle, so a deployment that
  an earlier attempt left in the portal has to be dropped first.
* Good, because the build step that runs project code never sees the PGP key or portal token.
* Good, because the feature stays off until the owner sets one repository variable.
* Good, because `./mvnw verify` already builds the sources and Javadoc jars, so a Javadoc error shows
  up in pull request CI.
* Bad, because every `package` run now also builds the sources and Javadoc jars, which took about 4
  seconds locally.
* Bad, because Maven Central publication starts after the GitHub Release is public. If it fails for
  a reason in the tagged code, the fix ships in the next patch release, and that version is missing
  from Maven Central.
* Bad, because Maven Central now has a long-lived PGP key and portal token that the owner must
  protect and rotate. ADR 0103 avoided keys for GitHub Release assets, but Maven Central accepts
  only PGP signatures.
* Bad, because pull request CI does not run JReleaser. Renovate therefore does not automerge
  `jreleaser-maven-plugin` updates, and a maintainer runs the dry run before merging one.
* Bad, because the Maven Central jar is the plain application jar. It is not an install path: the
  installers keep downloading GitHub Release archives, as [ADR 0049](0049-release-archive-installer.md)
  decided.

### Confirmation

* `MavenCentralPublicationTest` checks the job order and gate, the environment and permissions, that
  only the deploy step gets secrets, that the release job still builds and attests its own assets,
  that JReleaser skips tags and GitHub Releases and configures no release files or checksums, and
  that the POM metadata, sources jar, and Javadoc jar are present.
* `scripts/maven-central-publication.test.ts` checks the Maven commands of both scripts and that the
  deploy script refuses any stage other than `UPLOAD` or `FULL`.
* The local dry run in `CONTRIBUTING.md` under "Maven Central Publication" uses a throwaway PGP key
  and dummy portal credentials. It checks the POM, signs the staged files, and builds the bundle
  without uploading it. Run it before merging a `jreleaser-maven-plugin` update.

## Pros and Cons of the Options

### JReleaser Maven Plugin, Deploy Step Only

`jreleaser-maven-plugin` runs `jreleaser:deploy` in a Maven profile against a local staging
directory that `deploy:deploy` filled.

* Good, because it signs, adds checksums, checks Maven Central rules, uploads, and waits for portal
  validation in one goal.
* Good, because its `UPLOAD` stage gives the manual review step the issue asks for.
* Good, because Renovate updates it like every other Maven plugin in `pom.xml`.
* Good, because a dry run exercises the whole path without credentials for the portal.
* Bad, because it is one more plugin with its own configuration model.

### Sonatype Central Publishing Maven Plugin With Maven GPG Plugin

Sonatype's `central-publishing-maven-plugin` uploads during `mvn deploy`, and `maven-gpg-plugin`
signs the artifacts with a key imported into a GPG agent on the runner.

* Good, because Sonatype maintains it for its own portal.
* Good, because `autoPublish=false` also gives a manual review step.
* Bad, because the release would need `maven-gpg-plugin` with a GPG agent in CI and its own checksum
  and POM checks.
* Bad, because the issue chose JReleaser for this layer.

### JReleaser CLI or GitHub Action With a `jreleaser.yml` File

The workflow installs the JReleaser CLI or uses its GitHub Action, and a `jreleaser.yml` file in
the repository root holds the configuration.

* Good, because the same file would also work for non-Maven assets later.
* Bad, because it adds a second tool install and a second version to update next to the Maven
  wrapper.
* Bad, because the Maven plugin already reads the project coordinates and version from `pom.xml`.

### Keep the Existing Release Scripts for GitHub Release Assets

`scripts/package-release-assets.sh` and the other release scripts keep building, attesting, and
uploading the GitHub Release assets, and JReleaser handles only Maven Central.

* Good, because the installers, `checksums.txt`, and the attestation keep working without changes.
* Good, because the scripts and their tests already exist.
* Bad, because GitHub Release assets and Maven artifacts use two different tools.

### Move GitHub Release Assets to JReleaser

JReleaser assemblers would build the archives, JReleaser would write checksums, and its release step
would upload the assets.

* Good, because one tool would own all distribution.
* Bad, because the attestation must still run on the final files before upload, so the attest step
  would have to sit inside JReleaser's run.
* Bad, because JReleaser writes its own checksum files, while the installers read the SHA3-256
  `checksums.txt` and its exact asset names.
* Bad, because JReleaser would have to stop creating tags and releases, which Release Please owns.
* Bad, because every installer test and release script would change for no user-visible gain.

Revisit this when the project adds a channel that JReleaser serves well, such as a Homebrew tap or
a Scoop bucket. Those need owner-created repositories and tokens, and their install paths must stay
aligned with the installers from [ADR 0049](0049-release-archive-installer.md).

### A Second Job in `release-please.yml`

A `maven-central` job in the existing workflow file needs the release job and reads its outputs.

* Good, because the job runs only after the release job finished and published the release.
* Good, because the job has its own permissions and environment, so the secrets never reach the job
  that holds `contents: write` and `id-token: write`.
* Good, because the workflow file and trigger stay the same, so the signer identity does not change.
* Bad, because the release job needs to expose its outputs.

### Extra Steps at the End of the Release Job

The staging and deploy steps run after `Publish release` inside the existing release job.

* Good, because no job outputs are needed.
* Bad, because the PGP key and portal token would sit in a job with `contents: write`,
  `attestations: write`, and `id-token: write`.

### A Separate Workflow Triggered by `release: published`

A new workflow file starts when GitHub reports the published release.

* Good, because the publication would be independent of the release workflow file.
* Bad, because events caused by `GITHUB_TOKEN` do not start other workflows, and the release
  workflow publishes the release with `GITHUB_TOKEN`.

## More Information

* Owner setup and the switch between `UPLOAD` and `FULL` live in `CONTRIBUTING.md` under
  "Maintainer Release Setup".
* Maven Central requires PGP signatures. They do not replace the Sigstore attestation for GitHub
  Release assets, which stays the installers' trust anchor.
