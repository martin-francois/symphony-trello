import assert from "node:assert/strict";
import test from "node:test";
import {pollUntil, TimeoutError, waitFor} from "./symphony.ts";

test("pollUntil returns the first value the check produces", async () => {
  // given
  let calls = 0;
  const check = () => (++calls >= 3 ? "ready" : false);

  // when
  const value = await pollUntil(check, 1_000, 1);

  // then
  assert.equal(value, "ready");
  assert.equal(calls, 3);
});

test("pollUntil returns null at the deadline while waitFor throws a TimeoutError", async () => {
  // given
  const never = () => null;

  // when
  const polled = await pollUntil(never, 20, 5);

  // then
  assert.equal(polled, null);
  await assert.rejects(() => waitFor("nothing", never, 20, 5), TimeoutError);
});
