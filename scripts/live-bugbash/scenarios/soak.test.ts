import assert from "node:assert/strict";
import {setImmediate} from "node:timers/promises";
import test from "node:test";
import {SkipScenario} from "../lib/scenario.ts";
import {withScenario} from "./fixture.test-support.ts";
import {SOAK_SCENARIOS} from "./soak.ts";

test("a healthy eleven-minute soak completes at its requested end", async (t) => {
  const id = "passive-soak-trello-auth-board-process-codex-sentinel";
  await withScenario(id, async (context, cli) => {
    // given
    context.options.soak.durationMs = 11 * 60_000;
    t.mock.method(cli, "exec", async () => ({status: 0, stdout: "synthetic workspace", stderr: "", timedOut: false}));
    t.mock.timers.enable({apis: ["Date", "setTimeout"], now: new Date("2026-01-01T00:00:00Z")});

    // when
    const completion = SOAK_SCENARIOS[id]!(context);
    await setImmediate();
    t.mock.timers.tick(11 * 60_000);
    const summary = await completion;

    // then
    assert.match(summary, /1 soak cycle\(s\)/);
    assert.deepEqual(context.check.failures, []);
    t.mock.timers.reset();
  });
});

test("a resumed soak with an expired absolute deadline does not run a cycle or report coverage", async (t) => {
  const id = "passive-soak-trello-auth-board-process-codex-sentinel";
  await withScenario(id, async (context, cli) => {
    // given
    context.options.soak.until = new Date(0);
    const command = t.mock.method(cli, "exec", async () => ({status: 0, stdout: "", stderr: "", timedOut: false}));

    // when / then
    await assert.rejects(() => SOAK_SCENARIOS[id]!(context), SkipScenario);
    assert.equal(command.mock.callCount(), 0);
  });
});

for (const phase of ["installation", "command", "board query"] as const) {
  test(`the soak deadline cancels a blocked ${phase} without reporting a completed cycle`, async (t) => {
    const id = "passive-soak-trello-auth-board-process-codex-sentinel";
    await withScenario(id, async (context, cli) => {
      // given
      context.options.soak.durationMs = 1_000;
      const board = await context.trello().createBoard(id);
      const blocked = Promise.withResolvers<void>();
      let probeSignal: AbortSignal | undefined;
      const block = async (signal?: AbortSignal) => {
        probeSignal = signal;
        signal?.addEventListener("abort", () => blocked.resolve(), {once: true});
        await blocked.promise;
      };
      t.mock.method(cli, "exec", async (_args: readonly string[], options: {signal?: AbortSignal} = {}) => {
        if (phase === "command") await block(options.signal);
        return {status: 0, stdout: "", stderr: "", timedOut: false};
      });
      t.mock.method(context.trello(), "boardClosed", async (id: string, signal?: AbortSignal) => {
        assert.equal(id, board.id);
        if (phase === "board query") await block(signal);
        return true;
      });
      if (phase === "installation") {
        t.mock.method(context, "cli", async (signal?: AbortSignal) => {
          await block(signal);
          return cli;
        });
      }
      t.mock.timers.enable({apis: ["Date", "setTimeout"], now: new Date("2026-01-01T00:00:00Z")});
      const completion = SOAK_SCENARIOS[id]!(context);
      // Observe the result immediately so the abort cannot become an unhandled rejection.
      const rejected = completion.then(() => null, (error: unknown) => error);
      try {
        await setImmediate();

        // when
        t.mock.timers.tick(1_000);
        await setImmediate();

        // then
        assert.equal(probeSignal?.aborted, true, `${phase} must receive the expired soak signal`);
        assert.ok(await rejected instanceof SkipScenario, "an unfinished first cycle must not count as coverage");
      } finally {
        blocked.resolve();
        await rejected;
        t.mock.timers.reset();
      }
    });
  });
}

test("a command that returns at the soak deadline cannot start another board probe", async (t) => {
  const id = "passive-soak-trello-auth-board-process-codex-sentinel";
  await withScenario(id, async (context, cli) => {
    // given
    context.options.soak.durationMs = 1_000;
    await context.trello().createBoard(id);
    const boardQuery = t.mock.method(context.trello(), "boardClosed", async () => true);
    t.mock.timers.enable({apis: ["Date", "setTimeout"], now: new Date("2026-01-01T00:00:00Z")});
    t.mock.method(cli, "exec", async () => {
      t.mock.timers.tick(1_000);
      return {status: 0, stdout: "", stderr: "", timedOut: false};
    });

    // when / then
    try {
      await assert.rejects(() => SOAK_SCENARIOS[id]!(context), SkipScenario);
      assert.equal(boardQuery.mock.callCount(), 0, "no board query starts after the shared deadline");
    } finally {
      t.mock.timers.reset();
    }
  });
});

test("run interruption cancels an active soak probe before its own deadline", async (t) => {
  const id = "passive-soak-trello-auth-board-process-codex-sentinel";
  await withScenario(id, async (context, cli) => {
    // given
    const interruption = new AbortController();
    Object.assign(context, {signal: interruption.signal});
    context.options.soak.durationMs = 60_000;
    const blocked = Promise.withResolvers<void>();
    const ready = Promise.withResolvers<void>();
    let probeSignal: AbortSignal | undefined;
    t.mock.method(cli, "exec", async (_args: readonly string[], options: {signal?: AbortSignal} = {}) => {
      probeSignal = options.signal;
      probeSignal?.addEventListener("abort", () => blocked.resolve(), {once: true});
      ready.resolve();
      await blocked.promise;
      return {status: 0, stdout: "", stderr: "", timedOut: false};
    });
    t.mock.timers.enable({apis: ["Date", "setTimeout"], now: new Date("2026-01-01T00:00:00Z")});
    const completion = SOAK_SCENARIOS[id]!(context).then(() => null, (error: unknown) => error);
    try {
      await ready.promise;

      // when
      interruption.abort();
      await setImmediate();

      // then
      assert.equal(probeSignal?.aborted, true, "run interruption must reach the active probe before the soak deadline");
      assert.ok(await completion instanceof Error);
    } finally {
      blocked.resolve();
      t.mock.timers.tick(60_000);
      await completion;
      t.mock.timers.reset();
    }
  });
});
