package io.github.sceneview.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global demo-app settings that govern "showcase" behaviour vs deterministic "QA" behaviour.
 *
 * By default every demo that can do so plays a smooth camera-orbit idle animation, runs GLB
 * skeletal animations, and shows polished transitions — the "wow" state a first-time user
 * sees when browsing the sample app. QA tests flip [qaMode] to `true` so those animations
 * go dormant and screenshot captures are deterministic (same frame every time).
 *
 * Wire a demo control to [qaMode] when it owns an auto-rotate / auto-orbit / idle
 * animation that would otherwise produce different pixels on every run.
 *
 * **Turning QA mode on at runtime** — long-press the top-bar title of any demo (see
 * [DemoScaffold]) to open the settings drawer with a toggle. Instrumentation tests that
 * need determinism can set it programmatically before launching a demo:
 *
 * ```kotlin
 * DemoSettings.qaMode = true
 * ```
 */
object DemoSettings {
    /**
     * `true` = deterministic mode (no auto-orbit, no idle camera drift, no implicit
     * motion). `false` = full "wow" showcase mode. Default `false`.
     */
    var qaMode: Boolean by mutableStateOf(false)

    /**
     * Optional camera-to-model distance, in metres, the 3D demos should frame the model at
     * when they start — i.e. a zoom level. When non-null, the shared hero-orbit camera
     * ([rememberHeroOrbitCameraManipulator]) uses this value as its orbit radius instead of
     * the per-demo auto-fit distance, so a smaller value zooms in and a larger value zooms
     * out. When `null` the demo keeps its own framing.
     *
     * This exists because Maestro — the engine behind the Android device-QA harness
     * (`.maestro/android/`) — has no pinch gesture, so the flows cannot exercise 3D camera
     * zoom by touch. Wired via the `--ef camera_distance <f>` intent extra and the
     * `sceneview://demo/<id>?cameraDistance=<f>` deep-link query parameter so a Maestro flow
     * can launch a demo at a near or far framing and assert the scene reframes correctly.
     * See [issue #1571](https://github.com/sceneview/sceneview/issues/1571).
     *
     * [MainActivity] clamps the incoming value to a sane positive range
     * (`DeepLinkRouter.validateCameraDistance`) before storing it here — absent, non-finite,
     * or out-of-range values resolve to `null` (default framing, no crash).
     */
    var cameraDistance: Float? by mutableStateOf(null)

    /**
     * Optional ARCore playback file the AR Record & Playback demo should auto-load when it
     * starts. When non-null, the demo skips Mode.LIVE and enters Mode.PLAYBACK with this
     * file pre-selected — the same path a tester would take by tapping "Playback" then
     * picking the recording. Wired via the `--es ar_playback_file <path>` intent extra so
     * `ARDemoPlaybackSmokeTest` can drive deterministic replay without UiAutomator clicking
     * through the mode chips. Reset to `null` after consumption so a config change doesn't
     * re-trigger the auto-load.
     */
    var arPendingPlaybackFile: String? by mutableStateOf(null)

    /**
     * Monotonic count of ARCore session frames the AR Record & Playback demo has
     * consumed since the current [io.github.sceneview.ar.ARSceneView] mounted.
     * Incremented once per `onSessionUpdated` callback (one ARCore frame each).
     *
     * This is the **deterministic timing signal** for frame-indexed screenshot
     * regression — `ARPlaybackScreenshotTest` polls this counter and fires a
     * capture exactly when the desired frame index is reached, instead of
     * `Thread.sleep(...)` against wall-clock (which drifts with emulator load —
     * see issue [#1050](https://github.com/sceneview/sceneview/issues/1050),
     * "Why it's heavy" §2).
     *
     * Plain `Int` (not Compose state): it is written from the AR session callback
     * and read from the instrumentation thread; we deliberately do NOT want a
     * recomposition per frame. `@Volatile` guarantees the cross-thread read sees
     * the latest value. The demo resets it to `0` when a playback ARSceneView is
     * (re-)mounted so a re-run starts the frame index from a known origin.
     */
    @Volatile
    @JvmField
    var arPlaybackFrameCount: Int = 0
}
