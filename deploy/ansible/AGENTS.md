# Ansible Deployment Agent Instructions

These instructions apply to files under `deploy/ansible`. Follow the repository root
`AGENTS.md` as well.

## Deployment Contract

- Treat the Ansible playbook as the repeatable production deployment path for Symphony for Trello.
- Keep the playbook idempotent by design. A second run with the same inputs should report
  `changed=0`.
- Manage workflow services from declared desired state in `symphony_trello_workflows`. Removing a
  workflow from that list should stop and disable the matching service and remove the deployed
  workflow file.
- Do not hide required manual cleanup in documentation. Workspace data under
  `/var/lib/symphony-trello` may contain useful run output and should not be deleted automatically
  when a workflow is removed.
- Keep extra host path access opt-in. Use `symphony_trello_allowed_project_roots` for specific
  files or folders, and reserve `symphony_trello_allow_host_filesystem` for explicitly accepted broad
  host access.

## Local Verification Commands

Use a temporary Python virtualenv when Ansible is not already available on `PATH`:

```bash
VENV=/tmp/symphony-trello-ansible-venv
python3 -m venv "$VENV"
"$VENV/bin/python" -m pip install --disable-pip-version-check -q -r requirements-lint.txt
```

Install required collections and run syntax/lint checks from `deploy/ansible`:

```bash
ANSIBLE_COLLECTIONS_PATH="../../.ansible/collections:/root/.ansible/collections:/usr/share/ansible/collections" \
  "$VENV/bin/ansible-galaxy" collection install -r requirements.yml -p ../../.ansible/collections

ANSIBLE_COLLECTIONS_PATH="../../.ansible/collections:/root/.ansible/collections:/usr/share/ansible/collections" \
  "$VENV/bin/ansible-playbook" -i inventory.example.yml site.yml --syntax-check

ANSIBLE_COLLECTIONS_PATH="../../.ansible/collections:/root/.ansible/collections:/usr/share/ansible/collections" \
  "$VENV/bin/ansible-lint" .
```

Run the real local deployment only when runtime behavior changed or when the user asks for
deployment:

```bash
ANSIBLE_COLLECTIONS_PATH="../../.ansible/collections:/root/.ansible/collections:/usr/share/ansible/collections" \
  "$VENV/bin/ansible-playbook" -i inventory.yml site.yml --vault-password-file .vault-password
```

Run it a second time after a successful deployment to confirm idempotency.

## Live Deployment Verification

After deploying runtime-impacting changes, verify more than `systemctl`:

- `systemctl status symphony-trello@<workflow>`
- `curl -fsS http://127.0.0.1:<port>/api/v1/state`
- Trello card column and comments when a live card was processed

For a bug observed in live deployment, reproduce the relevant live behavior when reasonable. The
deployment is not healthy until the service is active, `/api/v1/state` has no unexpected running or
retrying entries, and Trello handoff matches the workflow expectation.

When verifying blocked-card behavior, check that a blocker comment does not leave the card in an
active column. If the workflow has no dedicated blocked column, the generated fallback should move the
card to the review handoff column so it stops running.

## Secrets And Redaction

- Keep real inventory, vault, vault password files, `.env`, and workflow files with private board ids
  ignored.
- Store Trello API values in Ansible Vault and deploy them through systemd credential files.
- Reuse the existing Codex CLI `auth.json`; do not add raw OpenAI API keys.
- Do not print secret values, Trello tokens, private board/card ids, private project names, or host
  paths in docs, issues, commits, or final summaries.
- Use `no_log: true` for tasks that copy or inspect secret material.

## Change Guidance

- Prefer Ansible modules over shell commands when an idempotent module exists.
- Keep local packaging conditional on source/build-input fingerprints so normal reruns stay
  unchanged.
- If a change alters systemd hardening, verify both `systemd-analyze verify` and a real Codex
  app-server run when feasible.
- If a change alters workflow deployment, verify changed workflow files restart only their matching
  services and unchanged workflows remain untouched.
