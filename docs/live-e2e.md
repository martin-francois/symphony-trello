# Live Trello E2E Verification

This runbook verifies Symphony against real Trello boards without committing secrets or disposable
board ids.

The main flow uses real Trello and a deterministic Java app-server test double in place of real
Codex. That makes Trello reads, board creation, card scheduling, comments, moves, and concurrency
reproducible. Run the optional real Codex smoke separately when you need confidence that the local
Codex CLI starts and speaks the app-server protocol in this checkout.

## Inputs

1. Create `.env` from `.env.example`.
2. Put `TRELLO_API_KEY` and `TRELLO_API_TOKEN` in `.env`.
3. Use a Trello account where disposable boards can be created and archived.

The live commands read `.env` automatically. Real environment variables with the same names take
precedence.

## What To Verify

Use a unique run id such as `live-e2e-YYYYMMDD-HHMMSS`.

1. `list-workspaces` reads credentials from `.env` and returns accessible Workspaces.
2. `new-board` creates a disposable board and writes a workflow.
3. `import-board` reads that board and writes a second workflow.
4. A disposable card in `Ready for Codex` is picked up by Symphony.
5. The app-server calls `trello_add_comment` and `trello_move_current_card`.
6. The card has a handoff comment and ends in `Review`.
7. Two Symphony processes can run against two boards at the same time on different ports.
8. Cleanup archives all disposable boards created by the run.

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
JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH ./mvnw -q package
```

Verify `.env` loading and Workspace discovery:

```bash
JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH \
  ./mvnw -q exec:java -Dexec.args='list-workspaces'
```

Create two disposable boards. If the token can see exactly one Workspace, omit `--workspace-id`;
that path must create the board in the only accessible Workspace.

```bash
JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH \
  ./mvnw -q exec:java \
  -Dexec.args="new-board --name 'Symphony $RUN_ID Board A' --workflow $RUN_DIR/board-a.WORKFLOW.md"

JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH \
  ./mvnw -q exec:java \
  -Dexec.args="new-board --name 'Symphony $RUN_ID Board B' --workflow $RUN_DIR/board-b.WORKFLOW.md"
```

Import Board A back into a separate workflow:

```bash
BOARD_A_ID="$(awk -F'"' '/board_id:/ {print $2; exit}' "$RUN_DIR/board-a.WORKFLOW.md")"

JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH \
  ./mvnw -q exec:java \
  -Dexec.args="import-board --board $BOARD_A_ID --active 'Ready for Codex' --terminal Done --workflow $RUN_DIR/imported-a.WORKFLOW.md"
```

Patch the generated workflows to use the deterministic Java app-server and different ports. Board B
uses two workers and a delay so `/api/v1/state` can prove that two cards run at once while the third
waits for a refresh.

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"
FAKE_JAVA="${JAVA_HOME:-/tmp/jdk25}/bin/java"
FAKE_CODEX="$(pwd)/scripts/FakeCodexAppServer.java"

$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/board-a.WORKFLOW.md" 1 250 "$FAKE_JAVA" "$FAKE_CODEX"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/imported-a.WORKFLOW.md" 1 250 "$FAKE_JAVA" "$FAKE_CODEX"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/board-b.WORKFLOW.md" 2 7000 "$FAKE_JAVA" "$FAKE_CODEX"
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
  jq -r '.[] | select(.name == "Review") | .id' "$RUN_DIR/lists-$board.json" > "$RUN_DIR/review-list-$board.txt"
done

curl -fsS -X POST "https://api.trello.com/1/cards" \
  --data-urlencode "key=$TRELLO_API_KEY" \
  --data-urlencode "token=$TRELLO_API_TOKEN" \
  --data-urlencode "idList=$(cat "$RUN_DIR/ready-list-a.txt")" \
  --data-urlencode "name=Symphony $RUN_ID single-card handoff" \
  --data-urlencode "desc=Disposable live E2E card." \
  > "$RUN_DIR/card-a-1.json"

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

Start Board A in one terminal, wait for the card to move to Review, then stop the service:

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH \
  java -jar target/quarkus-app/quarkus-run.jar "$RUN_DIR/board-a.WORKFLOW.md" --port 18181
```

Start Board B in another terminal and verify concurrency from a second shell:

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"

JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH \
  java -jar target/quarkus-app/quarkus-run.jar "$RUN_DIR/board-b.WORKFLOW.md" --port 18182
```

```bash
curl -fsS http://127.0.0.1:18182/api/v1/state \
  | jq '{counts, running: [.running[].cardId], retrying: [.retrying[].cardId]}'
```

Expected result while the fake app-server is sleeping: `counts.running` is `2`,
`counts.retrying` is `0`, and the third Board B card is still in `Ready for Codex`. After the first
two cards move to Review, request a refresh and verify the third card moves too:

```bash
curl -fsS -X POST http://127.0.0.1:18182/api/v1/refresh | jq .
```

Create a fresh Board A card for the imported workflow, because the first Board A card should already
be in `Review`:

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

JAVA_HOME=/tmp/jdk25 PATH=/tmp/jdk25/bin:$PATH \
  java -jar target/quarkus-app/quarkus-run.jar "$RUN_DIR/imported-a.WORKFLOW.md" --port 18183
```

Check any card's final Trello state with:

```bash
card_id="$(jq -r .id "$RUN_DIR/card-a-1.json")"
curl -fsS "https://api.trello.com/1/cards/$card_id?fields=name,idList&actions=commentCard&key=$TRELLO_API_KEY&token=$TRELLO_API_TOKEN" \
  > "$RUN_DIR/status-$card_id.json"
jq -r '[.id, .name, .idList, (.actions | length)] | @tsv' "$RUN_DIR/status-$card_id.json"
```

The final `idList` must match the board's `review-list-*.txt`, and the comment count must be at
least `1`.

An optional real Codex smoke can use an unpatched generated workflow whose `codex.command` remains
`codex app-server`. Keep it bounded and record whether Codex starts, emits app-server events, and
finishes the Trello handoff. The deterministic fake app-server remains the authoritative regression
check because it removes model latency and behavior variability from Trello protocol verification.

## Deterministic App-Server

For reproducible live testing, point `codex.command` at:

```bash
${JAVA_HOME:-/tmp/jdk25}/bin/java --source 25 /absolute/path/to/scripts/FakeCodexAppServer.java
```

The fake app-server is a single-file Java program that speaks the same stdin/stdout app-server
protocol Symphony uses for Codex, then calls the scoped Trello tools exposed by Symphony. This
verifies the scheduler, workspace creation, app-server protocol, Trello comments, Trello moves, and
handoff allowlist without relying on model behavior.

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
