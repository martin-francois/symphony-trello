# Production Deployment

This guide shows how to run Symphony for Trello on a Linux server with systemd.

Use this when you have one or more `WORKFLOW.md` files and want Symphony to keep running after you
log out. Each workflow file maps to one Trello board, so each workflow runs as one systemd service
instance.

## How It Works

One installed application can run many workflows:

1. Build Symphony once.
2. Copy the packaged app to `/opt/symphony-trello/app`.
3. Store shared secrets in `/etc/symphony-trello/env`.
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

Install Java 25 LTS and the Codex CLI on the server. The systemd unit starts Java through
`/usr/bin/env java`, so make sure `java` resolves to Java 25 for the service:

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
sudo install -d -o root -g root -m 0755 /etc/symphony-trello/workflows
sudo install -d -o symphony-trello -g symphony-trello -m 0750 /var/lib/symphony-trello
```

Authenticate Codex for the service user before starting real workflows. The exact command depends on
how your Codex CLI is installed, but run it as `symphony-trello` so credentials land under that
user's home directory:

```bash
sudo -u symphony-trello -H codex --help
```

## Build And Install The App

From your checkout:

```bash
./mvnw -q spotless:check verify
./mvnw -q package
```

Copy the Quarkus runner directory to the server:

```bash
sudo rm -rf /opt/symphony-trello/app
sudo cp -a target/quarkus-app /opt/symphony-trello/app
sudo chown -R root:root /opt/symphony-trello/app
```

## Configure Secrets

Create `/etc/symphony-trello/env`:

```bash
sudo install -m 0600 -o root -g root /dev/null /etc/symphony-trello/env
sudoedit /etc/symphony-trello/env
```

Use your real Trello credentials:

```dotenv
TRELLO_API_KEY=replace-with-your-key
TRELLO_API_TOKEN=replace-with-your-token
```

If Java or Codex is not on systemd's normal service path, add a `PATH` line that includes their
directories:

```dotenv
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/opt/java/bin:/opt/codex/bin
```

Do not put secrets in workflow files. Reference them from workflow config with `$TRELLO_API_KEY` and
`$TRELLO_API_TOKEN`.

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
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: abc123
  active_states:
    - Ready for Codex
  terminal_states:
    - Done
workspace:
  root: /var/lib/symphony-trello/workspaces/project-a
codex:
  command: codex app-server
---
```

Use a different `server.port` and `workspace.root` for each workflow. Keeping separate workspace
directories makes cleanup and debugging easier.

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
./mvnw -q spotless:check verify
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
