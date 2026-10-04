import assert from "node:assert/strict";
import test from "node:test";
import {publicText, tableCell} from "./redact.ts";

/** Trello-token-shaped but built at run time, so secret scanners never see a literal token. */
const SYNTHETIC_TOKEN = `ATTA${"0".repeat(32)}`;

test("public text replaces run paths, Trello links and ids, secrets, emails, and host paths", () => {
  // given
  const text = [
    "/work/repo/target/live-bugbash/run-1/config/a.WORKFLOW.md",
    "https://trello.com/c/SYNTH001/12-card",
    "board 000000000000000000000001",
    `token ${SYNTHETIC_TOKEN}`,
    "key 00000000000000000000000000000001",
    "mail someone@example.com",
    "/home/someone/.codex/auth.json",
    "http://127.0.0.1:18492/1",
  ].join("\n");
  const privatePaths = [
    {path: "/work/repo/target/live-bugbash/run-1", label: "<run-root>"},
    {path: "/work/repo", label: "<repo>"},
  ];

  // when
  const result = publicText(text, privatePaths);

  // then
  assert.equal(
    result,
    [
      "<run-root>/config/a.WORKFLOW.md",
      "<trello-url>",
      "board <trello-id>",
      "token <secret>",
      "key <secret>",
      "mail <email>",
      "<path>",
      "http://127.0.0.1:<port>/1",
    ].join("\n"),
  );
});

test("a short commit id stays readable", () => {
  // given
  const text = "target commit 0d135edfe5c6";

  // when
  const result = publicText(text);

  // then
  assert.equal(result, text);
});

test("table cells stay on one line and escape pipes", () => {
  // given
  const text = "first | second\nthird";

  // when
  const cell = tableCell(text);

  // then
  assert.equal(cell, "first \\| second third");
});
