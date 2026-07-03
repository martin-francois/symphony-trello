import { Composition } from "remotion";

import { Poster, ReadmeDemo } from "./video";

export const VIDEO_WIDTH = 1920;
export const VIDEO_HEIGHT = 1080;
export const FPS = 30;
export const DURATION_IN_FRAMES = 2370;

export function Root() {
  return (
    <>
      <Composition
        id="ReadmeDemo"
        component={ReadmeDemo}
        durationInFrames={DURATION_IN_FRAMES}
        fps={FPS}
        width={VIDEO_WIDTH}
        height={VIDEO_HEIGHT}
      />
      <Composition id="Poster" component={Poster} durationInFrames={1} fps={FPS} width={VIDEO_WIDTH} height={VIDEO_HEIGHT} />
    </>
  );
}
