---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #396](https://github.com/martin-francois/symphony-trello/issues/396)"
  - "[GitHub issue #388](https://github.com/martin-francois/symphony-trello/issues/388)"
  - "[ADR 0029](0029-setup-failure-diagnostics-policy.md)"
informed: [Future maintainers]
---

# Name The Underlying Cause In Setup Failure Messages With Path-Free Summaries

## Context and Problem Statement

Setup and lifecycle commands often turn an I/O or transport failure into a setup failure, a usage
error, or a warning. Local stderr shows the message of that wrapping failure, but never its cause
chain. Before [GitHub issue #396](https://github.com/martin-francois/symphony-trello/issues/396), the
wrap sites rendered the cause in three different ways:

* Some appended a `Type: detail` summary, as the `setup_env_write_failed` fix for
  [GitHub issue #388](https://github.com/martin-francois/symphony-trello/issues/388) did.
* Some appended only the exception message, which drops the exception type.
* Some appended nothing, for example the connected-board manifest wraps and the Trello transport
  wraps.

When a site appended nothing, a transient filesystem or network failure could not be diagnosed from
one occurrence. When a site appended the raw message, stderr could show private file paths or the
text of a configuration file. One example was the YAML parser message in `setup-local check`, which
quotes the offending workflow line, credentials included.

How should setup failure messages name their underlying cause?

## Decision Drivers

* A transient filesystem or network failure must be diagnosable from one occurrence.
* Local stderr must not show private paths or file content that users then paste into public
  issues. [ADR 0029](0029-setup-failure-diagnostics-policy.md) lists this as a driver.
* The expected and unexpected failure classification and the troubleshooting report redaction must
  stay as they are.
* One format, owned by one class, so new wrap sites do not invent their own.

## Considered Options

* Append a path-free cause summary through one shared helper.
* Append the full exception message through one shared helper.
* Keep the cause only in the exception chain.
* Let each wrap site choose its own wording.

## Decision Outcome

Chosen option: "Append a path-free cause summary through one shared helper", because it makes one
occurrence diagnosable without printing file-system paths or parser content.

`SetupFailureCauses` owns the format. A message keeps its actionable text and appends the summary in
parentheses:

```text
Could not update the connected-board manifest. Check the config directory permissions. (AccessDeniedException)
Trello request failed (ConnectException, caused by UnresolvedAddressException)
```

The summary is the exception's simple class name, followed by `: detail` when a detail remains. When
the exception wraps a different root cause, the summary adds `, caused by` and the root cause. The
JDK HTTP client throws a `ConnectException` without a message, and only the root cause tells a refused
connection apart from a DNS failure. Two kinds of detail are left out:

* A `FileSystemException` message repeats the affected file paths, so only its reason is kept. The
  actionable text already names the object, such as the manifest, the selected `.env` file, or the
  worker.
* A Jackson parser message quotes the parsed content, which can hold credentials or board names, so
  only the class name is kept. The search for a root cause stops at the first parser failure,
  because the parser's own causes quote the same content.

Other exception messages are kept as they are. The setup code writes its own `IOException` messages
without paths. Some JDK messages, such as a failed process launch, can still name a local path. Local
stderr may show it. The troubleshooting report redacts the whole message, cause summary included,
with the same public-safe rules as every other report field.

An `IOException` that reaches the command boundary without a wrapping setup failure has no
actionable text of its own. The command prints its cause summary as the message.

### Consequences

* Good, because every wrap site renders the cause in the same format.
* Good, because a transient failure shows its type and reason in one stderr line.
* Good, because the `setup-local check` warning no longer quotes workflow YAML content.
* Bad, because a `FileSystemException` without a reason, such as `NoSuchFileException`, shows only
  its class name. The operator relies on the actionable text to know which file was involved.
* Bad, because invalid YAML no longer shows the parser's line and column in the warning.

### Confirmation

Run the focused checks:

```bash
./mvnw -q -Dtest=SetupFailureCausesTest,SetupDiagnosticReporterTest,WorkflowConfigEditorTest test
```

`SetupFailureCausesTest` pins the format and the two omission rules.
`SetupDiagnosticReporterTest` asserts that the cause summary survives report redaction while private
paths, board names, and board ids do not. Review new `catch (IOException ...)` blocks in the setup
package: a block that wraps the failure must use `SetupFailureCauses`.

## Pros and Cons of the Options

### Append A Path-Free Cause Summary Through One Shared Helper

Each wrap site passes its actionable message and the caught exception to `SetupFailureCauses`. The
helper appends the exception type and a detail that leaves out file-system paths and parser content.

* Good, because one occurrence shows the failure type and reason.
* Good, because stderr does not gain private paths or file content.
* Good, because it keeps the path-free behavior that the `setup_env_write_failed` fix already had.
* Bad, because some failures lose the path of the file involved.

### Append The Full Exception Message Through One Shared Helper

Each wrap site appends `Type: message` with the unchanged exception message.

* Good, because local stderr shows the exact file involved.
* Bad, because `FileSystemException` messages put private paths on stderr, and users paste stderr
  into public issues by hand.
* Bad, because parser messages put workflow content, including credentials, on stderr.

### Keep The Cause Only In The Exception Chain

Wrap sites keep their fixed messages and pass the exception only as the cause.

* Good, because stderr never shows any cause detail.
* Bad, because stderr never prints the chain, so a transient failure cannot be diagnosed from one
  occurrence.

### Let Each Wrap Site Choose Its Own Wording

Each site formats its cause however its author prefers.

* Good, because no shared helper is needed.
* Bad, because this produced three different formats before
  [GitHub issue #396](https://github.com/martin-francois/symphony-trello/issues/396), and each format
  had its own gaps.

## More Information

[ADR 0029](0029-setup-failure-diagnostics-policy.md) still owns the classification of expected and
unexpected setup failures and the troubleshooting report. This ADR only decides how a failure message
names its cause. The `setup_env_write_failed` fix for
[GitHub issue #388](https://github.com/martin-francois/symphony-trello/issues/388) introduced the
path-free rule for one wrap site, and
[GitHub issue #396](https://github.com/martin-francois/symphony-trello/issues/396) extended it to every
setup wrap site.
