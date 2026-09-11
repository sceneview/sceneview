package io.github.sceneview.gesture

import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.android.filament.Camera
import com.google.android.filament.utils.Float2
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.distance
import com.google.android.filament.utils.mix
import kotlin.math.abs
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

        /**
         * A double-tap (or two-finger tap) asked the camera to zoom, at screen point [x], [y].
         *
         * Called by [CameraGestureDetector] when [CameraGestureDetector.isDoubleTapZoomEnabled] is
         * on. Coordinates are in Filament's convention — origin bottom-left, i.e. already
         * y-flipped from [android.view.MotionEvent] — the same ones [grabBegin] and [scrollBegin]
         * receive.
         *
         * The step is expected to be *animated*: implementations start the move here and advance
         * it from [update], which the render loop already calls once per frame. The default
         * implementation does nothing, so existing manipulators keep compiling and behaving
         * exactly as before.
         *
         * @param x      Tap x, in pixels, origin bottom-left.
         * @param y      Tap y, in pixels, origin bottom-left.
         * @param zoomIn `true` for a double-tap (move closer), `false` for a two-finger tap
         *               (move away).
         */
        fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {}
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
        /**
         * The `zoomSpeed` the wrapped [manipulator] was built with — Filament does not expose a
         * getter, so it has to be repeated here. Only used to invert Filament's absolute scroll
         * step into the relative one [scrollUpdate] wants; the default matches the `zoomSpeed`
         * the convenience constructors below configure.
         */
        protected val manipulatorZoomSpeed: Float = DEFAULT_ORBIT_ZOOM_SPEED,
    ): CameraManipulator {

        /**
         * Camera-to-orbit-pivot distance, tracked in Kotlin because Filament will not tell us.
         *
         * `Manipulator.getLookAt` is only usable for this **before the first orbit drag**: the
         * moment `grabUpdate` runs, `OrbitManipulator::jumpToBookmark` re-plants `mTarget` exactly
         * one unit in front of the eye, so the reported eye→target distance is a constant `1` from
         * then on and says nothing about the orbit radius. The radius itself only ever changes via
         * `scroll` (orbit preserves it by construction, pan translates eye and pivot together), so
         * seeding it once at construction and updating it by the step we ourselves request keeps
         * it exact. `-1` means "not measured yet".
         */
        private var orbitDistance: Float = -1f

        /**
         * Closest / furthest the pinch may take the camera, as multiples of the distance the
         * manipulator was *homed* at. Bounds-relative in practice, since the home distance is
         * whatever auto-fit or the demo's framing computed for the subject. The lower bound is
         * what stops `scroll` from punching the eye through the orbit pivot and inverting the
         * camera (#3403).
         */
        var minZoomDistanceFactor: Float = DEFAULT_MIN_ZOOM_DISTANCE_FACTOR

        /** @see minZoomDistanceFactor */
        var maxZoomDistanceFactor: Float = DEFAULT_MAX_ZOOM_DISTANCE_FACTOR

        /** The distance the manipulator was homed at — the reference for the zoom clamps. */
        private var homeDistance: Float = -1f

        /**
         * Double-tap to zoom in, two-finger tap to zoom out — the convention every photo viewer
         * and map trains users to expect, on by default (#3608).
         *
         * Set to `false` to opt out; the taps are then ignored by this manipulator and a consumer
         * `onDoubleTap` callback is the only thing that runs. Toggling it mid-animation cancels
         * the move in flight.
         *
         * ```kotlin
         * val manipulator = rememberCameraManipulator(orbitRadius = 3f).apply {
         *     (this as? CameraGestureDetector.DefaultCameraManipulator)
         *         ?.isDoubleTapZoomEnabled = false
         * }
         * ```
         */
        var isDoubleTapZoomEnabled: Boolean = true

        /**
         * Distance ratio one double-tap covers. `2` (the default) halves the camera-to-target
         * distance on a double-tap and doubles it back on a two-finger tap. Must be `> 1`;
         * anything else falls back to the default.
         */
        var doubleTapZoomFactor: Float = DEFAULT_DOUBLE_TAP_ZOOM_FACTOR

        /**
         * How long the double-tap zoom takes, in seconds. `0.3` matches the platform's own
         * double-tap zoom; `0` makes the step instantaneous.
         */
        var doubleTapZoomDurationSeconds: Float = DEFAULT_DOUBLE_TAP_ZOOM_DURATION_SECONDS

        /**
         * In-flight double-tap zoom: where it started, where it lands, and how far along it is.
         * Advanced from [update] (the render loop already ticks that every frame), so the SDK
         * needs neither a coroutine nor a second frame callback for it. `animationDuration <= 0`
         * means "no animation running".
         */
        private var animationStartDistance: Float = 0f
        private var animationTargetDistance: Float = 0f
        private var animationElapsed: Float = 0f
        private var animationDuration: Float = 0f
        private var animationX: Int = 0
        private var animationY: Int = 0

        /**
         * `true` when the wrapped manipulator is an `ORBIT` one, i.e. when `scroll` means "dolly
         * towards the pivot". `MAP` scrolls the map extent and `FREE_FLIGHT` scrolls its move
         * speed; converting a *distance* into a scroll delta is meaningless for both.
         */
        private fun isOrbitMode(): Boolean =
            runCatching { manipulator.mode == Manipulator.Mode.ORBIT }.getOrDefault(false)

        /**
         * Reads the orbit radius, seeding it from the manipulator's own pose the first time (which
         * is only correct before any orbit drag — see [orbitDistance]).
         */
        private fun currentOrbitDistance(): Float {
            if (!isOrbitMode()) return -1f
            if (orbitDistance > 0f) return orbitDistance
            val eye = FloatArray(3)
            val target = FloatArray(3)
            val upward = FloatArray(3)
            runCatching { manipulator.getLookAt(eye, target, upward) }.getOrElse { return -1f }
            val dx = eye[0] - target[0]
            val dy = eye[1] - target[1]
            val dz = eye[2] - target[2]
            val measured = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (!measured.isFinite() || measured <= 0f) return -1f
            orbitDistance = measured
            if (homeDistance <= 0f) homeDistance = measured
            return measured
        }

        /**
         * Builds a sensible default ORBIT-mode manipulator.
         *
         * @param eyePosition    Camera's initial eye position in **world space** (optional).
         *                       Filament's `orbitHomePosition` — there is no "home" gesture, it
         *                       is only where the camera starts. `null` keeps Filament's
         *                       `(0, 0, 1)`. See `rememberCameraManipulator` for how this
         *                       interacts with `autoCenterContent`.
         * @param targetPosition Point in world space the camera orbits around and initially
         *                       looks at (optional; defaults to the origin).
         */
        @JvmOverloads
        constructor(
            eyePosition: Position? = null,
            targetPosition: Position? = null,
            pinchZoomSpeed: Float = DEFAULT_PINCH_ZOOM_SPEED,
            pinchZoomDamping: Float = DEFAULT_PINCH_ZOOM_DAMPING,
        ) : this(
            Manipulator.Builder()
                .apply {
                    eyePosition?.let { orbitHomePosition(it) }
                    targetPosition?.let { targetPosition(it) }
                }
                // Re-tuned in #1427: orbit/pan felt "beaucoup trop vite" on-device
                // (2026-05-16 Pixel 9 QA). 0.005 → 0.003 makes finger drag track the
                // model more calmly without feeling sluggish.
                .orbitSpeed(0.003f, 0.003f)
                .zoomSpeed(DEFAULT_ORBIT_ZOOM_SPEED)
                .build(Manipulator.Mode.ORBIT),
            pinchZoomSpeed,
            pinchZoomDamping,
            DEFAULT_ORBIT_ZOOM_SPEED,
        )

        /**
         * Builds a default ORBIT-mode manipulator whose camera starts [orbitRadius] metres from
         * [targetPosition], along [DEFAULT_ORBIT_DIRECTION] — see [orbitEyePosition].
         *
         * @param orbitRadius    Camera-to-target distance in metres. Must be `> 0`.
         * @param targetPosition Point in world space the camera orbits around and initially
         *                       looks at (optional; defaults to the origin).
         */
        @JvmOverloads
        constructor(
            orbitRadius: Float,
            targetPosition: Position? = null,
            pinchZoomSpeed: Float = DEFAULT_PINCH_ZOOM_SPEED,
            pinchZoomDamping: Float = DEFAULT_PINCH_ZOOM_DAMPING,
        ) : this(
            eyePosition = orbitEyePosition(orbitRadius, targetPosition ?: Position(0f)),
            targetPosition = targetPosition,
            pinchZoomSpeed = pinchZoomSpeed,
            pinchZoomDamping = pinchZoomDamping,
        )

        override fun setViewport(width: Int, height: Int) {
            manipulator.setViewport(width, height)
            // First chance to read a still-truthful pose, and it always runs before any gesture —
            // `getLookAt` stops reporting the orbit radius after the first drag (see
            // [orbitDistance]), so the seed has to happen here rather than on the first pinch.
            currentOrbitDistance()
        }

        override fun getTransform(): Transform {
            return manipulator.transform
        }

        override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
            // The user's own gesture always wins over an animation still playing.
            cancelDoubleTapZoom()
            // Last moment the pose is still readable — `grabUpdate` is what re-plants the target.
            currentOrbitDistance()
            manipulator.grabBegin(x, y, strafe)
        }

        override fun grabUpdate(x: Int, y: Int) {
            manipulator.grabUpdate(x, y)
        }

        override fun grabEnd() {
            manipulator.grabEnd()
        }

        override fun scrollBegin(x: Int, y: Int, separation: Float) {
            // A pinch takes over from a double-tap zoom still in flight.
            cancelDoubleTapZoom()
            // Seed the tracked radius from the manipulator while its reported target is still the
            // orbit pivot (see [orbitDistance]) — cheap, and a no-op once measured.
            currentOrbitDistance()
        }

        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
            // The damping curve lives in [pinchZoomDelta] so it can be unit-tested on the JVM (no
            // Filament Manipulator instance needed).
            val zoomDelta =
                pinchZoomDelta(prevSeparation, currSeparation, pinchZoomSpeed, pinchZoomDamping)
            val distance = if (isOrbitMode()) currentOrbitDistance() else -1f
            if (distance <= 0f) {
                // Either no usable pose to scale against, or a mode where "distance" is not what
                // scroll means: `MapManipulator` scrolls the map extent and `FreeFlightManipulator`
                // scrolls its move *speed*, neither of which is a dolly. Hand those Filament's own
                // step rather than a dolly conversion that does not apply to them.
                manipulator.scroll(x, y, zoomDelta)
                return
            }
            val home = if (homeDistance > 0f) homeDistance else distance
            // Relative dolly: the same pinch covers the same *fraction* of the distance whatever
            // the subject's scale, and the clamp keeps the eye off (and never past) the pivot.
            val next = zoomedDistance(
                distance = distance,
                zoomDelta = zoomDelta,
                minDistance = home * minZoomDistanceFactor,
                maxDistance = home * maxZoomDistanceFactor,
            )
            if (next == distance) return
            manipulator.scroll(x, y, dollyScrollDelta(distance, next, manipulatorZoomSpeed))
            orbitDistance = next
        }

        override fun scrollEnd() {}

        /**
         * Starts the animated dolly towards (or away from) the orbit pivot.
         *
         * The tap point is remembered and forwarded to `Manipulator.scroll` for the modes that
         * read it, but an ORBIT manipulator does not: Filament's `OrbitManipulator::scroll` moves
         * the eye strictly along the gaze and ignores `x`/`y`, so the zoom is centred on the orbit
         * pivot rather than anchored under the finger. See the `doubleTapZoom` KDoc on
         * [CameraManipulator].
         */
        override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
            if (!isDoubleTapZoomEnabled) return
            val distance = if (isOrbitMode()) currentOrbitDistance() else -1f
            // Non-orbit modes (MAP scrolls an extent, FREE_FLIGHT a move speed): a
            // camera-to-target distance is not what their scroll means, so there is nothing
            // meaningful to animate. Leave them alone rather than invent a step.
            if (distance <= 0f) return
            val home = if (homeDistance > 0f) homeDistance else distance
            val target = doubleTapZoomedDistance(
                distance = distance,
                homeDistance = home,
                zoomIn = zoomIn,
                factor = doubleTapZoomFactor,
                minDistanceFactor = minZoomDistanceFactor,
                maxDistanceFactor = maxZoomDistanceFactor,
            )
            if (target == distance) return
            animationX = x
            animationY = y
            animationStartDistance = distance
            animationTargetDistance = target
            animationElapsed = 0f
            animationDuration = doubleTapZoomDurationSeconds.takeIf { it.isFinite() && it > 0f }
                ?: 0f
            if (animationDuration <= 0f) {
                // Duration 0 is a legal setting, not an error: land on the target immediately.
                applyDistance(target)
            }
        }

        /** Drops any double-tap zoom in flight, leaving the camera exactly where it is. */
        private fun cancelDoubleTapZoom() {
            animationDuration = 0f
        }

        override fun update(deltaTime: Float) {
            advanceDoubleTapZoom(deltaTime)
            manipulator.update(deltaTime)
        }

        /**
         * Advances an in-flight double-tap zoom by one frame. No-op — and no allocation, no JNI
         * call — when nothing is animating, which is every frame but the ~18 of a 300 ms move.
         */
        private fun advanceDoubleTapZoom(deltaTime: Float) {
            if (animationDuration <= 0f) return
            if (!deltaTime.isFinite() || deltaTime < 0f) return
            animationElapsed += deltaTime
            val progress = (animationElapsed / animationDuration).coerceIn(0f, 1f)
            val next = if (progress >= 1f) {
                animationTargetDistance
            } else {
                animatedZoomDistance(
                    animationStartDistance, animationTargetDistance, progress
                )
            }
            applyDistance(next)
            if (progress >= 1f) cancelDoubleTapZoom()
        }

        /**
         * Dollies the wrapped manipulator to an absolute camera-to-target [distance], going
         * through the same `scroll` inversion the pinch uses so the tracked [orbitDistance] and
         * Filament's own eye stay in step.
         */
        private fun applyDistance(distance: Float) {
            val current = currentOrbitDistance()
            if (current <= 0f || distance == current) return
            manipulator.scroll(
                animationX, animationY, dollyScrollDelta(current, distance, manipulatorZoomSpeed)
            )
            orbitDistance = distance
        }

        companion object {
            /**
             * Default pinch gain. **The unit changed in #3426**: the pinch is now a *ratio* of the
             * current camera-to-target distance, not a number of world units, so this constant is
             * "natural-log of the distance ratio per damped pixel" rather than "metres per damped
             * pixel" (see [zoomedDistance]).
             *
             * `1/60` puts a full-screen 200 px pinch at ~ln2 (`200^0.7 / 60 ≈ 0.69`), i.e. **one
             * comfortable pinch halves or doubles the distance** — the response Maps / Sketchfab
             * train users to expect. The old `1/18` under the absolute-translation model moved the
             * camera ~11 cm per pinch regardless of scale, which read as "many gestures for very
             * little zoom" on anything framed further than a metre away (#3426) and punched
             * straight through the pivot on anything closer (#3403).
             */
            const val DEFAULT_PINCH_ZOOM_SPEED: Float = 1f / 60f

            /**
             * The `zoomSpeed` the convenience constructors configure on the Filament
             * [Manipulator]. Only the *relative* step matters to the user now, so this is purely
             * the unit [dollyScrollDelta] inverts — it no longer sets the zoom feel.
             */
            const val DEFAULT_ORBIT_ZOOM_SPEED: Float = 0.05f

            /**
             * Closest the pinch may take the camera, as a fraction of the distance it was homed
             * at. `0.15` lets the user get comfortably inside a subject's silhouette while keeping
             * the eye well clear of the orbit pivot — crossing it is what inverts the camera
             * (#3403).
             */
            const val DEFAULT_MIN_ZOOM_DISTANCE_FACTOR: Float = 0.15f

            /** Furthest the pinch may take the camera, as a multiple of the homed distance. */
            const val DEFAULT_MAX_ZOOM_DISTANCE_FACTOR: Float = 8f

            /**
             * Default distance ratio one double-tap covers — `2`, i.e. a double-tap halves the
             * camera-to-target distance and a two-finger tap doubles it back. The step every map
             * and photo viewer uses, and small enough that two taps in a row stay legible.
             */
            const val DEFAULT_DOUBLE_TAP_ZOOM_FACTOR: Float = 2f

            /**
             * Default duration of the double-tap zoom animation, in seconds. `0.3` is the
             * platform's own double-tap zoom timing — long enough to read as a move rather than a
             * jump cut, short enough not to feel like waiting.
             */
            const val DEFAULT_DOUBLE_TAP_ZOOM_DURATION_SECONDS: Float = 0.3f

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

    /**
     * How far, in pixels, the two-finger midpoint or the inter-finger separation may drift and
     * still count as a tap. Matches [kPanConfidenceDistance] / [kZoomConfidenceDistance] on
     * purpose: past that drift the stream is already being promoted to PAN or ZOOM, so the tap and
     * the drag gestures can never both claim the same movement.
     */
    private val kTwoFingerTapSlop = 10f

    /**
     * How long, in milliseconds, two fingers may rest before lifting and still count as a tap.
     * [ViewConfiguration.getDoubleTapTimeout] is the platform's own answer to exactly this
     * question (300 ms), so the two-finger tap and the double-tap share one timing budget.
     */
    private val kTwoFingerTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    var isPanEnabled: Boolean = true

    /**
     * Double-tap to zoom in, two-finger tap to zoom out — on by default (#3608).
     *
     * When on, [onDoubleTap] and the two-finger tap recognised below are forwarded to
     * [CameraManipulator.doubleTapZoom]. Consumer `onDoubleTap` callbacks are untouched either
     * way: they come from the separate [GestureDetector] and both run.
     */
    var isDoubleTapZoomEnabled: Boolean = true

    /**
     * A confirmed double-tap landed on the scene: zoom in, animated.
     *
     * Called by `SceneView` from the [GestureDetector] that already owns double-tap recognition,
     * so the camera and the consumer's own `onDoubleTap` see the same event and neither steals it
     * from the other. Not called when the touch was absorbed by an editable node — that node owns
     * the gesture.
     */
    fun onDoubleTap(event: MotionEvent) {
        if (!isDoubleTapZoomEnabled) return
        twoFingerTapCandidate = false
        val touch = TouchPair.of(event, viewHeight())
        requestZoom(touch.x, touch.y, zoomIn = true)
    }

    /**
     * Asks the manipulator to zoom, tolerating a manipulator that predates the gesture.
     *
     * [CameraManipulator.doubleTapZoom] has a default implementation, so every Kotlin manipulator
     * — including one compiled against 4.35 — already answers it. A **Java** class implementing
     * the interface before 4.36 does not: Kotlin emits the default into a `DefaultImpls` bridge on
     * the implementor's side, which such a class never got, so the call would land on an abstract
     * method. Major version 4 is frozen, so that must not become a crash for a consumer who only
     * upgraded the SDK. Swallowing it here means the worst case is "double-tap does nothing",
     * which is exactly the 4.35 behaviour they already had.
     */
    private fun requestZoom(x: Int, y: Int, zoomIn: Boolean) {
        val manipulator = cameraManipulator ?: return
        runCatching { manipulator.doubleTapZoom(x, y, zoomIn) }
    }

    // ── Two-finger tap (zoom out) ────────────────────────────────────────────────────────────────
    //
    // Android's `GestureDetector` has no two-finger tap, but this class already tracks everything
    // needed to recognise one for free: a second pointer going down while no camera gesture has
    // started, both fingers lifting within the double-tap window, and the midpoint never
    // travelling past the pan slop. An aborted pinch fails all three — it either promotes itself
    // to ZOOM/PAN, or moves further than the slop — so it cannot be mistaken for a tap.
    private var twoFingerTapCandidate = false
    private var twoFingerTapDownTimeMs = 0L
    private var twoFingerTapDownMidpoint = Float2(0f)
    private var twoFingerTapDownSeparation = 0f

    fun onTouchEvent(event: MotionEvent) {
        val touch = TouchPair.of(event, viewHeight())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> twoFingerTapCandidate = false

            MotionEvent.ACTION_POINTER_DOWN -> beginTwoFingerTap(event, touch)

            MotionEvent.ACTION_MOVE -> {

                // A tap that travels is not a tap. Checked before the gesture logic below so a
                // stream promoted to PAN/ZOOM on this very event is already disqualified.
                invalidateTwoFingerTapIfMoved(touch)

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

            MotionEvent.ACTION_POINTER_UP -> endTwoFingerTap(event)

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                twoFingerTapCandidate = false
                endGesture()
            }
        }
    }

    /**
     * Arms the two-finger tap on the second finger going down: exactly two fingers, nothing else
     * in flight. A third pointer, or a pointer arriving mid-orbit, disqualifies the whole stream.
     */
    private fun beginTwoFingerTap(event: MotionEvent, touch: TouchPair) {
        twoFingerTapCandidate = isDoubleTapZoomEnabled &&
                event.pointerCount == 2 &&
                currentGesture == Gesture.NONE
        if (twoFingerTapCandidate) {
            twoFingerTapDownTimeMs = event.eventTime
            twoFingerTapDownMidpoint = touch.midpoint
            twoFingerTapDownSeparation = touch.separation
        }
    }

    /** Disarms the two-finger tap once either finger has travelled past the tap slop. */
    private fun invalidateTwoFingerTapIfMoved(touch: TouchPair) {
        if (!twoFingerTapCandidate) return
        val drifted = distance(touch.midpoint, twoFingerTapDownMidpoint) > kTwoFingerTapSlop
        val pinched =
            abs(touch.separation - twoFingerTapDownSeparation) > kTwoFingerTapSlop
        if (drifted || pinched) {
            twoFingerTapCandidate = false
        }
    }

    /**
     * Fires the zoom-out when the first of the two fingers lifts inside the tap window.
     *
     * `event.eventTime` rather than `System.currentTimeMillis()`: the event's own clock is what
     * the platform's tap timeouts are expressed in, and it does not drift with dispatch latency.
     */
    private fun endTwoFingerTap(event: MotionEvent) {
        val tapped = twoFingerTapCandidate &&
                currentGesture == Gesture.NONE &&
                event.eventTime - twoFingerTapDownTimeMs <= kTwoFingerTapTimeoutMs
        twoFingerTapCandidate = false
        if (!tapped) return
        requestZoom(
            twoFingerTapDownMidpoint.x.toInt(),
            twoFingerTapDownMidpoint.y.toInt(),
            zoomIn = false,
        )
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

    // Double-tap stays a dolly, handled by the inner manipulator: this class only re-maps the
    // *pinch* to a FOV change, and a double-tap that narrowed the lens instead of approaching the
    // subject would contradict the dolly the orbit/pan half of the camera still does.
    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) = inner.doubleTapZoom(x, y, zoomIn)

    companion object {
        const val DEFAULT_PINCH_FOV_SPEED: Float = 0.05f
    }
}