---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub Actions workflows](../agents/github-actions.md)"
  - "[GitHub Actions security](../agents/github-actions-security.md)"
  - "[ADR 0140](0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md)"
  - "[actions/github-script: run a separate file](https://github.com/actions/github-script#run-a-separate-file)"
  - "[actions/checkout: sparse checkout](https://github.com/actions/checkout#fetch-only-a-single-file)"
  - "[GitHub: securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)"
informed: [Future maintainers, Contributors]
---

# Keep workflow step code in repository files

## Context and Problem Statement

Fifteen `run:` and `actions/github-script` `script:` blocks in `.github/workflows` held between
17 and 268 lines of code each. Inside a YAML string the code had no syntax highlighting, no
linter, and no formatter. ShellCheck and shfmt run only on files with a Bash shebang. The tests
for the labeler and handoff scripts cut the block out of the workflow text by its indentation and
ran it through an `AsyncFunction`, so a re-indented step or a second `script: |` line broke the
test, not the workflow. Nothing held an author to the existing rule that a `run:` block should be
one or two commands.

Moving the code into files raises one question. Four of these workflows run on
`pull_request_target`, `workflow_run`, or `issue_comment` with write permissions and, on purpose,
had no checkout at all. A file in the repository has to be checked out before a step can load it,
and that checkout must never reach pull request code. The TanStack compromise of 2026-05-11, which
[ADR 0140](0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md) describes, started
with a `pull_request_target` workflow running the fork's files.

Where should workflow step code live, how long may a block stay inline, and how does a privileged
job load a file safely?

## Decision Drivers

* Code is readable and checkable where it lives: highlighting, ShellCheck, shfmt, and a direct
  import from a test.
* The rule is enforced by a check, as [Maintaining agent docs](../agents/maintaining-agent-docs.md)
  requires of every rule.
* A privileged job never checks out, installs, or runs anything a pull request controls, and keeps
  no cache ([GitHub Actions security](../agents/github-actions-security.md)).
* The extracted code does exactly what the inline code did.
* One convention for every workflow, so a reader finds the file from the step and the step from
  the file.

## Considered Options

* Keep the code inline and rely on review.
* Plain CommonJS files under `scripts/`, loaded with `require` from a sparse checkout of one
  base-branch commit.
* Composite actions under `.github/actions/`.
* TypeScript modules run by Node.
* A numeric inline limit of 10 lines.
* No numeric limit, only "short and simple".
* `github.sha` as the checkout `ref`.
* `github.event.pull_request.base.sha` as the checkout `ref`.
* No `ref` input on the checkout.

## Decision Outcome

Chosen option: plain CommonJS files under `scripts/`, with a 10-line inline limit and the
`ref: ${{ github.sha }}` checkout, because it is the only combination that gives every block a
file an editor and a test can open, keeps a privileged job free of installs and pull request
content, and is small enough to check mechanically.

A block stays inline only while it has at most 10 non-blank, non-comment lines and defines no
function. The limit is a chosen value, kept in one constant, `INLINE_LINE_LIMIT` in
`scripts/github-actions-inline-code.test.ts`. Shell code becomes `scripts/<name>` with a Bash
shebang, PowerShell code `scripts/<name>.ps1`, and github-script code
`scripts/<workflow stem>.cjs` or `scripts/<stem>-<step>.cjs`, exporting one
`async ({ github, context, core })` function that the step calls in one line. A privileged job
first checks out exactly those files at `github.sha` with `sparse-checkout-cone-mode: false` and
`persist-credentials: false`; only a job that also runs other `scripts/` files from that checkout
takes the whole tree. Tests import the module through `loadWorkflowScript`, which reads the step's
require call.

### Consequences

* Good, because every block over the limit is now a file: 13 github-script modules, one Bash
  script, and one PowerShell script, each checked or run by an existing CI job.
* Good, because the tests run the same file the workflow does, and no test parses workflow text
  for code any more.
* Good, because the one-line `require` call makes the mapping from step to file greppable in both
  directions, and the check fails on a module no workflow loads.
* Bad, because four privileged workflows now contain a checkout step, and each reader has to see
  that it fetches one file from a base-branch commit without the token. The header comment of each
  workflow and the audit table in [GitHub Actions security](../agents/github-actions-security.md)
  say so, and two script tests check the shape.
* Bad, because the modules are plain JavaScript and `tsc` does not see them. The inline blocks had
  no types either; the tests cover the behavior.
* Bad, because a change to a module takes effect only after it reaches the base branch, as a
  change to the workflow file already did on these events.

### Confirmation

`scripts/github-actions-inline-code.test.ts` fails on a block over the limit or with a function
definition, a script block that uses `require` in any other form, a missing or non-function
module, an unloaded `.cjs` file, a module loaded without a preceding root checkout that drops the
token, a sparse list that is not exactly the loaded modules, a full checkout in a job that runs no
other `scripts/` file, and, in a privileged workflow, a checkout with another `ref` or a step that
installs dependencies. It also covers the three failures of the module lookup the tests share.
`scripts/github-actions-security.test.ts` accepts the `ref` form and rejects a cache in these
jobs. Lowering the limit to 5 fails the test on a six-line `run` block in
`openrewrite-renovate.yml`; putting one moved block back inline fails it on that step and on
the module nothing loads any more. Both run in the required
`script-tests` job through `pnpm run verify:scripts`.

## Pros and Cons of the Options

### Keep the code inline and rely on review

The code stays in the workflow YAML and a reviewer reads it there.

* Good, because nothing moves and no privileged job gains a checkout.
* Bad, because the code has no highlighting, linter, or formatter, and the tests keep slicing YAML
  text by indentation.
* Bad, because the existing one-or-two-command rule had no check and was not followed.

### Plain CommonJS files under `scripts/` loaded from a sparse base-commit checkout

Each block becomes `scripts/<name>.cjs` exporting one function. The step is
`await require("./scripts/<name>.cjs")({ github, context, core });`. A privileged job checks out
only those files, at a base-branch commit, with `persist-credentials: false`. `actions/checkout`
fetches with `--filter=blob:none` and writes the patterns to `info/sparse-checkout`, so the
working tree holds the listed files and nothing else.

* Good, because github-script's `require` loads a `.cjs` file without `pnpm install`, a
  `package.json`, or a Node setup step, so a privileged job gains one checkout and nothing else.
* Good, because `.cjs` is CommonJS on every Node and in every directory, while a `.js` file would
  be ESM under the root `package.json` and CommonJS in a sparse checkout that lacks it.
* Good, because a test imports the module with `createRequire` and passes fakes for `github`,
  `context`, and `core` exactly as before.
* Bad, because `tsc` skips `.cjs` files.
* Bad, because a module cannot use a dependency: nothing installs one in a privileged job.

### Composite actions under `.github/actions/`

Each block becomes a local action with its own `action.yml` and script, and the step uses it with
`uses: ./.github/actions/<name>`.

* Good, because the action's inputs and outputs are declared.
* Bad, because a local action also needs a checkout before `uses:` can find it, so it solves none
  of the privileged-checkout question and adds an `action.yml` per block.
* Bad, because a github-script step would still hold the glue that calls the action's script.

### TypeScript modules run by Node

Each block becomes `scripts/<name>.ts`, type-checked by `tsc`, and the step runs
`node scripts/<name>.ts` after `actions/setup-node`.

* Good, because the module is typed and `pnpm run check:scripts` covers it.
* Bad, because the step needs `actions/setup-node` and a `GITHUB_TOKEN` plus an Octokit client
  of its own, while github-script already provides an authenticated `github` object.
* Bad, because loading a `.ts` file through github-script's `require` depends on the runner
  Node's type stripping, which is a newer feature than the `node24` runtime guarantees, and a
  `.ts` file under the root `package.json` is an ES module that `require` returns as a namespace.

### A numeric inline limit of 10 lines

The rule names a number of non-blank, non-comment lines, kept in one constant that the check
reads.

* Good, because a number is what a test can check, and 10 lines leaves room for a command with a
  few arguments or an `if` around it, while every moved block had 17 lines or more.
* Bad, because the number is a judgment; the owner may change the constant.

### No numeric limit, only "short and simple"

The rule says only that a block stays inline while it is short and simple, and review decides.

* Good, because no block has to move for a line count alone.
* Bad, because this is the rule the repository already had, and blocks of 268 lines grew under it.

### `github.sha` as the checkout `ref`

The checkout fetches `github.sha`, which GitHub sets to the last commit on the pull request's base
branch on `pull_request_target`, to the last commit on the default branch on `issues`,
`issue_comment`, `workflow_run`, and `schedule`, and to the dispatched branch's tip on
`workflow_dispatch`: on every event here, the commit the workflow file itself was read from.

* Good, because the module and the workflow that names it come from the same commit, so the file
  the step loads always exists.
* Good, because one expression serves every event, and a reader sees a deliberate ref.
* Bad, because the security page listed `github.event.pull_request.base.sha` and a `base_sha`
  step output as the trusted forms, so its rule and test gained a third form.

### `github.event.pull_request.base.sha` as the checkout `ref`

The checkout fetches the base-branch commit recorded on the pull request, the form the security
page allowed first and the one the OpenRewrite workflow's `base_sha` step output resembles.

* Good, because it names the pull request's base in the workflow text.
* Bad, because it is the base tip as of the pull request's last update, not when the run started.
  After a workflow and its module change together, a pull request not pushed since that merge
  checks out a base without the module, and its `labeled`, `edited`, or `/ready` run fails until
  the next push. The first such run after this change would hit every open pull request.
* Bad, because it is empty on `issues`, `issue_comment`, `workflow_run`, and `schedule`, so it
  needs a fallback anyway.

### No `ref` input on the checkout

`actions/checkout` takes `github.sha` when no `ref` is given, so the checkout fetches the same
commit as the chosen option.

* Good, because it is the shortest form, the one the OpenRewrite report job used and its publish
  job still uses.
* Bad, because a reader cannot tell a deliberate base checkout from a forgotten `ref`, and a check
  cannot either.

## More Information

The `pinned-betterleaks-image` job in `private-context.yml` runs on `pull_request`, where the
workflow file is read from the pull request's merge commit. Its comparison script comes from a
sparse checkout of that same merge commit, so a pull request opened before this change, whose head
and recorded base both lack the script, still finds it; running it from the head checkout under
`source/` would have failed that required check until the next rebase. The scan the step gates
still runs the base branch's scanner. The `windows-powershell` job in `ci.yml` runs
`scripts/check-powershell-scripts.ps1` from the ordinary checkout; a `pwsh` step passes the
script's exit code on through `$LASTEXITCODE`, tried in the pinned .NET SDK image.

This ADR amends the Confirmation section of
[ADR 0069](0069-repository-owned-pull-request-size-labeling.md) and the audit summary of
[ADR 0140](0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md), which said these
workflows had no checkout; both carry a note. [ADR 0075](0075-automate-pull-request-author-handoffs.md)
and [ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) stay as written: a checkout
of the base branch's own files is not pull request code.
