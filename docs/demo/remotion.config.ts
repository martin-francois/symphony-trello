import { Config } from "@remotion/cli/config";

Config.setVideoImageFormat("jpeg");
Config.setOverwriteOutput(true);
// The README demo is intentionally silent; never mux an audio track.
Config.setMuted(true);
Config.setCodec("h264");
// Tuned for a small, README-friendly MP4 that stays readable on phones.
Config.setCrf(23);
Config.setPixelFormat("yuv420p");
Config.setChromiumOpenGlRenderer("angle");
