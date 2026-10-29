package io.github.sceneview.demo.demos.internal

/**
 * Builds a persistent point cloud out of ARCore's per-frame feature points (#4624).
 *
 * `Frame.acquirePointCloud()` only returns the points ARCore tracks **in the current frame** —
 * a few tens at a time — so a demo that draws only those looks empty: "Tracking 35 points" over
 * an almost blank camera. ARCore gives every feature point a stable id, though, so the cloud
 * can be accumulated across frames: a new id adds a point, a known id moves it to its latest
 * (refined) position and confidence, and the cloud keeps everything it has seen as the user
 * moves around the room.
 *
 * The buffer is capped at [capacity]. Once full, the point that has gone longest without being
 * seen again is evicted to make room — the part of the room the user walked away from fades
 * out first, the area in front of the camera always stays.
 *
 * Pure Kotlin, no ARCore type: the demo copies the point cloud's `ids` / `points` buffers into
 * arrays and calls [ingest], so the accumulation, the cap and the eviction order are pinned by
 * `FeaturePointAccumulatorTest`. Not thread-safe — the demo drives it from the AR frame callback
 * only.
 */
internal class FeaturePointAccumulator(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val ids = IntArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val zs = FloatArray(capacity)
    private val confidences = FloatArray(capacity)
    private val lastSeen = LongArray(capacity)
    private val slotOf = HashMap<Int, Int>(capacity * 2)

    /** Points currently held, `0..capacity`. */
    var size: Int = 0
        private set

    /** Total number of evictions since the last [clear]. */
    var evicted: Long = 0
        private set

    /**
     * Merges one frame's feature points.
     *
     * @param pointIds   ARCore point ids, one per point (`PointCloud.getIds()`).
     * @param xyzc       Flat `x, y, z, confidence` quadruples (`PointCloud.getPoints()`), world
     *                   space metres, confidence in `[0, 1]`.
     * @param count      Number of points to read from both arrays.
     * @param nowMs      Time of the frame, used for "last seen" and eviction order.
     */
    fun ingest(pointIds: IntArray, xyzc: FloatArray, count: Int, nowMs: Long) {
        require(count <= pointIds.size && count * 4 <= xyzc.size) {
            "count $count exceeds the provided arrays"
        }
        for (i in 0 until count) {
            val id = pointIds[i]
            val o = i * 4
            val x = xyzc[o]
            val y = xyzc[o + 1]
            val z = xyzc[o + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            val slot = slotOf[id] ?: allocateSlot(id)
            xs[slot] = x
            ys[slot] = y
            zs[slot] = z
            confidences[slot] = xyzc[o + 3].coerceIn(0f, 1f)
            lastSeen[slot] = nowMs
        }
    }

    /** Number of held points whose confidence is at least [minConfidence]. */
    fun count(minConfidence: Float): Int {
        var n = 0
        for (i in 0 until size) if (confidences[i] >= minConfidence) n++
        return n
    }

    /** Point at [index] (`0 until size`), in no particular order. */
    fun x(index: Int): Float = xs[index]
    fun y(index: Int): Float = ys[index]
    fun z(index: Int): Float = zs[index]
    fun confidence(index: Int): Float = confidences[index]
    fun lastSeenMs(index: Int): Long = lastSeen[index]
    fun id(index: Int): Int = ids[index]

    /** Whether a point with this ARCore id is held. */
    operator fun contains(id: Int): Boolean = id in slotOf

    /** Drops every point (a session reset, or the user starting over). */
    fun clear() {
        slotOf.clear()
        size = 0
        evicted = 0
    }

    private fun allocateSlot(id: Int): Int {
        if (size == capacity) evictStalest()
        val slot = size++
        ids[slot] = id
        slotOf[id] = slot
        return slot
    }

    /** Removes the point seen longest ago, moving the last slot into its place. */
    private fun evictStalest() {
        var stalest = 0
        for (i in 1 until size) if (lastSeen[i] < lastSeen[stalest]) stalest = i
        slotOf.remove(ids[stalest])
        val last = size - 1
        if (stalest != last) {
            ids[stalest] = ids[last]
            xs[stalest] = xs[last]
            ys[stalest] = ys[last]
            zs[stalest] = zs[last]
            confidences[stalest] = confidences[last]
            lastSeen[stalest] = lastSeen[last]
            slotOf[ids[stalest]] = stalest
        }
        size = last
        evicted++
    }

    companion object {
        /**
         * Enough for a room-sized cloud at ARCore's density (it tracks a few tens of points a
         * frame and refines the same ids as the camera moves), while one upload stays well under
         * a megabyte of vertices.
         */
        const val DEFAULT_CAPACITY: Int = 4_000
    }
}
