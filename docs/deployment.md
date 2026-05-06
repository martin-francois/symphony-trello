# Production Deployment

This guide shows how to run Symphony for Trello on a Linux server with systemd.

Use this when you have one or more `WORKFLOW.md` files and want Symphony to keep running after you
log out. Each workflow file maps to one Trello board, so each workflow runs as one systemd service
instance.

## Deployment Paths

Use the manual steps below when you want to understand or control every server command.

Use the [Ansible deployment guide](ansible-deployment.md) when you want repeatable deployment from
declared workflow files and Ansible Vault secrets. The Ansible path manages the same systemd layout
and is easier to rerun when you add, change, or remove workflows.

## How It Works

One installed application can run many workflows:

1. Build Symphony once.
2. Copy the packaged app to `/opt/symphony-trello/app`.
3. Store Trello secrets in `/etc/symphony-trello/secrets`.
4. Store one workflow file per board in `/etc/symphony-trello/workflows`.
5. Start one systemd service instance per workflow.

For example, these two files:

```text
/etc/symphony-trello/workflows/project-a.WORKFLOW.md
/etc/symphony-trello/workflows/project-b.WORKFLOW.md
```

run as these services:

```bash
sudo systemctl enable --now symphony-trello@project-a
sudo systemctl enable --now symphony-trello@project-b
```

The part after `@` must match the workflow file name before `.WORKFLOW.md`.

## Prepare The Server

Install Java 25 LTS, Codex CLI, Git, and GitHub CLI on the server. The systemd unit starts Java
through `/usr/bin/env java`, so make sure `java` resolves to Java 25 for the service:

```bash
java -version
```

The workflow's `codex.command` defaults to `codex app-server`. That works when `codex` is on the
service user's `PATH`. If your Codex CLI is somewhere else, set `codex.command` in each workflow to
the full command path.

Create a dedicated OS user and directories:

```bash
sudo useradd --system --create-home --home-dir /var/lib/symphony-trello --shell /usr/sbin/nologin symphony-trello
sudo install -d -o root -g root -m 0755 /opt/symphony-trello
sudo install -d -o root -g root -m 0755 /etc/symphony-trello
sudo install -d -o root -g root -m 0700 /etc/symphony-trello/secrets
sudo install -d -o root -g root -m 0755 /etc/symphony-trello/workflows
sudo install -d -o symphony-trello -g symphony-trello -m 0750 /var/lib/symphony-trello
```

Authenticate Codex for the service user before starting real workflows. If the server has an
interactive shell for the service user, run `codex login` as that user so the Codex CLI writes its
auth file below `/var/lib/symphony-trello/.codex`.

```bash
sudo -u symphony-trello -H codex login
```

If you already use Codex CLI on another machine, copy that existing auth file instead:

```bash
sudo install -d -o symphony-trello -g symphony-trello -m 0700 /var/lib/symphony-trello/.codex
sudo install -o symphony-trello -g symphony-trello -m 0600 ~/.codex/auth.json /var/lib/symphony-trello/.codex/auth.json
```

Do not put Codex auth in workflow files or `/etc/symphony-trello/service.env`.

Authenticate GitHub for the service user when generated workflows should publish pull requests.
Either run `gh auth login` as the service user or copy an existing GitHub CLI hosts file:

```bash
sudo -u symphony-trello -H gh auth login
```

```bash
sudo install -d -o symphony-trello -g symphony-trello -m 0700 /var/lib/symphony-trello/.config/gh
sudo install -o symphony-trello -g symphony-trello -m 0600 ~/.config/gh/hosts.yml /var/lib/symphony-trello/.config/gh/hosts.yml
sudo -u symphony-trello -H git config --global credential.https://github.com.helper '!gh auth git-credential'
```

Do not put GitHub tokens in workflow files or `/etc/symphony-trello/service.env`.

## Build And Install The App

From your checkout:

```bash
./mvnw -q package
```

Copy the Quarkus runner directory to the server:

```bash
sudo rm -rf /opt/symphony-trello/app
sudo cp -a target/quarkus-app /opt/symphony-trello/app
sudo chown -R root:root /opt/symphony-trello/app
```

## Configure Secrets

Create root-only files for the Trello credentials:

```bash
sudo install -m 0600 -o root -g root /dev/null /etc/symphony-trello/secrets/trello-api-key
sudo install -m 0600 -o root -g root /dev/null /etc/symphony-trello/secrets/trello-api-token
sudoedit /etc/symphony-trello/secrets/trello-api-key
sudoedit /etc/symphony-trello/secrets/trello-api-token
sudo stat -c '%U %G %a %n' /etc/symphony-trello/secrets/trello-api-key /etc/symphony-trello/secrets/trello-api-token
```

Put only the raw API key into `trello-api-key` and only the raw token into `trello-api-token`. The
`stat` command should print `root root 600` for both files. systemd loads them as service
credentials and exposes them to Symphony as private files, not as normal environment variables.

If Java or Codex is not on systemd's normal service path, create
`/etc/symphony-trello/service.env` with a `PATH` line that includes their directories:

```bash
sudo install -m 0644 -o root -g root /dev/null /etc/symphony-trello/service.env
sudoedit /etc/symphony-trello/service.env
```

Do not put Trello credentials in `/etc/symphony-trello/service.env`. That file is only for non-secret
service environment settings such as `PATH`.

```dotenv
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/opt/java/bin:/opt/codex/bin
```

Do not put Trello credentials in shell commands, the systemd unit, or workflow files. Treat backups
and config-management copies of `/etc/symphony-trello/secrets` as secrets too.

In workflow files, reference the systemd credential files:

```yaml
tracker:
  api_key: file:$CREDENTIALS_DIRECTORY/trello-api-key
  api_token: file:$CREDENTIALS_DIRECTORY/trello-api-token
```

## Install The systemd Template

Copy the unit template:

```bash
sudo cp deploy/systemd/symphony-trello@.service /etc/systemd/system/symphony-trello@.service
sudo systemctl daemon-reload
```

The template runs:

```text
/usr/bin/env java -jar /opt/symphony-trello/app/quarkus-run.jar /etc/symphony-trello/workflows/%i.WORKFLOW.md
```

That means `symphony-trello@project-a` reads:

```text
/etc/symphony-trello/workflows/project-a.WORKFLOW.md
```

## Add Workflow Files

Put one workflow file per Trello board into `/etc/symphony-trello/workflows`.

Set a unique HTTP port in each workflow:

```yaml
---
server:
  port: 18081
tracker:
  kind: trello
  api_key: file:$CREDENTIALS_DIRECTORY/trello-api-key
  api_token: file:$CREDENTIALS_DIRECTORY/trello-api-token
  board_id: abc123
  active_states:
    - Ready for Codex
    - In Progress
  in_progress_state: In Progress
  terminal_states:
    - Done
workspace:
  root: /var/lib/symphony-trello/workspaces/project-a
trello_tools:
  enabled: true
  allow_writes: true
  allowed_move_list_names:
    - In Progress
    - Human Review
    - Blocked
  allow_comments: true
codex:
  command: codex app-server
---
```

Use a different `server.port` and `workspace.root` for each workflow. Keeping separate workspace
directories makes cleanup and debugging easier. Include a non-active blocked handoff list such as
`Blocked` when the board has one, so blocked cards do not stay eligible for another run.
The `allowed_move_list_names` key uses Trello's term for board lists.

## Allow Host Path Access

By default, the systemd unit lets Codex read and write only Symphony-managed paths such as
`/var/lib/symphony-trello`. This is safer for production because a Trello card cannot make Codex edit
unrelated host files.

If cards should work with an existing file or folder outside the managed workspace, explicitly allow
that host path. Create a drop-in:

```bash
sudo install -d -o root -g root -m 0755 /etc/systemd/system/symphony-trello@.service.d
sudoedit /etc/systemd/system/symphony-trello@.service.d/10-host-paths.conf
```

Use a concrete file or folder path:

```ini
[Service]
ProtectHome=false
TemporaryFileSystem=
TemporaryFileSystem=/home:ro /root:ro /run/user:ro
BindPaths=/srv/projects/example
ReadWritePaths=
ReadWritePaths=/var/lib/symphony-trello /srv/projects/example
```

Also add the same path to `/etc/symphony-trello/service.env` so Codex's own sandbox can write there:

```dotenv
SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS=/srv/projects/example
```

Then reload and restart the affected workflows:

```bash
sudo systemctl daemon-reload
sudo systemctl restart symphony-trello@project-a
```

The read-only temporary filesystems hide undeclared home-directory contents while allowing
`BindPaths` to make the declared path visible. The `ReadWritePaths` reset keeps Symphony's state
directory writable and adds the declared path as another writable location.

To allow more than one file or folder, repeat `BindPaths=` and add every path to `ReadWritePaths=`:

```ini
[Service]
ProtectHome=false
TemporaryFileSystem=
TemporaryFileSystem=/home:ro /root:ro /run/user:ro
BindPaths=/srv/projects/example-a
BindPaths=/srv/projects/example-b
ReadWritePaths=
ReadWritePaths=/var/lib/symphony-trello /srv/projects/example-a /srv/projects/example-b
```

Use `:` between paths in `/etc/symphony-trello/service.env`:

```dotenv
SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS=/srv/projects/example-a:/srv/projects/example-b
```

If you expose a parent directory and Codex reports a sandbox error for that parent, keep the systemd
drop-in narrow and relax only Codex's inner sandbox:

```dotenv
SYMPHONY_CODEX_DANGER_FULL_ACCESS=true
```

This is less strict than setting exact paths in `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`. The
systemd namespace still limits writable host paths to the paths in `ReadWritePaths=`, but Codex no
longer applies its own workspace-write root list inside that namespace.

A broader override is possible but less secure:

```ini
[Service]
ProtectHome=false
ReadWritePaths=
ReadWritePaths=/
```

Set this in `/etc/symphony-trello/service.env` with the broad systemd override:

```dotenv
SYMPHONY_CODEX_DANGER_FULL_ACCESS=true
```

Use the broad override only for a trusted single-user machine. It gives Codex sessions run by the
service much more host filesystem access than the default deployment.

## Start Workflows

Start one workflow:

```bash
sudo systemctl enable --now symphony-trello@project-a
```

Start another workflow:

```bash
sudo systemctl enable --now symphony-trello@project-b
```

Check status and logs:

```bash
sudo systemctl status symphony-trello@project-a
sudo journalctl -u symphony-trello@project-a -f
```

Check the HTTP state endpoint:

```bash
curl http://127.0.0.1:18081/api/v1/state
```

## Upgrade To A New Version

Build the new version from the checkout or release you want to deploy:

```bash
./mvnw -q package
```

Install the new package next to the old one:

```bash
sudo rm -rf /opt/symphony-trello/app.new
sudo cp -a target/quarkus-app /opt/symphony-trello/app.new
sudo chown -R root:root /opt/symphony-trello/app.new
```

List the running workflow services so you know what should come back:

```bash
sudo systemctl list-units 'symphony-trello@*'
```

Stop the workflows, swap the package directory, and start them again:

```bash
sudo systemctl stop 'symphony-trello@*'
sudo rm -rf /opt/symphony-trello/app.previous
sudo mv /opt/symphony-trello/app /opt/symphony-trello/app.previous
sudo mv /opt/symphony-trello/app.new /opt/symphony-trello/app
sudo systemctl start symphony-trello@project-a symphony-trello@project-b
```

Check each workflow:

```bash
sudo systemctl status symphony-trello@project-a
curl http://127.0.0.1:18081/api/v1/state
```

After the new version is working, remove the previous package:

```bash
sudo rm -rf /opt/symphony-trello/app.previous
```

If the new version fails before you remove `app.previous`, roll back:

```bash
sudo systemctl stop 'symphony-trello@*'
sudo rm -rf /opt/symphony-trello/app.failed
sudo mv /opt/symphony-trello/app /opt/symphony-trello/app.failed
sudo mv /opt/symphony-trello/app.previous /opt/symphony-trello/app
sudo systemctl start symphony-trello@project-a symphony-trello@project-b
```

## Remove Everything

List the installed workflow services:

```bash
sudo systemctl list-unit-files 'symphony-trello@*'
```

Stop and disable each workflow service:

```bash
sudo systemctl disable --now symphony-trello@project-a symphony-trello@project-b
```

Remove the systemd unit template:

```bash
sudo rm -f /etc/systemd/system/symphony-trello@.service
sudo systemctl daemon-reload
sudo systemctl reset-failed 'symphony-trello@*'
```

Remove the installed application:

```bash
sudo rm -rf /opt/symphony-trello
```

Remove configuration, workflow files, and Trello secrets:

```bash
sudo rm -rf /etc/symphony-trello
```

Remove workspaces and the service user's home directory:

```bash
sudo userdel symphony-trello
sudo rm -rf /var/lib/symphony-trello
```

If the Trello token was created only for this deployment, revoke it in Trello after the services are
removed.

Keep a copy of `/etc/symphony-trello` before deleting it when you want to reuse the workflow files or
credentials later.
