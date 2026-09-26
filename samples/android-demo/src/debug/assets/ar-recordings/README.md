# Bundled AR session recordings (debug-only)

ARCore session datasets (`.mp4` with H.264 video + ARCore metadata streams).
This directory lives in the `debug` sourceSet so files here are bundled in
**debug APKs only** — the release APK does NOT ship them, keeping the Play
Store APK lean (#934).

The `ARRecordPlaybackDemo` extracts each file here on first launch into
`context.getExternalFilesDir("ar-recordings")/` so it appears in the
**Recordings** step alongside any sessions the user records on-device.

This makes the AR demos **testable on devices without ARCore tracking**
(emulators, dev machines via the iframe Rerun viewer, CI runners) — replay
a stable real-world capture instead of needing live camera + IMU.

## Files

| File | Source device | Duration | Streams | Use case |
|---|---|---|---|---|
| `bundled-pixel9-sample.mp4` | Pixel 9 | 18 s | H.264 640×480 + 4 metadata tracks (pose, IMU, depth, point cloud) | Default starter — generic indoor scan |

## Adding a new bundled recording

1. Run the demo app on a real ARCore-capable device (Pixel 4+ recommended).
2. Open the **AR Recording** demo, tap the red shutter, capture an AR session, tap it again.
3. Pull the `.mp4` from the device:
   ```bash
   adb -s <SERIAL> pull /sdcard/Android/data/io.github.sceneview.demo/files/ar-recordings/<filename>.mp4
   ```
4. Verify the dataset is well-formed:
   ```bash
   ffprobe -v error -show_streams <filename>.mp4 | grep "codec_type"
   # Expect: 1 video (h264), 4+ data (mett) streams
   ```
5. Rename to a descriptive `bundled-<device>-<scene>.mp4`, drop here.
6. Document it in the table above.
7. The Robolectric test
   `samples/android-demo/.../ARBundledRecordingsTest.kt` automatically
   validates every file in this directory parses cleanly — no demo-side
   wiring needed for the new file to be picked up.

## ARCore SDK compatibility

Recordings are **bound to the ARCore SDK major version that produced them**.
The current pin lives in `gradle/libs.versions.toml` (`arCore = "X.Y.Z"`) —
read it there rather than mirror the version into this README, where it
would silently drift on the next bump.

If a future ARCore SDK upgrade breaks playback, the demo's auto-extraction
will surface as "Playback list still shows the file but ARCore replays
nothing". Re-record on a fresh device with the bumped SDK; the auto-extract
length-check in `ARRecordPlaybackDemo` (`target.length() == expectedBytes`)
will replace the stale extracted copy on next entry.

The recordings here are NOT versioned per release — they live alongside the
app as static debug assets. Re-record only when the runtime fails.
