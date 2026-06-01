package io.github.sceneview.gesture

import android.view.MotionEvent
import com.google.android.filament.Camera
import com.google.android.filament.utils.Float2
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.distance
import com.google.android.filament.utils.mix
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.node.CameraNode

/**
 * Pan fixed version of the mostly duplicated com.google.android.filament.utils.GestureDetector
 *
 * Responds to Android touch events and manages a camera manipulator.
 * Supports one-touch orbit, two-touch pan, and pinch-to-zoom.
 *
 * Copied from
 * filament-utils-android/src/main/java/com/google/android/filament/utils/GestureDetector.kt
 */
open class CameraGestureDetector(
    private val viewHeight: () -> Int,
    var cameraManipulator: CameraManipulator?,
) {
    /**
     * ## Deprecated: Use CameraGestureDetector.CameraManipulator
     *
     * Replace `manipulator = Manipulator.Builder().build()` with
     * `cameraManipulator = CameraGestureDetector.DefaultCameraManipulator(manipulator =
     * Manipulator.Builder().build())`
     */
    @Deprecated(
        "Use CameraGestureDetector.CameraManipulator",
        ReplaceWith("CameraGestureDetector(viewHeight, createDefaultCameraManipulator(manipulator))")
    )
    constructor(
        viewHeight: () -> Int,
        manipulator: Manipulator?
    ): this(
        viewHeight,
        createDefaultCameraManipulator(manipulator)
    )

    interface CameraManipulator {
        fun setViewport(width: Int, height: Int)
        fun getTransform(): Transform
        fun grabBegin(x: Int, y: Int, strafe: Boolean)
        fun grabUpdate(x: Int, y: Int)
        fun grabEnd()
        fun scrollBegin(x: Int, y: Int, separation: Float)
        fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float)
        fun scrollEnd()
        fun update(deltaTime: Float)
    }

    /**
     * The first onTouch event will make the first manipulator build. So you can change the camera
     * position before any user gesture.
     *
     * Clients notify the camera manipulator of various mouse or touch events, then periodically
     * call its getLookAt() method so that they can adjust their camera(s). Three modes are
     * supported: ORBIT, MAP, and FREE_FLIGHT. To construct a manipulator instance, the desired mode
     * is passed into the create method.
     *
     * @param manipulator        Underlying Filament [Manipulator]. The factory ctor below builds
     *                           a sensible default ORBIT-mode manipulator.
     * @param pinchZoomSpeed     Per-pixel zoom multiplier applied to the inter-finger separation
     *                           delta during a pinch gesture. Lower values = smoother zoom. The
     *                           default `1/18` (≈ 0.056) was re-tuned in #1427: the v4.0.x `1/30`
     *                           value felt too sluggish on-device ("hyper lent"), while the
     *                           pre-v4.0.x `1/10` lurched the camera through the target during
     *                           fast pinches. `1/18` sits between the two. Set to a higher value
     *                           (e.g. `1/5`) to restore the legacy fast-zoom feel.
     * @param pinchZoomDamping   Non-linear damping exponent applied to the zoom delta. Values < 1
     *                           soften large pinches without sacrificing small-pinch precision
     *                           (sqrt-style curve). The default `0.7` is a gentle knee; set to
     *                           `1.0` to disable damping (linear response).
     */
    open class DefaultCameraManipulator @JvmOverloads constructor(
        protected val manipulator: Manipulator,
        protected val pinchZoomSpeed: Float = DEFAULT_PINCH_ZOOM_SPEED,
        protected val pinchZoomDamping: Float = DEFAULT_PINCH_ZOOM_DAMPING,
    ): CameraManipulator {

        @JvmOverloads
        constructor(
            orbitHomePosition: Position? = null,
            targetPosition: Position? = null,
            pinchZoomSpeed: Float = DEFAULT_PINCH_ZOOM_SPEED,
            pinchZoomDamping: Float = DEFAULT_PINCH_ZOOM_DAMPING,
        ) : this(
            Manipulator.Builder()
                .apply {
                    orbitHomePosition?.let { orbitHomePosition(it) }
                    targetPosition?.let { targetPosition(it) }
                }
                // Re-tuned in #1427: orbit/pan felt "beaucoup trop vite" on-device
                // (2026-05-16 Pixel 9 QA). 0.005 → 0.003 makes finger drag track the
                // model more calmly without feeling sluggish.
                .orbitSpeed(0.003f, 0.003f)
                .zoomSpeed(0.05f)
                .build(Manipulator.Mode.ORBIT),
            pinchZoomSpeed,
            pinchZoomDamping,
        )

        override fun setViewport(width: Int, height: Int) {
            manipulator.setViewport(width, height)
        }

        override fun getTransform(): Transform {
            return manipulator.transform
        }

        override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
            manipulator.grabBegin(x, y, strafe)
        }

        override fun grabUpdate(x: Int, y: Int) {
            manipulator.grabUpdate(x, y)
        }

        override fun grabEnd() {
            manipulator.grabEnd()
        }

        override fun scrollBegin(x: Int, y: Int, separation: Float) {}

        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
            // Pixel 9 review v2: the legacy linear curve `(prev - curr) * 0.1` was too abrupt —
            // a 200 px pinch produced a 20-unit dolly translation, easily punching the camera
            // through the target. The damping curve lives in [pinchZoomDelta] so it can be
            // unit-tested on the JVM (no Filament Manipulator instance needed).
            manipulator.scroll(
                x,
                y,
                pinchZoomDelta(prevSeparation, currSeparation, pinchZoomSpeed, pinchZoomDamping),
            )
        }

        override fun scrollEnd() {}

        override fun update(deltaTime: Float) {
            manipulator.update(deltaTime)
        }

        companion object {
            /**
             * Default per-pixel zoom multiplier. Tuned in v4.0.x from `1/10` down to `1/30`
             * (felt too abrupt on dense screens), then re-tuned in #1427 from `1/30` up to
             * `1/18` — the `1/30` value made pinch-zoom feel "hyper lent" in the 2026-05-16
             * on-device QA. `1/18` restores a responsive pinch while staying well short of
             * the abrupt legacy `1/10`. See [pinchZoomSpeed] kdoc for tuning advice.
             */
            const val DEFAULT_PINCH_ZOOM_SPEED: Float = 1f / 18f

            /**
             * Default damping exponent for pinch deltas. Sub-1 values create a sqrt-like response
             * curve: small pinches stay 1:1, large pinches are progressively softened.
             */
            const val DEFAULT_PINCH_ZOOM_DAMPING: Float = 0.7f
        }
    }

    private enum class Gesture { NONE, ORBIT, PAN, ZOOM }

    // Simplified memento of MotionEvent, minimal but sufficient for our purposes.
    private data class TouchPair(var pt0: Float2, var pt1: Float2, var count: Int) {
        constructor() : this(Float2(0f), Float2(0f), 0)

        val separation get() = distance(pt0, pt1)
        val midpoint get() = mix(pt0, pt1, 0.5f)
        val x: Int get() = midpoint.x.toInt()
        val y: Int get() = midpoint.y.toInt()

        companion object {
            /**
             * Builds a [TouchPair] directly from a [MotionEvent], allocating only the [Float2]
             * point(s) that are actually present. The previous secondary constructor delegated
             * to the no-arg `this()` ctor, which allocated two throwaway `Float2(0f)` instances
             * that were immediately overwritten whenever a pointer was down — pure waste on every
             * touch event (#2328 SV10). For the common 1-2 pointer case this now allocates 1-2
             * `Float2` instead of 3-4. Geometry is byte-identical to the old constructor (a
             * single pointer leaves `pt1 == pt0`; zero pointers yields the same `Float2(0f)`
             * pair and `count == 0`).
             */
            fun of(me: MotionEvent, height: Int): TouchPair = when {
                me.pointerCount >= 2 -> {
                    val p0 = Float2(me.getX(0), height - me.getY(0))
                    TouchPair(p0, Float2(me.getX(1), height - me.getY(1)), 2)
                }

                me.pointerCount >= 1 -> {
                    val p0 = Float2(me.getX(0), height - me.getY(0))
                    TouchPair(p0, p0, 1)
                }

                else -> TouchPair(Float2(0f), Float2(0f), 0)
            }
        }
    }

    private var currentGesture = Gesture.NONE
    private var previousTouch = TouchPair()
    private val tentativePanEvents = ArrayList<TouchPair>()
    private val tentativeOrbitEvents = ArrayList<TouchPair>()
    private val tentativeZoomEvents = ArrayList<TouchPair>()

    private val kGestureConfidenceCount = 2
    private val kPanConfidenceDistance = 10
    private val kZoomConfidenceDistance = 10

    var isPanEnabled: Boolean = true

    fun onTouchEvent(event: MotionEvent) {
        val touch = TouchPair.of(event, viewHeight())
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {

                // CANCEL GESTURE DUE TO UNEXPECTED POINTER COUNT

                if ((event.pointerCount != 1 && currentGesture == Gesture.ORBIT) ||
                    (event.pointerCount != 2 && currentGesture == Gesture.PAN) ||
                    (event.pointerCount != 2 && currentGesture == Gesture.ZOOM)
                ) {
                    endGesture()
                    return
                }

                // UPDATE EXISTING GESTURE

                if (currentGesture == Gesture.ZOOM) {
                    val d0 = previousTouch.separation
                    val d1 = touch.separation
                    cameraManipulator?.scrollUpdate(touch.x, touch.y, d0, d1)
                    previousTouch = touch
                    return
                }

                if (currentGesture != Gesture.NONE) {
                    cameraManipulator?.grabUpdate(touch.x, touch.y)
                    return
                }

                // DETECT NEW GESTURE

                if (event.pointerCount == 1) {
                    tentativeOrbitEvents.add(touch)
                }

                if (event.pointerCount == 2) {
                    tentativePanEvents.add(touch)
                    tentativeZoomEvents.add(touch)
                }

                if (isOrbitGesture()) {
                    cameraManipulator?.grabBegin(touch.x, touch.y, false)
                    currentGesture = Gesture.ORBIT
                    return
                }

                if (isZoomGesture()) {
                    cameraManipulator?.scrollBegin(touch.x, touch.y, touch.separation)
                    currentGesture = Gesture.ZOOM
                    previousTouch = touch
                    return
                }

                if (isPanGesture()) {
                    cameraManipulator?.grabBegin(touch.x, touch.y, true)
                    currentGesture = Gesture.PAN
                    return
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                endGesture()
            }
        }
    }

    private fun endGesture() {
        tentativePanEvents.clear()
        tentativeOrbitEvents.clear()
        tentativeZoomEvents.clear()
        currentGesture = Gesture.NONE
        cameraManipulator?.grabEnd()
    }

    private fun isOrbitGesture(): Boolean {
        return tentativeOrbitEvents.size > kGestureConfidenceCount
    }

    private fun isPanGesture(): Boolean {
        if (!isPanEnabled || tentativePanEvents.size <= kGestureConfidenceCount) {
            return false
        }
        val oldest = tentativePanEvents.first().midpoint
        val newest = tentativePanEvents.last().midpoint
        return distance(oldest, newest) > kPanConfidenceDistance
    }

    private fun isZoomGesture(): Boolean {
        if (tentativeZoomEvents.size <= kGestureConfidenceCount) {
            return false
        }
        val oldest = tentativeZoomEvents.first().separation
        val newest = tentativeZoomEvents.last().separation
        return kotlin.math.abs(newest - oldest) > kZoomConfidenceDistance
    }

    companion object {
        fun createDefaultCameraManipulator(
            manipulator: Manipulator? = null,
        ): DefaultCameraManipulator? {
            if (manipulator == null) {
                return null
            }

            return DefaultCameraManipulator(manipulator)
        }
    }
}

/**
 * A [CameraGestureDetector.CameraManipulator] that maps pinch gestures to a **field-of-view
 * change** instead of a dolly translation. Useful for "cinematic zoom" demos where the camera
 * stays put and the world appears to come closer/farther — closer to the mental model of a
 * camera zoom lens than a physical dolly move.
 *
 * Wraps an inner manipulator (typically a [CameraGestureDetector.DefaultCameraManipulator])
 * which handles orbit/pan as usual. Pinch is intercepted: instead of forwarding the scroll
 * delta to the inner manipulator, this class adjusts the bound [CameraNode]'s vertical FOV
 * via [CameraNode.setProjection].
 *
 * @param inner          Underlying manipulator handling orbit/pan. Pinch events are NOT
 *                       forwarded — the FOV is mutated instead.
 * @param cameraNode     The camera whose FOV is mutated by pinch gestures.
 * @param fovRangeDegrees   Allowed FOV range. Pinch is clamped to stay inside.
 * @param pinchFovSpeed  Per-pixel FOV delta in degrees. Default `0.05` is a gentle response.
 *
 * Example:
 * ```kotlin
 * val cameraNode = rememberCameraNode(engine)
 * val manipulator = remember(cameraNode) {
 *     FovZoomCameraManipulator(
 *         inner = CameraGestureDetector.DefaultCameraManipulator(),
 *         cameraNode = cameraNode,
 *     )
 * }
 * SceneView(cameraNode = cameraNode, cameraManipulator = manipulator) { … }
 * ```
 */
class FovZoomCameraManipulator @JvmOverloads constructor(
    private val inner: CameraGestureDetector.CameraManipulator,
    private val cameraNode: CameraNode,
    private val fovRangeDegrees: ClosedFloatingPointRange<Float> = 10f..120f,
    private val pinchFovSpeed: Float = DEFAULT_PINCH_FOV_SPEED,
) : CameraGestureDetector.CameraManipulator {
    private var currentFov: Double = 60.0

    override fun setViewport(width: Int, height: Int) = inner.setViewport(width, height)
    override fun getTransform(): Transform = inner.getTransform()
    override fun grabBegin(x: Int, y: Int, strafe: Boolean) = inner.grabBegin(x, y, strafe)
    override fun grabUpdate(x: Int, y: Int) = inner.grabUpdate(x, y)
    override fun grabEnd() = inner.grabEnd()

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        // Snapshot the current FOV at gesture start so the delta is applied to a stable base.
        // We can't query the Camera directly for current FOV (Filament's Camera API exposes
        // setProjection but not a getter), so we track it locally.
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        // Pinch out (curr > prev) ⇒ user wants to zoom IN ⇒ smaller FOV.
        // Pure math is in [nextFov] for unit testability without a Filament Camera.
        currentFov = nextFov(currentFov, prevSeparation, currSeparation, fovRangeDegrees, pinchFovSpeed)
        cameraNode.setProjection(fovInDegrees = currentFov, direction = Camera.Fov.VERTICAL)
    }

    override fun scrollEnd() {}
    override fun update(deltaTime: Float) = inner.update(deltaTime)

    companion object {
        const val DEFAULT_PINCH_FOV_SPEED: Float = 0.05f
    }
}