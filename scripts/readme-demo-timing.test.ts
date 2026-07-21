import assert from "node:assert/strict";
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import test from "node:test";
import {materializeReadmeDemoTiming} from "./readme-demo-timing.ts";

function createDemoFixture(t: test.TestContext, normalWpm: number, reflectiveWpm: number): string {
  const demoDir = mkdtempSync(join(tmpdir(), "readme-demo-timing-"));
  t.after(() => rmSync(demoDir, {recursive: true, force: true}));
  // The HTML and destination are test-owned constants; neither contains external input.
  writeFileSync(
    join(demoDir, "index.html"), // nosemgrep: javascript.lang.security.audit.unknown-value-with-script-tag.unknown-value-with-script-tag
    `
      <div id="root" data-duration="0">
        <section
          id="s01"
          class="clip"
          data-start="0"
          data-duration="2"
          data-reading-pace="normal"
        ><h1 data-reading-copy>one two three four five</h1></section>
        <section
          id="s02"
          class="clip move-scene"
          data-start="s01"
          data-duration="3"
          data-reading-pace="normal"
        ><header class="cap" data-reading-copy>one two three four five</header></section>
        <section
          id="s03"
          class="clip move-scene"
          data-start="s02"
          data-duration="4"
          data-reading-pace="reflective"
        ><header class="cap" data-reading-copy>one two three four</header></section>
      </div>
      <script>
        const NORMAL_READING_WORDS_PER_MINUTE = ${normalWpm};
        const REFLECTIVE_READING_WORDS_PER_MINUTE = ${reflectiveWpm};
        const MANUAL_DRAG_SETTLE_SECONDS = 1;
      </script>
    `,
    "utf8",
  );
  return demoDir;
}

test("materializes all demo timings from the two reading-rate constants", (t) => {
  // given
  const demoDir = createDemoFixture(t, 75, 50);

  // when
  const timing = materializeReadmeDemoTiming(demoDir);

  // then
  assert.equal(timing.duration, 21.8);
  assert.deepEqual([...timing.sceneStarts], [["s01", 0], ["s02", 6], ["s03", 13]]);
  const html = readFileSync(join(demoDir, "index.html"), "utf8");
  assert.match(html, /id="root" data-duration="21\.8" data-timing-resolved/);
  assert.match(html, /id="s02"[\s\S]*?data-start="6"[\s\S]*?data-duration="7"/);
  assert.match(html, /id="s03"[\s\S]*?data-start="13"[\s\S]*?data-duration="8\.8"/);
});

test("changing one reading-rate constant recalculates the complete timeline", (t) => {
  // given
  const demoDir = createDemoFixture(t, 30, 50);

  // when
  const timing = materializeReadmeDemoTiming(demoDir);

  // then
  assert.equal(timing.duration, 33.8);
  assert.equal(timing.sceneStarts.get("s03"), 25);
});

test("counts only explicitly marked authored copy", (t) => {
  // given
  const demoDir = createDemoFixture(t, 60, 60);
  const htmlPath = join(demoDir, "index.html");
  const html = readFileSync(htmlPath, "utf8").replace(
    "</header></section>",
    "</header><article>decorative workpad words are not reading copy</article></section>",
  );
  writeFileSync(htmlPath, html, "utf8");

  // when
  const timing = materializeReadmeDemoTiming(demoDir);

  // then
  assert.equal(timing.duration, 23);
});

test("overlaps reading with visual action after a declared lead", (t) => {
  // given
  const demoDir = createDemoFixture(t, 60, 60);
  const htmlPath = join(demoDir, "index.html");
  const html = readFileSync(htmlPath, "utf8").replace(
    'id="s02"',
    'id="s02" data-action-lead="1.5"',
  );
  writeFileSync(htmlPath, html, "utf8");

  // when
  const timing = materializeReadmeDemoTiming(demoDir);

  // then
  assert.equal(timing.duration, 20);
  assert.equal(timing.sceneStarts.get("s03"), 12);
  const resolvedHtml = readFileSync(htmlPath, "utf8");
  assert.match(
    resolvedHtml,
    /id="s02"[\s\S]*?data-duration="5"[\s\S]*?data-visual-duration="3"/,
  );
});

test("adds the manual drag settle hold after visual action", (t) => {
  // given
  const demoDir = createDemoFixture(t, 75, 50);
  const htmlPath = join(demoDir, "index.html");
  const html = readFileSync(htmlPath, "utf8").replace(
    'id="s02"',
    'id="s02" data-post-action-hold="manual-drag"',
  );
  writeFileSync(htmlPath, html, "utf8");

  // when
  const timing = materializeReadmeDemoTiming(demoDir);

  // then
  assert.equal(timing.duration, 22.8);
  assert.equal(timing.sceneStarts.get("s03"), 14);
  const resolvedHtml = readFileSync(htmlPath, "utf8");
  assert.match(
    resolvedHtml,
    /id="s02"[\s\S]*?data-duration="8"[\s\S]*?data-visual-duration="3"/,
  );
});

test("doubling both rates halves sequential reading time without changing visual time", (t) => {
  // given
  const slowDemoDir = createDemoFixture(t, 60, 60);
  const fastDemoDir = createDemoFixture(t, 120, 120);

  // when
  const slowTiming = materializeReadmeDemoTiming(slowDemoDir);
  const fastTiming = materializeReadmeDemoTiming(fastDemoDir);

  // then
  const fixedVisualDuration = 9;
  assert.equal(
    slowTiming.duration - fixedVisualDuration,
    2 * (fastTiming.duration - fixedVisualDuration),
  );
});
