package io.github.sceneview.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one camera writer of a screen that has several sources of camera poses.
 *
 * `SceneView` draws `cameraManipulator.getTransform()` on every tick of its frame loop, so the
 * two ordinary ways a demo changes its camera are both **cuts**: handing `SceneView` a
 * *different* manipulator (a new `remember(key)` for a new framing, a scripted/free swap) shows
 * the newcomer's pose on the very next frame, and a script that `snapTo`s its start pose — a new
 * camera mode, a loop going round again — does the same without even changing instance. On screen
 * that is the camera teleporting, by up to half a turn, which is what "the camera jumps" comes
 * down to.
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
 * The ease is advanced by the render loop, not by the wall clock: one call to [getTransform]
 * counts for at most [MAX_FRAME_SECONDS], however long it took to arrive. A model decoding on the
 * main thread freezes the picture for seconds, and an ease timed on the wall clock would be over
 * before the next call — a cut again. For the same reason a long gap between two calls is itself
 * treated as a cut: the source moved on unseen, and the camera is eased from the last picture
 * drawn to wherever it is now — on the call after the gap and the [SETTLING_FRAMES] that follow,
 * because a source animated from another frame callback is a frame stale and only shows
 * afterwards how far it went.
 *
 * That clock is the loop's **tick**, which is not the same as a frame **presented**: `SceneView`
 * reads the manipulator on every tick, while `Renderer.beginFrame` refuses the frame itself when
 * the GPU is behind. So [MAX_FRAME_SECONDS] bounds what a *freeze* costs the ease — not what a
 * run of ticks whose frames never reached the surface costs it: those spend the ease at their own
 * pace while the picture stands still.
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

    /** When the pending [cutMillis] was armed, so an arm nothing came of can be dropped. */
    private var cutArmedNanos = 0L

    private var ease: Ease? = null

    /** Frames left, after a freeze, in which a step of the source still belongs to that freeze. */
    private var settlingFrames = 0

    /** `true` once [drive] has named a source. */
    val hasSource: Boolean get() = source != null

    /** Where the camera drawn last stands, or `null` before the first frame. */
    val eyePosition: Position? get() = shown?.position

    /** `true` while a change of source is still being eased into. */
    val isEasing: Boolean get() = ease != null

    /**
     * Forwards the source's own answer, and adds this class's three waiting states: an ease in
     * flight, the [SETTLING_FRAMES] owed after a freeze, and a cut armed but not yet spent.
     *
     * A wrapper that answered only for itself would hide the source's countdown — and the source
     * here is a [HeroOrbitCameraManipulator] whose idle orbit takes the camera back three seconds
     * after the last gesture. Under [io.github.sceneview.FrameRatePolicy.OnDemand] the loop must
     * stay alive across that gap or the hand-back never happens; see
     * [io.github.sceneview.gesture.CameraGestureDetector.CameraManipulator.isFrameActive].
     *
     * The arm carries its own deadline here rather than waiting to be dropped by the next
     * [getTransform]. Dropping it there is enough to keep the *ease* honest, because the tick that
     * would spend it is the same tick that reads it — but it is not enough for this answer, which
     * is what decides whether that tick happens at all. A script that announces a cut and then
     * suspends on something that never arrives leaves an arm nothing will ever spend, and the loop
     * would hold the full cadence on it for as long as anything else kept the loop alive: an
     * announcement is a *wait*, and a wait is not work.
     */
    override val isFrameActive: Boolean
        get() = source?.isFrameActive == true || isEasing || settlingFrames > 0 || isCutArmed

    /** A cut announced and not yet spent, within the [CUT_ARM_SECONDS] it is allowed to wait. */
    private val isCutArmed: Boolean
        get() = cutMillis != NO_CUT &&
            (nanoTime() - cutArmedNanos) / NANOS_PER_SECOND <= CUT_ARM_SECONDS

    /**
     * Whether the viewport has a subject in it ([driving]). A camera that moves behind a loading
     * cover or in front of an empty stage is not seen moving, and easing it would only make the
     * subject land in a frame that is still travelling: while this is `false` every change is
     * taken at once, and the first picture drawn afterwards has nothing to be continuous with.
     */
    var contentShown: Boolean = true
        set(value) {
            field = value
            if (!value) forget()
        }

    /**
     * Make [next] the source of poses and gestures. Idempotent for the current one. [cut] takes
     * the new pose at once — for a change nobody can see.
     */
    fun drive(next: CameraGestureDetector.CameraManipulator, cut: Boolean = false) {
        if (next === source) return
        source = next
        if (viewportW > 0 && viewportH > 0) next.setViewport(viewportW, viewportH)
        if (cut) {
            disarmCut()
            ease = null
        } else {
            easeNextCut()
        }
    }

    /**
     * The source is about to change its pose discontinuously (a `snapTo`, a loop restart): ease
     * from the pose on screen into whatever it shows next, over [millis], instead of cutting.
     *
     * The move being announced need not have happened yet — a suspending `snapTo` waiting on its
     * mutex lands a tick or two later — so the arm is held until an ease actually starts, and at
     * most [CUT_ARM_SECONDS].
     */
    fun easeNextCut(millis: Long = blendMillis) {
        armCut(millis, nanoTime())
    }

    private fun armCut(millis: Long, now: Long) {
        cutMillis = millis
        cutArmedNanos = now
    }

    private fun disarmCut() {
        cutMillis = NO_CUT
        cutArmedNanos = 0L
    }

    override fun setViewport(width: Int, height: Int) {
        viewportW = width
        viewportH = height
        source?.setViewport(width, height)
    }

    /**
     * The pose to draw — and the one place this class advances anything: it spends the tick's
     * time, counts down [SETTLING_FRAMES], starts and steps the ease, and records the pose as the
     * picture the next change has to be continuous with.
     *
     * It is therefore not a query. It has to be called **exactly once per tick** of the render
     * loop, by the caller that draws what it returns; asking it a second time — to compare
     * transforms and decide whether a frame is worth drawing, say — advances the ease twice and
     * breaks the continuity it exists for. By the same token the ease, and the [OrbitSpin] a
     * source may be turning on ([update]), only move while that loop runs: a loop that parks
     * itself when nothing invalidates the scene leaves both where they stand.
     */
    override fun getTransform(): Transform {
        val live = source?.getTransform() ?: return shown ?: Transform()
        if (!contentShown) {
            forget()
            return live
        }
        val now = nanoTime()
        val center = pivot()
        val liveFraming = orbitFramingOf(live.position, center)
        val sinceShown = (now - shownNanos) / NANOS_PER_SECOND
        // Nothing was drawn for a while: whatever the source did meanwhile is, on screen, a cut.
        if (shown != null && sinceShown > STALL_SECONDS) settlingFrames = SETTLING_FRAMES
        if (settlingFrames > 0) {
            settlingFrames--
            if (cutMillis == NO_CUT && sourceStepped(liveFraming)) armCut(blendMillis, now)
        }
        if (cutMillis != NO_CUT) {
            val started = beginEase(live, liveFraming, cutMillis, now)
            ease = started
            // An arm is spent by the ease it starts, not by the first tick that finds nothing to
            // ease. A script announces its cut and only then suspends — `Animatable.snapTo` waits
            // on its mutex — so on the tick in between the source still stands on the picture and
            // there is nothing to ease yet; consuming the arm there would let the snap through as
            // the very cut this class removes. Hold it instead, for at most [CUT_ARM_SECONDS], so
            // an arm nothing ever came of cannot ease an unrelated change much later.
            val awaiting = started == null && shown != null && eased() &&
                (now - cutArmedNanos) / NANOS_PER_SECOND <= CUT_ARM_SECONDS
            if (!awaiting) disarmCut()
        }
        val frameSeconds = sinceShown.coerceIn(0f, MAX_FRAME_SECONDS)
        val out = ease?.let { applyEase(it, live, liveFraming, now, frameSeconds) }
            ?.takeIf(::isFinite)
            ?: live
        record(out, center, now)
        return out
    }

    /** Whether the source stands further from the picture on screen than a frame of any move. */
    private fun sourceStepped(liveFraming: OrbitFraming): Boolean {
        val from = shown ?: return false
        if (ease != null) return false
        val offset = orbitFramingOffset(
            user = orbitFramingOf(from.position, liveFraming.pivot),
            authored = liveFraming,
        )
        return abs(offset.yawDegrees) > STEP_DEGREES ||
            abs(Math.toDegrees(offset.elevation.toDouble())) > STEP_DEGREES ||
            abs(ln(offset.distanceScale.coerceAtLeast(MIN_DISTANCE))) > STEP_ZOOM
    }

    /** Nothing on screen to be continuous with any more. */
    private fun forget() {
        settlingFrames = 0
        shown = null
        shownFraming = null
        speed = FramingSpeed.REST
        ease = null
        disarmCut()
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

    private fun applyEase(
        ease: Ease,
        live: Transform,
        liveFraming: OrbitFraming,
        now: Long,
        frameSeconds: Float,
    ): Transform {
        ease.elapsed += frameSeconds
        val elapsed = ease.elapsed
        val weight = resumeBlendWeight(elapsed, ease.seconds)
        if (weight <= 0f) {
            this.ease = null
            return live
        }
        val sinceStart = (now - ease.startNanos) / NANOS_PER_SECOND
        if (!ease.relative && sinceStart > MIN_SPEED_WINDOW_SECONDS) {
            // The new source moves too: what is carried is the speed *relative* to it, known as
            // soon as it has shown a second pose. One frame late, and exact from then on.
            if (sinceStart < MAX_SPEED_WINDOW_SECONDS) {
                ease.carried = ease.carried - FramingSpeed.between(ease.liveAtStart, liveFraming, sinceStart)
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
        val measurable = dt > MIN_SPEED_WINDOW_SECONDS && dt < MAX_SPEED_WINDOW_SECONDS
        speed = if (before != null && before.pivot == center && measurable) {
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

        /** Loop time into the ease, a tick counting for at most [MAX_FRAME_SECONDS]. */
        var elapsed = 0f
    }

    companion object {
        /** Long enough to read as a camera move, short enough not to delay the new shot. */
        const val DEFAULT_CUT_BLEND_MILLIS: Long = 700L

        /** Time constant of the carried speed's decay. */
        const val COAST_SECONDS: Float = 0.25f

        /** The most one frame advances an ease by, however long it took to arrive. */
        const val MAX_FRAME_SECONDS: Float = 0.05f

        /** A gap between two frames longer than this is a freeze, and what follows it a cut. */
        const val STALL_SECONDS: Float = 0.25f

        /** How long an announced cut waits for the move it protects before it is dropped. */
        const val CUT_ARM_SECONDS: Float = 0.25f

        /** How many frames after a freeze a step of the source is still put down to it. */
        const val SETTLING_FRAMES: Int = 3

        private const val NO_CUT = -1L
        private const val STEP_DEGREES = 2f
        private const val STEP_ZOOM = 0.03f
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
 * Suspends until frames come at a steady pace again — [STEADY_FRAMES] in a row less than
 * [STEADY_FRAME_NANOS] apart — or [timeoutMillis] have passed.
 *
 * The frames that first show a model are the ones it drops: shaders link, textures upload, the
 * picture freezes. A camera move timed on the wall clock and started on that frame is spent
 * before anything is drawn — on screen, a cut. Started after this, it is seen.
 */
suspend fun awaitSteadyFrames(timeoutMillis: Long = 1_500L) {
    withTimeoutOrNull(timeoutMillis) {
        var last = withFrameNanos { it }
        var steady = 0
        while (steady < STEADY_FRAMES) {
            val now = withFrameNanos { it }
            steady = if (now - last < STEADY_FRAME_NANOS) steady + 1 else 0
            last = now
        }
    }
}

private const val STEADY_FRAMES = 3
private const val STEADY_FRAME_NANOS = 100_000_000L

/**
 * A [ContinuousCameraManipulator] that survives every recomposition of the screen it belongs to.
 * [pivot] is the point the screen's cameras look at; name the source with [driving], or with
 * [ContinuousCameraManipulator.drive] when it only exists further down the composition.
 *
 * It is `remember`ed, not saved: a configuration change builds a new one with nothing on screen
 * to be continuous with, and takes the first pose as it comes — which is what a rotation is
 * anyway.
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
 * [contentShown] is whether the viewport has a subject in it. While it has none — a loading
 * cover, an empty stage between two models — every change is taken at once, so the framing that
 * arrives with a model (its measured fit, the next chip's radius) is not a camera move: the model
 * lands in a camera that already stands still.
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
        this.contentShown = contentShown
        drive(source)
    }
    return this
}
