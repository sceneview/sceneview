package io.github.sceneview

/**
 * How often a [SceneView] submits a frame to the GPU, and what refresh rate it asks the display for
 * while it is doing so.
 *
 * Two independent questions, two independent parts of the type:
 *
 * - **When may a frame be drawn at all?** [OnDemand] draws only when the picture changed;
 *   [Continuous] draws on every vsync.
 * - **How fast, at most?** [maxFps] is an optional ceiling on either of them. `null` — the default
 *   — means the display's own cadence.
 *
 * They compose, which is the point: `OnDemand(maxFps = 30)` is "draw only when something changed,
 * and even then never faster than 30 fps", the most commonly asked-for behaviour and one that no
 * arrangement of three flat cases could express.
 *
 * ```kotlin
 * SceneView { … }                                                  // OnDemand(), the default
 * SceneView(frameRatePolicy = FrameRatePolicy.OnDemand(maxFps = 30)) { … }
 * SceneView(frameRatePolicy = FrameRatePolicy.Continuous()) { … }   // a frame every vsync
 * SceneView(frameRatePolicy = FrameRatePolicy.Continuous(maxFps = 30)) { … }
 * ```
 *
 * The default is [OnDemand]: the library tracks what makes the picture change and draws only then.
 * This replaces the `isRendering` flag, which asked every caller to compute that answer by hand.
 *
 * Whatever the policy, the Compose frame loop itself keeps its normal cadence for everything that is
 * not a GPU submit — async loads still progress, node ticks still run. A policy only decides whether
 * `Renderer.beginFrame` / `render` / `endFrame` is reached on a given tick.
 *
 * **`ARSceneView` takes no policy.** A live camera feed is never idle, so there is nothing to park:
 * its loop runs every vsync, updates the ARCore session every vsync, and skips only the GPU submit,
 * on a tick where ARCore hands back a duplicate `Frame.timestamp` *and* nothing in the virtual scene
 * changed. Tracking, anchors and plane detection are unaffected.
 *
 * @see SceneView
 * @see rememberRenderInvalidator
 */
sealed interface FrameRatePolicy {

    /**
     * The highest frame rate this scene may present at, or `null` (the default) for the display's
     * own cadence.
     *
     * A cap can only ever be met by presenting on a **whole number of vsyncs**: nothing else exists
     * to present on. So the requested period is rounded to whole vsyncs of the display's *real*
     * period, and rounded **up**, because the promise is "never faster than [maxFps]".
     * `maxFps = 90` on a 120 Hz panel therefore runs at 60, not at 120: 90 is not reachable there,
     * and of the two reachable neighbours only 60 honours the promise.
     *
     * The cadence vote sent to the display follows it: a capped scene asks the panel for [maxFps]
     * rather than for its maximum, so a variable-refresh-rate panel can settle on a lower mode
     * instead of running at its ceiling to serve frames that will not come.
     *
     * Must be strictly positive when present — a cap of zero or less is a scene that never draws,
     * which is not what any caller means, so it is rejected at construction rather than silently
     * freezing the view.
     */
    val maxFps: Int?

    /**
     * Render only when something actually changed, then stay parked. **The default.**
     *
     * An idle scene costs no GPU frame and no periodic CPU wake-up — the loop suspends on the
     * snapshot rather than polling — and it wakes on its own for every source of change the library
     * knows about: a touch, a camera manipulator still coasting, a playing animation, a smooth
     * transform, a node added / moved / removed, a visibility, geometry or material change, an async
     * model or environment load, a video frame arriving on a `VideoNode`, a surface resize, a
     * lifecycle resume, an active `surfaceMirrorer`. After the last change it keeps drawing for a
     * short settle window, because Filament finalises texture uploads, IBL and shadow work across
     * several frames.
     *
     * What it never treats as a change is a **recomposition**: an invalidation comes from the thing
     * that changed, not from the fact that the composable ran again. Write your own screen the same
     * way — a counter that writes Compose state from `onFrame` recomposes once per presented frame
     * for no reason, and is measuring itself rather than the scene.
     *
     * Mutating Filament objects directly — a material parameter, a light property written through
     * `LightManager`, a `Skybox` or `IndirectLight` put straight on the Filament `Scene`, morph
     * weights or bone transforms through `RenderableManager`, an external `Stream`, runtime `View`
     * options — happens below the library's bookkeeping and is the one case it cannot see. Call
     * [io.github.sceneview.node.Node.requestRender] or [RenderInvalidator.requestRender] after such
     * an edit; before a `PixelCopy` / screenshot of the surface, request the frame and then wait for
     * your next `onFrame`, because the request is fire-and-forget.
     *
     * @param maxFps optional ceiling — see [FrameRatePolicy.maxFps]. With a cap, a change still
     * wakes the scene immediately; the cap only spaces out the frames drawn while it is awake.
     */
    data class OnDemand(override val maxFps: Int? = null) : FrameRatePolicy {
        init {
            requirePositiveMaxFps(maxFps)
        }
    }

    /**
     * Render on every vsync for as long as the view is composed, and hold a cadence vote the whole
     * time. This is what every `SceneView` did before [OnDemand] became the default.
     *
     * Reach for it when the scene is driven by something outside the library's knowledge and you do
     * not want to invalidate by hand — an external simulation writing into Filament each frame, a
     * custom `Renderer` hook, a texture updated off-thread.
     *
     * `Continuous(maxFps = 30)` is the deliberate-cadence case: a 30 fps product turntable on a
     * 120 Hz panel, drawn whether or not the library thinks anything changed.
     *
     * @param maxFps optional ceiling — see [FrameRatePolicy.maxFps].
     */
    data class Continuous(override val maxFps: Int? = null) : FrameRatePolicy {
        init {
            requirePositiveMaxFps(maxFps)
        }
    }
}

private fun requirePositiveMaxFps(maxFps: Int?) {
    require(maxFps == null || maxFps > 0) {
        "FrameRatePolicy.maxFps must be null (the display's cadence) or strictly positive, was $maxFps"
    }
}
