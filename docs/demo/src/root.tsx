import { Composition, Still } from "remotion";

import { Poster, ReadmeDemo } from "./video";

export const VIDEO_WIDTH = 1920;
export const VIDEO_HEIGHT = 1080;
export const FPS = 30;
export const DURATION_IN_FRAMES = 2910;

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
      <Still id="Poster" component={Poster} width={VIDEO_WIDTH} height={VIDEO_HEIGHT} />
    </>
  );
}
