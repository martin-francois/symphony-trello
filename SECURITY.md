# Security Policy

## Supported Versions

Only the most recent published release is supported with security updates unless a maintainer
announces a different support window in the release notes.

| Version | Supported |
| ------- | --------- |
| Most recent published release | Yes |
| Any older release | No |

## Reporting a Vulnerability

Do not open a public issue for a suspected vulnerability.

Use GitHub private vulnerability reporting through
[Report a Vulnerability](https://github.com/martinfrancois/symphony-trello/security/advisories/new)
if it is available for this repository. If that is not available, contact the maintainers privately
through GitHub and share only the minimum information needed to start triage.

Include:

- the affected commit or version;
- a short description of the impact;
- reproduction steps using a disposable Trello board or sanitized data;
- relevant logs with secrets removed.

Do not include Trello API keys, Trello tokens, Codex auth files, GitHub tokens, private board links,
or unrelated host paths.

You can expect an initial maintainer response within 7 days. If the report is accepted, maintainers
will coordinate the fix privately, publish a security advisory when appropriate, and credit reporters
who want public credit. If the report is declined, maintainers will explain why it is not considered
a project security issue or why it belongs in a normal public issue instead.

## Credential Handling

Symphony for Trello can read Trello credentials, Codex CLI auth, GitHub CLI auth, local workspaces,
and deployment configuration. Treat reports involving these files as sensitive even when the visible
symptom looks like a normal setup failure.

## Learning More About Security

For general application security background, review the
[OWASP Top Ten](https://owasp.org/www-project-top-ten/) and other resources from the
[Open Worldwide Application Security Project](https://owasp.org/).
