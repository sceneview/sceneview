package io.github.sceneview.demo.demos.internal

/**
 * The scan result's turntable: the model turns slowly about its vertical axis, gravity up, from
 * the three-quarter it opens on, until the first touch stops it for good. A double tap brings the
 * camera back to the opening pose ([ArDebugOrbitCamera.recenter]), still.
 *
 * The camera stands at the opening's distance all the way round, so the model does not breathe
 * in and out as it turns: it is moved back only at an angle where the box would leave the zone
 * the chrome frees ([zone]); the opening itself frames it to [RerunResultView.ZONE_FILL] of it.
 * Pure, so a test holds it; the screen [advance]s it from `onFrame`.
 */
internal class ScanResultTurntable(
    private val degreesPerSecond: Float = DEGREES_PER_SECOND,
) {
    /**
     * How far it has turned off the opening, degrees, always growing while it runs: never wrapped,
     * so the camera that follows it never turns back the long way at a full turn.
     */
    var angle: Float = 0f
        private set

    /** `false` once stopped: a touch, the plan, or a motion setting that keeps the view still. */
    var running: Boolean = true
        private set

    private var cachedFor: Key? = null
    private var cached: OrbitPose? = null

    /** Turns it on by [seconds]; nothing once stopped, nor on a frame time that cannot be one. */
    fun advance(seconds: Float) {
        if (!running || !seconds.isFinite() || seconds <= 0f || seconds > MAX_STEP_S) return
        angle += degreesPerSecond * seconds
    }

    private var lastFrameNanos = 0L

    /**
     * Turns it on to a frame drawn at [frameTimeNanos] (`onFrame`'s clock): by the time since
     * the last frame it was turned to, none on the first, nor after a pause ([MAX_STEP_S]).
     */
    fun advanceTo(frameTimeNanos: Long) {
        val last = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (last != 0L) advance((frameTimeNanos - last) / NANOS_PER_SECOND)
    }

    /** Stops it for good, back at the opening: the next pose asked is the opening's. */
    fun stop() {
        running = false
        angle = 0f
    }

    /**
     * The pose [opening] — the three-quarter the result opens on, in [frame]'s world — turned
     * [angle] about the model, at [opening]'s distance unless the box needs more room at that
     * angle to stay whole in [zone] on a view of [aspect] through [verticalFovDegrees].
     */
    @Suppress("LongParameterList")
    fun pose(
        frame: ScanResultFrame,
        opening: OrbitPose,
        elevationDegrees: Float,
        offsetDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        zone: FramingInsets,
    ): OrbitPose {
        if (angle == 0f) return opening
        val key = Key(frame, opening, angle, aspect, zone)
        cached?.takeIf { key == cachedFor }?.let { return it }
        val turned = frame.toWorld(
            ArDebugFraming.fit(
                bounds = frame.local,
                azimuthDegrees = frame.frontDegrees + frame.turn * offsetDegrees + angle,
                elevationDegrees = elevationDegrees,
                verticalFovDegrees = verticalFovDegrees,
                aspect = aspect,
                insets = zone,
                atDistance = opening.distance,
            ),
        ).let { it.copy(azimuthDegrees = opening.azimuthDegrees + angle) }
        cachedFor = key
        cached = turned
        return turned
    }

    private data class Key(
        val frame: ScanResultFrame,
        val opening: OrbitPose,
        val angle: Float,
        val aspect: Float,
        val zone: FramingInsets,
    )

    companion object {
        /** A full turn in a minute: slow enough to read, fast enough to be seen to turn. */
        const val DEGREES_PER_SECOND = 6f

        /** A frame longer than this is a pause — the app away, a GC — not time to turn through. */
        const val MAX_STEP_S = 0.25f

        private const val NANOS_PER_SECOND = 1e9f
    }
}
