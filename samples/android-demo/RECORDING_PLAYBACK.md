# AR Record & Playback — debug AR without holding a phone

The android-demo `Record & Playback` tile shows ARCore's most useful debugging primitive: capture a full AR session to an MP4, then replay it 1:1 from your desk.

## What this demo shows

ARCore's `Session.startRecording(RecordingConfig)` captures the entire AR session — camera frames, IMU, planes, depth, anchors, light estimation — into a single MP4 dataset. SceneView wraps that with `ARRecorder` for recording and a `playbackDataset` parameter on `ARSceneView` for replay. The replayed session re-runs deterministically: hit-tests return the same results, planes appear at the same moment, anchors track at the same poses.

## Why it matters

- **Iterate at the desk.** Record an outdoor session once; replay it any time without holding a phone in front of the laptop.
- **Reproduce bugs deterministically.** Share the MP4 with a teammate — they replay your exact session, including the lighting, motion, and surfaces you saw.
- **CI fixtures.** Bundle a recording as a test asset; assert that `onSessionUpdated` reports the expected planes/anchors.

## How to use the demo

The demo has two steps in the bottom dock, the way a camera app separates shooting from its gallery (#3831):

### Record

Live AR with a shutter. Tap any detected surface to place a fox. Tap the red shutter to start: a live card at the top shows the clock, the file size as it grows, and what is being captured — camera frames, the distance walked, the surfaces found and the foxes placed. If the camera loses its place for more than a few seconds, the card says so and warns that the take may not replay well. Tap the shutter again to stop; a card replaces it with the first frame of the take, its length, size and placements, and **Replay** / **Share** buttons.

Recordings are stored under `context.getExternalFilesDir("ar-recordings")` — app-private external storage, so no runtime permission is required to write the file — with a timestamped name like `ar-session-20260506-153045.mp4`.

### Recordings

Every MP4 on disk, newest first, each with its first-frame thumbnail, length, size and what it contains (video, motion sensors, placements). Tap one to replay it in place: the `ARSceneView` re-mounts with `playbackDataset = file`, a card shows the replay's progress and what has been rebuilt so far, and the foxes come back where they were placed. At the end, a report sums the take up — frames, distance, surfaces, how often the camera held its place and, when it did not, why. The row menu shares the file, saves it to Downloads, or deletes it.

Each take, live or replayed, mounts its own `ARSceneView` via `key(step, replayFile, generation)` because ARCore binds the playback source at session-creation time and cannot be toggled after resume.

### What a recording keeps

- **Camera video** — what the camera saw, playable in any video app.
- **Motion sensors** — the accelerometer and gyroscope tracks ARCore writes, so the replay moves exactly as you did.
- **Placements** — a custom data track (`application/vnd.sceneview.placement`) the demo adds with `ARRecorder.addTrack` and writes with `recordTrack`. Each placement is stored relative to the camera of its frame, so the replay can rebuild it in the replayed session's own world.

The camera path and the surfaces are not stored: ARCore rebuilds them on replay from the video and the sensors.

## Surprises and caveats

- **Camera permission is still requested during playback.** ARCore opens the camera even when replaying a dataset. Users see no live preview, but the permission gate fires regardless. The demo's normal permission flow handles it.
- **Neither recording nor replay runs on the arm64 emulator.** It has no camera HAL and there is no ARCore build for it (#2754). Record and replay on a phone; the emulator can only show the Recordings list and the QA previews of each card (`--ez qa_mode true --es qa_state recording|saved|recordings|replaying|replay-finished|empty`).
- **Same-device-class playback is most reliable.** A recording made on a phone replays cleanly on the same phone or a similar one. Heavily different sensor sets (e.g. phone → tablet) may degrade tracking quality.
- **MP4 file size is non-trivial.** Tens of MB per minute depending on resolution. The app-private `ar-recordings` directory has no quota beyond the user's free space, but don't ship recordings inside the APK.
- **Recording while in playback mode is rejected.** `ARRecorder.start()` returns `false` and surfaces an error message if the session is currently bound to a playback dataset. The demo only offers the shutter in the Record step, on a live camera.

## Pair with Rerun

The same MP4 can be replayed with the [Rerun bridge](src/main/java/io/github/sceneview/demo/demos/ARRerunDemo.kt) attached. Mount `ARSceneView` in playback mode, wire `rememberRerunBridge` against `onSessionUpdated`, and you get a frame-accurate 3D scrub-and-replay view of the session in the Rerun viewer. See the **AR Debug — Rerun.io integration** section in the repo-level [`llms.txt`](../../llms.txt) for the wire format and bridge API.

## Sharing a recording

**Share** on the saved-take card, or in a recording's row menu, hands the MP4 to the system share sheet through the app's `FileProvider`; **Save to Downloads** copies it to the public Downloads folder. From a computer, the file is also one `adb pull` away:

```bash
adb pull /sdcard/Android/data/io.github.sceneview.demo/files/ar-recordings/ar-session-20260506-153045.mp4
```

Drop the file into any messaging tool, GitHub issue, or shared drive. The receiver places it under their own `ar-recordings` directory (or any path you hand to `playbackDataset`) and replays it.
