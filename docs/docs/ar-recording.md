---
title: AR Recording & Playback — SceneView Android
description: "Record an entire ARCore session to an MP4 and replay it deterministically with SceneView. Debug AR code at the desk, share repros with teammates, build CI fixtures."
---

# AR Recording & Playback

Capture an entire ARCore session to an MP4 — camera frames, IMU, planes, depth, anchors, light estimation — and replay it 1:1 from your desk. SceneView wraps this with `ARRecorder` for recording and a `playbackDataset` parameter on `ARSceneView` for replay.

The replayed session re-runs deterministically: hit-tests return the same results, planes appear at the same moment, anchors track at the same poses.

!!! tip "Why this matters"
    Record an outdoor session once and iterate against it without leaving your desk. Share the MP4 with a teammate to reproduce a bug exactly. Bundle a recording as a CI fixture and assert against the expected planes/anchors.

---

## Quick overview

| API | Purpose |
|---|---|
| `ARRecorder` | Lifecycle-aware wrapper around `Session.startRecording`. State: `IDLE \| RECORDING \| ERROR`. |
| `rememberARRecorder()` | Composable factory that auto-stops on dispose. |
| `ARSceneView(playbackDataset = file)` | Replay an MP4 captured by `ARRecorder`. Snapshotted at session creation. |

---

## Recipe — record a session

```kotlin
import io.github.sceneview.ar.recording.rememberARRecorder
import io.github.sceneview.ar.ARSceneView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ARRecord() {
    val recorder = rememberARRecorder()
    val context = LocalContext.current
    val outputDir = remember { context.getExternalFilesDir("ar-recordings")!! }

    Column {
        Button(onClick = {
            val name = "ar-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}.mp4"
            recorder.start(File(outputDir, name))
        }) { Text("Record") }
        Button(onClick = { recorder.stop() }) { Text("Stop") }
        Text("State: ${recorder.state}")
    }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        // Wire attach() only through onSessionUpdated. The recorder publishes the
        // latest Session via an AtomicReference (cheap). The same Session instance
        // survives Activity pause/resume; swap only happens on full composable
        // disposal (key() remount, navigation away and back), and onSessionUpdated
        // re-fires naturally on the new Session — no need to also wire onSessionCreated.
        onSessionUpdated = { session, _ -> recorder.attach(session) }
    )
}
```

(Compose imports — `androidx.compose.runtime.*`, `androidx.compose.material3.*`, `androidx.compose.ui.platform.LocalContext`, `kotlinx.coroutines.delay` — are elided for brevity.)

`ARRecorder.state`, `recorder.errorMessage`, and `recorder.recordingFile` are `MutableState`-backed; reading them in a `@Composable` triggers recomposition and `LaunchedEffect` re-keying when they change. The composable auto-stops on dispose, so leaving the screen mid-recording flushes the MP4 cleanly. After `stop()`, `recorder.recordingFile` keeps pointing at the last MP4 so the caller can list / share / replay it.

!!! warning "Emulator: recording does not work"
    ARCore Recording requires a real camera and IMU. The emulator surfaces a `RecordingFailedException` (routed to `recorder.errorMessage` and `state = ERROR`). Capture on a real device, then replay anywhere — replay works fine on the emulator.

!!! note "Where to store recordings"
    `context.getExternalFilesDir("ar-recordings")` is app-private external storage. No runtime permission is required, files are wiped on uninstall, and the directory is reachable via `adb pull` for sharing.

---

## Recipe — replay a session

```kotlin
@Composable
fun ARReplay(file: File) {
    // playbackDataset MUST be set before the session resumes — switching at runtime
    // requires a full ARSceneView remount, hence the key().
    key(file) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            playbackDataset = file
        )
    }
}
```

ARCore replays at the original capture rate. The session looks identical to live: planes appear, anchors lock, depth occlusion works, gestures still hit-test correctly. The playback parameter is a plain `java.io.File` — no FileProvider, no scoped-storage gymnastics.

!!! warning "`key()` is mandatory"
    Switching live ↔ playback requires a full `ARSceneView` recreation. Wrap the composable in `key(playbackDataset) { ARSceneView(...) }` so Compose discards and rebuilds the session. Mutating the parameter after first composition is silently ignored — the value is snapshotted at session creation.

!!! note "Camera permission still required"
    ARCore opens the camera even when replaying a dataset. The user sees no live preview, but the runtime permission gate still fires. Run your normal permission flow.

---

## API reference

- `io.github.sceneview.ar.recording.ARRecorder` — full KDoc in the [arsceneview source](https://github.com/sceneview/sceneview/tree/main/arsceneview/src/main/java/io/github/sceneview/ar/recording).
- `ARSceneView(playbackDataset: File? = null, …)` — see [API Cheatsheet](cheatsheet.md) for the complete `ARSceneView` signature.

---

## Pair with Rerun

The same MP4 can be replayed with the Rerun debug bridge attached, giving you a frame-accurate 3D scrub-and-replay view of the session. Mount `ARSceneView` in playback mode and wire `rememberRerunBridge` against `onSessionUpdated`. See [Integrations](integrations.md) for the bridge architecture and the [hosted Rerun viewer](https://sceneview.github.io/rerun/) for inspecting sessions in the browser.

---

## Limits and caveats

- **Camera permission still required for playback.** ARCore opens the camera even when replaying a dataset; users see no live preview but the permission gate fires regardless.
- **Emulator: playback works, recording does not.** ARCore Recording requires a real camera + IMU.
- **Same device class.** Playback works best on the device that recorded it, or a similar one. Heavily different sensor sets (e.g. phone → tablet) may degrade tracking.
- **MP4 file size.** Tens of MB per minute depending on resolution. Store under `getExternalFilesDir("ar-recordings")` (no permission required, app-private).
- **Recording resolution.** ARCore writes the CPU image stream into the MP4; its default config is the device's *lowest* resolution (often 640×480 on Pixels). `ARSceneView` now defaults `sessionCameraConfig` to `highestResolutionCameraConfig`, so recordings capture at the full back-camera resolution out of the box. To force a specific size, pass `recorder.start(file, recordingResolution = Size(1920, 1080))` — the recorder picks the closest supported BACK-facing 30 FPS config.
- **Switching live ↔ playback** requires a full `ARSceneView` recreation — wrap in `key(playbackDataset) { ARSceneView(...) }`.
- **Recording while in playback mode is rejected.** `ARRecorder.start()` returns `false` and surfaces an error message if the session is currently bound to a playback dataset.

!!! warning "Mid-record session swap leaks the in-flight MP4"
    `attach(newSession)` while `state == RECORDING` is a pure pointer swap — the previous session never receives `stopRecording()`, so its MP4 is left dangling. The session swap happens on full ARSceneView disposal (e.g. `key()` remount, navigating away and back) — NOT on plain Activity pause/resume, where ARCore keeps the same `Session` instance. Mitigations: call `stop()` before any UI action that might dispose the view; or hook `onSessionCreated` to detect the new-session event and decide whether to stop + restart deliberately. Tested in `ARRecorderTest.kt`.
