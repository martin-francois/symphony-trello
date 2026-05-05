---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [Renovate documentation, GitHub Actions hardening guidance]
informed: [Future maintainers]
---

# Use Minimal Renovate Configuration with SHA-Pinned GitHub Actions

## Context and Problem Statement

The project should keep dependencies current with low maintenance overhead, but GitHub Actions tags
are mutable and Renovate configuration can become noisy if it repeats inherited defaults. The project
also uses a pinned pnpm version in a workflow command without a `package.json`.

How should dependency automation stay secure, maintainable, and clear for a Java repository?

## Decision Drivers

* Keep Renovate configuration minimal and avoid restating inherited defaults.
* Pin GitHub Actions to immutable full commit SHAs.
* Let Renovate update pinned actions and the Corepack pnpm version.
* Allow non-major updates to automerge after the configured release-age delay.
* Require human approval for major updates.
* Avoid adding `package.json` solely to run a JavaScript CLI in a Java project.

## Considered Options

* Minimal Renovate config with action SHA pinning and a regex manager for pnpm.
* Unpinned GitHub Actions tags.
* Commit a `package.json` only to pin pnpm.
* Verbose Renovate package rules for every inherited policy.

## Decision Outcome

Chosen option: "Minimal Renovate config with action SHA pinning and a regex manager for pnpm",
because it hardens CI and keeps automation behavior explicit only where it differs from presets.

### Consequences

* Good, because GitHub Actions references are immutable full SHAs.
* Good, because version comments next to action SHAs preserve Renovate tracking.
* Good, because the Corepack pnpm version remains updateable without a package manifest.
* Good, because major updates require dashboard approval and do not automerge.
* Bad, because SHA-pinned actions are less readable than tag-only action references.
* Bad, because the regex manager must stay aligned with the workflow command text.

### Confirmation

Run `pnpm dlx --package renovate renovate-config-validator renovate.json` and
`./mvnw -q spotless:check verify`. Review should confirm `.github/workflows/*.yml` uses full action
SHAs with version comments and that Renovate config does not duplicate global policy in package
rules.

## Pros and Cons of the Options

### Minimal Renovate config with action SHA pinning and a regex manager for pnpm

Use Renovate recommended presets, `helpers:pinGitHubActionDigests`, a regex manager for
`corepack prepare pnpm@... --activate`, and one package rule for major updates.

* Good, because security-sensitive action refs are immutable.
* Good, because Renovate still keeps the pins current.
* Good, because the config remains short enough to understand.
* Neutral, because Renovate needs a custom regex for the workflow command.
* Bad, because readers must know that some behavior comes from presets.

### Unpinned GitHub Actions tags

Keep actions referenced as `actions/checkout@v5` or similar.

* Good, because workflow files are easier to read.
* Bad, because tags can be moved.
* Bad, because a compromised action repository or maintainer account could change what CI executes.

### Commit a `package.json` only to pin pnpm

Add a minimal Node package manifest with `packageManager`.

* Good, because Corepack can read pnpm's version from a common manifest field.
* Bad, because it makes a Java repository look like it has Node project metadata.
* Bad, because it adds a file whose only purpose is running a JavaScript CLI in CI.

### Verbose Renovate package rules for every inherited policy

Spell out automerge, release age, grouping, and dashboard behavior in multiple package rules.

* Good, because every behavior is visible in one file.
* Bad, because it repeats preset defaults and global config.
* Bad, because repeated policy becomes easier to accidentally contradict.

## More Information

The workflow uses pnpm only for Renovate config validation. Java dependencies and build verification
remain Maven-based.
