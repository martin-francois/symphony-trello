#!/usr/bin/env python3
"""Deterministic Codex app-server test double for live Trello E2E checks."""

from __future__ import annotations

import json
import os
import sys
import time
import uuid


def send(message: dict) -> None:
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def request_tool(request_id: int, tool: str, arguments: dict) -> dict:
    send(
        {
            "id": request_id,
            "method": "item/tool/call",
            "params": {"tool": tool, "arguments": arguments},
        }
    )
    while True:
        line = sys.stdin.readline()
        if not line:
            raise RuntimeError("Symphony closed stdin while waiting for tool response")
        response = json.loads(line)
        if response.get("id") == request_id:
            return response


def tool_error(response: dict) -> str | None:
    if "error" in response:
        return json.dumps(response["error"], separators=(",", ":"))
    result = response.get("result")
    if isinstance(result, dict) and result.get("success") is False:
        return json.dumps(result, separators=(",", ":"))
    return None


def complete_turn(thread_id: str, turn_id: str, error: str | None) -> None:
    send(
        {
            "method": "turn/completed",
            "params": {
                "threadId": thread_id,
                "turn": {
                    "id": turn_id,
                    "error": None if error is None else {"message": error},
                    "usage": {"inputTokens": 1, "outputTokens": 1},
                },
            },
        }
    )


def handle_turn(message: dict) -> None:
    params = message.get("params", {})
    thread_id = params.get("threadId", "thread-fake")
    turn_id = f"turn-fake-{uuid.uuid4()}"
    send({"id": message["id"], "result": {"turn": {"id": turn_id}}})

    sleep_ms = int(os.environ.get("SYMPHONY_FAKE_CODEX_SLEEP_MS", "0"))
    if sleep_ms > 0:
        time.sleep(sleep_ms / 1000)

    comment_text = os.environ.get(
        "SYMPHONY_FAKE_CODEX_COMMENT",
        "Symphony live E2E fake Codex handoff: summary and verification complete.",
    )
    comment_response = request_tool(10_001, "trello_add_comment", {"text": comment_text})
    if error := tool_error(comment_response):
        complete_turn(thread_id, turn_id, error)
        return
    move_response = request_tool(10_002, "trello_move_current_card", {"list_name": "Review"})
    complete_turn(thread_id, turn_id, tool_error(move_response))


def main() -> int:
    for line in sys.stdin:
        if not line.strip():
            continue
        message = json.loads(line)
        method = message.get("method")
        if method == "initialize":
            send({"id": message["id"], "result": {"userAgent": "fake-codex-app-server/1.0"}})
        elif method == "initialized":
            continue
        elif method == "thread/start":
            send(
                {
                    "id": message["id"],
                    "result": {"thread": {"id": f"thread-fake-{uuid.uuid4()}"}},
                }
            )
        elif method == "turn/start":
            handle_turn(message)
        elif "id" in message:
            send(
                {
                    "id": message["id"],
                    "error": {"code": -32601, "message": f"Unsupported method: {method}"},
                }
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
