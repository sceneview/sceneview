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
import io.github.sceneview.math.worldToLocalDirection
import io.github.sceneview.math.worldToLocalPosition
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
 * ## The rendered view IS interactive
 *
 * A [ViewNode] renders its [View] into a texture; it does not put that view on screen, so Android
 * never dispatches touches to it (the hosting window is attached with `FLAG_NOT_TOUCHABLE`).
 * Instead the node forwards the events the scene's picking ray lands on it: the world hit point is
 * converted to a view pixel and the whole `DOWN → MOVE → UP` stream is dispatched into the
 * embedded hierarchy, so `Button.onClick`, press states, ripples and inner scrolling all work
 * (#2845).
 *
 * The stream follows Android's touch-target rule: once the embedded view consumes the `DOWN` it
 * owns the gesture — the scene gesture detectors and the camera manipulator do not see it — and a
 * pointer dragged off the quad gets an `ACTION_CANCEL` instead of a stuck press. An event the
 * embedded view does **not** consume falls through to the scene untouched, so picking a
 * non-interactive [ViewNode] still works:
 *
 * ```kotlin
 * SceneView(
 *     onGestureListener = rememberOnGestureListener(
 *         onSingleTapUp = { _, node -> if (node is ViewNode) doSomething() }
 *     ),
 *     // …
 * )
 * ```
 *
 * Set [isTouchForwardingEnabled] to `false` to opt out and always get the scene-level behaviour.
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
    private val invertFrontFaceWinding: Boolean = false,
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

    /**
     * Whether picked touch events are forwarded into the embedded [View] hierarchy (#2845).
     *
     * `true` (default) makes the rendered content behave like a real view: a `Button` inside it
     * clicks, ripples and press states animate, an inner list scrolls — and any gesture the view
     * consumes is **not** seen by the scene gesture detectors or the camera manipulator, exactly
     * like a view on screen.
     *
     * Set it to `false` for a purely decorative overlay whose quad must never steal a gesture: the
     * node then only reports hits through the usual picking path
     * (`onGestureListener` / `Node.onTouch`) — the behaviour of every release before #2845.
     */
    var isTouchForwardingEnabled: Boolean = true

    private val touchForwarder = ViewTouchForwarder(layout)

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
        // The collider follows the geometry on its own: `updateGeometry` reaches
        // `RenderableNode.setGeometry`, which re-derives `collisionShape` from the new AABB
        // (#3194). ViewNode needed an explicit `updateCollisionShape()` here while that refresh
        // was scoped to this one call site (#2845); it is now redundant, and removing it also
        // removes a second AABB read per resize.
        //
        // Why this mattered most for ViewNode: the quad is sized from the measured view, so a
        // stale `collisionShape` stayed at `Plane.DEFAULT_SIZE`'s 1 × 1 × 0 forever and
        // `CollisionSystem.hitTest` only ever reported a hit on the central 1 × 1 unit square. A
        // 410 × 420 px card at the default 250 `pxPerUnits` spans 1.64 × 1.68 units and lost its
        // outer ~30% margin — usually where the buttons are. Harmless while a ViewNode was only
        // pickable; not once it forwards touches.
        //
        // An app that assigns `collisionShape` by hand still opts out of the automatic refresh,
        // which the unconditional call here did not respect.
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

    /**
     * Forwards the picked event into the embedded view hierarchy before the scene sees it (#2845).
     *
     * Falls back to the regular [Node] behaviour when forwarding is disabled, when the quad or the
     * view is not measured yet, or when the embedded view simply does not want the gesture — so a
     * non-interactive [ViewNode] keeps behaving as a plain pickable node.
     *
     * Also gated on [isVisible]: hiding a node only drops its render layer, it does not detach its
     * collider, and this module's [io.github.sceneview.collision.CollisionSystem.hitTest] filters on
     * `isHittable` alone (unlike the KMP `SceneGraph.hitTest`, which also tests `isVisible`). Without
     * this guard a tap on apparently empty space would drive a real click on a control the user
     * cannot see.
     *
     * Either gate flipping to `false` mid-gesture closes a stream that is already open, here rather
     * than in [onCapturedTouchEvent] — the captured path in `SceneView`'s touch dispatcher only
     * runs for events whose ray *misses* the node, so a gate flipped while the finger is still on
     * the quad would otherwise leave the embedded view pressed forever and leak the rest of the
     * gesture, mid-stream, to detectors that never saw its `DOWN`.
     *
     * The mapping is **facing-aware** (#3329): the material is double-sided and un-mirrors its UVs
     * on the back face, so a quad orbited past edge-on keeps reading correctly on screen and the
     * touch mapping has to mirror with it. The facing comes from the picking ray — carried on the
     * [HitResult] by [io.github.sceneview.collision.CollisionSystem.hitTest] — not from the camera,
     * which this callback does not receive. Before that, every touch on a quad turned away from the
     * viewer landed on the horizontally mirrored pixel: on a spinning card the button under the
     * finger simply did nothing for half of every turn.
     */
    override fun onTouchEvent(e: MotionEvent, hitResult: HitResult): Boolean {
        if (isTouchForwardingEnabled && isVisible) {
            val worldToLocal = worldToLocal
            val point = viewTouchPixels(
                localPosition = worldToLocalPosition(hitResult.getWorldPosition(), worldToLocal),
                center = geometry.center,
                size = geometry.size,
                widthPx = layout.width,
                heightPx = layout.height,
                mirrorX = shouldMirrorX(
                    invertFrontFaceWinding = invertFrontFaceWinding,
                    localRayDirection = worldToLocalDirection(
                        hitResult.getWorldDirection(),
                        worldToLocal
                    )
                )
            )
            if (point != null && touchForwarder.onHit(e, point.x, point.y)) {
                return true
            }
        } else if (touchForwarder.onExit(e) && e.actionMasked != MotionEvent.ACTION_DOWN) {
            // Take the close, drop the verdict on a DOWN. `onExit` treats `ACTION_DOWN` as
            // terminal and returns true for it, but a fresh gesture belongs to whatever it hits —
            // consuming it here would hide the DOWN from the gesture detectors and the camera
            // manipulator while its MOVE/UP still reach them, which is the very stream-without-a-
            // DOWN this branch exists to prevent. `SceneView`'s captured path drops the same
            // return value for the same reason.
            return true
        }
        return super.onTouchEvent(e, hitResult)
    }

    /**
     * Continues a stream the embedded view captured on `ACTION_DOWN` once the picking ray leaves
     * the quad: the view gets a single `ACTION_CANCEL` (no stuck press, no phantom click) and the
     * rest of the gesture is swallowed.
     *
     * Reached only for events whose ray **misses** this node — that is the condition under which
     * `SceneView`'s dispatcher routes to the captured node. A gate flipped while the finger is
     * still *on* the quad is therefore not this method's job; [onTouchEvent] closes that stream
     * itself.
     */
    override fun onCapturedTouchEvent(e: MotionEvent): Boolean = touchForwarder.onExit(e)

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
         * If the owner View is not yet attached when `resume()` is called (common on a
         * background → foreground cycle, where the SurfaceView re-attaches after `onResume`), we
         * register an [View.OnAttachStateChangeListener] **synchronously** so the attach is retried
         * automatically the moment the owner View becomes attached, instead of being silently
         * dropped (sceneview/sceneview#984).
         *
         * Arming the listener synchronously — rather than from inside `ownerView.post { … }` — is
         * deliberate. `View.post()` on a *detached* View does not dispatch to a Handler: it queues
         * the Runnable in the View's `HandlerActionQueue`, which is flushed only once the View
         * attaches. By then `isAttachedToWindow` is already `true`, so a posted block would always
         * take the direct-attach branch and the #984 retry listener would never actually arm for a
         * detached owner — leaving the documented retry path as dead code.
         */
        fun resume(ownerView: View) {
            if (destroyed) return
            if (ownerView.isAttachedToWindow) {
                // Owner already attached. The window can only be added to the system WindowManager
                // after the activity has finished resuming, so defer the attach via post().
                ownerView.post {
                    // Recheck after the post: destroy() may have run while we were queued — without
                    // this guard the layout would be re-attached to the system WindowManager *after*
                    // it was explicitly torn down, leaking the window for the lifetime of the process.
                    if (destroyed) return@post
                    if (ownerView.isAttachedToWindow) {
                        clearPendingAttach()
                        tryAttachingView()
                    } else {
                        // Owner detached between resume() and now — wait for it to re-attach.
                        awaitOwnerAttach(ownerView)
                    }
                }
            } else {
                // Owner not attached yet — arm the retry listener now so the attach fires the
                // moment the owner attaches, instead of being silently dropped (#984).
                awaitOwnerAttach(ownerView)
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
