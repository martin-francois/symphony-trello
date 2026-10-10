import assert from "node:assert/strict";
import test from "node:test";
import {defaultRunId, parseDuration, parseOptions, UsageError} from "./options.ts";

const NOW = new Date("2026-07-01T12:00:00Z");

test("without a selection the quick profile runs against fakes", () => {
  // given
  const args: string[] = [];

  // when
  const options = parseOptions(args, NOW);

  // then
  assert.deepEqual(options.selection, {profiles: ["quick"], families: [], scenarioIds: []});
  assert.deepEqual([options.trello, options.codex, options.github, options.hostProfile], ["fake", "fake", "fake", "standard"]);
  assert.equal(options.runId, "live-bugbash-20260701T120000Z");
  assert.equal(defaultRunId(NOW), options.runId);
});

test("real integrations need explicit flags and accept inline values", () => {
  // given
  const args = ["--trello=real", "--codex", "real", "--profile", "trello-live", "--family", "setup"];

  // when
  const options = parseOptions(args, NOW);

  // then
  assert.deepEqual([options.trello, options.codex], ["real", "real"]);
  assert.deepEqual(options.selection, {profiles: ["trello-live"], families: ["setup"], scenarioIds: []});
});

test("unsafe run ids, unknown flags, and conflicting commands are usage errors", () => {
  // given
  const parse = (args: string[]) => () => parseOptions(args, NOW);

  // when / then
  assert.throws(parse(["--run-id", "../escape"]), UsageError);
  assert.throws(parse(["--run-id", ".."]), UsageError);
  assert.throws(parse(["--bogus"]), /unknown option --bogus/);
  assert.throws(parse(["--list", "--dry-run"]), /choose only one/);
  assert.throws(parse(["--profile", "everything"]), /--profile must be one of/);
});

test("soak accepts a duration or a future timezone-qualified hard stop", () => {
  // given
  const duration = ["--soak-duration", "10m"];
  const hardStop = ["--soak-until", "2026-07-01T13:00:00Z"];

  // when
  const byDuration = parseOptions(duration, NOW).soak;
  const byHardStop = parseOptions(hardStop, NOW).soak;

  // then
  assert.equal(byDuration.durationMs, 600_000);
  assert.equal(byHardStop.until?.toISOString(), "2026-07-01T13:00:00.000Z");
});

test("soak rejects a past hard stop, a hard stop without a timezone, and both limits at once", () => {
  // given
  const parse = (args: string[]) => () => parseOptions(args, NOW);

  // when / then
  assert.throws(parse(["--soak-until", "2026-07-01T11:00:00Z"]), /must be in the future/);
  assert.throws(parse(["--soak-until", "2026-07-01T13:00:00"]), /with a timezone/);
  assert.throws(parse(["--soak-duration", "1m", "--soak-until", "2026-07-01T13:00:00Z"]), /either/);
});

test("durations accept ms, s, m, and h and default to seconds", () => {
  // given
  const values = ["250ms", "90s", "10m", "2h", "30"];

  // when
  const parsed = values.map((value) => parseDuration(value, "--x"));

  // then
  assert.deepEqual(parsed, [250, 90_000, 600_000, 7_200_000, 30_000]);
  assert.throws(() => parseDuration("ten minutes", "--x"), UsageError);
});
