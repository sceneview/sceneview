package io.github.sceneview.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.slerp
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.quaternion
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The one camera writer of a screen that has several sources of camera poses.
 *
 * `SceneView` draws `cameraManipulator.getTransform()` every frame, so the two ordinary ways a demo
 * changes its camera are both **cuts**: handing `SceneView` a *different* manipulator (a new
 * `remember(key)` for a new framing, a scripted/free swap) shows the newcomer's pose on the very
 * next frame, and a script that `snapTo`s its start pose — a new camera mode, a loop going round
 * again — does the same without even changing instance. On screen that is the camera teleporting,
 * by up to half a turn, which is what "the camera jumps" comes down to.
 *
 * This manipulator is remembered **once** per screen, so `SceneView` never sees a swap, and every
 * change of source goes through it:
 *
 * - [drive] names the manipulator that currently produces the pose (and receives the gestures).
 *   A new one is eased into from the pose on screen — unless it already stands there, as a
 *   hand-over seeded from the current eye does, in which case nothing is added.
 * - [easeNextCut] is for a script about to move its own pose discontinuously.
 *
 * The ease reuses the hero orbit's framing algebra ([OrbitFraming], [offsetBy]): what is on screen
 * is kept as an *offset* from the live pose and faded out, so the live source keeps moving
 * underneath, the path goes **around** [pivot] rather than through the subject, and the last
 * frame of the ease is exactly the live pose. The speed the camera had going in is carried and
 * decays ([COAST_SECONDS]) instead of stopping dead: position *and* velocity are continuous.
 *
 * QA mode ([eased] `false`) keeps every change instantaneous, so goldens never catch a camera
 * half-way.
 */
class ContinuousCameraManipulator(
    private val pivot: () -> Position = { Position(0f, 0f, 0f) },
    private val blendMillis: Long = DEFAULT_CUT_BLEND_MILLIS,
    private val eased: () -> Boolean = { true },
    private val nanoTime: () -> Long = System::nanoTime,
) : CameraGestureDetector.CameraManipulator {

    private var source: CameraGestureDetector.CameraManipulator? = null
    private var viewportW = 0
    private var viewportH = 0

    /** What the last [getTransform] returned — the picture a change has to be continuous with. */
    private var shown: Transform? = null
    private var shownFraming: OrbitFraming? = null
    private var shownNanos = 0L

    /** Framing speed over the last two shown poses: yaw °/s, elevation rad/s, ln(distance)/s. */
    private var speed = FramingSpeed.REST

    private var cutMillis = NO_CUT
    private var ease: Ease? = null

    /** `true` once [drive] has named a source. */
    val hasSource: Boolean get() = source != null

    /** Where the camera drawn last stands, or `null` before the first frame. */
    val eyePosition: Position? get() = shown?.position

    /** `true` while a change of source is still being eased into. */
    val isEasing: Boolean get() = ease != null

    /**
     * Whether the scene had anything to show at the last composition ([driving]). A camera that
     * moves in front of an empty viewport is not seen moving, and easing it would only make the
     * subject land in a frame that is still travelling: that change stays a cut.
     */
    internal var contentShown = true

    /**
     * Make [next] the source of poses and gestures. Idempotent for the current one. [cut] takes
     * the new pose at once — for a change nobody can see.
     */
    fun drive(next: CameraGestureDetector.CameraManipulator, cut: Boolean = false) {
        if (next === source) return
        source = next
        if (viewportW > 0 && viewportH > 0) next.setViewport(viewportW, viewportH)
        if (cut) {
            cutMillis = NO_CUT
            ease = null
        } else {
            easeNextCut()
        }
    }

    /**
     * The source is about to change its pose discontinuously (a `snapTo`, a loop restart): ease
     * from the pose on screen into whatever it shows next, over [millis], instead of cutting.
     */
    fun easeNextCut(millis: Long = blendMillis) {
        cutMillis = millis
    }

    override fun setViewport(width: Int, height: Int) {
        viewportW = width
        viewportH = height
        source?.setViewport(width, height)
    }

    override fun getTransform(): Transform {
        val live = source?.getTransform() ?: return shown ?: Transform()
        val now = nanoTime()
        val center = pivot()
        val liveFraming = orbitFramingOf(live.position, center)
        if (cutMillis != NO_CUT) {
            ease = beginEase(live, liveFraming, cutMillis, now)
            cutMillis = NO_CUT
        }
        val out = ease?.let { applyEase(it, live, liveFraming, now) }?.takeIf(::isFinite) ?: live
        record(out, center, now)
        return out
    }

    private fun beginEase(live: Transform, liveFraming: OrbitFraming, millis: Long, now: Long): Ease? {
        val from = shown ?: return null
        if (!eased() || millis <= 0L) return null
        val fromFraming = orbitFramingOf(from.position, liveFraming.pivot)
        val offset = orbitFramingOffset(user = fromFraming, authored = liveFraming)
        val residual = (from.quaternion * inverse(aimed(live, liveFraming, fromFraming).quaternion))
            .let { if (it.w < 0f) Quaternion(-it.x, -it.y, -it.z, -it.w) else it }
        val standsThere = abs(offset.yawDegrees) < SEAMLESS_DEGREES &&
            abs(Math.toDegrees(offset.elevation.toDouble())) < SEAMLESS_DEGREES &&
            abs(offset.distanceScale - 1f) < SEAMLESS_DISTANCE_RATIO &&
            residual.w > SEAMLESS_QUATERNION_W
        // Already on the pose on screen (a hand-over seeded from the current eye): an ease would
        // add nothing but weight to the first second of the user's drag.
        if (standsThere || !residual.w.isFinite()) return null
        // Half a turn takes twice as long as a nudge, so a big move stays readable.
        val stretch = 1f + (abs(offset.yawDegrees) / HALF_TURN_DEGREES).coerceAtMost(1f)
        return Ease(
            startNanos = now,
            seconds = millis * stretch / MILLIS_PER_SECOND,
            offset = offset,
            residual = residual,
            liveAtStart = liveFraming,
            carried = speed,
        )
    }

    private fun applyEase(ease: Ease, live: Transform, liveFraming: OrbitFraming, now: Long): Transform {
        val elapsed = (now - ease.startNanos) / NANOS_PER_SECOND
        val weight = resumeBlendWeight(elapsed, ease.seconds)
        if (weight <= 0f) {
            this.ease = null
            return live
        }
        if (!ease.relative && elapsed > MIN_SPEED_WINDOW_SECONDS) {
            // The new source moves too: what is carried is the speed *relative* to it, known as
            // soon as it has shown a second pose. One frame late, and exact from then on.
            if (elapsed < MAX_SPEED_WINDOW_SECONDS) {
                ease.carried = ease.carried - FramingSpeed.between(ease.liveAtStart, liveFraming, elapsed)
            }
            ease.relative = true
        }
        // What the camera was doing going in, coasting to a stop on top of the fading offset.
        val coast = COAST_SECONDS * (1f - exp(-elapsed / COAST_SECONDS))
        val offset = ease.offset.copy(
            yawDegrees = ease.offset.yawDegrees + ease.carried.yaw * coast,
            elevation = ease.offset.elevation + ease.carried.elevation * coast,
            distanceScale = ease.offset.distanceScale * exp(ease.carried.zoom * coast),
        )
        val aimed = aimed(live, liveFraming, liveFraming.offsetBy(offset, weight))
        val roll = slerp(Quaternion(), ease.residual, weight)
        return Transform(position = aimed.position, quaternion = roll * aimed.quaternion)
    }

    /**
     * [live] carried rigidly from the orbit it is on ([from]) onto [to]: the eye lands on
     * `to.eye()`, and whatever the live camera does besides looking at its pivot — a pan, a roll —
     * rides along unchanged.
     */
    private fun aimed(live: Transform, from: OrbitFraming, to: OrbitFraming): Transform =
        lookAt(to.eye(), to.pivot, UP) * inverse(lookAt(from.eye(), from.pivot, UP)) * live

    private fun isFinite(transform: Transform): Boolean =
        transform.toFloatArray().all { it.isFinite() }

    private fun record(out: Transform, center: Position, now: Long) {
        val framing = orbitFramingOf(out.position, center)
        val before = shownFraming
        val dt = (now - shownNanos) / NANOS_PER_SECOND
        speed = if (before != null && before.pivot == center &&
            dt > MIN_SPEED_WINDOW_SECONDS && dt < MAX_SPEED_WINDOW_SECONDS
        ) {
            FramingSpeed.between(before, framing, dt)
        } else {
            FramingSpeed.REST
        }
        shown = out
        shownFraming = framing
        shownNanos = now
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        source?.grabBegin(x, y, strafe)
    }

    override fun grabUpdate(x: Int, y: Int) {
        source?.grabUpdate(x, y)
    }

    override fun grabEnd() {
        source?.grabEnd()
    }

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        source?.scrollBegin(x, y, separation)
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        source?.scrollUpdate(x, y, prevSeparation, currSeparation)
    }

    override fun scrollEnd() {
        source?.scrollEnd()
    }

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        source?.doubleTapZoom(x, y, zoomIn)
    }

    override fun update(deltaTime: Float) {
        source?.update(deltaTime)
    }

    /** Yaw in °/s, elevation in rad/s, zoom in ln(distance)/s — the axes an offset is made of. */
    private data class FramingSpeed(val yaw: Float, val elevation: Float, val zoom: Float) {
        operator fun minus(other: FramingSpeed) =
            FramingSpeed(yaw - other.yaw, elevation - other.elevation, zoom - other.zoom).bounded()

        /** A fling or a one-frame glitch must not be carried into a wild swing. */
        fun bounded() = FramingSpeed(
            yaw = yaw.coerceIn(-MAX_YAW_SPEED, MAX_YAW_SPEED),
            elevation = elevation.coerceIn(-MAX_ELEVATION_SPEED, MAX_ELEVATION_SPEED),
            zoom = zoom.coerceIn(-MAX_ZOOM_SPEED, MAX_ZOOM_SPEED),
        )

        companion object {
            val REST = FramingSpeed(0f, 0f, 0f)

            fun between(from: OrbitFraming, to: OrbitFraming, seconds: Float): FramingSpeed {
                val measurable = from.distance > MIN_DISTANCE && to.distance > MIN_DISTANCE
                return FramingSpeed(
                    yaw = wrapDegrees(to.yawDegrees - from.yawDegrees) / seconds,
                    elevation = (to.elevation - from.elevation) / seconds,
                    zoom = if (measurable) ln(to.distance / from.distance) / seconds else 0f,
                ).bounded()
            }
        }
    }

    private class Ease(
        val startNanos: Long,
        val seconds: Float,
        val offset: OrbitFramingOffset,
        val residual: Quaternion,
        val liveAtStart: OrbitFraming,
        var carried: FramingSpeed,
    ) {
        /** Whether [carried] has been made relative to the new source's own speed yet. */
        var relative = false
    }

    companion object {
        /** Long enough to read as a camera move, short enough not to delay the new shot. */
        const val DEFAULT_CUT_BLEND_MILLIS: Long = 700L

        /** Time constant of the carried speed's decay. */
        const val COAST_SECONDS: Float = 0.25f

        private const val NO_CUT = -1L
        private const val HALF_TURN_DEGREES = 180f
        private const val SEAMLESS_DEGREES = 0.25f
        private const val SEAMLESS_DISTANCE_RATIO = 0.005f
        private const val SEAMLESS_QUATERNION_W = 0.99999f
        private const val MIN_DISTANCE = 1e-4f
        private const val MIN_SPEED_WINDOW_SECONDS = 1e-4f
        private const val MAX_SPEED_WINDOW_SECONDS = 0.25f
        private const val MAX_YAW_SPEED = 120f
        private const val MAX_ELEVATION_SPEED = 1f
        private const val MAX_ZOOM_SPEED = 1.5f
        private const val NANOS_PER_SECOND = 1e9f
        private const val MILLIS_PER_SECOND = 1_000f
        private val UP = Float3(0f, 1f, 0f)
    }
}

/**
 * A [ContinuousCameraManipulator] that survives every recomposition. [pivot] is the point the
 * screen's cameras look at; name the source with [driving], or with [ContinuousCameraManipulator.drive]
 * when it only exists further down the composition.
 */
@Composable
fun rememberContinuousCameraManipulator(
    pivot: Position = Position(0f, 0f, 0f),
    blendMillis: Long = ContinuousCameraManipulator.DEFAULT_CUT_BLEND_MILLIS,
): ContinuousCameraManipulator {
    val center = rememberUpdatedState(pivot)
    return remember(blendMillis) {
        ContinuousCameraManipulator(
            pivot = { center.value },
            blendMillis = blendMillis,
            eased = { !DemoSettings.qaMode },
        )
    }
}

/**
 * Keeps [this] driving [source] — the manipulator the screen would have handed to `SceneView`
 * directly, however often it is rebuilt or swapped — and returns it for `SceneView`.
 *
 * [contentShown] is whether the viewport has a subject in it. A new source is eased into only if
 * it had one *before* the change: the framing that arrives with a model (its measured fit, the
 * next chip's radius behind an empty stage) is taken at once, so the model lands in a camera that
 * already stands still.
 */
@Composable
fun ContinuousCameraManipulator.driving(
    source: CameraGestureDetector.CameraManipulator,
    contentShown: Boolean = true,
): ContinuousCameraManipulator {
    // Nothing has been drawn before the first source: no frame to be continuous with, and
    // `SceneView` may read the pose before the first side effect runs.
    if (!hasSource) drive(source)
    SideEffect {
        drive(source, cut = !this.contentShown)
        this.contentShown = contentShown
    }
    return this
}
