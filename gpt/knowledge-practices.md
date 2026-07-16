<!--
  GENERATED FILE — DO NOT EDIT.
  Source of truth: /llms.txt  (SceneView 4.22.0)
  Regenerate:      node tools/generate-gpt-knowledge.js
  Drift is caught in CI (ci.yml -> repo-hygiene). Edit llms.txt instead.
  See issue #2724.
-->

# SceneView — Best Practices & Troubleshooting

> Threading, performance, error handling, debugging, recording, and media.
> Auto-generated from `llms.txt` (SceneView 4.22.0). This is a slice of the machine-readable API reference — the same content an AI reads to generate SceneView code.

## Render Quality

```kotlin
enum class RenderQuality { Cinematic, Default, Performance }
```

| Preset | Shadows | SSAO | Bloom | MSAA | DoF | Use case |
|---|---|---|---|---|---|---|
| `Cinematic` | high-res VSM | ON | ON | 4x | ON | hero product viewer, marketing demos |
| `Default` | medium PCF | ON | ON | 2x | OFF | most apps — shipped as the SceneView default |
| `Performance` | low PCF | OFF | OFF | 1x | OFF | low-end devices, AR overlays, battery-sensitive flows |

```kotlin
SceneView(renderQuality = RenderQuality.Cinematic) { /* … */ }

// AR: renderQuality is NULLABLE (#2524). Omit it (null) to keep the camera-feed-tuned defaults;
// pass a preset to opt in (e.g. Performance for battery-sensitive overlays):
ARSceneView(renderQuality = RenderQuality.Performance) { /* … */ }

// Equivalent imperative path on a shared AR view (also still supported):
import io.github.sceneview.applyRenderQuality
val arView = rememberARView(engine)
arView.applyRenderQuality(RenderQuality.Performance)
ARSceneView(view = arView) { /* … */ }

// Imperative — applies to any Filament View directly:
view.applyRenderQuality(RenderQuality.Cinematic)
```

The extension `View.applyRenderQuality(RenderQuality)` configures `shadowOptions`, `ambientOcclusionOptions`, `bloomOptions`, `dynamicResolutionOptions`, `antiAliasing`, and `multiSampleAntiAliasingOptions` in one call — useful when you manage the `View` yourself.

---

## Threading Rules

- Filament JNI calls must run on the **main thread**.
- `rememberModelInstance` is safe — reads bytes on IO, creates Filament objects on Main.
- `modelLoader.createModel*` and `modelLoader.createModelInstance*` (synchronous) — **main thread only**.
- `materialLoader.createColorInstance(...)` — **main thread only**. Safe inside `remember { }` in SceneScope.
- `environmentLoader.createHDREnvironment(...)` — **main thread only**.
- Use `modelLoader.loadModelInstanceAsync(...)` or `suspend fun loadModelInstance(...)` for imperative async code.
- Inside `SceneView { }` composable scope, you are on the main thread — safe for all Filament calls.

---

## Performance

- **Frame budget**: 16.6ms at 60fps. Target 12ms for headroom.
- **Cold start**: ~120ms (3D), ~350ms (AR, ARCore init dominates).
- **APK size**: +3.2MB (sceneview), +5.1MB (sceneview + arsceneview).
- **Memory**: ~25MB empty 3D scene, ~45MB empty AR scene.
- **Triangle budget**: <100K per model, <200K total scene (mid-tier devices).
- **Textures**: use KTX2 with Basis Universal, max 2048x2048 on mobile. **WebP is NOT supported on Android**: Filament's Android prebuilt ships `gltfio` with WebP compiled out, so glTF textures encoded as `EXT_texture_webp` (`image/webp`) fail with "Missing texture provider for image/webp" and render untextured. Re-encode such models' textures to PNG/JPEG or KTX2 before loading on Android (#2305). PNG/JPEG/KTX2 are decoded natively.
- **Draw calls**: aim for <100 per frame. Merge static geometry in Blender before export.
- **Lights**: 1 directional + IBL covers most cases. Max 2-3 additional point/spot lights.
- **Post-processing**: Bloom ~1ms, SSAO ~2-3ms. Disable SSAO on low-end devices.
- **Compose**: use `remember` for Position/Rotation/Scale — no allocations in composition body.
- **Engine**: create one `rememberEngine()` at app level, share across all scenes.
- **AR**: disable `planeRenderer` after object placement to reduce overdraw.
- **Rerun bridge**: adds ~0.5ms when active. Gate with `BuildConfig.DEBUG`.
- **Hot paths**: never call a decomposing/allocating getter inside `onFrame`/`onSessionUpdated`. Set whole `node.transform = …` once (don't write `position`/`quaternion`/`scale` one at a time → recompose + drift); use `mat4.copyColumnsInto(scratch)` not `toColumnsFloatArray()`; use the TRS-tuple `slerp(...)` overload; `worldPosition`/`worldQuaternion` are cached so per-frame reads are fine. AR: don't read `pose.transform` per frame (allocates) — use `pose.toTransform(scratch)` or a node setter. See docs/docs/performance.md § Hot Paths & Allocation-Free APIs (audit #2263).
- See full guide: docs/docs/performance.md

---

## Error Handling

| Problem | Cause | Fix |
|---------|-------|-----|
| Model not showing | `rememberModelInstance` returns null | Always null-check: `model?.let { ModelNode(...) }` |
| Black screen | No environment / no light | Add `mainLightNode` and `environment` |
| Crash on background thread | Filament JNI on wrong thread | Use `rememberModelInstance` or `Dispatchers.Main` |
| AR not starting | Missing CAMERA permission or ARCore | Handle `onSessionFailure`, check `ArCoreApk.checkAvailability()` |
| Model too big/small | Model units mismatch | Use `scaleToUnits` parameter |
| Oversaturated AR camera | Wrong tone mapper | Use `rememberARView(engine)` (Filmic tone mapper — round-trips the camera background, #1434) |
| Crash on empty bounding box | Filament 1.70+ enforcement | SceneView auto-sanitizes; update to latest version |
| Material crash on dispose | Entity still in scene | SceneView handles cleanup order automatically |

### `ARSessionFailure` — typed AR error taxonomy (#1759)

`onSessionFailed: (Exception) -> Unit` lumps all 25 ARCore exception subclasses into a single `Exception`. Apps that want to retry vs fallback vs "install ARCore" vs "open Settings" need exhaustive `when` matching — string-matching on exception messages is fragile. Wire `onSessionFailure: (ARSessionFailure) -> Unit` instead (it fires alongside `onSessionFailed`, so legacy callers keep working).

Use an **exhaustive `when` with NO `else` branch** — that is the whole point of the sealed hierarchy. The compiler then forces you to revisit this site the day SceneView adds a new subtype (#1759). An `else ->` fallback silently defeats that contract.

```kotlin
ARSceneView(
    onSessionFailure = { failure ->
        when (failure) {
            // Install / availability
            is ARSessionFailure.ArCoreNotInstalled       -> showInstallArCoreCta()
            is ARSessionFailure.UserDeclinedInstall      -> showRetryCta()
            is ARSessionFailure.ApkTooOld                -> showUpdateArCoreCta()
            is ARSessionFailure.SdkTooOld                -> reportToCrashlytics(failure.cause)
            is ARSessionFailure.DeviceNotCompatible      -> disableArEntryPoints()
            // Permissions
            is ARSessionFailure.FineLocationMissing      -> requestLocationPermission()
            is ARSessionFailure.GooglePlayServicesLocationLibraryNotLinked ->
                reportToCrashlytics(failure.cause)
            // Camera
            is ARSessionFailure.CameraNotAvailable       -> showCameraBusyCta()
            is ARSessionFailure.TextureNotSet            -> reportToCrashlytics(failure.cause)
            is ARSessionFailure.MissingGlContext         -> reportToCrashlytics(failure.cause)
            // Quota / runtime
            is ARSessionFailure.ResourceExhausted        -> showCloudQuotaErrorCta()
            is ARSessionFailure.DeadlineExceeded         -> showRetryCta()
            is ARSessionFailure.Fatal                    -> recreateSession()
            // Cloud Anchor
            is ARSessionFailure.CloudAnchorsNotConfigured -> showCloudKeySetupHelp()
            is ARSessionFailure.AnchorNotSupportedForHosting -> showMoveDeviceCta()
            // Augmented image
            is ARSessionFailure.ImageInsufficientQuality -> showBetterReferenceImageCta()
            // Recording / playback
            is ARSessionFailure.RecordingFailed          -> showRecordingErrorCta()
            is ARSessionFailure.PlaybackFailed           -> showPlaybackErrorCta()
            is ARSessionFailure.DataInvalidFormat        -> showPlaybackErrorCta()
            is ARSessionFailure.DataUnsupportedVersion   -> showPlaybackErrorCta()
            is ARSessionFailure.MetadataNotFound         -> showPlaybackErrorCta()
            // Session / config
            is ARSessionFailure.SessionUnsupported       -> disableArEntryPoints()
            is ARSessionFailure.SessionPaused            -> reportToCrashlytics(failure.cause)
            is ARSessionFailure.SessionNotPaused         -> reportToCrashlytics(failure.cause)
            is ARSessionFailure.NotTracking              -> showMoveDeviceCta()
            is ARSessionFailure.NotYetAvailable          -> retryNextFrame()
            is ARSessionFailure.UnsupportedConfiguration -> reportToCrashlytics(failure.cause)
            // Catch-all (forward compat — new ARCore exceptions land here until SceneView
            // adds a typed subclass, at which point the compiler flags every `when` site)
            is ARSessionFailure.Other                    -> reportToCrashlytics(failure.cause)
        }
    }
) { /* … */ }
```

For apps that route many subtypes to the same action, extract a tiny helper that returns a coarse category and `when` on that — still exhaustive, no `else ->`:

```kotlin
private enum class ArFailureAction { Install, Retry, Permission, Settings, Report }

private fun ARSessionFailure.action(): ArFailureAction = when (this) {
    is ARSessionFailure.ArCoreNotInstalled, is ARSessionFailure.ApkTooOld -> ArFailureAction.Install
    is ARSessionFailure.UserDeclinedInstall, is ARSessionFailure.DeadlineExceeded,
    is ARSessionFailure.NotTracking, is ARSessionFailure.NotYetAvailable -> ArFailureAction.Retry
    is ARSessionFailure.FineLocationMissing -> ArFailureAction.Permission
    is ARSessionFailure.CameraNotAvailable -> ArFailureAction.Settings
    is ARSessionFailure.SdkTooOld, is ARSessionFailure.DeviceNotCompatible,
    is ARSessionFailure.GooglePlayServicesLocationLibraryNotLinked,
    is ARSessionFailure.TextureNotSet, is ARSessionFailure.MissingGlContext,
    is ARSessionFailure.ResourceExhausted, is ARSessionFailure.Fatal,
    is ARSessionFailure.CloudAnchorsNotConfigured, is ARSessionFailure.AnchorNotSupportedForHosting,
    is ARSessionFailure.ImageInsufficientQuality,
    is ARSessionFailure.RecordingFailed, is ARSessionFailure.PlaybackFailed,
    is ARSessionFailure.DataInvalidFormat, is ARSessionFailure.DataUnsupportedVersion,
    is ARSessionFailure.MetadataNotFound,
    is ARSessionFailure.SessionUnsupported, is ARSessionFailure.SessionPaused,
    is ARSessionFailure.SessionNotPaused, is ARSessionFailure.UnsupportedConfiguration,
    is ARSessionFailure.Other -> ArFailureAction.Report
}
```

Avoid `else -> …` in the `when (failure)` site: it silently catches future SceneView subtypes and you lose the compile-time alarm that justifies the sealed hierarchy in the first place.

Subtypes are organised into Install/availability, Permissions, Camera, Quota/runtime, Cloud Anchor, Augmented image, Recording/playback, Session/config, and an `Other` catch-all for forward compatibility with future ARCore exception classes. Every subtype preserves the original `Exception` on `.cause`, so apps that need the raw stack trace can still get at it.

### ARConfigDowngrade — observe silently-downgraded session capabilities (#2096)

Several `Config` knobs are support-gated inside `ARSceneView`'s session pipeline: an unsupported
request (e.g. `depthMode = AUTOMATIC` on a device without motion-stereo depth, or a
`sessionCameraConfig` selector returning a config not in `getSupportedCameraConfigs()`) is quietly
replaced with a safe fallback instead of crashing the session. Wire `onConfigDowngraded` to *know*
when that happened — hide a UI affordance, warn the user, or report to analytics.

```kotlin
public sealed class ARConfigDowngrade {
    // Requested DepthMode unsupported — effective is always DISABLED.
    data class DepthMode(val requested: Config.DepthMode, val effective: Config.DepthMode) : ARConfigDowngrade()
    // sessionCameraConfig selector not honoured — ARCore kept its stock config.
    data class CameraConfig(val requested: com.google.ar.core.CameraConfig,
                            val effective: com.google.ar.core.CameraConfig) : ARConfigDowngrade()
}

ARSceneView(
    depthMode = Config.DepthMode.AUTOMATIC,
    onConfigDowngraded = { downgrade ->
        when (downgrade) {
            is ARConfigDowngrade.DepthMode -> occlusionAvailable = false  // no depth on this device
            is ARConfigDowngrade.CameraConfig -> log("running at ${downgrade.effective.imageSize}")
        }
    },
) { /* … */ }
```

**Per-node error states** that already ship:
- `CloudAnchorNode.onHosted: (cloudAnchorId: String?, state: CloudAnchorState) -> Unit` — receives the specific `CloudAnchorState` (`ERROR_NOT_AUTHORIZED`, `ERROR_RESOURCE_EXHAUSTED`, `ERROR_HOSTING_SERVICE_UNAVAILABLE`, …) instead of a binary `isError`. Pair with the resolve overload's same signature.
- `AugmentedImageNode.trackingMethod: TrackingMethod` + `onTrackingMethodChanged: ((TrackingMethod) -> Unit)?` — observes `FULL_TRACKING` vs `LAST_KNOWN_POSE` transitions for image tracking robustness.
- `Config.addAugmentedImage` throws `ImageInsufficientQualityException` — caught by SceneView and routed through `ARSessionFailure.ImageInsufficientQuality` to your `onSessionFailure` callback.

---

## AR Debug — Rerun.io integration

Stream an ARCore or ARKit session to the [Rerun](https://rerun.io) viewer for scrub-and-replay debugging. Camera pose, detected planes, point cloud, anchors, and hit results appear on a 3D timeline you can scrub frame-by-frame.

**When to use:** debugging flaky plane detection, tracking drift, anchor instability, or comparing two AR sessions side by side. **Dev-time only** — gate with `BuildConfig.DEBUG` in release builds.

### Two modes

- **Live (default)** — sidecar spawns the Rerun viewer, you debug interactively.
- **Save & share** — sidecar writes a `.rrd` file. Drop it onto https://sceneview.github.io/rerun/ to view in-place, or re-host (R2, GitHub release, gist) and open `https://sceneview.github.io/rerun/?url=<encoded>` to share with remote teammates. Lets you attach a fully-replayable session to a bug report.

### Architecture

```
┌──────────────┐   TCP JSON-lines  ┌──────────────────┐  rerun-sdk    ┌──────────────────┐
│  RerunBridge │ ─────────────────▶│ Python sidecar   │ ─── live ────▶│ Rerun viewer     │
│ (Kt or Swift)│   one obj/line \n │ (rerun-bridge.py)│ ─── save ────▶│ .rrd file        │
└──────────────┘   control ack ◀── └──────────────────┘   on demand   └──────────────────┘
                                                                              │
                                                                       upload to R2/etc
                                                                              │
                                                              https://sceneview.github.io/rerun/
```

Same wire format on Android and iOS. A single sidecar handles both platforms.

### Save & share flow

1. Run sidecar in save mode: `python rerun-bridge.py --save`
2. In the app, tap **Save & Share** while streaming. The bridge sends a `{"type":"control","cmd":"save_now"}` line; the sidecar flushes a `.rrd` and replies with `{"type":"control","ack":"saved","path":"…","viewerUrl":"…","events":N}`.
3. Open https://sceneview.github.io/rerun/ — when no session is loaded the page shows a drop-zone (drag the `.rrd` onto it to render in-place) and a QR code that opens the AR Rerun demo on a phone for users who don't have one to start from.
4. To share with a remote teammate, re-host the `.rrd` on a public URL (Cloudflare R2, GitHub release asset, S3, gist) and send them `https://sceneview.github.io/rerun/?url=<encoded-public-url>`.

The Kotlin API surface for step 2:

```kotlin
bridge.requestSaveAndShare { result: RerunBridge.ShareResult ->
    if (result.success) {
        // result.path = "/home/dev/.sceneview/recordings/2026-05-06_23-30-12.rrd"
        // result.viewerUrl = "https://sceneview.github.io/rerun/?url=file%3A%2F%2F…"
        // result.events = 1234
    } else {
        // result.reason explains why (e.g. "sidecar started in live mode; relaunch with --save")
    }
}
```

`callback` fires on the bridge's I/O thread — marshal to your UI thread before touching state.

### Android — `rememberRerunBridge`

```kotlin
import io.github.sceneview.ar.rerun.rememberRerunBridge

@Composable
fun ARDebugScreen() {
    val bridge = rememberRerunBridge(
        host = "127.0.0.1",          // paired with `adb reverse tcp:9876 tcp:9876`
        port = 9876,
        rateHz = 10,                  // throttle; 0 = unlimited
        enabled = BuildConfig.DEBUG   // no-op in release builds
    )

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        onSessionUpdated = { session, frame ->
            bridge.logFrame(session, frame)
        }
    )
}
```

`logFrame` logs camera pose + planes + point cloud in one call, honours `rateHz`. Finer-grained methods are available if you want to emit events selectively: `logCameraPose(Pose, Long)`, `logPlanes(Collection<Plane>, Long)`, `logPointCloud(PointCloud, Long)`, `logAnchors(Collection<Anchor>, Long)`, `logHitResult(HitResult, Long)`.

**Tier-S "wow" events** (call from your own code, not auto-emitted by `logFrame`):

```kotlin
// Polyline through every accumulated camera position — flat [x,y,z,…] buffer.
bridge.logCameraTrail(positions = trailFloats, timestampNanos = frame.timestamp)

// Generic scalar timeseries — graphs in the Rerun timeline panel.
bridge.logScalar(value = trackingQuality, entity = "world/camera/tracking_quality",
                 timestampNanos = frame.timestamp)
```

The Python sidecar maps `camera_trail` to `rr.LineStrips3D` and `scalar` to `rr.Scalars`. Same surface in Swift: `bridge.logCameraTrail(positions:timestampNanos:)` and `bridge.logScalar(_:entity:timestampNanos:)`.

**Threading:** the bridge owns a private `Dispatchers.IO` + `SupervisorJob` scope and a `Channel.CONFLATED` outbox. Every `log*` call is non-blocking — the newest event overwrites any pending one (drop-on-backpressure). Filament's render thread is never blocked.

### iOS — `RerunBridge` + new `ARSceneView.onFrame`

```swift
import SceneViewSwift
import ARKit

struct ARDebugView: View {
    @StateObject private var bridge = RerunBridge(
        host: "192.168.1.42",  // your Mac's LAN IP
        port: RerunBridge.defaultPort,
        rateHz: 10
    )

    var body: some View {
        ARSceneView()
            .onFrame { frame, _ in
                bridge.logFrame(frame)
            }
            .onAppear { bridge.connect() }
            .onDisappear { bridge.disconnect() }
    }
}
```

`RerunBridge` is an `ObservableObject` with `@Published eventCount` you can bind to a SwiftUI status overlay. Uses `Network.framework` `NWConnection` on a dedicated utility queue — no blocking on the ARKit delegate.

### Python sidecar (dev machine)

```bash
pip install rerun-sdk numpy
python samples/android-demo/tools/rerun-bridge.py
# Rerun viewer window opens automatically via rr.init(spawn=True)

# On the device:
adb reverse tcp:9876 tcp:9876                       # Android, USB-tethered
# or connect iPhone and Mac to the same LAN and point bridge at Mac's IP
```

The sidecar maps each JSON event to the matching Rerun archetype:
- `camera_pose` → `rr.Transform3D`
- `plane` → `rr.LineStrips3D` (closed world-space polygon)
- `point_cloud` → `rr.Points3D`
- `anchor` → `rr.Transform3D`
- `hit_result` → `rr.Points3D` (single highlighted point)

### Wire format (JSON-lines over TCP)

```json
{"t":123456789,"type":"camera_pose","entity":"world/camera","translation":[x,y,z],"quaternion":[x,y,z,w]}
{"t":123456789,"type":"plane","entity":"world/planes/<id>","kind":"horizontal_upward","polygon":[[x,y,z],...]}
{"t":123456789,"type":"point_cloud","entity":"world/points","positions":[[x,y,z],...],"confidences":[f,...]}
{"t":123456789,"type":"anchor","entity":"world/anchors/<id>","translation":[x,y,z],"quaternion":[x,y,z,w]}
{"t":123456789,"type":"hit_result","entity":"world/hits/<id>","translation":[x,y,z],"distance":f}
```

Non-finite floats (NaN/Infinity) are clamped to `0` so every line stays parseable. Byte-identical output from Kotlin and Swift — enforced by 24 golden-string tests (12 per platform).

### Generating the boilerplate with AI

The [`rerun-3d-mcp`](https://www.npmjs.com/package/rerun-3d-mcp) MCP server generates the integration code for you. Install once:

```bash
npx rerun-3d-mcp
```

Then ask Claude / Cursor / any MCP client:

> Generate an Android AR scene that logs camera pose, planes, and point cloud to Rerun at 10 Hz, and give me the matching Python sidecar.

The MCP exposes 5 tools: `setup_rerun_project`, `generate_ar_logger`, `generate_python_sidecar`, `embed_web_viewer`, `explain_concept`.

### Limits

- **Dev-time only.** Gate with `BuildConfig.DEBUG` / `#if DEBUG`. The bridge is safe to leave wired in release (`setEnabled(false)` short-circuits the hot path), but the socket attempt alone wastes battery.
- **No Rerun on visionOS yet.** `RerunBridge` is iOS-only because it reads from `ARFrame`, which isn't part of the visionOS API surface.
- **10 Hz default.** Higher rates are possible but the sidecar becomes a bottleneck beyond ~30 Hz on a typical laptop.

---

## Record the scene to MP4 — Surface mirroring (no MediaProjection)

`SurfaceMirrorer` (`io.github.sceneview.utils.SurfaceMirrorer`, #2626) copies every rendered frame GPU-side onto additional `Surface`s. Attach a `MediaRecorder` input surface and you get a **shareable MP4 of exactly what the scene renders** — in AR, the camera feed and the virtual content composited. No MediaProjection: no system consent dialog, no `mediaProjection` foreground service (and no Play Console FGS declaration), and no overlay UI in the frame — only the 3D/AR scene is mirrored, never your Compose UI.

Works identically on `SceneView` (3D) and `ARSceneView` (AR) via the `surfaceMirrorer` parameter.

```kotlin
import io.github.sceneview.rememberSurfaceMirrorer

@Composable
fun RecordableARScreen() {
    val context = LocalContext.current
    val surfaceMirrorer = rememberSurfaceMirrorer()
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }

    ARSceneView(  // or SceneView(...) — same parameter
        modifier = Modifier.fillMaxSize(),
        surfaceMirrorer = surfaceMirrorer,
    ) { /* nodes */ }

    Button(onClick = {
        recorder?.let { active ->                        // ── Stop ──
            surfaceMirrorer.stopMirroring(active.surface)
            active.stop(); active.release()
            recorder = null
        } ?: run {                                       // ── Start ──
            val outputFile = File(context.getExternalFilesDir(null), "scene_${System.currentTimeMillis()}.mp4")
            recorder = MediaRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(1280, 720)
                setVideoEncodingBitRate(8_000_000)
                setVideoFrameRate(30)
                setOutputFile(outputFile.absolutePath)
                prepare()
                surfaceMirrorer.startMirroring(surface, width = 1280, height = 720)
                start()
            }
        }
    }) { Text(if (recorder != null) "Stop recording" else "Record") }
}
```

Key facts:
- `startMirroring(surface, left = 0, bottom = 0, width = null, height = null)` — `width`/`height` are the destination rectangle on the recording surface; pass the `MediaRecorder` video size. `null` defaults to the scene view's size (correct when the surface matches it). The frame is letterboxed to preserve aspect ratio.
- `stopMirroring(surface)` — idempotent; call before `recorder.stop()`. Main thread (Filament rule). `startMirroring` is JNI-free and safe from any thread.
- Multiple surfaces can be mirrored at once (e.g. recorder + live preview/RTMP encoder).
- The mirror copy runs only while frames render — pausing the lifecycle pauses the feed.
- Audio: add `setAudioSource(...)` / `setAudioEncoder(...)` to the `MediaRecorder` for mic audio (needs `RECORD_AUDIO` permission).
- Don't confuse with **AR Recording & Playback** below: `ARRecorder` records an ARCore *dataset* MP4 (camera + sensor tracks, for session replay/debugging — virtual content is NOT in the video). `SurfaceMirrorer` records the *rendered* scene — the shareable video with virtual content composited.

Sample: `VideoRecordingDemo` (Android demo registry id `video-recording`) — Record/Stop button over a rotating model, saves an MP4 to app files.

---

## AR Recording & Playback — debug without a phone

ARCore captures the **entire** AR session (camera frames, IMU, planes, depth, anchors, light estimation) into an MP4. SceneView wraps this with [`ARRecorder`](arsceneview/src/main/java/io/github/sceneview/ar/recording/ARRecorder.kt) for recording and a `playbackDataset` parameter on `ARSceneView` for replay. The replayed session re-runs as if you were there: hit-tests return the same results, planes appear at the same moment, anchors track at the same poses.

### Why this matters

- **Iterate at the desk.** Record an outdoor session once; replay it any time without holding a phone in front of the laptop.
- **Reproduce bugs deterministically.** Share the MP4 with a teammate — they replay your exact session, including the lighting, motion, and surfaces you saw.
- **CI tests.** Bundle a recording as a test fixture; assert that `onSessionUpdated` reports the expected planes/anchors.
- **Pair with Rerun.** Record → replay with the [Rerun bridge](#ar-debug--rerunio-integration) attached → inspect every frame in 3D.

### Record a session

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
        // Stateless side-channel — pass the session per frame, exactly like
        // RerunBridge.logFrame. The recorder publishes the latest reference
        // through an AtomicReference (cheap), and the same Session instance
        // survives Activity pause/resume.
        onSessionUpdated = { session, _ -> recorder.recordFrame(session) }
    )
}
```

`ARRecorder.state`, `recorder.errorMessage`, and `recorder.recordingFile` are all `MutableState`-backed under the hood — read them from a `@Composable` and Compose recomposes / `LaunchedEffect` re-keys when they change. The composable auto-stops on dispose. After `stop()`, `recorder.recordingFile` keeps pointing at the last MP4 so the caller can list / share / replay it.

`ARRecorder.start(file, recordingRotation = null, recordingResolution = null)` — ARCore writes the **CPU image stream** into the MP4, whose stock default is the device's *lowest*-resolution config (often 640×480 on Pixels). `ARSceneView`'s `sessionCameraConfig` now defaults to `highestResolutionCameraConfig`, so every recording captures at the full back-camera resolution without opt-in. To force a specific size, pass `recorder.start(file, recordingResolution = android.util.Size(1920, 1080))` — the recorder applies the closest supported BACK-facing, 30 FPS `CameraConfig` before recording starts.

### Auto-stop after N seconds

Drive `stop()` from a `LaunchedEffect` keyed on `recorder.state` so you don't block the UI thread:

```kotlin
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

LaunchedEffect(recorder.state) {
    if (recorder.state == ARRecorder.State.RECORDING) {
        delay(30_000L)
        recorder.stop()
    }
}
// after the LaunchedEffect fires, the file is at recorder.recordingFile
```

### Replay a session

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

ARCore replays at the original capture rate. The session looks **identical** to live: planes appear, anchors lock, depth occlusion works, gestures still hit-test correctly. The playback param is a plain `java.io.File` — no FileProvider, no scoped-storage gymnastics.

### Export to public Downloads

`ARRecorder.exportToDownloads()` copies a recorded `.mp4` from app-private storage into the user's public **Downloads/SceneView/** folder so it can be picked up by the system file manager, transferred to a desk machine, or attached to a bug report.

```kotlin
import io.github.sceneview.ar.recording.ARRecorder

// On Android Q+ uses MediaStore so no WRITE_EXTERNAL_STORAGE permission is required.
// On Android < Q falls back to a direct File copy into /sdcard/Download/SceneView/.
val uri: Uri? = ARRecorder.exportToDownloads(
    context,
    recording = file,                    // the .mp4 returned by ARRecorder.stop()
    displayName = "ar-session.mp4",      // optional, defaults to recording.name
    subdirectory = "SceneView",          // optional, defaults to "SceneView"
)
```

Returns a content `Uri` on success, or `null` if the copy fails. Throws `FileNotFoundException` if the source file does not exist. Pair with `record-once / play-many` workflows where the same recording is replayed across multiple devices for repeated regression checks.

### Limits

- **Camera permission still required for playback.** ARCore opens the camera even when replaying a dataset; users see no live preview but the permission gate fires regardless. Run your normal permission flow.
- **Emulator: playback works, recording does not.** ARCore Recording requires a real camera + IMU. Use `getExternalFilesDir("ar-recordings")` to store recordings made on a device, then replay them anywhere.
- **Same device class.** Playback works best on the device that recorded it, or a similar one. Heavily different sensor sets (e.g. phone → tablet) may degrade tracking.
- **MP4 file size.** A few tens of MB per minute depending on resolution. Store under `getExternalFilesDir("ar-recordings")` (no permission required, app-private).
- **Switching live ↔ playback** requires a full `ARSceneView` recreation — wrap in `key(playbackDataset) { ARSceneView(...) }` so Compose discards and rebuilds the session. Mutating the param after first composition is silently ignored (the value is snapshotted at session creation).
- **Recording while in playback mode is rejected.** `ARRecorder.start()` returns `false` and surfaces an error message if the session is currently bound to a playback dataset.
- **`attach(newSession)` mid-RECORDING is a pointer swap, not a graceful handoff.** If the underlying `Session` instance changes while a recording is in flight (e.g. the user navigates away and back, or `key(...)` triggers a full ARSceneView teardown), the old session never receives `stopRecording()` — the in-flight MP4 is left dangling. `stop()` on the new session is a no-op for the orphaned recording. Mitigation: call `stop()` BEFORE any UI action that might dispose the ARSceneView; or hook `onSessionCreated` to detect the new-session event and stop+restart deliberately. Note that ARCore keeps the same `Session` instance across plain Activity pause/resume — you only need to worry about swap on composable disposal.

### Recording/playback completeness (#1770)

Beyond `start` / `stop`, ARCore exposes four more state signals SceneView wires through:

```kotlin
// 1. PlaybackStatus as Compose State — observe NONE / OK / FINISHED / IO_ERROR.
//    The FINISHED transition is the only public end-of-replay signal — use it to loop,
//    rewind, or advance to the next dataset.
val playback by rememberARPlaybackStatus(arSession)
LaunchedEffect(playback) {
    if (playback == PlaybackStatus.FINISHED) loopCount++
}

// 2. RecordingStatus.IO_ERROR — disk-full / storage-detached / permission-revoked
//    mid-recording. ARRecorder.State.IO_ERROR is distinct from State.ERROR so apps
//    can offer a "clear cache and retry" CTA without overloading the generic error.
when (recorder.state) {
    ARRecorder.State.IO_ERROR -> Text("Storage full — free up space and retry")
    ARRecorder.State.ERROR    -> Text("Recording failed: ${recorder.errorMessage}")
    else                      -> /* … */
}

// 3. Custom data tracks — annotate the MP4 with ML detections, ground truth boxes,
//    or any side-channel data. Wraps RecordingConfig.addTrack + Frame.recordTrackData.
val detectionsTrack = remember {
    recorder.addTrack(UUID.fromString("…"), "application/text") // BEFORE start()
}
recorder.start(file)
// In onSessionUpdated:
val payload = ByteBuffer.wrap(detectionJson.toByteArray(Charsets.UTF_8))
recorder.recordTrack(detectionsTrack, frame, payload)
// On playback (replay side):
val tracks = frame.getUpdatedTrackData(detectionsTrack.uuid)

// 4. Scoped-storage playback Uri — Android 10+ apps can replay an MP4 picked via the
//    Storage Access Framework without copying it into app-private storage first.
ARSceneView(playbackDatasetUri = pickedUri) { /* … */ }
//    Mutually exclusive with playbackDataset: File? — setting both throws
//    IllegalArgumentException at session creation.
```

See [`ARRecorder.kt`](arsceneview/src/main/java/io/github/sceneview/ar/recording/ARRecorder.kt).

### AR Record interpretation — quantify a replayed session (#1441)

Replay alone re-renders the camera feed; it does not tell you *whether the session
tracked well*. `ARRecordInterpreter` is the analysis half: feed it every replayed frame
and it folds the camera pose + plane trackables into an `ARRecordInterpretation` — a
quantified, CI-assertable tracking-quality report. This is the deterministic, desk-side
counterpart to on-device QA: record a hard tracking stress-case once, then replay +
interpret it on every CI run and assert the metrics never regress.

```kotlin
import io.github.sceneview.ar.recording.rememberARRecordInterpreter

val interpreter = rememberARRecordInterpreter()
ARSceneView(
    playbackDataset = recordedDataset,
    onSessionUpdated = { session, frame -> interpreter.ingest(session, frame) },
    content = { /* … */ }
)
// When rememberARPlaybackStatus(arSession) reports PlaybackStatus.FINISHED:
val report = interpreter.interpretation
// report.frameCount / trackedFrameCount / trackedFrameRatio (0.0..1.0)
// report.durationSeconds
// report.trajectoryLengthMeters   — total camera path length over tracked frames
// report.trajectoryExtentMeters   — diagonal of the bounding box of the path
// report.failureReasonFrameCounts — Map<TrackingFailureReason, Int> of lost frames
// report.horizontalPlaneCount / verticalPlaneCount / planeCount / planeAreaMeters2
```

- `ingest(session, frame)` is the stateless side-channel shape used by `ARRecorder.recordFrame`
  and `RerunBridge.logFrame` — call it from `onSessionUpdated`. It does no Filament JNI work,
  no extra ARCore acquire calls, and no allocation beyond the immutable snapshot.
- A lost frame breaks the trajectory so a relocalization teleport is **not** counted as travel.
- Planes are de-subsumed and counted once across frames; `planeAreaMeters2` uses each plane's
  largest observed extent so a growing plane is not double-counted.
- Call `interpreter.reset()` to interpret another dataset with the same instance.
- The pure folding core (`ARRecordInterpreterCore`, ARCore-free) is JVM-unit-tested directly.

See [`ARRecordInterpreter.kt`](arsceneview/src/main/java/io/github/sceneview/ar/recording/ARRecordInterpreter.kt).

### iOS — `ARRecorder` (record-only via ReplayKit, v4.3.0+)

iOS gets the record half via `ReplayKit.RPScreenRecorder`. There is no replay half because ARKit has no deterministic playback API (see `cheatsheet-ios.md` parity table, #1036). The MP4 plays back in the Photos app or any QuickTime player; it cannot be fed back into `ARSession`.

```swift
import SceneViewSwift

struct MyARScreen: View {
    @StateObject private var recorder = ARRecorder()

    var body: some View {
        ZStack {
            ARSceneView(planeDetection: .horizontal)
            VStack {
                Spacer()
                Button(recorder.isRecording ? "Stop" : "Record") {
                    Task {
                        if recorder.isRecording {
                            let url = try await recorder.stopRecording()
                            // url is an MP4 in NSTemporaryDirectory();
                            // pass to ShareLink / PHPhotoLibrary to keep.
                        } else {
                            try await recorder.startRecording()
                        }
                    }
                }
            }
        }
    }
}
```

`@MainActor`-isolated API: `startRecording() async throws`, `stopRecording(outputURL: URL?) async throws -> URL`. State is `@Published` so SwiftUI views observing the `@StateObject` recompose on `.idle / .recording / .error(message)` transitions. `isRecording` mirrors `RPScreenRecorder.shared().isRecording`.

**iOS limits (different from Android):**

- **Screen capture only.** `RPScreenRecorder` records pixels, not session state. No IMU / depth / anchors are persisted in the MP4 — the clip is a video, not a dataset.
- **No deterministic replay.** ARKit's `ARSession` cannot consume the recorded MP4 as input. If you need replay-driven testing on iOS, use the Rerun integration (`RerunBridge` — section above) to log frames + scrub-and-replay in the Rerun viewer.
- **Simulator support is limited.** `RPScreenRecorder.shared().isAvailable` returns false on Xcode simulator < 17.4 for the non-microphone code paths. Test on a real iPhone or iPad.
- **App-foreground constraint.** Recording is bound to the recording app. Backgrounding the app or letting the OS show a recording-blocking system view (e.g. a system alert) will end the recording.
- **No permission needed at start time** — the user gets a one-time consent sheet on `startRecording()` the first time the app uses the API; subsequent calls go straight through. If the user dismisses the sheet, the call throws `ARRecorderError.permissionDenied` (mapped from `RPRecordingErrorCode.userDeclined`, code -5803).
- **Auto-stop at ~15 min** — Apple's docs note that long recordings may auto-stop around the 15-minute mark on some iOS versions to bound memory pressure. There is no API to extend this cap; restart the recording if needed.
- **No `recordingRotation` parameter** — Android takes a `recordingRotation: Int` so the MP4 plays back upright when recorded in landscape. `RPScreenRecorder` always captures at the current screen orientation, so no per-call rotation knob is needed — the resulting clip already orients correctly for the way the user held the device.
- **Output filename is `.mov`** (QuickTime container), not `.mp4`. Most players (Photos, QuickTime, VLC, browsers) accept both transparently.

**Save to Photos (v4.3.1+)** — `let localID = try await ARRecorder.saveToPhotoLibrary(url)` wraps `PHPhotoLibrary.shared().performChanges` so the recorded `.mov` lands in the user's Photos library, and returns the saved asset's `PHAsset.localIdentifier` (`String?`) so callers can deep-link to or later resolve the recording — parity with Android's `ARRecorder.exportToDownloads()` `Uri?` return. The host app's `Info.plist` MUST declare `NSPhotoLibraryAddUsageDescription` or iOS crashes the app on the first call. Throws `ARRecorderError.photoLibraryDenied` on user denial, `.photoLibrarySaveFailed` on `performChanges` error.

---

## AR Image Stabilization (EIS)

ARCore 1.37+ exposes **Electronic Image Stabilization** as a single `Config` flag. When enabled, ARCore smooths the camera background image so handheld micro-shake doesn't translate into perceived judder. The virtual content stays anchored at the same world pose either way — only the camera image is stabilized. Useful for handheld AR, panoramic captures, and any video-style recording where jitter is distracting.

```kotlin
ARSceneView(
    sessionConfiguration = { session, config ->
        if (session.isImageStabilizationModeSupported(Config.ImageStabilizationMode.EIS)) {
            config.imageStabilizationMode = Config.ImageStabilizationMode.EIS
        }
        // ... your other config flags
    }
)
```

- **Not all devices support EIS.** Always gate with `session.isImageStabilizationModeSupported(Config.ImageStabilizationMode.EIS)` — calling `setImageStabilizationMode(EIS)` on an unsupported device throws.
- **Back-camera only.** EIS is not supported with `Session.Feature.FRONT_CAMERA`. The `isImageStabilizationModeSupported` check returns `false` for front-camera sessions, so the gate above already covers selfie configurations — but be aware that toggling EIS in a front-camera demo will be a no-op.
- **Toggling at runtime works** via `session.configure {}`, but the camera background can briefly stutter while the stabilization buffers re-prime. If you expose an in-app toggle, prefer remounting via `key(eisEnabled) { ARSceneView(...) }` for a clean swap.
- **Interactive demo** at [`samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARImageStabilizationDemo.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/demos/ARImageStabilizationDemo.kt).

---

## Haptic Feedback

`io.github.sceneview.haptic.SceneViewHaptic` (Android) and
`SceneViewSwift.SceneViewHaptic` (iOS) wrap the platform vibration APIs
behind a **semantic** preset table so cross-platform code paths stay
symmetric. Seven presets cover the common gesture / AR confirmation
vocabulary (`light`, `medium`, `heavy`, `success`, `warning`, `error`,
`selection`) plus a `continuous(intensity, durationMs)` /
`pattern(events)` escape hatch for richer feedback. The Web library
exposes the same surface via `sceneview.haptic.*`, mapped to
`navigator.vibrate(...)`.

**Phase 1** ships the library API + sample-app migration; the
**NodeGesture** modifiers (`Modifier.tapHaptic(...)`, drag-tick
haptics) and **AR-event** modifiers (haptic on anchor placed, tracking
degraded, plane detected) land in phase 2 / phase 3 of #1901.

### Android (Jetpack Compose)

```kotlin
import io.github.sceneview.haptic.rememberHapticFeedback

@Composable
fun PlaceAnchorButton(onPlace: () -> Unit) {
    val haptic = rememberHapticFeedback()
    Button(onClick = {
        haptic.medium()    // confirm the placement
        onPlace()
    }) {
        Text("Place")
    }
}

@Composable
fun ARAnchorStatus(isStable: Boolean) {
    val haptic = rememberHapticFeedback()
    LaunchedEffect(isStable) {
        if (isStable) haptic.success() else haptic.warning()
    }
}
```

**Permission policy.** The `sceneview` library does **NOT** auto-merge
`android.permission.VIBRATE` — consumer apps opt in. Add to your
`AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.VIBRATE" />
```

When the permission is missing (or the device has no vibrator), every
method becomes a silent no-op + one `Log.d("SceneViewHaptic", …)` line
on the first call. The API never throws.

### iOS (SwiftUI)

```swift
import SceneViewSwift

struct PlaceAnchorButton: View {
    @StateObject private var haptic = SceneViewHaptic()

    var body: some View {
        Button("Place") {
            haptic.medium()
        }
    }
}

// or via the shared singleton:
SceneViewHaptic.shared.success()
```

iOS uses `UIImpactFeedbackGenerator` / `UISelectionFeedbackGenerator` /
`UINotificationFeedbackGenerator` for the presets, and `CHHapticEngine`
for `continuous(intensity:durationMs:)` / `pattern(_:)`. The Core Haptics
escape hatches gracefully fall back to the preset generators on
devices without Core Haptics support — they never throw.

`continuous` takes a millisecond `Int` on every platform —
`continuous(intensity:durationMs:)` on iOS, `continuous(intensity, durationMs)`
on Android and Web — so cross-platform callers pass the same value:

```swift
haptic.continuous(intensity: 0.8, durationMs: 200)
```

### Web (plain JS)

```js
sceneview.haptic.light();
sceneview.haptic.success();
sceneview.haptic.continuous(1.0, 200);  // intensity ignored on Web
sceneview.haptic.pattern([10, 50, 20]);
```

Maps to `window.navigator.vibrate(...)`. `continuous(intensity, durationMs)`
maps to `navigator.vibrate(durationMs)` — the Web Vibration API exposes
durations only, so `intensity` is accepted for cross-platform parity but
ignored at runtime. Desktop browsers / Safari iOS that don't expose
`navigator.vibrate` are silently no-ops.

### Preset mapping table

| Preset | Android (API 29+) | iOS | Web |
|---|---|---|---|
| `light()` | `EFFECT_CLICK` | `UIImpactFeedbackGenerator(.light)` | `vibrate(10)` |
| `medium()` | `EFFECT_TICK` | `UIImpactFeedbackGenerator(.medium)` | `vibrate(20)` |
| `heavy()` | `EFFECT_HEAVY_CLICK` | `UIImpactFeedbackGenerator(.heavy)` | `vibrate(40)` |
| `success()` | `EFFECT_DOUBLE_CLICK` | `UINotificationFeedbackGenerator.success` | `vibrate([10,50,20])` |
| `warning()` | waveform `[0,30,30,30]` | `UINotificationFeedbackGenerator.warning` | `vibrate([30,30,30])` |
| `error()` | waveform `[0,50,30,50,30,50]` | `UINotificationFeedbackGenerator.error` | `vibrate([50,30,50])` |
| `selection()` | `EFFECT_TICK` | `UISelectionFeedbackGenerator.selectionChanged` | `vibrate(5)` |

Android API 24..28 fall back to short one-shots (`light` → 10 ms,
`medium`/`selection` → 20 ms, `heavy` → 40 ms) when the predefined
effects aren't available. The fallback table is encoded as part of the
public contract — see `AndroidSceneViewHaptic` and pinned in
`SceneViewHapticTest`.

---

## Spatial Audio

`SpatialAudioNode` attaches positional 3D audio to the scene graph: a sound source that sits at a world position, pans between the listener's ears, and attenuates with distance. Distance attenuation is configurable with an inverse (physically-realistic) or linear falloff curve. Available on Android, iOS, and Web with platform-native audio back-ends — this is **phase 1** of [#1900](https://github.com/sceneview/sceneview/issues/1900). In phase 1 the caller drives the listener pose from the render loop; automatic camera-tracked listening is phase 2.

### Android (Jetpack Compose)

```kotlin
SceneView(
    cameraNode = cameraNode,
    onFrame = {
        // Phase 1: drive the listener from the camera each frame. The basis is
        // (position, forward, up) — the engine derives the right vector internally.
        val pose = cameraNode.worldTransform
        setSpatialAudioListenerPose(
            position = cameraNode.worldPosition,
            forward = Position(-pose.z.x, -pose.z.y, -pose.z.z),
            up = Position(pose.y.x, pose.y.y, pose.y.z),
        )
    },
) {
    AudioListener()                                       // declares phase-1 intent
    rememberAudioSource("audio/bell.wav")?.let { source ->
        SpatialAudioNode(
            source = source,
            position = Position(z = -2f),
            falloff = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 20f),
            loop = true,
        )
    }
}
```

- Load assets with `rememberAudioSource("audio/file.wav")` — returns `null` while loading, like `rememberModelInstance`. WAV / MP3 / OGG / FLAC are supported. An `AudioSource` is a lightweight, shareable handle: each `SpatialAudioNode` builds its own `MediaPlayer` from it, so two nodes never cross-talk.
- **Phase 1 listener is caller-driven.** `AudioListener()` documents the intent but `SceneScope` does not expose the camera, so you must call `setSpatialAudioListenerPose(position, forward, up)` from a `SceneView` `onFrame` callback as shown above. Automatic camera tracking is phase 2. `AudioListenerSource.Anchor` is reserved for phase 2 and falls back to the camera in phase 1.
- `setSpatialAudioListenerPose` takes the `(position, forward, up)` basis on every platform (Android / Web).
- `AudioFalloff` has three variants: `Inverse(refDistance, maxDistance, rolloffFactor)`, `Linear(refDistance, maxDistance)`, and `None`.
- The composable's `apply` lambda exposes an `AudioController` (`play()` / `pause()` / `stop()` / `seekTo()`) for imperative control from outside the tree.
- The node reads its `position` *parameter* on recomposition — pass an animated position as Compose state (e.g. from a `withFrameNanos` loop). A parent node moved imperatively does not move the sound.

### iOS (SwiftUI)

```swift
// `spatial(named:loop:)` loads the resource with shouldLoop set for you —
// the loop flag is honoured for real (RealityKit fixes looping at load time).
let node = try await SpatialAudioNode.spatial(
    named: "bell.wav",
    falloff: .inverse(refDistance: 0.5, maxDistance: 6),
    loop: true
)
content.add(node.entity)        // attach to the scene, or to a moving entity
```

- Backed by RealityKit's spatial-audio renderer — the active camera IS the listener, so distance attenuation and panning apply automatically once the entity is added to a scene.
- Prefer `SpatialAudioNode.spatial(named:loop:)` when you need looping. The `spatial(source:)` overload takes a pre-loaded `AudioResource` and cannot change its loop configuration — load it with `AudioFileResource.Configuration(shouldLoop: true)` yourself.
- `node.play()` / `pause()` / `stop()` control playback; `play()` after `pause()` resumes from the paused position. `setFalloff(_:)` and `updateGain(forDistance:)` push curve changes.
- Apply `.audioListener(.camera)` on the view for parity with Android's `AudioListener`.

### Web (Kotlin/JS or plain JavaScript)

```kotlin
val source = loadAudioSource("audio/bell.wav")            // suspend; or loadAudioSourcePromise(...)
val node = SpatialAudioNode(
    source = source,
    position = Vec3(0f, 0f, -2f),
    falloff = AudioFalloff.Inverse(refDistance = 1f, maxDistance = 20f),
    loop = true,
)
setSpatialAudioListenerPose(cameraPos, cameraForward, cameraUp)  // call as the camera moves
```

- Backed by the Web Audio API: each node owns an `AudioBufferSourceNode → PannerNode → GainNode → destination` chain. The `PannerNode` runs in `"HRTF"` mode for binaural panning.
- `node.play()` / `pause()` / `stop()` / `seekTo(positionMs)` control playback. `setSpatialAudioListenerPose(position, forward, up)` is the same `(position, forward, up)` basis as Android.

### Platform back-ends

| Platform | Back-end | Listener (phase 1) |
|---|---|---|
| Android | Phase 1: per-node `MediaPlayer` + manual L/R pan (works on API 24+, no asset preprocessing). Phase 2: `AudioTrack` + `android.media.Spatializer` HRTF on API 33+ devices. | Caller-driven via `setSpatialAudioListenerPose` from `onFrame` |
| iOS / macOS / visionOS | RealityKit spatial audio (`SpatialAudioComponent` + `Entity.playAudio`) | Active camera (RealityKit default) |
| Web | Web Audio `PannerNode` (`panningModel = "HRTF"`, `distanceModel = "inverse"` / `"linear"`) | Caller-driven via `setSpatialAudioListenerPose` |

**Phases.** Phase 1 (this release) ships positional playback with inverse / linear falloff. The listener is **caller-driven** in phase 1 — call `setSpatialAudioListenerPose` from your render loop. Automatic camera-tracked listening and anchor-attached listeners are phase 2. Occlusion and reverb zones are phase 3. See [#1900](https://github.com/sceneview/sceneview/issues/1900).

**Interactive demos** — Android [`SpatialAudioDemo.kt`](samples/android-demo/src/main/java/io/github/sceneview/demo/demos/SpatialAudioDemo.kt), iOS [`SpatialAudioDemo.swift`](samples/ios-demo/SceneViewDemo/Views/Demos/SpatialAudioDemo.swift), and the **Spatial Audio** tab of the Web demo.

---
