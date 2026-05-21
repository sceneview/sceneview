package io.github.sceneview.node

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeFullyDrawnReporterOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.collision.HitResult
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Size
import io.github.sceneview.node.ViewNode.WindowManager

/**
 * A Node that can display an Android [View]
 *
 * This node contains a View for the rendering engine to render.
 *
 * Manages a [FrameLayout] that is attached directly to a [WindowManager] that other views can be
 * added and removed from.
 *
 * To render a [View], the [View] must be attached to a [WindowManager] so that it can be properly
 * drawn. This class encapsulates a [FrameLayout] that is attached to a [WindowManager] that other
 * views can be added to as children. This allows us to safely and correctly draw the [View]
 * associated with a [RenderableManager] [Entity] and a [MaterialInstance] while keeping them
 * isolated from the rest of the activities View hierarchy.
 *
 * Additionally, this manages the lifecycle of the window to help ensure that the window is
 * added/removed from the WindowManager at the appropriate times.
 *
 * @param view The 2D Android [View] that is rendered by this [ViewNode]
 * @param unlit True to disable all lights influences on the rendered view
 * @param invertFrontFaceWinding Inverts the winding order of front faces.
 * Inverting the winding order of front faces is useful when rendering mirrored reflections
 * (water, mirror surfaces, front camera in AR, etc.).
 * True to invert front faces, false otherwise
 */
class ViewNode(
    engine: Engine,
    val windowManager: WindowManager,
    private val materialLoader: MaterialLoader,
    view: View,
    unlit: Boolean = false,
    invertFrontFaceWinding: Boolean = false,
) : PlaneNode(engine = engine) {

    // Updated when the view is added to the view manager
    var pxPerUnits = 250.0f
        set(value) {
            field = value
            updateGeometrySize()
        }

    var viewSize = Size(0.0f)
        set(value) {
            field = value
            updateGeometrySize()
        }

    val layout: Layout = Layout(view.context).apply {
        addView(view)
    }

    private val surfaceTexture = SurfaceTexture(0).also { it.detachFromGLContext() }
    private val surface = Surface(surfaceTexture)

    val stream: Stream = Stream.Builder()
        .stream(surfaceTexture)
        .build(engine)

    val texture: Texture = Texture.Builder()
        .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB8)
        .build(engine)
        .apply {
            setExternalStream(engine, stream)
        }

    override var materialInstance: MaterialInstance = materialLoader.createViewInstance(viewTexture = texture,
        unlit = unlit,
        invertFrontFaceWinding = invertFrontFaceWinding
    ).also {
        setMaterialInstanceAt(0, it)
    }
        set(value) {
            // ── UAF-safe swap order ──────────────────────────────────────────
            // (1) Wire the NEW MaterialInstance to the renderable FIRST so any
            //     concurrent render frame finds a valid pointer, then
            // (2) destroy the OLD MaterialInstance.
            //
            // The previous `destroy(old) → setMaterialInstanceAt(new)` order
            // left the renderable holding a dangling MI pointer between the
            // two calls. A frame submitted in that window re-creates the same
            // class of UAF bug fixed in PR #851/#852. Mirrors the pattern
            // adopted for ViewNode lifecycle there.
            val old = field
            field = value
            setMaterialInstanceAt(0, value)
            materialLoader.destroyMaterialInstance(old)
        }

    constructor(
        engine: Engine,
        windowManager: WindowManager,
        materialLoader: MaterialLoader,
        @LayoutRes viewLayoutRes: Int,
        unlit: Boolean = false,
        invertFrontFaceWinding: Boolean = false
    ) : this(
        engine = engine,
        windowManager = windowManager,
        materialLoader = materialLoader,
        view = LayoutInflater.from(materialLoader.context).inflate(viewLayoutRes, null, false),
        unlit = unlit,
        invertFrontFaceWinding = invertFrontFaceWinding
    )

    /**
     * Set the Jetpack Compose UI content for this view.
     * Initial composition will occur when the view becomes attached to a window or when
     * createComposition is called, whichever comes first.
     *
     * @param content the themed composable.
     * E.g.
     * ```
     * MaterialTheme {
     *     // In Compose world
     *     Text("Hello Compose!")
     * }
     */
    constructor(
        engine: Engine,
        windowManager: WindowManager,
        materialLoader: MaterialLoader,
        unlit: Boolean = false,
        invertFrontFaceWinding: Boolean = false,
        content: @Composable () -> Unit
    ) : this(
        engine = engine,
        windowManager = windowManager,
        materialLoader = materialLoader,
        view = ComposeView(materialLoader.context).apply {
            // Dispose of the Composition when the view's LifecycleOwner
            // is destroyed
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        },
        unlit = unlit,
        invertFrontFaceWinding = invertFrontFaceWinding
    )

    fun updateGeometrySize() {
        updateGeometry(size = viewSize / pxPerUnits)
    }

    override fun onAddedToScene(scene: Scene) {
        super.onAddedToScene(scene)

        windowManager.addView(layout)
    }

    override fun onRemovedFromScene(scene: Scene) {
        super.onRemovedFromScene(scene)

        windowManager.removeView(layout)
    }

    override fun onTouchEvent(e: MotionEvent, hitResult: HitResult): Boolean {
        return super.onTouchEvent(e, hitResult)
    }

    override fun destroy() {
        windowManager.removeView(layout)
        // Capture MI before super.destroy() removes the renderable component (after which
        // getMaterialInstanceAt would fail). Order: renderable (via super) → MI → texture → stream.
        // The texture/stream destroys are frame-deferred via EngineDestroyQueue so Filament has
        // reclaimed the MaterialInstance before the external Texture it was bound to is freed —
        // destroying it eagerly can race that reclamation, mirroring the ImageNode crash that
        // sceneview/sceneview#874 fixes. The queue keeps the FIFO texture-before-stream order.
        val mi = materialInstance
        super.destroy()
        materialLoader.destroyMaterialInstance(mi)
        EngineDestroyQueue.of(engine).apply {
            enqueueTexture(texture)
            enqueueStream(stream)
        }
    }

    /**
     * Used to render an Android view to a native open GL texture that can then be rendered by
     * Filament.
     *
     * To correctly draw a hardware accelerated animated view to a surface texture, the view MUST be
     * attached to a window and drawn to a real DisplayListCanvas, which is a hidden class.
     * To achieve this, the following is done:
     *
     *  - Attach [Layout] to the [WindowManager].
     *  - Override dispatchDraw.
     *  - Call super.dispatchDraw with the real DisplayListCanvas
     *  - Draw the clear color the DisplayListCanvas so that it isn't visible on screen.
     *  - Draw the view to the SurfaceTexture every frame. This must be done every frame, because
     *  the view will not be marked as dirty when child views are animating when hardware
     *  accelerated.
     */
    inner class Layout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)

            // Only called when we first get View size
            surfaceTexture.setDefaultBufferSize(width, height)

        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)

            viewSize = Size(width.toFloat(), height.toFloat())
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (!isAttachedToWindow) return

            // Sanity that the surface is valid.
            val viewSurface = surface.takeIf { it.isValid } ?: return
            val surfaceCanvas = viewSurface.lockCanvas(null)
            surfaceCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            super.dispatchDraw(surfaceCanvas)
            viewSurface.unlockCanvasAndPost(surfaceCanvas)
        }
    }

    class WindowManager(context: Context) {

        private val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        private var destroyed = false

        // The owner View we are currently waiting on to become attached to a window so we can
        // retry the off-screen attach. Non-null only between a failed `resume()` and the moment
        // the owner View finally attaches (or `pause()`/`destroy()` cancels the wait).
        private var pendingAttachOwner: View? = null

        // Re-runs the off-screen attach the instant the owner View becomes attached to a window.
        // This is the fix for sceneview/sceneview#984: on a background → foreground cycle the
        // owner SurfaceView/TextureView re-attaches *after* `onResume` fires, so the `post`ed
        // attach in `resume()` runs while the owner is still detached and would otherwise be
        // silently dropped — leaving the ViewNode as a black rectangle until the next process
        // restart.
        private val onOwnerAttachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!destroyed) tryAttachingView()
            }

            override fun onViewDetachedFromWindow(v: View) {
                // Owner window is gone — drop the off-screen window so it is not leaked, and keep
                // waiting on this owner for the next attach.
                tryDetachingView()
            }
        }

        val layout by lazy {
            FrameLayout(context).also {
                context.findActivity()?.let { activity ->
                    it.setViewTreeLifecycleOwner(activity)
                    it.setViewTreeSavedStateRegistryOwner(activity)
                    it.setViewTreeViewModelStoreOwner(activity)
                    it.setViewTreeFullyDrawnReporterOwner(activity)
                    it.setViewTreeOnBackPressedDispatcherOwner(activity)
                }
            }
        }

        fun addView(view: View) = layout.addView(view)
        fun addView(view: View, params: FrameLayout.LayoutParams) = layout.addView(view, params)

        fun removeView(view: View) = layout.removeView(view)

        /**
         * An owner View can only be added to the WindowManager after the activity has finished
         * resuming **and** the owner View itself is attached to a window.
         *
         * If the owner View is not yet attached when we try (common on a background → foreground
         * cycle, where the SurfaceView re-attaches after `onResume`), we register an
         * [View.OnAttachStateChangeListener] so the attach is retried automatically the moment the
         * owner View becomes attached, instead of being silently dropped (sceneview/sceneview#984).
         */
        fun resume(ownerView: View) {
            if (destroyed) return
            // A ownerView can only be added to the WindowManager after the activity has finished resuming.
            // Therefore, we must use post to ensure that the window is only added after resume is finished.
            ownerView.post {
                // Recheck after the post: destroy() may have run while we were queued — without this
                // guard the layout would be re-attached to the system WindowManager *after* it was
                // explicitly torn down, leaking the window for the lifetime of the process.
                if (destroyed) return@post
                if (ownerView.isAttachedToWindow) {
                    clearPendingAttach()
                    tryAttachingView()
                } else {
                    // Owner not attached yet — wait for it instead of dropping the attach.
                    awaitOwnerAttach(ownerView)
                }
            }
        }

        /**
         * The [layout] must be removed from the [windowManager] before the activity is destroyed, or
         * the window will be leaked. Therefore we add/remove the ownerView in resume/pause.
         */
        fun pause() {
            clearPendingAttach()
            tryDetachingView()
        }

        fun destroy() {
            destroyed = true
            clearPendingAttach()
            tryDetachingView()
        }

        /** Registers [onOwnerAttachListener] on [ownerView] so the attach retries once it attaches. */
        private fun awaitOwnerAttach(ownerView: View) {
            if (pendingAttachOwner === ownerView) return
            clearPendingAttach()
            pendingAttachOwner = ownerView
            ownerView.addOnAttachStateChangeListener(onOwnerAttachListener)
        }

        /** Stops waiting on the pending owner View, if any. */
        private fun clearPendingAttach() {
            pendingAttachOwner?.removeOnAttachStateChangeListener(onOwnerAttachListener)
            pendingAttachOwner = null
        }

        private fun tryAttachingView() {
            if (destroyed || layout.parent != null) return
            try {
                windowManager.addView(layout, LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.TYPE_APPLICATION_PANEL,
                    LayoutParams.FLAG_NOT_FOCUSABLE
                            or LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            or LayoutParams.FLAG_NOT_TOUCHABLE
                            or LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    title = "ViewNodeWindowManager"
                })
                // Attached successfully — stop waiting on any pending owner View.
                clearPendingAttach()
            } catch (t: Throwable) {
                // Attach failed despite the owner View being attached (e.g. a transient bad
                // window token). This is the rare retry path, hence Log.w rather than Log.e:
                // the attach is retried on the next resume()/owner-attach callback rather than
                // leaving the ViewNode permanently black.
                Log.w(
                    "ViewNode",
                    "ViewNode layout attach to system WindowManager failed — will retry on next resume",
                    t
                )
            }
        }


        private fun tryDetachingView() {
            try {
                if (layout.parent != null) {
                    windowManager.removeView(layout)
                }
            } catch (t: Throwable) {
                Log.e(
                    "ViewNode",
                    "Failed to detach ViewNode layout from system WindowManager — window may leak",
                    t
                )
            }
        }
    }
}


internal fun Context.findActivity(): ComponentActivity? {
    return generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<ComponentActivity>()
        .firstOrNull()
}
