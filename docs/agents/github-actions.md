# GitHub Actions workflows

## Scope

Where the code a workflow step runs lives, how a step loads it, and how tests exercise it. What a
workflow may do in a privileged run is in [GitHub Actions security](github-actions-security.md).

## Rules

Every rule names its check. `scripts/github-actions-inline-code.test.ts` runs in the
`script-tests` job through `pnpm run verify:scripts`.

- A `run:` block or an `actions/github-script` `script:` block stays inline only while it has at
  most 10 non-blank, non-comment lines and defines no function. Longer or function-defining code is
  a file under `scripts/` that the step runs, so it gets syntax highlighting, a direct test, a
  local run, and for Bash the shell linters. The limit is `INLINE_LINE_LIMIT` in the test. Check:
  the test fails on a block over the limit or with a `function`, a Bash `name() {`, or a named
  arrow function.
- Shell code is `scripts/<name>` with a Bash shebang. `scripts/check-shell-scripts` finds
  `#!/bin/bash` and `#!/usr/bin/env bash` and runs `bash -n`, ShellCheck, and shfmt on every such
  file. PowerShell code is `scripts/<name>.ps1`, run from a step with `shell: pwsh`. Check: the
  `lint` CI job for Bash; the `windows-powershell` job runs the PowerShell file.
- github-script code is a CommonJS module with no dependencies, `scripts/<workflow file stem>.cjs`,
  or `scripts/<stem>-<step>.cjs` when the workflow has more than one such step. It exports one
  function: `module.exports = async ({ github, context, core }) => { ... }`. The step's whole
  block is `await require("./scripts/<name>.cjs")({ github, context, core });`, and the file starts
  with a comment naming the workflow and job that run it. Check: the test rejects any other block
  that contains `require("./`, a module that is missing or does not export a function, and a
  `.cjs` file no workflow loads; review checks the comment.
- A job that loads a module checks it out first with `actions/checkout` and
  `persist-credentials: false`, listing exactly the modules it loads in `sparse-checkout` with
  `sparse-checkout-cone-mode: false`; only a job that also runs other `scripts/` files from that
  checkout takes the whole tree. In a workflow on `pull_request_target`, `workflow_run`, or
  `issue_comment`, that checkout uses `ref: ${{ github.sha }}`, the commit the workflow file was
  read from: the base-branch tip on `pull_request_target`, the default-branch tip on the other
  events, the dispatched branch's tip on `workflow_dispatch`. That holds only while every trigger
  of the workflow is in `TRIGGERS_WITH_A_TRUSTED_SHA` of `scripts/test-support/workflow-script.ts`:
  `pull_request_target`, `workflow_run`, `issue_comment`, `issues`, `schedule`,
  `workflow_dispatch`; on `pull_request` `github.sha` is the pull request's merge
  commit and on `push` whatever was pushed, so such a workflow cannot load a module in a privileged
  job this way at all. `github.event.pull_request.base.sha`
  is not that commit: it is the base tip as of the pull request's last update, so a checkout there
  can miss a module the workflow already loads.
  Such a privileged job installs no dependency (`pnpm`, `npm`, `yarn`) and uses no cache; a module
  that needs a dependency cannot run there. Check: the test for the checkout shape in every
  workflow and for the `ref` and the install in a privileged one;
  `scripts/github-actions-security.test.ts` for the `ref` form, the token, and caches.
- A test of a module imports it through `loadWorkflowScript(workflow, step)` from
  `scripts/test-support/workflow-script.ts`, which reads the step's require call, so the test and
  the workflow run the same file. Never cut code out of the workflow YAML in a test. Pin
  `process.env` with `withEnvironment` and the clock with `withDateNow` from the same file. Check:
  the helper fails when the step is missing, not unique, or its block is not the require call,
  and the inline-code test covers those three failures; review for the import.

## References

- [GitHub Actions security](github-actions-security.md)
- [Default workflow](default-workflow.md)
- [Testing](testing.md)
- [ADR 0143](../adr/0143-keep-workflow-step-code-in-repository-files.md)
