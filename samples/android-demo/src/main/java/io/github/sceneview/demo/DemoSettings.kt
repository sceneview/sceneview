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
    /** Optional bundled asset/slug handed from Model Viewer to AR Placement. */
    var requestedModel: String? by mutableStateOf(null)

    /** One-shot in-app route request used by demos that cannot own a NavController. */
    var requestedRoute: String? by mutableStateOf(null)

    /**
     * A model file another app handed to this one — "Open with SceneView" (#3482), staged into the
     * cache by [OpenedModelIntent]. Consumed once by the Model Viewer, which shows it instead of
     * the bundled hero, then nulled so a configuration change does not re-open it.
     */
    var openedModel: OpenedModel? by mutableStateOf(null)

    /**
     * The opened model's real-world size in metres — its longest dimension, measured from the
     * loaded model by the viewer and handed to AR placement.
     *
     * It matters most for the format this whole path exists for: a 3MF carries true manufacturing
     * size, so a 60 mm print placed in a room must be 60 mm, not the catalogue's default 30 cm.
     * `null` when nothing has been measured yet.
     */
    var openedModelSizeMeters: Float? by mutableStateOf(null)

    /**
     * The opened model's own file name, for the AR placement row's label.
     *
     * AR receives the file as a `file://` location, and the staged copy is deliberately named
     * `opened-model` on disk (a display name comes from whichever app shared the file, so putting
     * it on a path would mean sanitising untrusted text). The basename is therefore *not* the
     * user's file name, and a picker row reading "opened-model" tells them nothing about the file
     * they just opened. `null` when nothing has been opened.
     */
    var openedModelDisplayName: String? by mutableStateOf(null)
    /**
     * `true` = deterministic mode (no auto-orbit, no idle camera drift, no implicit
     * motion). `false` = full "wow" showcase mode. Default `false`.
     */
    var qaMode: Boolean by mutableStateOf(false)

    /**
     * QA-only synthetic camera backdrop for the AR demos (#3308). `null` (default) follows
     * [qaMode]; `--ez qa_backdrop true|false` forces it. See
     * [io.github.sceneview.demo.common.QaCameraBackdrop].
     */
    var qaBackdrop: Boolean? by mutableStateOf(null)

    /**
     * QA-only id of the visual state a demo should force itself into (`--es qa_state
     * <id>`), or `null` for the demo's real, live state (#3421, #3455).
     *
     * The generic seam for a problem `qaBackdrop` does not solve. The backdrop gives an AR
     * demo a *background* on a device without a camera; it cannot give it a *state*. Cloud
     * Anchors was the first demo whose interesting states — hosting, hosted, code expired —
     * need a live cloud service and a second phone, so none of them can be reached on
     * `emulator-5554` (#2754) and the smoke suite could only ever capture the empty first
     * frame. That is precisely the frame that looked fine while #3421 was open. Point & Ask
     * has the same problem with AICore: without it, every card past "checking" is
     * unreachable (#3407).
     *
     * One extra, per-demo vocabularies. Each demo resolves the ids it knows and ignores the
     * rest, so an unknown id leaves the demo alone rather than guessing:
     *  - Cloud Anchors: [io.github.sceneview.demo.demos.internal.cloudAnchorScenarioOf]
     *    (`placing`, `hosted`, `resolve_not_found`, …);
     *  - Point & Ask: [io.github.sceneview.demo.ai.askStepForQaOverride], ids listed in
     *    [io.github.sceneview.demo.ai.ASK_QA_STATE_IDS] (`ready`, `streaming`, `failed`, …).
     *
     * Set only through [io.github.sceneview.demo.DeepLinkRouter.resolveQaState], which
     * returns `null` unless [qaMode] is on, and only read where
     * [io.github.sceneview.demo.common.qaStateOverridesAllowed] is honoured — so a release
     * build cannot be steered into a fake state by an intent, and toggling QA mode off from
     * the sheet drops the pin at once.
     */
    var qaDemoState: String? by mutableStateOf(null)

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

    /**
     * Optional 0-based tab index a consolidated (segmented-button) demo should pre-select
     * when it starts, instead of its default first tab. Set at launch from the alias that
     * opened it (e.g. `shape` → the Shape tab of `custom-geometry`) or an explicit
     * `--es tab <i>` extra / `?tab=<id|index>` deep-link query, resolved by
     * [DeepLinkRouter.resolveInitialTab] (#2315).
     *
     * Consumed exactly once via [consumeInitialTab] in the demo's `remember { … }`
     * initializer, then cleared so a later in-app navigation to another consolidated demo
     * isn't affected. An out-of-range value is clamped to the default tab by
     * [initialDemoMode] — never a crash. Plain `var` (not Compose state): it is read once at
     * composition entry and written back to `null` in the same block, so observing it would
     * only invite a read-then-write-in-composition snapshot warning for no benefit.
     */
    @JvmField
    var initialTab: Int? = null

    /**
     * Returns the pending [initialTab] and clears it, so the launch-time pre-selection
     * applies to exactly one demo and a subsequent navigation falls back to the default tab.
     */
    fun consumeInitialTab(): Int? {
        val value = initialTab
        initialTab = null
        return value
    }
}

/**
 * Resolves the one-shot launch-time initial tab ([DemoSettings.initialTab], consumed here)
 * into one of a consolidated demo's segmented [entries], clamped: an absent or out-of-range
 * index falls back to [default] (the demo's normal first tab). This is the single seam every
 * consolidated demo uses so alias / `?tab=` pre-selection (#2315) stays uniform and a bad
 * index can never crash a demo.
 *
 * Call once, inside the demo's `remember { mutableStateOf(initialDemoMode(...)) }`
 * initializer — `remember` runs the block exactly once per composition entry, so the pending
 * value is consumed exactly once.
 */
fun <T> initialDemoMode(entries: List<T>, default: T): T {
    val index = DemoSettings.consumeInitialTab() ?: return default
    return entries.getOrNull(index) ?: default
}
