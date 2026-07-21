import {readFileSync, writeFileSync} from "node:fs";
import {join} from "node:path";

export interface CompositionTiming {
  duration: number;
  sceneStarts: ReadonlyMap<string, number>;
}

interface SceneTiming {
  duration: number;
  id: string;
  start: number;
  visualDuration: number;
}

const WORD_PATTERN = /\b[\w’'-]+\b/g;

function roundToTenth(value: number): number {
  return Math.round(value * 10) / 10;
}

function formatSeconds(value: number): string {
  return value.toFixed(1).replace(/\.0$/, "");
}

function readWordsPerMinute(html: string, constantName: string): number {
  const match = html.match(
    new RegExp(`const\\s+${constantName}\\s*=\\s*(\\d+(?:\\.\\d+)?)\\s*;`),
  );
  const wordsPerMinute = Number(match?.[1]);
  if (!Number.isFinite(wordsPerMinute) || wordsPerMinute <= 0) {
    throw new Error(`docs/demo/index.html must define a positive ${constantName}`);
  }
  return wordsPerMinute;
}

function readAttribute(tag: string, name: string): string | undefined {
  return tag.match(new RegExp(`\\b${name}=["']([^"']+)["']`, "i"))?.[1];
}

function replaceAttribute(tag: string, name: string, value: string): string {
  const pattern = new RegExp(`(\\b${name}=["'])[^"']+(["'])`, "i");
  if (!pattern.test(tag)) {
    throw new Error(`docs/demo/index.html is missing ${name}`);
  }
  return tag.replace(pattern, `$1${value}$2`);
}

function appendAttribute(tag: string, name: string, value: string): string {
  if (readAttribute(tag, name) !== undefined) {
    throw new Error(`docs/demo/index.html must not define generated ${name}`);
  }
  return tag.replace(/>$/, ` ${name}="${value}">`);
}

function readReadingCopy(content: string, sceneId: string): string {
  const copies = [...content.matchAll(
    /<([a-z][a-z0-9]*)\b[^>]*\bdata-reading-copy(?:=["'][^"']*["'])?[^>]*>([\s\S]*?)<\/\1>/gi,
  )];
  if (copies.length === 0) {
    throw new Error(`${sceneId} must declare the text used to calculate its reading hold`);
  }
  return copies.map((copy) => copy[2] ?? "").join(" ").replace(/<[^>]+>/g, " ");
}

function calculateSceneTiming(html: string): SceneTiming[] {
  const wordsPerMinute = {
    normal: readWordsPerMinute(html, "NORMAL_READING_WORDS_PER_MINUTE"),
    reflective: readWordsPerMinute(html, "REFLECTIVE_READING_WORDS_PER_MINUTE"),
  } as const;
  const scenes: SceneTiming[] = [];
  let nextStart = 0;

  for (const match of html.matchAll(/(<section\b[^>]*>)([\s\S]*?)<\/section>/gi)) {
    const tag = match[1];
    const content = match[2];
    if (tag === undefined || content === undefined) {
      continue;
    }
    const id = readAttribute(tag, "id");
    const visualDuration = Number(readAttribute(tag, "data-duration"));
    if (id === undefined || !Number.isFinite(visualDuration) || visualDuration <= 0) {
      throw new Error("docs/demo/index.html contains an invalid scene timing declaration");
    }

    const readingPace = readAttribute(tag, "data-reading-pace");
    if (readingPace !== "normal" && readingPace !== "reflective") {
      throw new Error(`${id} must use the normal or reflective reading pace`);
    }
    const readingText = readReadingCopy(content, id);
    const wordCount = readingText.match(WORD_PATTERN)?.length ?? 0;
    const readingHold = Math.ceil((wordCount * 60 * 10) / wordsPerMinute[readingPace]) / 10;

    const actionLeadAttribute = readAttribute(tag, "data-action-lead");
    const actionLead = actionLeadAttribute === undefined
      ? readingHold
      : Number(actionLeadAttribute);
    if (!Number.isFinite(actionLead) || actionLead < 0) {
      throw new Error(`${id} must declare a non-negative data-action-lead`);
    }

    const duration = roundToTenth(Math.max(readingHold, actionLead + visualDuration));
    scenes.push({duration, id, start: nextStart, visualDuration});
    nextStart = roundToTenth(nextStart + duration);
  }

  if (scenes.length === 0) {
    throw new Error("docs/demo/index.html does not contain any scenes");
  }
  return scenes;
}

export function materializeReadmeDemoTiming(demoDir: string): CompositionTiming {
  const htmlPath = join(demoDir, "index.html");
  const sourceHtml = readFileSync(htmlPath, "utf8");
  const rootPattern = /<[a-z][^>]*\bid=["']root["'][^>]*>/i;
  const rootTag = sourceHtml.match(rootPattern)?.[0];
  if (rootTag === undefined || Number(readAttribute(rootTag, "data-duration")) !== 0) {
    throw new Error(
      "docs/demo/index.html #root data-duration must be 0 so the reading pace owns the timeline",
    );
  }

  const scenes = calculateSceneTiming(sourceHtml);
  const duration = roundToTenth(scenes.at(-1)!.start + scenes.at(-1)!.duration);
  let resolvedHtml = sourceHtml.replace(rootPattern, (tag) => {
    const withDuration = replaceAttribute(tag, "data-duration", formatSeconds(duration));
    return withDuration.replace(/>$/, " data-timing-resolved>");
  });

  for (const scene of scenes) {
    const scenePattern = new RegExp(
      `<section\\b(?=[^>]*\\bid=["']${scene.id}["'])[^>]*>`,
      "i",
    );
    resolvedHtml = resolvedHtml.replace(scenePattern, (tag) =>
      appendAttribute(
        replaceAttribute(
          replaceAttribute(tag, "data-start", formatSeconds(scene.start)),
          "data-duration",
          formatSeconds(scene.duration),
        ),
        "data-visual-duration",
        formatSeconds(scene.visualDuration),
      ));
  }
  writeFileSync(htmlPath, resolvedHtml, "utf8");

  return {
    duration,
    sceneStarts: new Map(scenes.map((scene) => [scene.id, scene.start])),
  };
}
