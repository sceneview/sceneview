package io.github.sceneview

/**
 * How often a [SceneView] / `ARSceneView` submits a frame to the GPU, and what refresh rate it asks
 * the display for while it is doing so.
 *
 * The default is [OnDemand]: the library tracks what makes the picture change and draws only then.
 * This replaces the `isRendering` flag, which asked every caller to compute that answer by hand.
 *
 * Whatever the policy, the Compose frame loop itself keeps its normal cadence for everything that is
 * not a GPU submit — the ARCore session is still updated every vsync, async loads still progress,
 * node ticks still run. A policy only decides whether `Renderer.beginFrame` / `render` / `endFrame`
 * is reached on a given tick.
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
     * transform, a node added / moved / removed, an async model or environment load, a surface
     * resize, a lifecycle resume, an active `surfaceMirrorer` recording, or a new ARCore camera
     * image. After the last change it keeps drawing for a short settle window, because Filament
     * finalises texture uploads, IBL and shadow work across several frames.
     *
     * Mutating Filament objects directly — a material parameter, a light intensity, a
     * [io.github.sceneview.loaders.MaterialLoader] instance — happens below the library's
     * bookkeeping and is the one case it cannot see. Call
     * [io.github.sceneview.node.Node.requestRender] or [RenderInvalidator.requestRender] after such
     * an edit, and before a `PixelCopy` / screenshot of the surface.
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
