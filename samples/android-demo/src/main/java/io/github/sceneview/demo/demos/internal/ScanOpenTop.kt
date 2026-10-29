package io.github.sceneview.demo.demos.internal

/**
 * A room's scan with its lid off while it is saved (#4742): the saving orbit rises out of the
 * room through its top ([ScanSavingPath]), and a room scanned whole has a ceiling there — flown
 * through, its points flashed as large squares a few centimetres from the lens, and once above it
 * the view only saw the roof. So the cloud loses what stands higher than
 * [RoomDollhouse.CUTAWAY_HEIGHT_M] over its floor, the cut the saved scan's dollhouse makes
 * ([RoomDollhouse.cropDense]): the view the orbit ends on is the one the result opens on.
 */
object ScanOpenTop {
    /** One point in this many is enough to find the floor of a cloud of a few hundred thousand. */
    private const val FLOOR_SAMPLE_STEP = 16

    /**
     * The floor's height under [points] (`x, y, z` triples): their lowest percent
     * ([RoomDollhouse.DENSE_OUTLIER_SHARE]), so a few stray points under it do not count; `null`
     * with none.
     */
    fun floorOf(points: FloatArray): Float? {
        val count = points.size / 3
        if (count == 0) return null
        val step = if (count > FLOOR_SAMPLE_STEP * FLOOR_SAMPLE_STEP) FLOOR_SAMPLE_STEP else 1
        val ys = FloatArray((count + step - 1) / step) { points[it * step * 3 + 1] }.also { it.sort() }
        return ys[(ys.size * RoomDollhouse.DENSE_OUTLIER_SHARE).toInt()]
    }

    /** [points] without those higher than [RoomDollhouse.CUTAWAY_HEIGHT_M] over [floorOf]: as is with none. */
    fun positions(points: FloatArray): FloatArray {
        val floor = floorOf(points) ?: return points
        val top = floor + RoomDollhouse.CUTAWAY_HEIGHT_M
        val count = points.size / 3
        val kept = (0 until count).count { points[it * 3 + 1] <= top }
        if (kept == count) return points
        val out = FloatArray(kept * 3)
        var k = 0
        for (i in 0 until count) {
            if (points[i * 3 + 1] > top) continue
            points.copyInto(out, k * 3, i * 3, i * 3 + 3)
            k++
        }
        return out
    }

    /**
     * [cloud] cut open as the dollhouse cuts it, its floor read from [floorFrom] (`x, y, z`
     * triples): [cloud]'s own points, or the map's whole when [cloud] is the live view's, merged
     * far from the eye (#4792). [cloud] itself without a floor. Off the main thread.
     */
    fun cloud(cloud: DenseCloud, floorFrom: FloatArray = cloud.positions): DenseCloud {
        val floor = floorOf(floorFrom) ?: return cloud
        return RoomDollhouse.cropDense(cloud, floor).takeIf { it.count > 0 } ?: cloud
    }
}
