package io.github.sceneview.demo.demos.internal

/**
 * Whether a scan's point count rose within the last [windowSeconds] of the scan. A scan whose
 * points keep rising adds to the room, so it is not stuck — even while the coverage grid, which
 * counts floor cells and not points, finds nothing new.
 *
 * Fed the count and the scan's clock on each frame of the HUD; asking twice with the same values
 * answers the same. A count that falls or a clock that runs back is a new scan: it starts over.
 */
internal class PointsRise(private val windowSeconds: Float = ScanCoach.STALL_SECONDS) {
    private var points = NONE
    private var roseAt = Float.NEGATIVE_INFINITY

    fun rising(points: Int, seconds: Float): Boolean {
        when {
            points < this.points || seconds < roseAt -> roseAt = Float.NEGATIVE_INFINITY
            this.points != NONE && points > this.points -> roseAt = seconds
        }
        this.points = points
        return seconds - roseAt <= windowSeconds
    }

    private companion object {
        const val NONE = -1
    }
}
