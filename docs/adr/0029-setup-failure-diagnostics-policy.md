---
status: accepted
date: 2026-05-27
decision-makers: [François Martin, Codex]
consulted: [AGENTS.md, src/main/java/ch/fmartin/symphony/trello/setup/SetupDiagnosticReporter.java]
informed: [Future maintainers]
---

# Separate Expected Setup Errors From Unexpected Diagnostics

## Context and Problem Statement

The setup and local lifecycle commands can fail for two different reasons.

Some failures are expected user-correctable states: a missing Trello API key, invalid CLI arguments,
missing Codex login, missing GitHub login, a declined setup action, an ambiguous board selector, or
a Trello 401/403/404 response. These should tell the user what to do next.

Other failures are unexpected or degraded runtime states: workflow write failures, workflow scan
failures, unhealthy local startup with no known user-correctable cause, failed process stop, Trello
transport failures, malformed Trello payloads, or generic IO exceptions. These can need a sanitized
troubleshooting report.

How should Symphony for Trello decide when to create a diagnostics report or offer to open a GitHub
issue?

## Decision Drivers

* Do not flood users with diagnostics for normal setup mistakes.
* Do not ask users to open GitHub issues for failures they can fix locally.
* Keep unexpected failures debuggable with sanitized public issue-report data.
* Avoid leaking private paths, Trello identifiers, credentials, Codex auth data, GitHub tokens, or
  account names in stderr or generated reports.
* Make the rule clear for future setup features and new failure codes.

## Considered Options

* Classify setup failure codes as expected or unexpected before writing diagnostics.
* Write diagnostics for every setup failure.
* Never write diagnostics automatically.

## Decision Outcome

Chosen option: "Classify setup failure codes as expected or unexpected before writing diagnostics",
because it keeps common user-correctable failures simple while preserving useful reports for
unexpected failures.

Expected failures do not create troubleshooting reports and do not offer to open a GitHub issue by
default. They should print the failure code, a clear message, and a next step when the message alone
is not enough. Examples include:

* invalid CLI shape or mutually exclusive options;
* missing Trello credentials;
* missing required setup choices such as workspace id or board selector;
* missing prerequisite tools;
* Codex or GitHub authentication not completed;
* declined optional setup actions;
* known Trello auth, permission, invalid-request, archived-board, or not-found responses.

Unexpected failures may create a sanitized troubleshooting report. Examples include:

* workflow scan or write failures;
* local worker start, health-check, stop, or state-read failures;
* Trello transport failures, rate limits, server errors, and unknown payloads;
* generic `IOException`, unchecked exceptions, and unknown setup codes.

`SetupDiagnosticReporter.shouldReport(...)` owns the code-level classification. When adding a new
setup failure code, the implementer must decide whether it is expected or unexpected and update that
classification, tests, and user-action hints if needed.

Local worker startup is a boundary case. The worker can exit before the health check succeeds, and
the health check alone only sees "not healthy". If the just-written worker logs show a known
user-correctable Trello problem, such as authentication failure, permission denial, or an archived
board, the lifecycle command should rethrow the matching expected setup code instead of the generic
`setup_start_unhealthy` code. The CLI should then print the actionable next step and skip diagnostics.
If the logs do not show a known expected cause, keep `setup_start_unhealthy` so a sanitized report can
include system, workflow, health, and log context.

The `start` command should also check local worker credentials before launching the worker. If the
selected `.env` file and process environment do not provide `TRELLO_API_KEY` and `TRELLO_API_TOKEN`,
the command should fail with an expected missing-credentials code and a next step that points to the
selected `.env` file. It should not start the packaged app and wait for a later Trello authentication
failure, because that makes a missing credential file look like an invalid token.
This preflight only applies when `tracker.kind` is `trello` and the workflow uses `$VAR_NAME`
credential references or omits the credential fields, which means the default `$TRELLO_API_KEY` and
`$TRELLO_API_TOKEN` references apply. Workflows with another or missing tracker kind are left to
normal workflow config validation. Workflows that configure literal or `file:` tracker credentials do
not need `.env` credentials for this preflight.

### Consequences

* Good, because missing credentials and invalid arguments stay concise and actionable.
* Good, because unexpected failures still include enough sanitized system, workflow, health, and log
  context to debug public issue reports.
* Good, because new setup features have a clear classification rule.
* Bad, because maintainers must keep the expected-failure list updated when adding new failure
  codes.
* Bad, because a misclassified expected failure can still create unnecessary diagnostics until fixed.

### Confirmation

Run this focused check to verify expected failures are not reported, unexpected failures are reported,
missing worker credentials fail before launch, and user-facing CLI output remains sanitized:

```bash
./mvnw -q -Dtest=SetupDiagnosticReporterTest,TrelloBoardSetupMainTest,LocalWorkerManagerTest test
```

Run `./mvnw -q spotless:check verify` before merging.

## Pros and Cons of the Options

### Classify Setup Failure Codes As Expected Or Unexpected Before Writing Diagnostics

Maintain an explicit list of expected, user-correctable setup failure codes. Commands print a short
next step for expected codes and write a sanitized troubleshooting report (optionally offering a
GitHub issue) only for codes that are not on the list.

* Good, because the command can distinguish user mistakes from report-worthy failures.
* Good, because expected failures can provide short next steps without generating report files.
* Good, because unexpected failures still produce sanitized debug context.
* Bad, because each new failure code needs an explicit classification decision.

### Write Diagnostics For Every Setup Failure

Generate a sanitized troubleshooting report for every setup failure, regardless of whether the user
can fix the problem from the error message alone.

* Good, because every failure has debug context.
* Bad, because users see long reports for simple mistakes such as missing credentials.
* Bad, because users may open GitHub issues for expected local configuration problems.

### Never Write Diagnostics Automatically

Print only the error message for every failure and rely on users to collect diagnostics manually
with a separate command when a maintainer asks for them.

* Good, because setup output stays short.
* Bad, because unexpected failures lose the context needed to debug path, OS, tool, workflow, and
  log problems.
* Bad, because users have to manually collect data that the command can sanitize for them.

## More Information

Diagnostics and troubleshooting reports are intended to be public-safe, but users are still told to
review generated output before sharing it.
