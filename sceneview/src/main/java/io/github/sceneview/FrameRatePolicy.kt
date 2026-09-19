package io.github.sceneview

/**
 * How often a [SceneView] submits a frame to the GPU, and what refresh rate it asks the display for
 * while it is doing so.
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
     * Render only when something actually changed, then stay parked. **The default.**
     *
     * An idle scene costs no GPU frame and no periodic CPU wake-up — the loop suspends on the
     * snapshot rather than polling — and it wakes on its own for every source of change the library
     * knows about: a touch, a camera manipulator still coasting, a playing animation, a smooth
     * transform, a node added / moved / removed, a visibility, geometry or material change, an async
     * model or environment load, a surface resize, a lifecycle resume, an active `surfaceMirrorer`
     * recording. After the last change it keeps drawing for a short settle window, because Filament
     * finalises texture uploads, IBL and shadow work across several frames.
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
     */
    data object OnDemand : FrameRatePolicy

    /**
     * Render on every vsync for as long as the view is composed, and hold a display-max refresh rate
     * vote the whole time. This is what every `SceneView` did before [OnDemand] became the default.
     *
     * Reach for it when the scene is driven by something outside the library's knowledge and you do
     * not want to invalidate by hand — an external simulation writing into Filament each frame, a
     * custom `Renderer` hook, a video texture updated off-thread.
     */
    data object Continuous : FrameRatePolicy

    /**
     * Render continuously, but never faster than [fps], and vote for [fps] rather than for the
     * display maximum.
     *
     * Useful to hold a deliberate cadence — a 30 fps product turntable on a 120 Hz panel — where
     * neither "only when it changes" nor "as fast as the panel allows" is what you want.
     *
     * @param fps target frames per second. Must be strictly positive.
     */
    data class Capped(val fps: Int) : FrameRatePolicy {
        init {
            require(fps > 0) { "FrameRatePolicy.Capped requires fps > 0, was $fps" }
        }
    }
}
