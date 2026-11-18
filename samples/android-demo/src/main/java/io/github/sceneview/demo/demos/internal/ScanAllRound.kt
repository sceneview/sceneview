package io.github.sceneview.demo.demos.internal

/**
 * How much of a full turn the phone has faced: the capture's one measure of "how far along",
 * drawn as the circle round the red button. Someone who has never scanned can act on it, since
 * turning is the only thing that fills it and a full turn always does.
 *
 * The turn is cut into [SECTORS]; one counts once the phone has faced it for [DWELL_SECONDS], so
 * a whip round fills nothing. Any tilt short of straight up or down counts: a phone is held
 * looking some 30° down, and a real 75 s room scan (2026-11-14) gated to within 45° of level
 * stopped at 11 sectors of 12 for good, where ungated it closed at 37 s. Walking round an object
 * turns the phone the same way, so the same measure says an object was seen from every side.
 */
class ScanAllRound {
    private val faced = FloatArray(SECTORS)
    private var lastTime = Float.NaN

    /** The sectors faced long enough, of [SECTORS]. */
    var count = 0
        private set

    /** The share of the turn faced, `0..1`. */
    val share: Float get() = count / SECTORS.toFloat()

    /** One frame at [time], in seconds, with the phone at [camera]; returns [share]. */
    fun update(time: Float, camera: DebugPose?): Float {
        if (camera == null || !time.isFinite()) return share
        val before = lastTime
        lastTime = time
        if (before.isNaN()) return share
        val dt = time - before
        // A clock that went back is another capture: nothing of the last one counts.
        if (dt < 0f) {
            reset()
            lastTime = time
            return share
        }
        val heading = ScanFollow.headingOf(camera) ?: return share
        val sector = (((heading % FULL_TURN) + FULL_TURN) % FULL_TURN / (FULL_TURN / SECTORS)).toInt().coerceIn(0, SECTORS - 1)
        val had = faced[sector]
        // A gap in the frames (tracking lost, the app paused) is not time spent facing anything.
        faced[sector] = had + dt.coerceAtMost(MAX_STEP_SECONDS)
        if (had < DWELL_SECONDS && faced[sector] >= DWELL_SECONDS) count++
        return share
    }

    fun reset() {
        faced.fill(0f)
        count = 0
        lastTime = Float.NaN
    }

    companion object {
        /** Twelve sectors of 30°: a step of the circle for every step of a slow turn. */
        const val SECTORS = 12

        /** How long the phone faces a sector before it counts. */
        const val DWELL_SECONDS = 0.15f

        private const val MAX_STEP_SECONDS = 0.1f
        private const val FULL_TURN = 360f
    }
}
