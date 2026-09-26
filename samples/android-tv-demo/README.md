# SceneView Android TV Demo

A 3D model gallery for Android TV and Google TV, driven entirely by the remote's D-pad.
It is the same `SceneView` + `rememberModelInstance` code as the phone demo, with a
10-foot UI on top.

## Controls

The D-pad has two homes, and the key hints at the top right always show which one is active.

| Focus | ◀ ▶ | ▲ ▼ | OK | Back |
|---|---|---|---|---|
| **Gallery** (row of cards, focused at launch) | Pick a model — the stage follows the focused card | Zoom in / out | Take the camera | Exit the app |
| **Stage** (framed in `primary` while focused) | Orbit around the model | Tilt the camera up / down | Back to the gallery | Back to the gallery |

Play/Pause toggles the turntable. It only turns while the gallery has focus: once you take
the camera, it stays where you put it.

## What it shows

- 10 CC BY 4.0 / CC0 models, each with its author and licence on screen (`assets/CREDITS.md`).
- Studio HDR lighting (`environments/studio_2k.hdr`) without its backdrop, on `stage-background`.
- Colours, spacing, type and motion from [`DESIGN.md`](../../DESIGN.md) (`TvTokens.kt`),
  dark only — Google TV has no light theme and the chrome floats over the 3D stage.
- Focus visible from the sofa: the focused card scales up with a 3 dp `primary` ring and glow,
  the model on stage keeps a 2 dp ring, the stage gets a `primary` frame when it holds the D-pad.
- Overscan-safe margins (48 dp sides, 32 dp top and bottom).
- An idle TV renders nothing: the camera loop parks once the turntable is off and the last
  D-pad step has eased in.

## Play TV readiness

The manifest already declares what the Play TV checks look for: a `LEANBACK_LAUNCHER` activity,
a 320 × 180 banner with the app name (`res/drawable-xhdpi/tv_banner.png`, source in
`art/tv_banner.svg`), and `android.software.leanback` / `android.hardware.touchscreen` both
`required="false"`. The app is not published: no Play workflow uploads it.

## Run

```bash
./gradlew :samples:android-tv-demo:assembleDebug
adb -s <tv-serial> install -r samples/android-tv-demo/build/outputs/apk/debug/android-tv-demo-debug.apk
```

Android TV device or emulator, API 28+ (the demo's `minSdk`) — for instance a Google TV AVD
(`system-images;android-34;google-tv;arm64-v8a`, device `tv_1080p`).
