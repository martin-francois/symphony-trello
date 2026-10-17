---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #114](https://github.com/martin-francois/symphony-trello/issues/114)"
  - "[ADR 0029](0029-setup-failure-diagnostics-policy.md)"
  - "[ADR 0030](0030-private-diagnostics-context-flag.md)"
  - "[Codex CLI non-interactive mode](https://developers.openai.com/codex/noninteractive)"
informed: [Future maintainers, Contributors]
---

# Offer A Local Codex Investigation Before Setup-Failure Issues

## Context and Problem Statement

[ADR 0029](0029-setup-failure-diagnostics-policy.md) splits setup failures into expected failures
with a direct next step and unexpected failures that write a sanitized troubleshooting report. For
an unexpected failure in an interactive terminal, the command then offers to post that report as a
GitHub issue when GitHub CLI is signed in.

Many users who install Symphony for Trello already have Codex CLI installed and signed in, because
the worker needs it. Codex can often find the cause of a local failure, and sometimes fix it, faster
than a maintainer can answer an issue.
[GitHub issue #114](https://github.com/martin-francois/symphony-trello/issues/114) asks for an
opt-in Codex investigation before the issue prompt.

How should the setup commands run Codex on a failure without leaking private context into a public
issue, without surprising the user, and without loops?

## Decision Drivers

* The user decides. Codex never starts without an explicit yes.
* The prompt Symphony builds and every GitHub issue draft contain only sanitized text.
* Codex needs write access to fix anything, but only where a fix can belong.
* Nothing is committed, pushed, or posted automatically.
* A Codex failure, a timeout, or a missing Codex login falls back to the existing issue prompt.
* Non-interactive runs keep their current behavior.
* Tests run without the real Codex service.

## Considered Options

* Run `codex exec` with a sanitized prompt, a structured answer schema, and the `workspace-write`
  sandbox rooted in the Symphony config directory.
* Send raw local paths and private context in the prompt.
* Run Codex read-only in a neutral temporary directory.
* Print a ready-made Codex command for the user to run.
* Open an interactive Codex session.

## Decision Outcome

Chosen option: "Run `codex exec` with a sanitized prompt, a structured answer schema, and the
`workspace-write` sandbox rooted in the Symphony config directory", because it is the only option
that lets Codex fix local problems while Symphony itself never hands private values to Codex or to
GitHub.

The flow for an unexpected setup failure in an interactive terminal is:

1. Write the sanitized troubleshooting report as before.
2. If Codex CLI is on `PATH` and `codex login status` succeeds, explain what Codex receives and ask
   `Ask Codex to investigate this failure? [y/N]`. The default answer is no.
3. On yes, run `codex exec --sandbox workspace-write --skip-git-repo-check --ephemeral
   --output-schema ... --output-last-message ... --cd <config dir> -` with the prompt on standard
   input. When the app home is a Git checkout from a source install, add it with `--add-dir` so
   Codex can fix code. A release archive install gets no code write access.
4. Show the classification, diagnosis, changed files, validation commands with their result, and
   next step from the structured answer.
5. For a local configuration problem, stop with the next step, or with "rerun the command" when
   Codex already fixed it, and do not offer an issue. For a fix with changed files whose validation
   all passed, ask whether to post an issue that describes the failure and the local fix. In every
   other case, show the existing issue prompt. When Codex answered, the issue draft is the report
   plus a `Local Codex Investigation` section, written next to the report.

Privacy boundary:

* The prompt contains fixed instructions, the version, the command name, how the command was started
  (installer onboarding, installed command, or direct Java command), the error code, and the
  sanitized report. It contains no raw paths, Trello names or identifiers, account names, or
  credentials. The question before the run lists the same items.
* Codex runs locally. Like any local Codex session, Codex sees its working directory and writable
  roots, and it can read local files. It may resolve report tokens with
  `symphony-trello diagnostics --show-private-context --lookup <token>`, as
  [ADR 0030](0030-private-diagnostics-context-flag.md) allows for local agents.
* Every text Codex returns goes through the same sanitizer as the report before it is printed or
  written into an issue draft.
* `--ephemeral` keeps Codex from saving a session log of the investigation. Codex progress output
  is captured in a temporary file and deleted, because it can echo local file contents.

Codex gets 15 minutes. On timeout, Ctrl+C, or JVM exit, Symphony stops the Codex process and its
child processes. The Codex process environment carries `SYMPHONY_TRELLO_CODEX_INVESTIGATION`, and a
setup command that sees it never offers another investigation, so a failed validation run cannot
start a second Codex run.

### Consequences

* Good, because users can get a diagnosis or a working local fix before anyone opens an issue.
* Good, because a public issue that follows a local fix carries the diagnosis, a short fix summary,
  the changed files, and the validation result.
* Good, because a local configuration problem ends with a next step instead of an issue.
* Good, because the sanitizer that already protects the report also protects Codex answers.
* Bad, because Codex sees local paths through its working directory and writable roots, and it can
  read local files, including files with credentials. The prompt tells Codex not to print or change
  credentials, but that is an instruction, not a technical guarantee.
* Bad, because the investigation spends the user's Codex usage.
* Bad, because a Codex CLI that does not support `--ephemeral`, `--output-schema`, or
  `--output-last-message` exits with an error. The flow then falls back to the issue prompt.

### Confirmation

Run the focused tests:

```bash
./mvnw -q -Dtest=SetupFailureFollowUpTest,ProcessCodexInvestigationRunnerTest test
```

`SetupFailureFollowUpTest` drives the real report and sanitizer with a fake Codex runner and a fake
`gh`. It checks the prompt order, the default-no answer, each fallback, the local configuration
stop, the verified-fix issue draft, the non-interactive and loop guards, and that no secret, board
name, Trello URL, or temporary path reaches the Codex prompt or the issue draft.
`ProcessCodexInvestigationRunnerTest` runs the process runner against a fake `codex` script and
checks the command line, the standard-input prompt, the schema, the marker variable, the timeout
stop, and every failure result.

## Pros and Cons of the Options

### Run `codex exec` With A Sanitized Prompt And The `workspace-write` Sandbox

Symphony builds the prompt from the sanitized report, runs `codex exec` non-interactively with a
JSON answer schema, and lets Codex write only in the config directory and, for source installs, the
source checkout.

* Good, because Symphony never puts private values into the prompt or the issue draft.
* Good, because Codex can still fix workflow, manifest, and source-checkout problems.
* Good, because the structured answer gives the changed files and validation results that the issue
  requires without parsing free text.
* Neutral, because Codex still learns local paths from its own working directory and from files it
  reads. That exposure goes to the Codex service the user already uses for the worker, not to
  GitHub.
* Bad, because the CLI flags tie the feature to Codex CLI versions that support them.

### Send Raw Local Paths And Private Context In The Prompt

The prompt would include config, workflow, state, and log paths, and possibly the private-context
diagnostics output.

* Good, because Codex would need fewer lookup commands.
* Bad, because Symphony itself would send private values even when Codex never needs them.
* Bad, because a Codex answer that repeats prompt text could carry those values toward an issue
  draft, and the sanitizer would be the only barrier.

### Run Codex Read-Only In A Neutral Temporary Directory

Codex would start in an empty temporary directory with the `read-only` sandbox.

* Good, because Codex would not see the config directory as its working root.
* Bad, because Codex could never fix anything, which drops a core part of the issue.
* Bad, because Codex can still read local files and learn the same paths, so the privacy gain is
  small.

### Print A Ready-Made Codex Command For The User

Symphony would print a `codex` command and leave the run to the user.

* Good, because it needs no process handling.
* Bad, because the result does not come back to Symphony, so it cannot show the outcome or build
  an issue draft with the fix.
* Bad, because the printed command would have to carry the report and the instructions in a shell
  command line.

### Open An Interactive Codex Session

Symphony would start the Codex terminal interface with the failure context.

* Good, because the user could steer the investigation.
* Bad, because there is no structured result to show or to add to an issue draft.
* Bad, because the session would take over the setup command's terminal and would be hard to stop
  on a timeout.

## More Information

[GitHub PR #779](https://github.com/martin-francois/symphony-trello/pull/779) adds cause summaries
to setup failure messages. The report that Codex receives includes those summaries once both changes
are merged.
