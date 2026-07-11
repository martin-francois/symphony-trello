# Live Trello E2E Verification

This runbook verifies Symphony against real Trello boards without committing secrets or disposable
board ids.

Run this in two phases. First use real Trello with the deterministic Java app-server test double in
place of real Codex. That catches scheduler, Trello, workflow import, handoff tool, and concurrency
issues without model variability. Then run the real-Codex phase that matches the claim you need
before claiming real `codex app-server` coverage.

## Inputs

1. Create `.env` from `.env.example`.
2. Put `TRELLO_API_KEY` and `TRELLO_API_TOKEN` in `.env`.
3. Use a Trello account where disposable boards can be created and archived.

The live commands read `.env` automatically. Real environment variables with the same names take
precedence.

## Automated Fake-Codex Harness

Use the Java harness first when you need to verify real Trello setup, card ordering, imports,
handoff moves, comments, and multi-board concurrency. It creates disposable Trello boards, runs the
packaged Symphony service on local ports, uses the deterministic Java fake Codex app-server, and
archives the created boards during cleanup.

```bash
SYMPHONY_RUN_LIVE_E2E=1 ./mvnw -q -Dit.test=LiveTrelloE2eIT verify
```

If the Trello token can access more than one Workspace, choose the Workspace for disposable test
boards:

```bash
SYMPHONY_RUN_LIVE_E2E=1 \
SYMPHONY_LIVE_E2E_TRELLO_WORKSPACE_ID=replace-with-workspace-id \
./mvnw -q -Dit.test=LiveTrelloE2eIT verify
```

The harness covers:

1. `.env` / environment credential loading.
2. Recommended-board creation.
3. Generated-board import.
4. Import of a non-default existing board structure.
5. `max_concurrent_agents: 1` ordering, including a later-created card moved above an older card.
6. `max_concurrent_agents: 2` on one board.
7. Two Symphony processes watching two boards at the same time.
8. Fake app-server Trello comments and handoff moves.
9. A long-running external-project card with Docker fixture files, proving Trello shows
   `In Progress` while work is underway and `Human Review` after handoff.
10. Final `/api/v1/state` drain to zero running and retrying entries.

The fake-Codex external-project case creates a disposable directory under `target/live-e2e-it/`
outside the Symphony workspace root. The directory contains a minimal Dockerfile with a 30-second
build step. The fake app-server sleeps for 30 seconds instead of running Docker, which keeps this
test deterministic while still reproducing the board lifecycle that exposed the original problem.

## Optional Real Codex Docker Harness

Use this after the fake-Codex harness when you need proof that real Codex, real Trello, and Docker
work together. It is opt-in because it uses model time, Docker daemon access, and an image pull.

```bash
SYMPHONY_RUN_REAL_CODEX_DOCKER_E2E=1 ./mvnw -q -Dit.test=LiveTrelloE2eIT verify
```

If the Trello token can access more than one Workspace:

```bash
SYMPHONY_RUN_REAL_CODEX_DOCKER_E2E=1 \
SYMPHONY_LIVE_E2E_TRELLO_WORKSPACE_ID=replace-with-workspace-id \
./mvnw -q -Dit.test=LiveTrelloE2eIT verify
```

The real-Codex Docker test creates a disposable Trello board and a disposable external project under
`target/live-e2e-it/`. The card asks Codex to build a small Docker image whose Dockerfile contains
`RUN sleep 30`, run the image, write `docker-output.txt`, verify the file, comment on the Trello
card, and move the card to `Human Review`. The test starts Symphony with
`SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` because Docker daemon access usually fails inside Codex's
workspace-write sandbox. The test verifies that the card first appears in `In Progress`, then
reaches `Human Review`, and that the output file contains the run id.

Use the manual sections below when you need a step-by-step reproduction, real-Codex coverage,
deployment troubleshooting, or a scenario the Java harness does not automate yet.

## Optional Repository-Need Classification Matrix

Use this opt-in procedure to verify repository-source classification with real Codex, real Trello,
and real GitHub. The deterministic tests verify the generated and runtime prompt contract; they do
not prove how a model follows that contract.

Before starting, obtain explicit authority to create and remove two run-scoped private GitHub sandbox
repositories, their issues, and one disposable Trello board. Use a GitHub CLI login that can comment
on the sandbox issues and remove the repositories during cleanup. Configure the workflow to use the
build under test and real `codex app-server`.

1. Create a unique run id, a disposable Trello board with the generated active/review/blocked lists,
   two private sandbox repositories, and one issue in each repository.
2. Keep one repository as `<workflow-default-repository>` and the other as
   `<direct-target-repository>`.
3. For each table row, update the workflow default as shown, restart or reload the worker, create a
   fresh Trello card with the task text, and move it to `Ready for Codex`.
4. After every non-blocked row, verify the card reaches `Human Review`, `/api/v1/state` drains, and no
   new blocker comment appears. For API-only rows, inspect the per-card workspace and verify Codex did
   not create a Git checkout or repository clone; workspace-local Symphony skill files may exist.
5. After every blocked row, verify the card reaches `Blocked`, the workpad gives the expected safe
   reason, and no GitHub issue or repository was changed.

| Card task | Workflow default | Required live result |
| --- | --- | --- |
| Perform a repository-independent Trello-only status action with no repository-relative reference. | None | Run and hand off without a checkout. |
| Add a status note to the full issue URL in `<direct-target-repository>` and do not change files. | None | Comment on that exact issue, hand off, and create no checkout. |
| Add a status note to the full pull-request URL in `<direct-target-repository>` and do not change files. | None | Comment on that exact pull request, hand off, and create no checkout. |
| Add a status note to the full issue URL in `<direct-target-repository>` and do not change files. | Malformed `<workflow-default-repository>` URL | Ignore the malformed fallback, comment on that exact issue, hand off, and create no checkout. |
| Add a status note to the full pull-request URL in `<direct-target-repository>` and do not change files. | Malformed `<workflow-default-repository>` URL | Ignore the malformed fallback, comment on that exact pull request, hand off, and create no checkout. |
| Add a status note to a bare `#<issue-number>` reference. | None | Move to `Blocked` because repository identity is ambiguous; do not comment on either issue. |
| Add a status note to a bare `#<issue-number>` reference. | Valid `<workflow-default-repository>` clone URL | Resolve the issue in the default repository, comment, hand off, and create no checkout. |
| Add a status note to a bare `#<issue-number>` reference. | Malformed workflow URL plus a valid lower-priority path | Move to `Blocked`; the path must not replace the invalid selected URL or establish identity. |
| Change a repository file in the repository identified by a full repository, issue, pull-request, or file URL and prepare the normal review artifact. | None | Resolve that repository, prepare its checkout, change the file, and hand off. |
| Change a repository file in the repository clearly identified as `owner/repository` and prepare the normal review artifact. | None | Resolve that repository, prepare its checkout, change the file, and hand off. |
| Change a repository file using each identity form above or `owner/repository`. | Malformed `<workflow-default-repository>` URL | Ignore the malformed fallback, select the single card repository, prepare a checkout because files are needed, and hand off. |
| Change a repository file while naming two conflicting repository identities. | Any malformed fallback | Move to `Blocked`; do not choose one card identity or use the fallback. |
| Change a repository file without identifying any repository. | None | Move to `Blocked` because repository identity is ambiguous. |
| Perform the repository-independent status action. | A `repository.default_url` value that remains invalid after compatibility normalization, or a valid-looking source that a read-only probe proves unavailable, unreadable, or uncheckoutable | Move to `Blocked`; do not treat the broken selected source as absent. |
| Perform the repository-independent status action. | Valid `<workflow-default-repository>` clone URL | Run and hand off; validate the selected source read-only and create no checkout. |

For each full-URL row, keep the workflow default absent. In a second targeted check, configure the
other repository as the valid default and repeat the direct-target task; the card's repository must
still win and must not be replaced by the default repository identity.

For checkout-candidate coverage, configure remote repository A together with path P, then select A
from an explicit card remote and repeat with a different workflow URL B. In both cases A remains the
identity, P appears only as a candidate, and P is used before general discovery only after read-only
inspection proves its Git remotes match A. The test must not infer that P belongs to A merely because
P and a workflow URL were configured together. Repeat with an explicitly selected local path L and
verify P is not emitted as a second candidate.

For a local-source identity check, configure the default as a local path and then as the equivalent
`file://` URL. A bare issue reference may run only when read-only inspection finds exactly one
explicit, unambiguous compatible remote. Remove that remote or make the compatible remote selection
ambiguous and repeat the card; it must move to `Blocked` and request a fully qualified repository URL
together with the issue or pull request number without deriving identity from the path, directory
name, or branch.

Archive the disposable Trello board and remove both private sandbox repositories after collecting the
sanitized result. Do not reuse production cards, boards, issues, or repositories for this procedure.

A targeted real-Trello/real-Codex row was executed for
[GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545) using one disposable
board and an unmodified legacy generated workflow with no repository default. Before deployment, a
local-only repository-changing card that identified a public repository with an ordinary full URL
moved to `Blocked`. After deployment, a fresh equivalent card reached `Human Review`, state drained,
the expected file existed in the per-card checkout, and no blocker comment appeared. The board was
archived after verification; no GitHub repository, issue, pull request, or branch was mutated. The
broader multi-repository matrix remains opt-in and requires its full external-resource authority.

## What To Verify

Use a unique run id such as `live-e2e-YYYYMMDD-HHMMSS`.

1. `list-workspaces` reads credentials from `.env` and returns accessible Workspaces.
2. `new-board` creates a disposable board and writes a workflow.
3. `import-board` reads both a Symphony-generated board and a custom existing-board structure, then
   writes workflows for them.
4. With `max_concurrent_agents: 1`, two `Ready for Codex` cards run one at a time in Trello position
   order. This includes a later-created card that was moved above the older card.
5. With `max_concurrent_agents: 2`, two cards on one board run at the same time while a third waits.
6. The app-server calls `trello_upsert_workpad`, `trello_add_comment`, and
   `trello_move_current_card`.
7. Processed cards have one workpad comment, a handoff comment, and end in `Human Review` on
   recommended boards.
8. Two Symphony processes can run against two boards at the same time on different ports.
9. Cleanup archives all disposable boards created by the run.

## Reproducible Command Flow

Run these commands from the repository root. They intentionally keep every generated workflow,
card response, and status response under `target/$RUN_ID/`, which is ignored by Git.

```bash
RUN_ID="live-e2e-$(date -u +%Y%m%d-%H%M%S)"
RUN_DIR="target/$RUN_ID"
mkdir -p "$RUN_DIR"
printf '%s\n' "$RUN_ID" > target/live-e2e-current-run-id
```

Build the packaged runner used by the service-start commands later in this runbook:

```bash
./mvnw -q package
```

Verify `.env` loading and Workspace discovery:

```bash
./mvnw -q exec:java -Dexec.args='list-workspaces'
```

Create two disposable boards. If the token can see exactly one Workspace, omit `--workspace-id`;
that path must create the board in the only accessible Workspace.

```bash
./mvnw -q exec:java \
  -Dexec.args="new-board --name 'Symphony $RUN_ID Board A' --workflow $RUN_DIR/board-a.WORKFLOW.md"

./mvnw -q exec:java \
  -Dexec.args="new-board --name 'Symphony $RUN_ID Board B' --workflow $RUN_DIR/board-b.WORKFLOW.md"
```

Import Board A back into a separate workflow:

```bash
BOARD_A_ID="$(awk -F'"' '/board_id:/ {print $2; exit}' "$RUN_DIR/board-a.WORKFLOW.md")"

./mvnw -q exec:java \
  -Dexec.args="import-board --board $BOARD_A_ID --active 'Ready for Codex' --terminal Done --workflow $RUN_DIR/imported-a.WORKFLOW.md"
```

Create and import a disposable board that represents a non-default existing team workflow. This
proves `import-board` is not only compatible with Symphony-generated boards.

```bash
if [ -z "${TRELLO_API_KEY:-}" ] || [ -z "${TRELLO_API_TOKEN:-}" ]; then
  set -a
  . ./.env
  set +a
fi

WORKSPACE_ID="$(./mvnw -q exec:java -Dexec.args='list-workspaces' \
  | grep -Eo '[0-9a-f]{24}' \
  | head -1)"

curl -fsS -X POST "https://api.trello.com/1/boards" \
  --data-urlencode "key=$TRELLO_API_KEY" \
  --data-urlencode "token=$TRELLO_API_TOKEN" \
  --data-urlencode "name=Symphony $RUN_ID Custom Existing Board" \
  --data-urlencode "defaultLists=false" \
  --data-urlencode "defaultLabels=false" \
  --data-urlencode "idOrganization=$WORKSPACE_ID" \
  > "$RUN_DIR/custom-board.json"

CUSTOM_BOARD_ID="$(jq -r .id "$RUN_DIR/custom-board.json")"

for list_name in "Intake" "Queue for Codex" "Escalated for Codex" "Review" "Blocked Work" "Released" "Parked"; do
  safe_name="$(printf '%s' "$list_name" | tr '[:upper:] ' '[:lower:]-')"
  curl -fsS -X POST "https://api.trello.com/1/lists" \
    --data-urlencode "key=$TRELLO_API_KEY" \
    --data-urlencode "token=$TRELLO_API_TOKEN" \
    --data-urlencode "idBoard=$CUSTOM_BOARD_ID" \
    --data-urlencode "name=$list_name" \
    --data-urlencode "pos=bottom" \
    > "$RUN_DIR/list-$safe_name.json"
done

./mvnw -q exec:java \
  -Dexec.args="import-board --board $CUSTOM_BOARD_ID --active 'Queue for Codex' --active 'Escalated for Codex' --terminal Released --terminal Parked --blocked 'Blocked Work' --max-agents 2 --workflow $RUN_DIR/custom-import.WORKFLOW.md"

./mvnw -q exec:java \
  -Dexec.args="import-board --board $CUSTOM_BOARD_ID --active 'Queue for Codex' --active 'Escalated for Codex' --terminal Released --terminal Parked --blocked 'Blocked Work' --max-agents 2 --workflow $RUN_DIR/custom-import-real.WORKFLOW.md"
```

The Trello REST API path is `/lists` because Trello calls board lanes lists in API fields and
endpoints.

Patch the generated workflows to use the deterministic Java app-server and different ports. Board A
uses one worker and a delay so `/api/v1/state` can prove that one card runs while another waits.
Board B uses two workers and a delay so `/api/v1/state` can prove that two cards run at once while
the third waits for a refresh.

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"
FAKE_JAVA="$(command -v java)"
FAKE_CODEX="$(pwd)/scripts/FakeCodexAppServer.java"

$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/board-a.WORKFLOW.md" 1 7000 "$FAKE_JAVA" "$FAKE_CODEX" "Human Review"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/imported-a.WORKFLOW.md" 1 250 "$FAKE_JAVA" "$FAKE_CODEX" "Human Review"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/board-b.WORKFLOW.md" 2 7000 "$FAKE_JAVA" "$FAKE_CODEX" "Human Review"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/custom-import.WORKFLOW.md" 2 7000 "$FAKE_JAVA" "$FAKE_CODEX" "Review"
```

Create disposable cards in the active lists. Use Trello's REST API directly so the live test is
independent of manual board interaction.

```bash
if [ -z "${TRELLO_API_KEY:-}" ] || [ -z "${TRELLO_API_TOKEN:-}" ]; then
  set -a
  . ./.env
  set +a
fi

for board in a b; do
  board_id="$(awk -F'"' '/board_id:/ {print $2; exit}' "$RUN_DIR/board-$board.WORKFLOW.md")"
  curl -fsS "https://api.trello.com/1/boards/$board_id/lists?fields=name,closed&key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN" \
    > "$RUN_DIR/lists-$board.json"
  jq -r '.[] | select(.name == "Ready for Codex") | .id' "$RUN_DIR/lists-$board.json" > "$RUN_DIR/ready-list-$board.txt"
  jq -r '.[] | select(.name == "Human Review") | .id' "$RUN_DIR/lists-$board.json" > "$RUN_DIR/review-list-$board.txt"
done

curl -fsS -X POST "https://api.trello.com/1/cards" \
  --data-urlencode "key=$TRELLO_API_KEY" \
  --data-urlencode "token=$TRELLO_API_TOKEN" \
  --data-urlencode "idList=$(cat "$RUN_DIR/ready-list-a.txt")" \
  --data-urlencode "name=Symphony $RUN_ID sequential older card" \
  --data-urlencode "desc=Disposable live E2E card that should run second after Trello reordering." \
  > "$RUN_DIR/card-a-1.json"

curl -fsS -X POST "https://api.trello.com/1/cards" \
  --data-urlencode "key=$TRELLO_API_KEY" \
  --data-urlencode "token=$TRELLO_API_TOKEN" \
  --data-urlencode "idList=$(cat "$RUN_DIR/ready-list-a.txt")" \
  --data-urlencode "name=Symphony $RUN_ID sequential later card moved first" \
  --data-urlencode "desc=Disposable live E2E card created later, then moved to the top of Ready for Codex." \
  > "$RUN_DIR/card-a-2.json"

CARD_A_2_ID="$(jq -r .id "$RUN_DIR/card-a-2.json")"
curl -fsS -X PUT "https://api.trello.com/1/cards/$CARD_A_2_ID" \
  --data-urlencode "key=$TRELLO_API_KEY" \
  --data-urlencode "token=$TRELLO_API_TOKEN" \
  --data-urlencode "pos=top" \
  > "$RUN_DIR/card-a-2-reordered.json"

for n in 1 2 3; do
  curl -fsS -X POST "https://api.trello.com/1/cards" \
    --data-urlencode "key=$TRELLO_API_KEY" \
    --data-urlencode "token=$TRELLO_API_TOKEN" \
    --data-urlencode "idList=$(cat "$RUN_DIR/ready-list-b.txt")" \
    --data-urlencode "name=Symphony $RUN_ID concurrent handoff $n" \
    --data-urlencode "desc=Disposable live E2E concurrency card." \
    > "$RUN_DIR/card-b-$n.json"
done
```

Start Board A and Board B in separate terminals. Keep both running at the same time.

Board A proves `max_concurrent_agents: 1` and Trello position ordering. The later-created Board A
card was moved to the top of `Ready for Codex`, so it should run first while the older card waits.

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

java -jar target/quarkus-app/quarkus-run.jar "$RUN_DIR/board-a.WORKFLOW.md" --port 18181
```

Board B proves `max_concurrent_agents: 2` on a second board at the same time. Two cards should run
while the third waits.

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

java -jar target/quarkus-app/quarkus-run.jar "$RUN_DIR/board-b.WORKFLOW.md" --port 18182
```

Verify both boards from a third shell while the fake app-server is sleeping:

```bash
if [ -z "${TRELLO_API_KEY:-}" ] || [ -z "${TRELLO_API_TOKEN:-}" ]; then
  set -a
  . ./.env
  set +a
fi

RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"
CARD_A_1_ID="$(jq -r .id "$RUN_DIR/card-a-1.json")"
CARD_A_2_ID="$(jq -r .id "$RUN_DIR/card-a-2.json")"
CARD_B_3_ID="$(jq -r .id "$RUN_DIR/card-b-3.json")"

curl -fsS http://127.0.0.1:18181/api/v1/state \
  | jq '{counts, running: [.running[].card_id], retrying: [.retrying[].card_id]}'

curl -fsS http://127.0.0.1:18182/api/v1/state \
  | jq '{counts, running: [.running[].card_id], retrying: [.retrying[].card_id]}'

curl -fsS "https://api.trello.com/1/cards/$CARD_A_1_ID?fields=idList&key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN" \
  > "$RUN_DIR/status-card-a-1-waiting.json"

curl -fsS "https://api.trello.com/1/cards/$CARD_B_3_ID?fields=idList&key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN" \
  > "$RUN_DIR/status-card-b-3-waiting.json"

jq -e --arg ready "$(cat "$RUN_DIR/ready-list-a.txt")" '.idList == $ready' "$RUN_DIR/status-card-a-1-waiting.json"
jq -e --arg ready "$(cat "$RUN_DIR/ready-list-b.txt")" '.idList == $ready' "$RUN_DIR/status-card-b-3-waiting.json"
```

Expected Board A result while the fake app-server is sleeping: `counts.running` is `1`, the running
card ID is `CARD_A_2_ID`, `counts.retrying` is `0`, and `card-a-1` is still in `Ready for Codex`.
Expected Board B result while the fake app-server is sleeping: `counts.running` is `2`,
`counts.retrying` is `0`, and the third Board B card is still in `Ready for Codex`.

After the active cards move to Human Review, request refreshes and verify the waiting cards move too:

```bash
curl -fsS -X POST http://127.0.0.1:18181/api/v1/refresh | jq .
curl -fsS -X POST http://127.0.0.1:18182/api/v1/refresh | jq .
```

Create a fresh Board A card for the imported workflow, because the first Board A card should already
be in `Human Review`:

```bash
if [ -z "${TRELLO_API_KEY:-}" ] || [ -z "${TRELLO_API_TOKEN:-}" ]; then
  set -a
  . ./.env
  set +a
fi

RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

curl -fsS -X POST "https://api.trello.com/1/cards" \
  --data-urlencode "key=$TRELLO_API_KEY" \
  --data-urlencode "token=$TRELLO_API_TOKEN" \
  --data-urlencode "idList=$(cat "$RUN_DIR/ready-list-a.txt")" \
  --data-urlencode "name=Symphony $RUN_ID imported workflow handoff" \
  --data-urlencode "desc=Disposable live E2E card for the imported workflow." \
  > "$RUN_DIR/card-a-imported.json"
```

Run the same single-card handoff with the imported workflow:

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

java -jar target/quarkus-app/quarkus-run.jar "$RUN_DIR/imported-a.WORKFLOW.md" --port 18183
```

For the custom imported board, create one card in each configured active list and run that workflow
on a separate port. The expected concurrency state is the same as Board B: `counts.running == 2`,
then both cards end in `Review` with at least one comment and state drains to zero.

```bash
jq -r .id "$RUN_DIR/list-queue-for-codex.json" > "$RUN_DIR/custom-queue-list.txt"
jq -r .id "$RUN_DIR/list-escalated-for-codex.json" > "$RUN_DIR/custom-escalated-list.txt"
jq -r .id "$RUN_DIR/list-review.json" > "$RUN_DIR/custom-review-list.txt"

for spec in "queue:$(cat "$RUN_DIR/custom-queue-list.txt")" "escalated:$(cat "$RUN_DIR/custom-escalated-list.txt")"; do
  key="${spec%%:*}"
  list_id="${spec#*:}"
  curl -fsS -X POST "https://api.trello.com/1/cards" \
    --data-urlencode "key=$TRELLO_API_KEY" \
    --data-urlencode "token=$TRELLO_API_TOKEN" \
    --data-urlencode "idList=$list_id" \
    --data-urlencode "name=Symphony $RUN_ID custom import $key handoff" \
    --data-urlencode "desc=Disposable live E2E card for custom existing-board import." \
    > "$RUN_DIR/card-custom-$key.json"
done

java -jar target/quarkus-app/quarkus-run.jar "$RUN_DIR/custom-import.WORKFLOW.md" --port 18184
```

Check any card's final Trello state with:

```bash
card_id="$(jq -r .id "$RUN_DIR/card-a-1.json")"
curl -fsS "https://api.trello.com/1/cards/$card_id?fields=name,idList&actions=commentCard&key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN" \
  > "$RUN_DIR/status-$card_id.json"
jq -r '[.id, .name, .idList, (.actions | length)] | @tsv' "$RUN_DIR/status-$card_id.json"
```

The final `idList` must match the Trello list id for the board's review handoff list, and the
comment count must be at least `1`.

## Strict Real-Codex Phase

After the deterministic phase passes, create fresh disposable real-Codex boards. Do not reuse the
deterministic workflows here because those were patched to run the fake app-server.

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

./mvnw -q exec:java \
  -Dexec.args="new-board --name 'Symphony $RUN_ID Real Codex Board A' --workflow $RUN_DIR/real-board-a.WORKFLOW.md"

REAL_BOARD_A_ID="$(awk -F'"' '/board_id:/ {print $2; exit}' "$RUN_DIR/real-board-a.WORKFLOW.md")"

./mvnw -q exec:java \
  -Dexec.args="import-board --board $REAL_BOARD_A_ID --active 'Ready for Codex' --terminal Done --workflow $RUN_DIR/real-imported-a.WORKFLOW.md"

./mvnw -q exec:java \
  -Dexec.args="new-board --name 'Symphony $RUN_ID Real Codex Board B' --max-agents 2 --workflow $RUN_DIR/real-board-b.WORKFLOW.md"
```

Create strict real-Codex workflows from those fresh generated workflows. These keep the same real
Trello board configuration and `codex.command: codex app-server`, but replace the normal engineering
prompt with a handoff-only E2E prompt. The normal generated prompt is intentionally broader and can
make Codex inspect or verify code instead of finishing a small protocol check quickly.

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"
REAL_JAVA="$(command -v java)"

$REAL_JAVA --source 25 scripts/WriteNarrowRealCodexWorkflow.java "$RUN_DIR/real-board-a.WORKFLOW.md" "$RUN_DIR/real-narrow-a.WORKFLOW.md" "real Codex generated handoff" "$RUN_ID"
$REAL_JAVA --source 25 scripts/WriteNarrowRealCodexWorkflow.java "$RUN_DIR/real-imported-a.WORKFLOW.md" "$RUN_DIR/real-narrow-imported-a.WORKFLOW.md" "real Codex imported handoff" "$RUN_ID"
$REAL_JAVA --source 25 scripts/WriteNarrowRealCodexWorkflow.java "$RUN_DIR/real-board-b.WORKFLOW.md" "$RUN_DIR/real-narrow-b.WORKFLOW.md" "real Codex concurrent handoff" "$RUN_ID"
$REAL_JAVA --source 25 scripts/WriteNarrowRealCodexWorkflow.java "$RUN_DIR/custom-import-real.WORKFLOW.md" "$RUN_DIR/real-narrow-custom-import.WORKFLOW.md" "real Codex custom import handoff" "$RUN_ID"
```

Create fresh cards in `Ready for Codex` for each strict workflow. Start the strict workflows on
separate unused ports the same way as the deterministic phase, but do not stop the service at the
first Trello move. Wait until both conditions are true:

1. Each target card has at least one Trello comment and its `idList` matches the board's Human Review
   list.
2. `/api/v1/state` reports `counts.running == 0` and `counts.retrying == 0`.

For the concurrency workflow, first verify `/api/v1/state` shows `counts.running == 2` while both
real Codex workers are active, then wait for both cards to satisfy the final handoff and zero-state
conditions.

Repeat the strict real-Codex concurrency check for the custom existing-board import workflow with one
fresh card in `Queue for Codex` and one fresh card in `Escalated for Codex`. This is the live proof
that imported boards with non-default active and terminal lists still dispatch, expose the Trello
handoff tools, and move cards to the board-local `Review` list.

You may also run an exploratory smoke with an unmodified generated workflow. Record it separately
from the strict real-Codex phase because the generated prompt represents real engineering work and is
not a deterministic handoff-only protocol check.

## Live Deployment Troubleshooting Loop

Use this loop when a real deployed workflow is already running and you need to prove whether the
deployment is healthy. Checking only the managed process status or `/api/v1/state` is not enough:
Codex can add a Trello blocker comment while the service still looks active.

Set `CARD_ID` to the card currently being processed and `EXPECTED_LIST` to the handoff list for
that workflow, usually `Human Review` on recommended boards.

```bash
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

test -n "${TRELLO_API_KEY:-}" && test -n "${TRELLO_API_TOKEN:-}" || {
  echo "Set TRELLO_API_KEY and TRELLO_API_TOKEN before running this check." >&2
  exit 2
}

CARD_ID="replace-with-card-id"
EXPECTED_LIST="Human Review"
STATE_URL="http://127.0.0.1:18080/api/v1/state"
CUTOFF="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

for attempt in {1..60}; do
  state="$(curl -fsS "$STATE_URL")"
  card="$(curl -fsS "https://api.trello.com/1/cards/$CARD_ID?fields=name,idList,url&actions=commentCard&actions_limit=10&key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN")"
  list_id="$(printf '%s' "$card" | jq -r '.idList')"
  list_name="$(curl -fsS "https://api.trello.com/1/lists/$list_id?fields=name&key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN" | jq -r '.name')"
  new_comments="$(printf '%s' "$card" | jq --arg cutoff "$CUTOFF" '[.actions[]? | select(.date >= $cutoff) | {date, text: .data.text}]')"

  jq -n \
    --argjson state "$state" \
    --argjson card "$card" \
    --arg list "$list_name" \
    --argjson newComments "$new_comments" \
    '{
      card: {name: $card.name, url: $card.url, list: $list},
      counts: $state.counts,
      runningLastEvent: ($state.running[0].last_event // null),
      runningLastMessage: ($state.running[0].last_message // null),
      retrying: $state.retrying,
      newComments: $newComments
    }' | tee target/live-deployment-check.json

  if printf '%s' "$new_comments" | rg -q 'Blocked:|bwrap:|Unauthorized|Address family not supported|overflowuid'; then
    echo "Live deployment failed: Codex added a new blocker/auth/sandbox comment after $CUTOFF." >&2
    exit 2
  fi

  if [ "$list_name" = "$EXPECTED_LIST" ] \
      && printf '%s' "$state" | jq -e '.counts.running == 0 and .counts.retrying == 0' >/dev/null; then
    echo "Live deployment passed: card moved to $EXPECTED_LIST and state is drained."
    exit 0
  fi

  sleep 10
done

echo "Live deployment did not finish before the timeout." >&2
exit 1
```

Use a fresh `CUTOFF` after every deployment restart or host-access setting change. That prevents old
blocker comments from failing a fixed run, while still catching new comments created after the current
verification started.

The deployment is healthy only when all of these are true:

1. The service is active.
2. `/api/v1/state` reaches `counts.running == 0` and `counts.retrying == 0`.
3. The card moved out of the active list into the expected handoff list.
4. The latest new Trello comments are successful handoff notes, not blocker/auth/sandbox comments.

### Regression Scenario: Deployed Project Root Access

Use this when changing deployed host-path access.

Use disposable external projects for this scenario. Do not reuse a private checkout. A suitable
fixture is a temporary directory outside the Symphony workspace root with this Dockerfile:

```dockerfile
FROM alpine:3.22
RUN sleep 30
COPY expected.txt /expected.txt
CMD ["cat", "/expected.txt"]
```

Put a unique run id in `expected.txt`. The 30-second build step gives enough time to observe that
Trello moves the card from `Ready for Codex` to `In Progress` before the final handoff.

1. Deploy with no extra allowed host paths.
2. Create a Trello card that asks Codex to inspect a disposable host path outside the managed
   workspace root.
3. Verify the card moves to the blocked handoff list and the new Trello comment explains that the
   requested path is inaccessible because undeclared host paths are blocked by default, avoids
   absolute host paths and per-card workspace locations, and says deployment access can be relaxed with
   allowed host paths.
4. Deploy again with that disposable path configured through setup or workflow host-path access, such
   as `--add-path`, `codex.additional_writable_roots`, or
   `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`.
5. Create a fresh card that asks Codex to read and write a harmless marker file in the allowed path.
6. Verify the card moves to the review handoff list, the marker file changed as requested, and
   `/api/v1/state` drains to zero running and retrying entries.
7. If the allowed path is a parent directory and Codex reports a sandbox error for the parent, rerun
   with `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` only for a trusted disposable workflow.
8. Deploy again with a different allowed path and create a card for the previous path. It should
   block again, proving the allowlist did not become broad host access.

For the successful path, the card should ask Codex to build the Docker image from the disposable
project, run it, write the output to a file in the same disposable project, verify the output, then
move the Trello card to `Human Review`. While Docker is running, the card should already be visible
in `In Progress`.

### Regression Scenario: Repository URL Or Read-Only Host Checkout

Use this when changing generated workflow repository checkout instructions or deployed host-path
access.
Use a disposable repository, not a private project checkout.

1. Prepare a readable local repository checkout outside the Symphony workspace root and make it
   read-only for the service user.
2. Deploy with the parent directory configured as an allowed host path.
3. Create one Trello card whose title names only the repository URL and asks for a small committed
   code or documentation change.
4. Create another Trello card whose title names the read-only local checkout path and asks for the
   same kind of change.
5. Verify Codex does not edit the shared checkout directly. It should clone from the readable local
   checkout or repository URL into the per-card workspace, make the change there, commit when the
   card asks for commits, add handoff evidence, and move the card to `Human Review`.
6. Verify the shared checkout remains unchanged, `/api/v1/state` drains to zero running and retrying
   entries, and the latest Trello comments are handoff comments rather than filesystem blockers.

### Regression Scenario: PR Handoff With Unavailable Or Stale CI

Use this when changing generated workflow PR feedback instructions, review-sweep behavior, handoff
skills, or live deployment auth. Use a disposable repository or a private test repository whose PRs
can be left open temporarily.

The problem this protects against is moving cards to `Blocked` only because CI did not provide a
useful signal, or trying to reproduce clearly unrelated CI failures locally. Symphony should keep
the card in `In Progress` while related failures are being fixed, but it should still hand off when
CI is unavailable or clearly unrelated and the appropriate local or card-specific evidence is clean.

1. Create a fresh Trello card for repository-changing work that must create a pull request.
2. Let the deployed workflow move the card from `Ready for Codex` to `In Progress`.
3. For a related CI failure, make the PR fail a check that is tied to the card's change. Verify
   Codex reruns that check or the closest local equivalent. If it fails locally, the card must remain
   in `In Progress` while Codex fixes and pushes again. If it passes locally and the CI result looks
   flaky after a reasonable refresh or rerun, Codex may move the card to `Human Review` with a clear
   flaky-check caveat.
4. For unavailable CI, use a repository workflow that skips CI for PRs unless a command is posted, or
   otherwise produces no usable CI result. Verify Codex runs equivalent local CI checks before
   handoff. The card may move to `Human Review` only when those local checks pass or only have
   failures clearly unrelated to the card.
5. For clearly unrelated CI failure, make an independent check fail for a reason unrelated to the
   card, such as a fixture-only job or a known external service failure. Verify Codex does not spend
   time reproducing that unrelated failure locally. It should explain why the failure is unrelated
   and move to `Human Review` when card-specific validation and related checks are clean.
6. In every case, verify the Trello workpad and handoff comment record the PR URL, the check state,
   local commands used when local validation was required, and any unavailable, flaky, or unrelated
   check caveat.
7. Verify `/api/v1/state` reaches zero running and retrying entries after the handoff.

### Regression Scenario: Existing PR With Wrong Commit Author

Use this when changing generated workflow PR publication instructions, commit author policy, shipped
Codex skills, or deployed workspace skill installation.

The problem this protects against is a card reaching `Human Review` because the new commit has the
right author while an existing commit in the same PR still uses a generic Codex author. The final
assertion must inspect every commit on the resulting PR through GitHub, not only the latest local
commit.

1. Create a temporary recommended Trello board with the fast path command and deploy that workflow
   with the managed local worker.
2. In a disposable repository or private test repository, create a temporary non-default branch with
   one harmless commit authored as `Codex <codex@openai.com>`, push it, and open a temporary PR.
3. Create a Trello card in `Ready for Codex` that asks Codex to continue that existing PR and make
   one tiny documentation-only commit.
4. Before the fix, this reproduced the bug: Codex created a new correctly-authored commit, pushed
   the branch, moved the card to `Human Review`, and the PR still contained the earlier generic
   Codex-author commit.
5. After the fix, wait until the card reaches `Human Review`, then inspect the PR with
   `gh pr view --json commits`.
6. Verify every PR commit author matches the authenticated GitHub user identity reported by the
   workflow's author check, and no commit is authored as `Codex <codex@openai.com>`.
7. Verify the Trello workpad or handoff comment records that the PR branch author range was checked
   or rewritten, and `/api/v1/state` reaches zero running and retrying entries.
8. Close the temporary PR and delete the temporary branch after recording the result.

### Regression Scenario: In-Progress Pickup Visibility

Use this when changing generated workflows, Trello move tools, or the recommended board layout.

1. Deploy a workflow whose board has `Ready for Codex`, `In Progress`, `Human Review`, and
   `Blocked`.
2. Ensure `tracker.active_states` includes both `Ready for Codex` and `In Progress`.
3. Ensure `tracker.in_progress_state` is set to `In Progress`.
4. Ensure `trello_tools.allowed_move_list_names` includes `In Progress`, `Human Review`, and
   `Blocked`.
5. Create a fresh card in `Ready for Codex`.
6. Verify Trello card actions include a move from `Ready for Codex` to `In Progress` after the card is
   picked up.
7. Wait for the normal handoff. The card should then move to `Human Review` or `Blocked`, and
   `/api/v1/state` should drain to zero running and retrying entries.
8. Deploy a second workflow for the same board shape without an in-progress list configured in the
   workflow.
9. Create a fresh card in `Ready for Codex` and verify it reaches the final handoff list without any
   Trello action moving it to `In Progress`.

Observed on 2026-05-06 against a real deployed workflow:

- The service dispatched a card from `Ready for Codex`, and `/api/v1/state` showed one running
  worker.
- Trello still showed the card in `Ready for Codex` while Codex was already executing the requested
  command.
- An operator manually moved the card to `In Progress` so the board matched the running state.

The fix is to set `tracker.in_progress_state` and let Symphony move the card during pre-dispatch
preparation instead of relying on the agent prompt alone.

### Regression Scenario: Blocked Work Stays Active

Observed on 2026-05-05 against a real Trello board:

- Card: requested running Docker-based E2E tests for another local project checkout.
- The deployed local workflow watched `Ready for Codex`.
- The service user could not access the requested local project checkout.
- Codex added a `Blocked:` Trello comment and left the card in `Ready for Codex`, as the workflow
  prompt instructed for blocked work.
- Symphony saw a successful Codex turn, saw the card still in an active list, and scheduled another
  continuation run.
- The second run added another `Blocked:` comment for the same underlying problem.

A live deployment check for this scenario should fail when a new blocker comment appears after the
verification cutoff, and it should also flag repeated blocker comments on the same card as a product
problem. A workflow with a configured blocked handoff list should move blocked cards out of the
active list. A workflow without a blocked list should move blocked cards to the review handoff list
when one is configured.

### Regression Scenario: In-Progress List Shows Only Running Work

Observed on 2026-05-07 against a real Trello board:

- The workflow had `max_concurrent_agents: 1` and a configured `In Progress` pickup list.
- Two cards were visible in `In Progress`, but `/api/v1/state` showed only one running worker.
- The extra visible card had failed quickly and was waiting for retry/backoff, so it was not actually
  being worked.

Reproduce this with a disposable board and no private project paths:

1. Use a workflow with `Ready for Codex` and `In Progress` as active lists,
   `in_progress_state: In Progress`, and `max_concurrent_agents: 1`.
2. Create two `Ready for Codex` cards whose work fails after pickup, for example by asking for a
   write to a disposable host path that is intentionally not allowed by the deployed sandbox.
3. Verify the first picked-up card moves to `In Progress` while the worker is actually running.
4. After the failure is detected and a retry is scheduled, verify that any retry/backoff card is moved
   back to `Ready for Codex` when that release target exists.
5. While one worker is running, verify no second card remains in `In Progress` unless
   `/api/v1/state` also reports a matching running worker for it.

## Deterministic App-Server

For reproducible live testing, point `codex.command` at:

```bash
java --source 25 /absolute/path/to/scripts/FakeCodexAppServer.java
```

The fake app-server is a single-file Java program that speaks the same stdin/stdout app-server
protocol Symphony uses for Codex, then calls the scoped Trello tools exposed by Symphony. This
verifies the scheduler, workspace creation, app-server protocol, Trello workpads, Trello comments,
Trello moves, and handoff allowlist without relying on model behavior.

Optional environment variables:

- `SYMPHONY_FAKE_CODEX_COMMENT`: Trello comment text to add.
- `SYMPHONY_FAKE_CODEX_SLEEP_MS`: delay before handoff, useful for checking concurrency.

## Cleanup

Archive every disposable board created by the run. Do not reuse a live E2E board for normal work.

```bash
if [ -z "${TRELLO_API_KEY:-}" ] || [ -z "${TRELLO_API_TOKEN:-}" ]; then
  set -a
  . ./.env
  set +a
fi

RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

for workflow in "$RUN_DIR"/*.WORKFLOW.md; do
  board_id="$(awk -F'"' '/board_id:/ {print $2; exit}' "$workflow")"
  [ -n "$board_id" ] || continue
  curl -fsS -X PUT "https://api.trello.com/1/boards/$board_id/closed?key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN" \
    --data-urlencode value=true \
    > /dev/null
done
```
