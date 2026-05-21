package io.github.sceneview

import android.content.Context
import android.graphics.PixelFormat
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
        // `view.blendMode = BlendMode.TRANSLUCENT` set in `Scene.kt`.
        uiHelper.isOpaque = isOpaque

        uiHelper.renderCallback = makeRendererCallback(viewHeight = { surfaceView.height })
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
        uiHelper.isOpaque = isOpaque  // Pair with view.blendMode in Scene.kt (#1077).

        uiHelper.renderCallback = makeRendererCallback(viewHeight = { textureView.height })
        uiHelper.attachTo(textureView)

        onTouch?.let { dispatch ->
            textureView.setOnTouchListener { _, event -> dispatch(event); true }
        }
    }

    // ── Render frame ────────────────────────────────────────────────────────────────────────────

    /**
     * Presents a single frame if a swap chain is available.
     *
     * Call this from a `withFrameNanos` block. The [onBeforeRender] callback is invoked
     * after the swap chain check but before `beginFrame`, giving the caller a chance to
     * run per-frame logic (model loading, node updates, camera manipulator, AR frame, etc.).
     *
     * @param frameTimeNanos The choreographer timestamp for this frame.
     * @param onBeforeRender Pre-render callback; skipped if no swap chain is ready.
     */
    fun renderFrame(frameTimeNanos: Long, onBeforeRender: () -> Unit) {
        val sc = swapChainRef.get() ?: return

        onBeforeRender()

        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }

        // Destroy GPU resources whose grace period has elapsed. Runs after endFrame on the main
        // (render) thread so Filament has reclaimed any MaterialInstance the texture was bound to —
        // see EngineDestroyQueue (sceneview/sceneview#874). Driven here rather than from a
        // Choreographer callback so it advances in lock-step with real rendered frames, and stops
        // the moment the surface (and thus the render loop) goes away.
        EngineDestroyQueue.of(engine).drain()
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
    private fun makeRendererCallback(viewHeight: () -> Int) = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            // Create a new swap chain for the surface; destroy the old one if any.
            swapChainRef.getAndSet(
                engine.createSwapChain(surface, uiHelper.swapChainFlags)
            )?.let { engine.destroySwapChain(it) }

            displayHelper?.let { dh ->
                display?.let { d -> dh.attach(renderer, d) }
            }

            onSurfaceReady?.invoke(viewHeight)
        }

        override fun onDetachedFromSurface() {
            onSurfaceDestroyed?.invoke()
            swapChainRef.getAndSet(null)?.let { engine.destroySwapChain(it) }
            engine.flushAndWait()
            displayHelper?.detach()
        }

        override fun onResized(width: Int, height: Int) {
            applyResize(width, height)
            engine.drainFramePipeline()
        }
    }
}
