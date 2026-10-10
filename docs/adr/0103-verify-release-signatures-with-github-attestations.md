---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #364](https://github.com/martin-francois/symphony-trello/issues/364)"
  - "[GitHub issue #69](https://github.com/martin-francois/symphony-trello/issues/69)"
  - "[GitHub issue #446](https://github.com/martin-francois/symphony-trello/issues/446)"
  - "[ADR 0049](0049-release-archive-installer.md)"
  - "[GitHub artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations)"
  - "[gh attestation verify manual](https://cli.github.com/manual/gh_attestation_verify)"
  - "[cosign verify-blob-attestation](https://docs.sigstore.dev/cosign/verifying/verify/)"
informed: [Future maintainers, Contributors, Users]
---

# Verify Release Signatures With GitHub Artifact Attestations

## Context and Problem Statement

[ADR 0049](0049-release-archive-installer.md) made GitHub Release archives the default install path.
The installers check each archive against the SHA3-256 hash in `checksums.txt`. That catches broken
and mismatched downloads. It does not prove who built the archive, because anyone who can replace the
archive can also replace `checksums.txt`.

Since commit
[c88645a](https://github.com/martin-francois/symphony-trello/commit/c88645aacd5cac2a22621f74c8ad7f1ccd463c9c),
the release workflow already signs every release asset. The `actions/attest` step creates one SLSA
build provenance attestation that lists every asset with its SHA-256 digest. Sigstore signs it with a
short-lived certificate for the workflow run, and the workflow publishes the Sigstore bundle as
`symphony-trello-<version>.intoto.jsonl` next to `checksums.txt`. Release 1.2.0 is the first release
with that file.

What was missing: a recorded choice between signing options, installer verification, and
instructions for checking a download by hand. Which signing approach should the project keep, and
how should `install.sh` and `install.ps1` use it?

## Decision Drivers

* Keep the default install a single command that works without extra tools.
* Do not store a long-lived signing key or secret that maintainers must protect and rotate.
* Let the installer check the archive automatically when the user has a tool that can do it.
* Use a check that fails when the archive was built by another repository, workflow, or branch.
* Keep the SHA3-256 checksum check from [ADR 0049](0049-release-archive-installer.md).
* Keep `install.sh` and `install.ps1` behavior the same.
* Keep Release Please as the version and GitHub Release source of truth.

## Considered Options

* Sign `checksums.txt` with GPG.
* Sign each release asset with a detached GPG signature.
* Keyless Sigstore signing through GitHub artifact attestations, verified with GitHub CLI.
* Keyless Sigstore signing with a separate cosign signature per asset.
* minisign or signify detached signatures.
* Keep checksums only and defer signing.

Installer behavior options:

* Require signature verification for every install.
* Verify when GitHub CLI can verify attestations, otherwise print a note and continue.
* Verify when possible, and add a flag that skips verification.
* Leave verification to a separate manual command.

## Decision Outcome

Chosen option: keyless Sigstore signing through GitHub artifact attestations, verified with GitHub
CLI when it is installed.

The release workflow keeps its `actions/attest` step and the `symphony-trello-<version>.intoto.jsonl`
asset. Before upload, `scripts/verify-release-signatures` checks every public asset against that
bundle with the same signer identity the installers use. A release whose signer no longer matches the
installers fails before it is published.

The signer identity is the release workflow on `main`:

```text
https://github.com/martin-francois/symphony-trello/.github/workflows/release-please.yml@refs/heads/main
```

The certificate issuer is `https://token.actions.githubusercontent.com`.

Installer behavior: verify when GitHub CLI can verify attestations, otherwise print a note and
continue.

1. The installer downloads the archive and `checksums.txt` and checks the SHA3-256 hash, as before.
2. For releases older than 1.2.0, it prints a note that the release predates signed assets and
   continues. This keeps `--version` working for older releases.
3. If `gh` is not on `PATH`, or `gh attestation verify --help` fails because the version is older
   than 2.49, it prints one note and continues:
   `Release signature not checked: install GitHub CLI (gh) 2.49 or newer to check it. SHA3-256 checksum verified.`
4. Otherwise it downloads the bundle and runs:

   ```bash
   GH_HOST=github.com gh attestation verify ARCHIVE --bundle BUNDLE \
     --repo martin-francois/symphony-trello \
     --cert-identity https://github.com/martin-francois/symphony-trello/.github/workflows/release-please.yml@refs/heads/main
   ```

   `--bundle` makes the check work without `gh auth login`. `GH_HOST=github.com` keeps a GitHub
   Enterprise default host from changing the trust root.
5. If the bundle download or the check fails, the installer stops before it unpacks the archive. It
   prints the `gh` error lines, says that a network problem reaching Sigstore can cause this, and
   otherwise points to private vulnerability reporting.

The installers verify the archive they install. Users who also want to verify `install.sh` or
`install.ps1` before running it follow the manual steps in the README.

### Consequences

* Good, because no private key or repository secret exists. The workflow uses its GitHub OIDC token
  (`id-token: write`) and writes the attestation (`attestations: write`). There is nothing to rotate.
* Good, because the check fails for an archive built by another repository, another workflow file, or
  a workflow run on another branch, even when the attacker also replaces `checksums.txt`.
* Good, because a user without GitHub CLI gets the same install as before plus one note line.
* Good, because users can verify any asset by hand with `gh` or with `cosign`, using the published
  bundle and the identity above.
* Good, because the release workflow tests the installer's exact check before publication.
* Bad, because the automatic check depends on GitHub CLI 2.49 or newer and on HTTPS access to
  Sigstore's public trust data. Without network access to Sigstore, `gh` cannot verify and the
  install stops. There is no flag that skips the check. A user who cannot reach Sigstore can run the
  installer in a shell without `gh` on `PATH`, which falls back to the checksum check. The README
  documents this.
* Bad, because a compromised installer script can skip its own check. The automatic check protects
  the archive. Protecting the script needs the manual check before running it.
* Bad, because renaming `.github/workflows/release-please.yml` or running it from another ref changes
  the signer identity. `install.sh`, `install.ps1`, and `scripts/verify-release-signatures` must change
  in the same pull request, and installers that pin the old identity cannot verify the new releases.
  `ReleaseWorkflowTest` checks that the three files agree.
* Bad, because shell and PowerShell both carry the check. Java cannot own it, because the check runs
  before the Java app is unpacked.

### Confirmation

* `ReleaseWorkflowTest` checks that the workflow attests the assets, verifies them before upload, keeps
  the `main` push trigger and permissions, and that `install.sh`, `install.ps1`, and
  `scripts/verify-release-signatures` pin the same signer identity and first signed version.
* `InstallerScriptTest` installs a served release archive with a fake GitHub CLI and checks the
  verified, skipped, and refused paths for `install.sh`. The `*OnWindows` variants check `install.ps1`
  in the Windows CI job.
* `scripts/verify-release-signatures.test.ts` checks the release workflow script.
* Manual check against a published release:

  ```bash
  gh release download v1.2.0 --repo martin-francois/symphony-trello \
    --pattern symphony-trello-1.2.0.tar.gz --pattern symphony-trello-1.2.0.intoto.jsonl
  gh attestation verify symphony-trello-1.2.0.tar.gz --bundle symphony-trello-1.2.0.intoto.jsonl \
    --repo martin-francois/symphony-trello \
    --cert-identity https://github.com/martin-francois/symphony-trello/.github/workflows/release-please.yml@refs/heads/main
  ```

## Pros and Cons of the Options

### Sign `checksums.txt` With GPG

The release workflow signs `checksums.txt` with a project GPG key stored as a repository secret.
Users check the signature with `gpg --verify` and then check the hashes.

* Good, because one signature covers every asset.
* Good, because GPG is a familiar tool for many Linux users.
* Bad, because the project must create, protect, publish, and rotate a long-lived private key.
* Bad, because a leaked repository secret lets an attacker sign anything until the key is revoked.
* Bad, because the installers would need GPG, which is often missing on macOS and Windows.

### Sign Each Release Asset With A Detached GPG Signature

The release workflow writes one `.asc` file per asset with the same kind of project GPG key.

* Good, because users can check one file without the checksum list.
* Bad, because it has all key management costs of signing `checksums.txt`.
* Bad, because it doubles the number of release assets.

### Keyless Sigstore Signing Through GitHub Artifact Attestations

`actions/attest` signs an SLSA provenance statement for every asset with a short-lived Sigstore
certificate that names the workflow, ref, and repository. The bundle is a release asset, and users
verify with `gh attestation verify` or `cosign verify-blob-attestation`.

* Good, because there is no long-lived key or secret.
* Good, because the certificate identity pins the exact workflow and branch that built the release.
* Good, because the workflow already produced this bundle for release 1.2.0.
* Good, because many developers already have GitHub CLI, and `--bundle` works without logging in.
* Bad, because verification needs network access to Sigstore's trust data.
* Bad, because the attestation names SHA-256 digests while `checksums.txt` uses SHA3-256. Both stay,
  so the files serve separate purposes.

### Keyless Sigstore Signing With Separate Cosign Signatures

The workflow installs cosign and runs `cosign sign-blob` for each asset, publishing one bundle per
asset.

* Good, because cosign is the reference Sigstore client.
* Bad, because it adds a tool install and more release assets for the same guarantee the existing
  attestation bundle already gives.
* Bad, because fewer users have cosign than GitHub CLI.

### minisign Or signify Detached Signatures

The workflow signs assets with a small Ed25519 key, and users check with `minisign -V`.

* Good, because signatures and verification are simple and work offline.
* Bad, because the project still needs a long-lived secret key and a rotation process.
* Bad, because few users have minisign installed, so the installer would skip the check most of the
  time.

### Keep Checksums Only

Publish `checksums.txt` and document that signing is deferred.

* Good, because there is nothing new to build.
* Bad, because it ignores the attestations the release workflow already produces.
* Bad, because a replaced archive and checksum file would still install.

### Require Signature Verification For Every Install

The installer refuses to install unless it can verify the signature.

* Good, because every install has the same guarantee.
* Bad, because every user would need GitHub CLI before the first install, which makes the one-line
  install harder.

### Verify When GitHub CLI Can, Otherwise Print A Note

The installer checks the SHA3-256 checksum, then verifies the archive with
`gh attestation verify` and the published bundle when GitHub CLI 2.49 or newer is installed, and
stops when that check fails. Without such a GitHub CLI, or for releases before 1.2.0, it prints one
note and installs after the checksum check.

* Good, because the default install stays the same for users without GitHub CLI.
* Good, because users with GitHub CLI get the stronger check without doing anything.
* Bad, because users without GitHub CLI only get the checksum check.

### Verify When Possible, With A Skip Flag

Like the chosen behavior, plus a flag such as `--no-signature-check`.

* Good, because a user who cannot reach Sigstore has a documented way forward.
* Bad, because it adds a public flag to both installers for a rare case, and a skip flag is the first
  thing a social engineering message tells users to pass.
* Bad, because the same result is already possible by running the installer without `gh` on `PATH`.

Revisit this option if users report that blocked Sigstore access stops their installs.

### Leave Verification To A Separate Manual Command

The installers keep only the checksum check, and the README documents `gh attestation verify`.

* Good, because the installers stay unchanged.
* Bad, because almost nobody runs the manual check, so the signatures would protect almost no
  installs.

## More Information

* The manual verification steps for users live in the README under "Verify Release Downloads".
* Maintainer notes live in `CONTRIBUTING.md` under "Maintainer Release Setup".
* [GitHub issue #69](https://github.com/martin-francois/symphony-trello/issues/69) plans JReleaser
  for Maven Central publication. Maven Central requires its own GPG signatures. That does not
  replace the attestation for GitHub Release assets. If JReleaser takes over building or uploading
  release assets, the `actions/attest` step must still run on the final asset files before upload,
  the bundle name must stay `symphony-trello-<version>.intoto.jsonl`, and the signer workflow and
  ref must stay the same or change together with both installers and
  `scripts/verify-release-signatures`.
* [GitHub issue #446](https://github.com/martin-francois/symphony-trello/issues/446) covers
  reproducible builds, which would let users rebuild an archive and compare it with the signed one.
* GitHub immutable releases also sign a release attestation, checked with `gh release verify-asset`.
  That check needs `gh auth login`, so the installers use the build provenance bundle instead.
