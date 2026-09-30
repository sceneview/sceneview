package io.github.sceneview.demo.demos.internal

/**
 * Which planes a scan takes off its map at one plane update.
 *
 * ARCore dropping every plane it tracked at once is a tracking reset, not the room changing. A
 * Pixel 9 scan went from 7 surfaces to 0 at a reset: its replay opened on "0 planes", unpainted,
 * and its map zoomed onto the camera path, which was all that was left to frame. A scan keeps those
 * planes, as it keeps its points, its photos and its dense map across the reset. A plane merged into
 * another, or dropped while ARCore still tracks others, still leaves.
 */
object ScanPlanes {
    /**
     * The planes of [live] — those drawn after the last update — to remove now that ARCore tracks
     * [seen]. [merged] are those ARCore folded into another plane: they leave even at a reset.
     */
    fun <T> removed(live: Set<T>, seen: Set<T>, merged: Set<T> = emptySet()): Set<T> {
        val gone = live - seen
        val reset = gone.isNotEmpty() && gone.size == live.size
        return if (reset) gone.intersect(merged) else gone
    }
}
