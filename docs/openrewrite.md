# OpenRewrite Maintenance

OpenRewrite is an opt-in semantic-maintenance lane. It complements the repository's formatting,
compiler, static-analysis, and test gates; it does not replace them. The normal
`./mvnw -q spotless:check verify` command remains non-mutating and does not activate OpenRewrite.

The reviewed allowlist is the `ch.fmartin.symphony.trello.OpenRewriteMaintenance` composite in
[`rewrite.yml`](../rewrite.yml). The recipe-specific positive and zero-result decisions are in the
[recipe decision record](openrewrite-recipe-decisions.md) and its linked audit appendix. The
composite contains 437 exact entries: the previous 405-entry reviewed state plus 32 application
maintenance guards selected by the issue #600 re-evaluation.

## Accepted Ordered State

OpenRewrite and Spotless form one ordered normalization pipeline. If `R` is the reviewed
OpenRewrite composite, `F` is Spotless, and `S` is the committed repository state, CI accepts
exactly this condition:

```text
F(R(S)) = S
```

Spotless owns final Java, POM, JSON, YAML, and Markdown formatting. OpenRewrite may therefore emit
noncanonical intermediate formatting. This decision does not require:

- raw OpenRewrite output to equal the committed state;
- a second unformatted OpenRewrite run to produce no changes;
- raw OpenRewrite output to be idempotent; or
- OpenRewrite and Spotless to commute.

## Commands

Run discovery when editing the allowlist or updating a recipe catalog:

```shell
./mvnw -Popenrewrite rewrite:discover
```

Apply and verify the accepted pipeline in this exact order:

```shell
./mvnw -Popenrewrite rewrite:run
./mvnw -q spotless:apply
git diff
./mvnw -q spotless:check verify
```

Review the complete combined diff. After committing it, rerun the first two commands from clean
`HEAD` and require this command to print nothing:

```shell
git status --porcelain=v1 --untracked-files=all
```

Use `rewrite:dryRun` only as an optional raw preview:

```shell
./mvnw clean
./mvnw -Popenrewrite -Drewrite.exportDatatables=true rewrite:dryRun
```

The preview writes `target/rewrite/rewrite.patch` and, when requested, timestamped data tables
under `target/rewrite/datatables/`. OpenRewrite does not remove stale generated output, so run
`./mvnw clean` before collecting evidence. These files contain source paths and excerpts. Scan
them before copying or uploading:

```shell
find target/rewrite -type f \( -name '*.patch' -o -name '*.csv' \) -print0 |
  xargs -0 -r -n 1 scripts/check-private-context --file
```

## CI

The required Linux `test` job:

1. applies the curated OpenRewrite composite;
2. starts a separate Maven invocation and applies Spotless;
3. fails when `git status --porcelain=v1 --untracked-files=all` reports a tracked or nonignored
   untracked change; and
4. runs `spotless:check verify`.

Separate Maven invocations are intentional. When OpenRewrite changes `pom.xml`, Spotless must load
the rewritten Maven model before applying Java formatting and `sortPom`.

The checkout is writable, but the job has only `contents: read` repository permission. It does not
commit, push, comment, open a pull request, or upload source, patches, data tables, or semantic
trees. Maven may write ignored `target/` content; the gate checks the Git worktree, not every
generated filesystem path.

## Renovate Automation

Renovate groups the OpenRewrite toolchain on the explicit
`renovate/openrewrite-toolchain` branch, waits seven days after each release, and opens an untouched
dependency-only pull request without dashboard approval. Major updates are created automatically but
retain repository-wide manual merge. A trusted workflow validates that the pull
request:

- has an `opened` or `synchronize` webhook whose actual sender is `renovate[bot]` for the exact
  source SHA;
- comes from the same repository and `renovate[bot]`;
- contains one Renovate-authored commit directly on the current base;
- changes only `pom.xml`; and
- changes only the exact OpenRewrite toolchain version properties within that POM, including the
  Error Prone Core and compiler-side Picnic properties. Renovate groups Error Prone Core with the
  Picnic annotation processor and Refaster runner because Picnic's serialized Refaster templates
  require the Error Prone version used to produce them.

The workflow checks out only the trusted base, materializes the exact validated Renovate `pom.xml`,
and establishes that tree as a local baseline. It then discovers recipes, applies OpenRewrite and
Spotless, repeats the ordered pipeline, and runs the full Maven gate inside a pinned, unprivileged
container. The container receives no runner environment, GitHub token, cache token, Docker socket,
or repository write credential; it mounts only the generated workspace plus fresh cache and output
subdirectories that exclude the runner's command files. The host accepts only the expected regular
output files after the container exits. A separate job validates the reconciliation artifact and
publishes the update-validation status; updated Maven code never receives that job's
status-write token.

Regular pull-request CI recognizes both the Renovate source branch and its generated branch. It
keeps the existing required `test` job name but runs every Maven and updated-artifact invocation in
the same pinned isolation model, with the checkout credentials disabled and `.git` mounted
read-only. Native Windows Maven tests and pull-request dependency submission do not execute for
these branches; both resume after the validated or reviewed update reaches `main`. This prevents
another workflow from exposing runner command files, a reusable Maven cache, or a repository
credential to the pre-merge artifacts.

When an eligible non-major update produces no repository change, required CI passes and Renovate
automerges the dependency-only pull request on a subsequent Renovate run. GitHub native auto-merge
is disabled for this group, so Renovate requires every branch status—not only repository-required
checks—to pass before merging. The `OpenRewrite update validation` status is published on the exact
Renovate head and succeeds only after source-shape validation, recipe discovery, both fixed-point
passes, the full Maven gate, and artifact publication complete with no generated diff. A maintainer
does not need to approve, run commands, or review an inactive upstream recipe inventory.
The separate `OpenRewrite source provenance` status records the webhook sender rather than
forgeable Git author fields. Scheduled and manual reconciliation require a successful status created
by GitHub Actions on the exact source SHA before executing Maven.

When the ordered pipeline changes Java source or non-version POM content, the dependency-only pull
request remains blocked by the update-validation status. A separate publication job creates or
refreshes `automation/openrewrite/renovate-<source-number>` with the verified generated patch. Its
pull request lists the exact source SHA, version changes, generated paths, and validation commands.
A maintainer reviews and merges that generated pull request without running local commands or
adding a follow-up commit. The source head's update-validation status remains failed when generated
changes exist, so the dependency-only pull request cannot merge instead of the derived result.

Renovate's source branch remains untouched. A newer release replaces its one source commit and
automatically regenerates the derived pull request. Per-source workflow concurrency cancels older
runs, the publisher revalidates the source repository, author, branch, base, label, state, and SHA
immediately before writing, and the generated branch uses force-with-lease. Reconciliation compares
the existing commit's parent and tree and leaves the branch and pull request untouched when the
fixed-point result is unchanged, preserving CI results and maintainer approval. The
`OpenRewrite source freshness` status turns pending on the previous generated head as soon as the
source changes and passes only when the generated commit's parent is the current Renovate SHA.
Scheduled and manual reconciliation mark stale generated heads pending before starting regeneration,
so a missed pull-request event does not leave stale output mergeable during the Maven run.

## One-Time GitHub App Setup

Renovate and the reconciliation workflow use the repository's `openrewrite` label as the candidate
discriminator. That label must exist before Renovate opens an update; it is provisioned as repository
configuration for this automation. If it is deleted, recreate the exact label name before the next
Renovate run.

Create a GitHub App dedicated to OpenRewrite update publication. Install it only on this repository
and grant:

- Metadata: read-only;
- Contents: read and write; and
- Pull requests: read and write.

The App must not bypass pull-request or required-check rules on `main`. Store its numeric App ID and
private key as these Actions secrets:

```text
OPENREWRITE_AUTOMATION_APP_ID
OPENREWRITE_AUTOMATION_PRIVATE_KEY
```

The workflow exchanges the private key for a short-lived installation token only in the publication
job and only when it must create, refresh, or remove generated state. A no-result update does not
read either secret when it is eligible for automerge and there is no prior generated state, so it
continues to automerge when the App is unavailable. The generation job cannot read either secret.
Before using artifact metadata or applying its patch, the privileged publisher independently
validates the source number and SHA, allowed paths, file modes, and generated-file manifest.
Rotate the private key through the GitHub App settings before removing the previous key, then update
`OPENREWRITE_AUTOMATION_PRIVATE_KEY`.

Required repository rules must keep the Linux fixed-point/test job, commitlint,
`OpenRewrite source provenance`, `OpenRewrite update validation`, and
`OpenRewrite source freshness` blocking; require the generated branch to be current with `main` or
dismiss stale approvals after a changed push. The workflow reports all three custom contexts as not
applicable and successful where they do not govern the pull request—including unrelated Renovate
branches—so all three are safe to require repository-wide.

## Manual Fallback

Use a clean branch or disposable worktree if the automation is unavailable or a maintainer must
investigate a rejected update:

1. Confirm that every configured recipe is discoverable.
2. Apply OpenRewrite and then Spotless.
3. Review every source, POM, test, workflow, and documentation change in the combined diff.
4. Run the focused tests for affected behavior, then the full Maven gate.
5. Commit the reviewed result.
6. From clean `HEAD`, apply OpenRewrite and Spotless again and confirm that Git remains clean.

For rollback, delete a disposable worktree or restore only the explicitly reviewed paths. Do not
use a broad restore in a worktree that contains unrelated changes.

## Pinned Inventory

Versions are exact Maven properties in the root POM. The POM is the version source of truth; this
table names each owning property so documentation does not need a synchronized literal copy.

| Artifact | Version owner | License | Use |
| --- | --- | --- | --- |
| `org.openrewrite.maven:rewrite-maven-plugin` | `rewrite-maven-plugin.version` | Apache-2.0 | Local Maven integration |
| `org.openrewrite:rewrite-java` | `rewrite-maven.version` through the plugin BOM | Apache-2.0 | Java parser and core recipes |
| `org.openrewrite:rewrite-maven` | `rewrite-maven.version` | Apache-2.0 | Maven recipes |
| `org.openrewrite.recipe:rewrite-static-analysis` | `rewrite-static-analysis.version` | Moderne Source Available | Curated Java analysis recipes |
| `org.openrewrite.recipe:rewrite-testing-frameworks` | `rewrite-testing-frameworks.version` | Moderne Source Available | Curated JUnit, Mockito, and AssertJ recipes |
| `org.openrewrite.recipe:rewrite-migrate-java` | `rewrite-migrate-java.version` | Moderne Source Available | Curated Java migrations |
| `tech.picnic.error-prone-support:error-prone-contrib` | `error-prone-support.version` | MIT | Compiler-side Error Prone checks |
| `tech.picnic.error-prone-support:refaster-runner` | `error-prone-support.version` | MIT | Compiler-side Refaster template application |
| `tech.picnic.error-prone-support:error-prone-contrib:recipes` | `rewrite-error-prone-support.version` | MIT | Directly pinned AssertJ Refaster recipe leaves |

The source-available terms permit an end user to apply the selected recipes to its own code. This
repository uses the recipes locally and does not redistribute them as a service. No source or
lossless semantic tree is uploaded to Moderne. A Moderne platform or CLI integration requires a
separate privacy, security, licensing, and operational decision.

The direct Picnic `recipes` classifier makes the selected generated leaf IDs an explicit,
versioned dependency. A catalog update that renames or removes one of those IDs fails active-recipe
validation and requires the same candidate review as any other executable transformation update.

## Updates And Ownership

Recipe and engine updates change executable transformations. Renovate waits seven days, groups the
OpenRewrite toolchain, and keeps it out of unrelated dependency updates. Updates with no generated
repository result automerge after the ordered fixed-point and full Maven gates pass unless they are
major updates. Major updates are created without dashboard approval and require manual merge. Updates
with a result require review of the complete generated pull request.

Newly discovered upstream leaves remain inactive by default and do not block the version update.
Reviewing whether to add them is separate recipe-curation work. When evaluating a leaf, select it if
it makes code meaningfully better or enforces a generally useful invariant for Java, Maven, or an
existing repository ecosystem. Judge compatibility from the current generated diff and supported
behavior of this deployed application, not hypothetical compatibility for a Java library the
repository does not publish. A zero-result guard changes no deployment, and each future finding
requires a new generated-diff review. Correcting invalid or already-broken behavior is compatible
when the generated behavior is genuinely better. Record preferable transformations that stop
supported application use as inactive breaking-release candidates. Reject unsafe,
context-dependent, defective, or worse output. Do not load a recipe solely for an absent language,
build system, library, framework, or capability. Keep target-version migrations with the workflow
that selects that target.

Quarkus migrations remain owned by `quarkus:update` in the dependency-update branch. Generic
dependency and plugin version changes remain owned by their POM properties and Renovate. The
general maintenance composite does not update the Quarkus BOM or dependency versions.

## Candidate Evidence

The initial broad families and supplementary candidates were measured independently against
pre-application source and clean worktrees at the same rebased PR baseline on 2026-07-18. Every
applicable audit covered all 142 Maven main and 113 Maven test Java files plus the root POM without
`SourcesFileErrors`. Three Java helper programs under `scripts/` remain outside Maven source roots
and therefore outside this lane.

Counts, provider and license ownership, exact decisions, compilation failures, formatting failures,
compatible bug corrections, breaking-release candidates, and recurrence guards are recorded per
candidate in the
[recipe decision record](openrewrite-recipe-decisions.md) and its linked zero-result appendix. A
parent composite is not treated as an individual rejection when its descendants have mixed
decisions.
