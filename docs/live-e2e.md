# Live Trello E2E Verification

This runbook verifies Symphony against real Trello boards without committing secrets or disposable
board ids.

Run this in two phases. First use real Trello with the deterministic Java app-server test double in
place of real Codex. That catches scheduler, Trello, workflow import, handoff tool, and concurrency
issues without model variability. Then run the strict real-Codex phase against fresh disposable
cards before claiming real `codex app-server` coverage.

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
3. `import-board` reads both a Symphony-generated board and a custom existing-board structure, then
   writes workflows for them.
4. With `max_concurrent_agents: 1`, two `Ready for Codex` cards run one at a time in Trello position
   order. This includes a later-created card that was moved above the older card.
5. With `max_concurrent_agents: 2`, two cards on one board run at the same time while a third waits.
6. The app-server calls `trello_add_comment` and `trello_move_current_card`.
7. Processed cards have a handoff comment and end in `Review`.
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

Patch the generated workflows to use the deterministic Java app-server and different ports. Board A
uses one worker and a delay so `/api/v1/state` can prove that one card runs while another waits.
Board B uses two workers and a delay so `/api/v1/state` can prove that two cards run at once while
the third waits for a refresh.

```bash
RUN_ID="$(cat target/live-e2e-current-run-id)"
RUN_DIR="target/$RUN_ID"
FAKE_JAVA="$(command -v java)"
FAKE_CODEX="$(pwd)/scripts/FakeCodexAppServer.java"

$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/board-a.WORKFLOW.md" 1 7000 "$FAKE_JAVA" "$FAKE_CODEX"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/imported-a.WORKFLOW.md" 1 250 "$FAKE_JAVA" "$FAKE_CODEX"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/board-b.WORKFLOW.md" 2 7000 "$FAKE_JAVA" "$FAKE_CODEX"
$FAKE_JAVA --source 25 scripts/PatchLiveE2eWorkflow.java "$RUN_DIR/custom-import.WORKFLOW.md" 2 7000 "$FAKE_JAVA" "$FAKE_CODEX"
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
  | jq '{counts, running: [.running[].cardId], retrying: [.retrying[].cardId]}'

curl -fsS http://127.0.0.1:18182/api/v1/state \
  | jq '{counts, running: [.running[].cardId], retrying: [.retrying[].cardId]}'

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

After the active cards move to Review, request refreshes and verify the waiting cards move too:

```bash
curl -fsS -X POST http://127.0.0.1:18181/api/v1/refresh | jq .
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

The final `idList` must match the board's `review-list-*.txt`, and the comment count must be at
least `1`.

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

1. Each target card has at least one Trello comment and its `idList` matches the board's Review list.
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
deployment is healthy. Checking only `systemctl` or `/api/v1/state` is not enough: Codex can add a
Trello blocker comment while the service still looks active.

Set `CARD_ID` to the card currently being processed and `EXPECTED_LIST` to the handoff list for that
workflow, usually `Review`.

```bash
if [ -z "${TRELLO_API_KEY:-}" ] || [ -z "${TRELLO_API_TOKEN:-}" ]; then
  TRELLO_API_KEY="$(tr -d '\r\n' </etc/symphony-trello/secrets/trello-api-key)"
  TRELLO_API_TOKEN="$(tr -d '\r\n' </etc/symphony-trello/secrets/trello-api-token)"
fi

CARD_ID="replace-with-card-id"
EXPECTED_LIST="Review"
STATE_URL="http://127.0.0.1:8080/api/v1/state"
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
      runningLastEvent: ($state.running[0].lastEvent // null),
      runningLastMessage: ($state.running[0].lastMessage // null),
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

Use a fresh `CUTOFF` after every deployment restart or systemd hardening change. That prevents old
blocker comments from failing a fixed run, while still catching new comments created after the
current verification started.

The deployment is healthy only when all of these are true:

1. The service is active.
2. `/api/v1/state` reaches `counts.running == 0` and `counts.retrying == 0`.
3. The card moved out of the active list into the expected handoff list.
4. The latest new Trello comments are successful handoff notes, not blocker/auth/sandbox comments.

If new comments mention `bwrap: Can't read /proc/sys/kernel/overflowuid`, the systemd unit is hiding
`/proc/sys`; do not use `ProcSubset=pid` for this service. If new comments mention
`bwrap: loopback: Failed to create NETLINK_ROUTE socket`, the unit is blocking the address family
Codex's sandbox needs; `RestrictAddressFamilies` must include `AF_NETLINK`.

### Regression Scenario: Blocked Work Stays Active

Observed on 2026-05-05 against a real Trello board:

- Card: requested running Docker-based E2E tests for another local project checkout.
- The deployed `symphony-trello@local` workflow watched `Ready for Codex`.
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

## Deterministic App-Server

For reproducible live testing, point `codex.command` at:

```bash
java --source 25 /absolute/path/to/scripts/FakeCodexAppServer.java
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
