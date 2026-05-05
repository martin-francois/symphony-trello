# Ansible Deployment

Use this path when you want a repeatable server deployment. The manual systemd guide still works;
this playbook automates the same layout.

The playbook manages:

- the `symphony-trello` service user and directories
- `/opt/symphony-trello/app`
- `/etc/symphony-trello/secrets`
- `/etc/symphony-trello/workflows/*.WORKFLOW.md`
- one `symphony-trello@name` systemd service per workflow
- removed workflow files and their stopped systemd services

Run it twice with the same inputs. The second run should report `ok` for already-correct resources.

## Prerequisites

On the machine where you run Ansible, install:

- Ansible
- Java 25 LTS
- Codex CLI

The playbook uses the Maven wrapper in this repository, so you do not need to install Maven
separately.

Log in with the Codex CLI on the machine where you run Ansible:

```bash
codex login
```

The playbook copies that existing Codex CLI auth file to the service user on the target server.

Make sure the target server has:

- Java 25 LTS
- Codex CLI available to the service, or a workflow-specific `codex.command`
- `rsync`
- passwordless `sudo` for the Ansible user

Install the Ansible collection used for file sync:

```bash
cd deploy/ansible
ansible-galaxy collection install -r requirements.yml
```

## Configure Inventory

Create your local inventory:

```bash
cp inventory.example.yml inventory.yml
```

Edit `inventory.yml` and point it at your server:

```yaml
---
symphony_trello:
  hosts:
    symphony-server:
      ansible_host: your-server.example.com
      ansible_user: deploy
```

`inventory.yml` is ignored because it is local to your deployment.

## Configure Workflows

Create your local group variables:

```bash
cp examples/vars.yml group_vars/symphony_trello/vars.yml
```

Edit `group_vars/symphony_trello/vars.yml`:

```yaml
---
symphony_trello_workflows:
  - name: project-a
    src: "{{ playbook_dir }}/../../WORKFLOW.project-a.md"
  - name: project-b
    src: "{{ playbook_dir }}/../../WORKFLOW.project-b.md"
```

Each `name` becomes a systemd service instance. For example, `project-a` becomes
`symphony-trello@project-a` and reads `/etc/symphony-trello/workflows/project-a.WORKFLOW.md`.

Each `src` points to the local workflow file that should be deployed for that service.

For deployed workflow files, set Trello credentials to the systemd credential files:

```yaml
tracker:
  api_key: file:$CREDENTIALS_DIRECTORY/trello-api-key
  api_token: file:$CREDENTIALS_DIRECTORY/trello-api-token
```

The workflow list is the desired state. By default, a workflow service that exists on the server but
is no longer listed here is stopped, disabled, and removed from `/etc/symphony-trello/workflows`.

If Java or Codex is installed in a directory systemd does not normally search, add:

```yaml
symphony_trello_service_path: /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/opt/java/bin:/opt/codex/bin
```

Use paths the `symphony-trello` service user can read. The systemd unit protects home directories, so
do not point this at tools installed below `/root` or a personal home directory.

By default, the playbook reuses the Codex CLI auth file from the user running Ansible:

```text
~/.codex/auth.json
```

If your existing Codex auth file is somewhere else, add:

```yaml
symphony_trello_codex_auth_src: /path/to/existing/.codex/auth.json
```

The target server receives that file as `/var/lib/symphony-trello/.codex/auth.json`, owned by the
`symphony-trello` service user with mode `0600`.

Set `symphony_trello_manage_codex_auth: false` only when you already created that auth file for the
service user on the target server.

`vars.yml` is ignored because workflow paths and host-specific choices differ per deployment.

## Configure Secrets With Ansible Vault

Create an encrypted vault file:

```bash
ansible-vault create group_vars/symphony_trello/vault.yml
```

Add the Trello credentials:

```yaml
---
symphony_trello_trello_api_key: replace-with-your-key
symphony_trello_trello_api_token: replace-with-your-token
```

`vault.yml` is ignored. Keep the vault password outside the repository.

The playbook writes the vault values to root-only files on the server and loads them through systemd
credentials. They are not written to the service environment.

Codex CLI auth is copied from the existing auth file configured in `vars.yml`; do not put it in the
vault file.

## Deploy

Run the playbook:

```bash
ansible-playbook -i inventory.yml site.yml --ask-vault-pass
```

The playbook packages the app on your machine when build inputs changed or when the packaged app is
missing. It checks the Maven wrapper, Maven config, `pom.xml`, `src/main`, and `src/test`.

Run the playbook again after it finishes. With no local changes, the second run should report no
changed tasks.

## Update The App

Pull or edit the newer version, then rerun the playbook:

```bash
cd deploy/ansible
ansible-playbook -i inventory.yml site.yml --ask-vault-pass
```

The playbook packages the app if needed, syncs changed app files to `/opt/symphony-trello/app`, and
restarts the managed workflow services when the app changed.

## Add Or Change A Workflow

Add or edit a local workflow file, then update `symphony_trello_workflows`:

```yaml
symphony_trello_workflows:
  - name: project-a
    src: "{{ playbook_dir }}/../../WORKFLOW.project-a.md"
  - name: project-b
    src: "{{ playbook_dir }}/../../WORKFLOW.project-b.md"
  - name: project-c
    src: "{{ playbook_dir }}/../../WORKFLOW.project-c.md"
```

Rerun the playbook. New workflow files are installed, changed workflow files are updated, and managed
services restart when their inputs changed.

## Remove A Workflow

Remove the workflow from `symphony_trello_workflows` and rerun the playbook.

The playbook stops and disables the removed `symphony-trello@name` service and deletes the matching
workflow file from `/etc/symphony-trello/workflows`.

It does not delete workspaces under `/var/lib/symphony-trello`; those can contain useful run output
or generated code that should be reviewed before removal.
