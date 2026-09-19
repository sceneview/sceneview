package io.github.sceneview

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import io.github.sceneview.utils.SurfaceMirrorer
import java.util.concurrent.atomic.AtomicReference

/**
 * Encapsulates the Filament surface lifecycle and render-frame pipeline.
 *
 * Both [SceneView] (3D) and [ARSceneView][io.github.sceneview.ar.ARSceneView] (AR) share the
 * identical surface-management and frame-presentation code:
 * - [UiHelper] ↔ SurfaceView / TextureView hookup
 * - [SwapChain] creation / destruction (thread-safe via [AtomicReference])
 * - `beginFrame` → `render` → `endFrame` pipeline
 * - Viewport resize
 * - [DisplayHelper] attachment for frame pacing
 *
 * Extracting this into a standalone class removes ~120 lines of duplication between the two
 * composables and makes the render loop independently testable.
 *
 * ### Usage from a composable
 * ```kotlin
 * val sceneRenderer = remember(engine, renderer) {
 *     SceneRenderer(engine, view, renderer)
 * }
 * DisposableEffect(sceneRenderer) { onDispose { sceneRenderer.destroy() } }
 * ```
 *
 * @param engine   The Filament [Engine] that owns native resources.
 * @param view     The Filament [View] to render into.
 * @param renderer The Filament [Renderer] bound to the OS window.
 */
class SceneRenderer(
    private val engine: Engine,
    val view: View,
    private val renderer: Renderer
) {
    // ── Surface / SwapChain state ────────────────────────────────────────────────────────────────

    /** Current swap chain — set when the surface is ready, cleared when destroyed. */
    private val swapChainRef = AtomicReference<SwapChain?>(null)

    /** Filament's UiHelper that manages the native surface lifecycle. */
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    /** Display helper for frame pacing (vsync). */
    private var displayHelper: DisplayHelper? = null

    /** Display used for frame pacing — captured during surface attachment. */
    private var display: Display? = null

    /** Whether the renderer is currently attached to a surface. */
    val isAttached: Boolean get() = swapChainRef.get() != null

    /**
     * The native surface currently rendered into, kept so the cadence vote can reach it.
     * `null` on a [TextureView], which renders through a `SurfaceTexture` the view system owns —
     * there is no surface whose frame rate this library may vote on.
     */
    private val votableSurfaceRef = AtomicReference<Surface?>(null)

    /** Last frame rate voted, so an unchanged vote is not re-sent every frame. */
    private var votedFrameRate: Float = 0f

    /**
     * The highest refresh rate the current display can run at, or `null` before attachment.
     *
     * This is what [FrameRatePolicy.OnDemand] and [FrameRatePolicy.Continuous] vote for while the
     * scene is moving: asking for the panel's maximum is what makes a 120 Hz device actually run a
     * gesture at 120 Hz instead of the 60 Hz an idle-looking surface is often given.
     */
    @Suppress("DEPRECATION")
    val maxRefreshRate: Float?
        get() = display?.let { d ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d.supportedModes?.maxOfOrNull { it.refreshRate }
            } else {
                d.refreshRate
            }
        }

    // ── Surface mirroring ───────────────────────────────────────────────────────────────────────

    /**
     * Optional [SurfaceMirrorer] that mirrors every rendered frame to additional [Surface]s —
     * e.g. a [android.media.MediaRecorder] input surface for clean in-app video recording
     * (no MediaProjection consent dialog, no foreground service, no overlay UI in the frame).
     *
     * Wired automatically by the `SceneView` / `ARSceneView` composables from their
     * `surfaceMirrorer` parameter. Mirroring runs **after** the scene's `Renderer.endFrame()`:
     * each mirrored surface gets its own swap chain and its own render pass, so the live view's
     * frame is complete and presented before any mirror work starts. Wiring a mirrorer changes
     * nothing about the window swap chain — it can be attached and detached at any time.
     */
    var surfaceMirrorer: SurfaceMirrorer? = null

    // ── Resize callback ─────────────────────────────────────────────────────────────────────────

    /**
     * Called whenever the surface is resized. Consumers should update the viewport, camera
     * projection, and any AR display geometry here.
     */
    var onSurfaceResized: ((width: Int, height: Int) -> Unit)? = null

    /**
     * Called when the first surface is ready (swap chain created). Useful for one-time setup
     * like creating a [CameraGestureDetector][io.github.sceneview.gesture.CameraGestureDetector].
     *
     * @param viewHeight a lambda returning the current view height (for gesture calculations).
     */
    var onSurfaceReady: ((viewHeight: () -> Int) -> Unit)? = null

    /**
     * Called when the surface is destroyed. Useful for cleanup of gesture detectors etc.
     */
    var onSurfaceDestroyed: (() -> Unit)? = null

    // ── Surface attachment ───────────────────────────────────────────────────────────────────────

    /**
     * Attaches this renderer to a [SurfaceView].
     *
     * Creates the UiHelper callbacks, wires the touch listener, and begins swap chain management.
     *
     * @param surfaceView  The SurfaceView to render into.
     * @param isOpaque     Whether the surface is opaque (true) or translucent (false).
     * @param context      Android context for the DisplayHelper.
     * @param display      Display for frame pacing.
     * @param onTouch      Touch event dispatcher.
     */
    fun attachToSurfaceView(
        surfaceView: SurfaceView,
        isOpaque: Boolean,
        context: Context,
        display: Display,
        onTouch: ((MotionEvent) -> Unit)? = null
    ) {
        this.display = display
        this.displayHelper = DisplayHelper(context)

        if (!isOpaque) surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        // Wire UiHelper.isOpaque BEFORE `attachTo`. Without this the swap chain
        // is built with `CONFIG_DEFAULT` (opaque) regardless of the SurfaceView's
        // PixelFormat — fragments are rendered opaque + nothing under the SurfaceView
        // shows through. Pre-#1077 the only "transparency" was the α=0 skybox at
        // `SceneFactories.kt:206` which is itself rendered opaque. Pair with the
        // `view.blendMode = BlendMode.TRANSLUCENT` set in `SceneView.kt`.
        uiHelper.isOpaque = isOpaque

        uiHelper.renderCallback =
            makeRendererCallback(viewHeight = { surfaceView.height }, votable = true)
        uiHelper.attachTo(surfaceView)

        onTouch?.let { dispatch ->
            surfaceView.setOnTouchListener { _, event -> dispatch(event); true }
        }
    }

    /**
     * Attaches this renderer to a [TextureView].
     *
     * @param textureView  The TextureView to render into.
     * @param isOpaque     Whether the surface is opaque.
     * @param context      Android context for the DisplayHelper.
     * @param display      Display for frame pacing.
     * @param onTouch      Touch event dispatcher.
     */
    fun attachToTextureView(
        textureView: TextureView,
        isOpaque: Boolean,
        context: Context,
        display: Display,
        onTouch: ((MotionEvent) -> Unit)? = null
    ) {
        this.display = display
        this.displayHelper = DisplayHelper(context)

        textureView.isOpaque = isOpaque
        uiHelper.isOpaque = isOpaque  // Pair with view.blendMode in SceneView.kt (#1077).

        // `votable = false`: a TextureView draws through a SurfaceTexture owned by the view
        // system, so there is no surface of ours to vote a frame rate on.
        uiHelper.renderCallback =
            makeRendererCallback(viewHeight = { textureView.height }, votable = false)
        uiHelper.attachTo(textureView)

        onTouch?.let { dispatch ->
            textureView.setOnTouchListener { _, event -> dispatch(event); true }
        }
    }

    // ── Render frame ────────────────────────────────────────────────────────────────────────────

    /**
     * How many frames this renderer has actually **presented** — incremented after `endFrame`,
     * and never on a call that drew nothing because no swap chain was ready or because
     * `Renderer.beginFrame` refused the frame for pacing.
     *
     * [SceneView]'s render loop needs to tell "I asked for a frame" apart from "a frame reached
     * the surface" (#3109): a loop that is about to park must not clear its owed-frame debt on an
     * attempt that presented nothing, or a freshly created swap chain stays blank until something
     * unrelated wakes the loop. `internal` because it is a render-loop implementation detail, not
     * a metric — it adds no public surface and no `.api` entry.
     */
    internal var presentedFrameCount: Long = 0L
        private set

    /**
     * Presents a single frame if a swap chain is available.
     *
     * Call this from a `withFrameNanos` block. The [onBeforeRender] callback is invoked
     * after the swap chain check but before `beginFrame`, giving the caller a chance to
     * run per-frame logic (model loading, node updates, camera manipulator, AR frame, etc.).
     *
     * Returns `true` when a frame actually reached the surface, `false` when it did not: no swap
     * chain yet, [shouldPresent] declined, or `Renderer.beginFrame` refused the frame for pacing.
     * Callers use it to settle a frame debt only against a frame that really landed — settling on
     * the attempt parks with a blank surface, which is the bug #3109 is about.
     *
     * @param frameTimeNanos The choreographer timestamp for this frame.
     * @param onBeforeRender Pre-render callback; skipped if no swap chain is ready.
     * @param shouldPresent Consulted **after** [onBeforeRender] and before `beginFrame`: return
     * `false` to run the tick's CPU work without submitting anything to the GPU. This is where
     * [FrameRatePolicy.OnDemand] skips a frame, and the ordering is the whole point — the ARCore
     * session update, the async-load pump and the node ticks inside [onBeforeRender] have already
     * run, so a skipped frame costs the scene no progress, only pixels. `null` (the default) always
     * presents.
     */
    fun renderFrame(
        frameTimeNanos: Long,
        shouldPresent: (() -> Boolean)? = null,
        onBeforeRender: () -> Unit
    ): Boolean {
        val sc = swapChainRef.get() ?: return false

        onBeforeRender()

        if (shouldPresent?.invoke() == false) {
            // Still drain: the queue's grace periods are counted in ticks of real scene time, and a
            // scene that settles into on-demand would otherwise hold destroyed GPU resources until
            // something happened to wake it.
            EngineDestroyQueue.of(engine).drain()
            return false
        }

        var presented = false
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            presentedFrameCount++
            // Mirror the scene onto any mirrored surfaces (in-app video recording), as a second
            // render pass on its own renderer. Deliberately AFTER endFrame: mirroring must never
            // touch the window swap chain's frame — `Renderer.copyFrame`, which ran between
            // render() and endFrame(), left the window's colour buffer undefined on drivers that
            // discard it once it leaves the EGL draw slot, blacking both the recording and the
            // live view (#3602).
            surfaceMirrorer?.onFrame(engine, view, frameTimeNanos)
            presented = true
        }

        // Destroy GPU resources whose grace period has elapsed. Runs after endFrame on the main
        // (render) thread so Filament has reclaimed any MaterialInstance the texture was bound to —
        // see EngineDestroyQueue (sceneview/sceneview#874). Driven here rather than from a
        // Choreographer callback so it advances in lock-step with real rendered frames, and stops
        // the moment the surface (and thus the render loop) goes away.
        EngineDestroyQueue.of(engine).drain()

        return presented
    }

    // ── Frame rate vote ─────────────────────────────────────────────────────────────────────────

    /**
     * Tells the platform what refresh rate this surface would like, so a variable-refresh-rate panel
     * can follow the scene instead of guessing.
     *
     * Votes on the [SurfaceView]'s own [Surface] — never on the host `Window`. A library has no
     * business deciding the refresh rate of an app's window: the app may be showing a video, a list
     * and this scene at once, and only the app can arbitrate. `Surface.setFrameRate` is the
     * per-surface mechanism built for exactly this, and the compositor does the arbitration.
     *
     * Silently does nothing below API 30, and on a [TextureView] (no surface of our own to vote on).
     * On API 31+ the vote is `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`, so a device that would have to
     * blank the screen to switch mode simply keeps its current rate rather than flashing mid-gesture.
     *
     * @param fps the wanted frame rate, or `0f` to withdraw the vote and let the platform decide —
     * which is what an idle on-demand scene does.
     */
    fun setFrameRateVote(fps: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (votedFrameRate == fps) return
        val surface = votableSurfaceRef.get() ?: return
        // A surface can be destroyed between the last frame and this call; voting on it throws.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }.onSuccess {
            votedFrameRate = fps
        }.onFailure { e ->
            android.util.Log.w("SceneRenderer", "Frame rate vote of $fps rejected", e)
        }
    }

    // ── Viewport ────────────────────────────────────────────────────────────────────────────────

    /**
     * Updates the Filament viewport and notifies the resize callback.
     */
    fun applyResize(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        onSurfaceResized?.invoke(width, height)
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Detaches from the current surface and releases all native resources.
     *
     * Safe to call multiple times.
     */
    fun destroy() {
        // Release mirror swap chains first — they were created on the engine the mirrorer
        // bound at its first mirrored frame.
        surfaceMirrorer?.destroy()
        surfaceMirrorer = null
        votableSurfaceRef.set(null)
        votedFrameRate = 0f
        uiHelper.detach()
        swapChainRef.getAndSet(null)?.let {
            runCatching { engine.destroySwapChain(it) }
                .onFailure { e -> android.util.Log.w("SceneRenderer", "Failed to destroy SwapChain", e) }
        }
        displayHelper?.detach()
        displayHelper = null
        display = null
    }

    // ── Internal ────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the [UiHelper.RendererCallback] that manages swap chain creation, destruction
     * and resize for both SurfaceView and TextureView paths.
     */
    private fun makeRendererCallback(
        viewHeight: () -> Int,
        votable: Boolean
    ) = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            // Create a new swap chain for the surface; destroy the old one if any.
            swapChainRef.getAndSet(
                engine.createSwapChain(surface, uiHelper.swapChainFlags)
            )?.let { engine.destroySwapChain(it) }

            if (votable) votableSurfaceRef.set(surface)

            displayHelper?.let { dh ->
                display?.let { d -> dh.attach(renderer, d) }
            }

            onSurfaceReady?.invoke(viewHeight)
        }

        override fun onDetachedFromSurface() {
            // Drop the vote target before the surface goes away: a vote on a destroyed surface
            // throws, and the next surface starts from no vote at all.
            votableSurfaceRef.set(null)
            votedFrameRate = 0f
            onSurfaceDestroyed?.invoke()
            // Detach the DisplayHelper (unregisters its display-changed listener) BEFORE
            // destroying the swap chain and flushAndWait(). Destroying the surface makes an
            // adaptive-refresh display switch its refresh rate back, posting a display-changed
            // event onto the main-thread queue; flushAndWait() then blocks the main thread so
            // that queued event is delivered only after detach() has nulled the DisplayHelper's
            // renderer, NPEing inside Filament's DisplayHelper.updateDisplayInfo — a race that
            // is unfixed in the pinned Filament 1.71.5 (google/filament#9352, fixed upstream in
            // 1.71.6). Unregistering the listener first (as SceneView 2.3.0 did) closes the
            // window. (#2709)
            displayHelper?.detach()
            swapChainRef.getAndSet(null)?.let { engine.destroySwapChain(it) }
            engine.flushAndWait()
        }

        override fun onResized(width: Int, height: Int) {
            applyResize(width, height)
            engine.drainFramePipeline()
        }
    }
}
