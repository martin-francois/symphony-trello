---
status: accepted
date: 2026-06-08
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #363](https://github.com/martin-francois/symphony-trello/issues/363)"
  - "[GitHub issue #364](https://github.com/martin-francois/symphony-trello/issues/364)"
  - "[Release Please action documentation](https://github.com/googleapis/release-please-action)"
informed: [Future maintainers, Contributors]
---

# Install from GitHub Release Archives

## Context and Problem Statement

The first installer path cloned the Git repository and built Symphony for Trello locally. That made
the simple install path depend on Git and Maven even though most users only need a released app.

[GitHub issue #363](https://github.com/martin-francois/symphony-trello/issues/363) asked for an
OpenClaw-style one-line install command and a smoother move away from source-checkout installs. The
release path matches the public path users run.

The installer still needs a source-checkout mode for development and release testing.

## Decision Drivers

* Keep first install simple.
* Avoid requiring Git or Maven for normal users.
* Keep shell and PowerShell behavior close to each other.
* Keep setup decisions in Java, not duplicated across install scripts.
* Keep release artifacts reproducible from the tagged source.
* Use the repository's SHA3 policy for project-owned checksums.
* Leave signing for a separate issue so this release path can ship first.

## Considered Options

* Keep cloning and building from Git as the default install path.
* Publish executable release archives on GitHub Releases and install those by default.
* Publish to Maven Central and make the installer resolve the app from Maven Central.
* Host a stable install script URL that redirects to the latest GitHub Release asset.
* Require users to download archives manually from the GitHub Releases page.

## Decision Outcome

Chosen option: publish GitHub Release archives and make the stable installer URL install those
archives by default.

Release Please remains the release automation entry point. When it creates a GitHub release, it
creates a draft release and forces tag creation. The release workflow checks out the new tag as the
release source, runs the packaging script from that tag, builds the packaged Quarkus app, packages
Linux and Windows archives, uploads installer and uninstaller scripts, and uploads `checksums.txt`
while the release is still a draft. It verifies every expected asset and then publishes the release.
Release assets are built from tag-local source, scripts, and installer templates. Development test
tags that predate the packaging script are not supported by the public release asset workflow.

Release asset uploads do not clobber existing assets. If a public release is wrong or incomplete,
publish a new patch release instead of replacing assets on the existing release. The workflow still
verifies that all public download assets exist after upload, so a partial upload is visible
immediately.

The default installer downloads the archive for the selected version, verifies its SHA3-256 checksum
from `checksums.txt`, and unpacks it into the installer-managed app directory. It does not require
Git or Maven for that default path.

The source-checkout path remains available with `--from-source`, `--repo`, and `--ref`. Source mode
is for development, local testing, and unusual debugging. In source mode, the installer still clones
or updates the configured ref and builds with the Maven wrapper.

The public install URLs live under `https://symphony-trello.fmartin.ch/`. Cloudflare redirects these
stable paths to the latest GitHub Release download assets. That keeps README commands stable while
the release asset implementation stays on GitHub Releases.

Signing is not part of this decision.
[GitHub issue #364](https://github.com/martin-francois/symphony-trello/issues/364) tracks future
signing evaluation.

### Consequences

* Good, because normal users no longer need Git or Maven for installation.
* Good, because the README can show stable one-line install commands.
* Good, because release artifacts are built from the exact tag that Release Please created.
* Good, because assets are uploaded and verified before immutable release publication.
* Good, because public release assets are not replaced in place.
* Good, because source-checkout installation is still available when needed.
* Bad, because release asset downloads need hosted GitHub release validation in addition to local
  packaging checks.
* Bad, because a broken public release requires a new patch release instead of a same-tag asset
  repair.
* Bad, because checksum verification is weaker than signing. Signing remains a follow-up.

### Confirmation

Build release assets locally:

```bash
scripts/package-release-assets.sh 0.2.0
```

Run installer dry runs:

```bash
bash install.sh --dry-run --no-onboard
bash install.sh --dry-run --no-onboard --from-source
./scripts/pwsh-docker.sh -NoProfile -File ./install.ps1 --dry-run --no-onboard
```

Run the installer tests:

```bash
./mvnw -q -Dtest=InstallerScriptTest test
```

## Pros and Cons of the Options

### Keep Git Clone and Local Build as Default

Keep the previous installer shape.

* Good, because it already worked during development.
* Good, because it always uses source from the requested ref.
* Bad, because it makes normal users install Git and build tooling.
* Bad, because install time depends on local Maven dependency resolution and local build speed.

### Install GitHub Release Archives by Default

Publish archives on GitHub Releases and make installers download them.

* Good, because users install a prebuilt app with fewer prerequisites.
* Good, because GitHub Releases are already part of Release Please's release flow.
* Good, because archives can include the same directory layout that the existing wrapper uses.
* Bad, because artifacts need an additional packaging step and validation.

### Publish Through Maven Central

Publish the app to Maven Central and make the installer resolve the released artifact.

* Good, because Maven Central is a standard Java distribution channel.
* Bad, because this is a CLI application, not a library dependency.
* Bad, because it would still require resolver tooling in the installer path.
* Bad, because it adds release complexity.

### Stable Redirect URL

Use Cloudflare to redirect stable install URLs to GitHub Release assets.

* Good, because user commands stay short and do not include a version.
* Good, because moving the backing storage later does not change the command users type.
* Bad, because redirect configuration is another deployed dependency to manage.

### Manual Archive Download

Tell users to download archives from GitHub Releases themselves.

* Good, because it avoids remote script execution.
* Bad, because it is a slower first-run path and makes guided setup harder to discover.
* Bad, because it is less consistent with similar tools that provide one-line installers.

## More Information

The default install command is:

```bash
curl -fsSL https://symphony-trello.fmartin.ch/install.sh | bash
```

Native Windows PowerShell uses:

```powershell
powershell -c "irm https://symphony-trello.fmartin.ch/install.ps1 | iex"
```

The stable domain currently redirects asset paths to GitHub's latest release download URLs. For
example, `/install.sh` redirects to:

```text
https://github.com/martin-francois/symphony-trello/releases/latest/download/install.sh
```
